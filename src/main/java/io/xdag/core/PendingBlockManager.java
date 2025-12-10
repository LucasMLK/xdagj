/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.xdag.core;

import io.xdag.DagKernel;
import io.xdag.store.DagStore;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Manages blocks with missing dependencies (pending blocks).
 *
 * <p>This class handles blocks that cannot be immediately imported because
 * their parent blocks have not yet been received. This is a network
 * synchronization concern, NOT consensus-related.
 *
 * <p><strong>Note:</strong> "Pending block" refers to blocks waiting for
 * missing parent dependencies. This is different from "orphan block" in
 * XDAG consensus, which refers to blocks that lost the epoch competition
 * (height=0).
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Persist blocks with missing dependencies and their parent mappings</li>
 *   <li>Watch for parent arrivals and enqueue dependents for retry</li>
 *   <li>Run a background worker to retry pending blocks without blocking import</li>
 * </ul>
 *
 * <h2>Workflow</h2>
 * <pre>
 * Block received → Check parents exist?
 *     ↓ No
 * registerMissingDependency() → Save to pending store → Enqueue for retry
 *     ↓
 * Parent arrives → onBlockImported() → Trigger dependent blocks retry
 *     ↓
 * Retry succeeds → clearMissingDependency()
 * </pre>
 *
 * @since XDAGJ 5.1
 */
@Slf4j
public class PendingBlockManager {

  private static final long RETRY_INTERVAL_MS = 2000L;
  private static final int MAX_BATCH_RETRY = 64;
  private static final int FALLBACK_BATCH = 32;

  private final DagStore dagStore;
  private final ScheduledExecutorService retryExecutor;
  private final ConcurrentLinkedQueue<Bytes32> retryQueue = new ConcurrentLinkedQueue<>();
  private final Set<Bytes32> queuedBlocks = ConcurrentHashMap.newKeySet();

  private volatile boolean running = false;
  private volatile Function<Block, DagImportResult> retryFunction;

  public PendingBlockManager(DagKernel dagKernel) {
    this.dagStore = dagKernel.getDagStore();
    this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "PendingBlockRetryExecutor");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * Start asynchronous retry worker.
   *
   * @param retryFunction function to retry importing a pending block
   */
  public synchronized void start(Function<Block, DagImportResult> retryFunction) {
    if (running) {
      return;
    }
    this.retryFunction = retryFunction;
    running = true;
    retryExecutor.scheduleWithFixedDelay(this::processQueue,
        RETRY_INTERVAL_MS,
        RETRY_INTERVAL_MS,
        TimeUnit.MILLISECONDS);
    enqueueMissingDependencyBlocks(FALLBACK_BATCH);
  }

  /**
   * Stop the retry worker and clear queues.
   */
  public synchronized void stop() {
    if (!running) {
      retryExecutor.shutdownNow();
      retryQueue.clear();
      queuedBlocks.clear();
      return;
    }
    running = false;
    retryExecutor.shutdown();
    try {
      if (!retryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        retryExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      retryExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    } finally {
      retryQueue.clear();
      queuedBlocks.clear();
    }
  }

  /**
   * Register a block that has missing parent dependencies.
   *
   * <p>The block will be persisted and enqueued for retry when parents arrive.
   *
   * <p><strong>BUG-SYNC-009 fix:</strong> After registering, we check if parents now exist
   * (they could have been imported between detection and registration). If so, we immediately
   * enqueue for retry instead of waiting for the next onBlockImported() callback which may
   * have already fired.
   *
   * @param block          the block with missing dependencies
   * @param missingParents list of missing parent hashes
   */
  public void registerMissingDependency(Block block, List<Bytes32> missingParents) {
    List<Bytes32> parents = missingParents == null ? Collections.emptyList() : missingParents;

    // Save to pending store (now atomic thanks to WriteBatch fix)
    dagStore.saveMissingDependencyBlock(block, parents);
    dagStore.saveOrphanReason(block.getHash(), OrphanReason.MISSING_DEPENDENCY);

    // BUG-SYNC-009 fix: Check if parents are now available
    // This handles the race condition where parent arrives between:
    // 1. BlockImporter detecting MISSING_DEPENDENCY
    // 2. This registerMissingDependency() call
    // Without this check, the block could be stuck in pending state forever.
    boolean allParentsAvailable = parents.isEmpty() || parents.stream()
        .allMatch(parentHash -> dagStore.getBlockByHash(parentHash) != null);

    if (allParentsAvailable) {
      log.debug("Pending block {} has all parents now available, immediate retry",
          formatHash(block.getHash()));
    }

    // Always enqueue for retry - the retry logic will handle the actual import
    enqueueRetry(block.getHash());
  }

  /**
   * Clear pending state once block successfully imports.
   *
   * @param blockHash hash of the block that was successfully imported
   */
  public void clearMissingDependency(Bytes32 blockHash) {
    dagStore.deleteMissingDependencyBlock(blockHash);
    queuedBlocks.remove(blockHash);
  }

  /**
   * Triggered when a block is imported to the DAG.
   *
   * <p>Checks if any pending blocks were waiting for this block as a parent,
   * and enqueues them for retry.
   *
   * @param block the newly imported block
   */
  public void onBlockImported(Block block) {
    if (!running) {
      return;
    }
    List<Bytes32> dependents = dagStore.getBlocksWaitingForParent(block.getHash());
    if (!dependents.isEmpty()) {
      log.debug("Parent {} satisfied {} pending block(s)", formatHash(block.getHash()), dependents.size());
    }
    for (Bytes32 dependent : dependents) {
      enqueueRetry(dependent);
    }
  }

  private void processQueue() {
    if (!running || retryFunction == null) {
      return;
    }

    int processed = 0;
    while (processed < MAX_BATCH_RETRY) {
      Bytes32 hash = retryQueue.poll();
      if (hash == null) {
        break;
      }
      queuedBlocks.remove(hash);
      processed++;
      retryBlock(hash);
    }

    if (retryQueue.isEmpty()) {
      enqueueMissingDependencyBlocks(FALLBACK_BATCH);
    }
  }

  private void retryBlock(Bytes32 hash) {
    try {
      Block pendingBlock = dagStore.getBlockByHash(hash);
      if (pendingBlock == null) {
        log.debug("Pending block {} no longer exists", formatHash(hash));
        return;
      }
      retryFunction.apply(pendingBlock);
    } catch (Exception e) {
      log.error("Failed to retry pending block {}: {}", formatHash(hash), e.getMessage());
    }
  }

  private void enqueueMissingDependencyBlocks(int maxCount) {
    List<Bytes32> hashes = dagStore.getMissingDependencyBlockHashes(maxCount);
    for (Bytes32 hash : hashes) {
      enqueueRetry(hash);
    }
  }

  private void enqueueRetry(Bytes32 hash) {
    if (hash == null) {
      return;
    }
    if (queuedBlocks.add(hash)) {
      retryQueue.offer(hash);
    }
  }

  private String formatHash(Bytes32 hash) {
    return hash.toHexString().substring(0, 16);
  }
}
