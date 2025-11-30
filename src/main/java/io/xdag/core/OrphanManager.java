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
 * Asynchronous orphan tracker / retry executor.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Persist missing-dependency blocks and their parent mappings</li>
 *   <li>Watch parent arrivals and enqueue dependents for retry</li>
 *   <li>Run a background worker to retry pending blocks without blocking import path</li>
 * </ul>
 */
@Slf4j
public class OrphanManager {

  private static final long RETRY_INTERVAL_MS = 2000L;
  private static final int MAX_BATCH_RETRY = 64;
  private static final int FALLBACK_BATCH = 32;

  private final DagStore dagStore;
  private final ScheduledExecutorService retryExecutor;
  private final ConcurrentLinkedQueue<Bytes32> retryQueue = new ConcurrentLinkedQueue<>();
  private final Set<Bytes32> queuedBlocks = ConcurrentHashMap.newKeySet();

  private volatile boolean running = false;
  private volatile Function<Block, DagImportResult> retryFunction;

  public OrphanManager(DagKernel dagKernel) {
    this.dagStore = dagKernel.getDagStore();
    this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "OrphanRetryExecutor");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * Start asynchronous retry worker.
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
   * Persist missing dependency information.
   */
  public void registerMissingDependency(Block block, List<Bytes32> missingParents) {
    List<Bytes32> parents = missingParents == null ? Collections.emptyList() : missingParents;
    dagStore.saveMissingDependencyBlock(block, parents);
    dagStore.saveOrphanReason(block.getHash(), OrphanReason.MISSING_DEPENDENCY);
    enqueueRetry(block.getHash());
  }

  /**
   * Clear pending state once block successfully imports.
   */
  public void clearMissingDependency(Bytes32 blockHash) {
    dagStore.deleteMissingDependencyBlock(blockHash);
    queuedBlocks.remove(blockHash);
  }

  /**
   * Triggered when a parent block becomes part of the DAG.
   */
  public void onBlockImported(Block block) {
    if (!running) {
      return;
    }
    List<Bytes32> dependents = dagStore.getBlocksWaitingForParent(block.getHash());
    if (!dependents.isEmpty()) {
      log.debug("Parent {} satisfied {} orphan(s)", formatHash(block.getHash()), dependents.size());
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
      Block pendingBlock = dagStore.getBlockByHash(hash, true);
      if (pendingBlock == null) {
        log.debug("Missing dependency block {} no longer exists", formatHash(hash));
        return;
      }
      retryFunction.apply(pendingBlock);
    } catch (Exception e) {
      log.error("Failed to retry orphan block {}: {}", formatHash(hash), e.getMessage());
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
