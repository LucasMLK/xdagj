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
package io.xdag.p2p;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.xdag.DagKernel;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.channel.ChannelManager;
import io.xdag.p2p.message.GetEpochHashesMessage;
import io.xdag.p2p.message.XdagMessageCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * FastDAG Sync Manager (v3.1).
 *
 * <p>Periodically schedules {@link GetEpochHashesMessage} requests so the node can learn about
 * new epochs and fetch missing blocks via {@link XdagMessageCode#GET_BLOCKS}.
 *
 * <p>Uses binary search to locate the minimum valid epoch when local chain is far behind
 * remote chain. This avoids memory explosion from recursive dependency fetching on long-running
 * nodes (2+ years of history).
 */
@Slf4j
public class SyncManager implements AutoCloseable {

  private static final long SYNC_INTERVAL_MS = 5_000;
  private static final long MAX_EPOCHS_PER_REQUEST = 256;
  private static final long MAX_PIPELINE_GAP = 4_096;

  /**
   * Sync tolerance: allow local tip to be this many epochs behind remote tip
   * and still consider synchronized (handles concurrent block production).
   */
  private static final long SYNC_TOLERANCE = 2;

  /**
   * Threshold to trigger binary search.
   * If gap between local and remote is larger than this, use binary search.
   */
  private static final long BINARY_SEARCH_THRESHOLD = 1024;

  /**
   * Maximum iterations for binary search to prevent infinite loops.
   */
  private static final int MAX_BINARY_SEARCH_ITERATIONS = 20;

  /**
   * Sync state enum.
   */
  private enum SyncState {
    /** Normal forward sync mode */
    FORWARD_SYNC,
    /** Binary search to locate minimum valid epoch */
    BINARY_SEARCH,
    /** Binary search completed, transitioning to forward sync */
    BINARY_SEARCH_COMPLETE
  }

  private final DagKernel dagKernel;
  private final DagChain dagChain;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FastDag-SyncManager");
        t.setDaemon(true);
        return t;
      });
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong lastRequestedEpoch = new AtomicLong(-1);
  private final boolean enableScheduler;

  /**
   * Maximum tip epoch known from peer STATUS_REPLY messages.
   * Used to determine when sync is complete.
   */
  private final AtomicLong remoteTipEpoch = new AtomicLong(0);

  /**
   * Maximum main chain height known from peer STATUS_REPLY messages.
   * Used together with remoteTipEpoch for accurate sync completion detection.
   * Check BOTH epoch AND height to prevent mining before all historical blocks are synced.
   */
  private final AtomicLong remoteTipHeight = new AtomicLong(0);

  // ==================== Binary search state ====================

  /** Current sync state */
  private final AtomicReference<SyncState> syncState = new AtomicReference<>(SyncState.FORWARD_SYNC);

  /** Binary search lower bound (inclusive) */
  private final AtomicLong binarySearchLow = new AtomicLong(0);

  /** Binary search upper bound (inclusive) */
  private final AtomicLong binarySearchHigh = new AtomicLong(0);

  /** Last epoch probed during binary search */
  private final AtomicLong binarySearchProbe = new AtomicLong(0);

  /** Binary search iteration counter */
  private final AtomicInteger binarySearchIterations = new AtomicInteger(0);

  /** Minimum epoch found to have blocks during binary search */
  private final AtomicLong minValidEpochFound = new AtomicLong(Long.MAX_VALUE);

  /**
   * Epoch from which forward sync should start after binary search.
   * Used to prevent re-triggering binary search before blocks are actually synced.
   * Set to -1 when not active.
   */
  private final AtomicLong forwardSyncStartEpoch = new AtomicLong(-1);

  // ==================== Empty epoch tracking ====================

  /**
   * Request sequence counter for matching responses to requests.
   * Ensures that late-arriving responses from old requests are ignored.
   */
  private final AtomicLong requestSequence = new AtomicLong(0);

  /**
   * Context for tracking epoch requests.
   * Contains sequence ID to prevent response mismatch when requests overlap.
   */
  private static final class EpochRequestContext {
    final long sequenceId;
    final long startEpoch;
    final long endEpoch;

    EpochRequestContext(long sequenceId, long startEpoch, long endEpoch) {
      this.sequenceId = sequenceId;
      this.startEpoch = startEpoch;
      this.endEpoch = endEpoch;
    }
  }

  /** Current pending request context. Only responses matching this context are processed. */
  private final AtomicReference<EpochRequestContext> lastRequestContext =
      new AtomicReference<>(null);

  /**
   * Cache of confirmed empty epochs (no blocks exist on the network).
   * When an epoch is requested and the response shows later epochs exist but not this one,
   * we mark it as confirmed empty and skip it in future gap detection.
   *
   * <p>Bounded to prevent memory leaks:
   * <ul>
   *   <li>Maximum 1024 entries</li>
   *   <li>Entries expire after 1 hour (allows re-verification from new peers)</li>
   * </ul>
   */
  private final Cache<Long, Boolean> confirmedEmptyEpochs = Caffeine.newBuilder()
      .maximumSize(1024)
      .expireAfterWrite(1, TimeUnit.HOURS)
      .build();

  public SyncManager(DagKernel dagKernel) {
    this(dagKernel, true);
  }

  SyncManager(DagKernel dagKernel, boolean enableScheduler) {
    this.dagKernel = Objects.requireNonNull(dagKernel, "DagKernel cannot be null");
    this.dagChain = Objects.requireNonNull(dagKernel.getDagChain(), "DagChain cannot be null");
    this.enableScheduler = enableScheduler;
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    if (enableScheduler) {
      executor.scheduleWithFixedDelay(this::performSync,
          SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);
      log.info("FastDAG SyncManager started (interval={} ms, batch={} epochs)",
          SYNC_INTERVAL_MS, MAX_EPOCHS_PER_REQUEST);
    } else {
      log.info("FastDAG SyncManager started in manual mode");
    }
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    executor.shutdownNow();
    log.info("FastDAG SyncManager stopped");
  }

  @Override
  public void close() {
    stop();
  }

  void performSync() {
    if (!running.get()) {
      return;
    }

    P2pService p2pService = dagKernel.getP2pService();
    if (p2pService == null) {
      return;
    }

    List<Channel> channels = getActiveChannels(p2pService);
    if (channels.isEmpty()) {
      return;
    }

    // Handle different sync states
    SyncState currentState = syncState.get();
    switch (currentState) {
      case BINARY_SEARCH:
        performBinarySearch(channels);
        return;
      case BINARY_SEARCH_COMPLETE:
        transitionToForwardSync();
        // Fall through to forward sync
      case FORWARD_SYNC:
      default:
        performForwardSync(channels);
    }
  }

  /**
   * Perform binary search to locate minimum valid epoch.
   */
  private void performBinarySearch(List<Channel> channels) {
    long low = binarySearchLow.get();
    long high = binarySearchHigh.get();
    int iterations = binarySearchIterations.get();

    // Check termination conditions
    if (iterations >= MAX_BINARY_SEARCH_ITERATIONS || high - low <= MAX_EPOCHS_PER_REQUEST) {
      // Binary search complete
      long minEpoch = minValidEpochFound.get();
      if (minEpoch == Long.MAX_VALUE) {
        // No blocks found during binary search, use the narrowed range's lower bound
        // This is the best estimate of where blocks might exist
        minEpoch = low;
      }
      // IMPORTANT: Update minValidEpochFound so transitionToForwardSync() uses correct value
      minValidEpochFound.set(minEpoch);
      log.info("Sync: Binary search complete, starting from epoch {}", minEpoch);
      syncState.set(SyncState.BINARY_SEARCH_COMPLETE);
      return;
    }

    // Calculate probe point (middle of range)
    long mid = low + (high - low) / 2;
    binarySearchProbe.set(mid);
    binarySearchIterations.incrementAndGet();

    log.debug("Binary search iteration {}: probing epoch {} (range [{}, {}])",
        iterations + 1, mid, low, high);

    // Send probe request
    Channel channel = selectChannel(channels);
    try {
      long endEpoch = Math.min(mid + MAX_EPOCHS_PER_REQUEST - 1, high);
      channel.send(new GetEpochHashesMessage(mid, endEpoch));
    } catch (Exception e) {
      log.warn("Failed to send binary search probe: {}", e.getMessage());
    }
  }

  /**
   * Handle binary search response.
   * Called from XdagP2pEventHandler when receiving EPOCH_HASHES_REPLY during binary search.
   *
   * @param hasBlocks true if the response contained any block hashes
   * @param minEpochInResponse the minimum epoch that had blocks in the response, or -1 if none
   */
  public void onBinarySearchResponse(boolean hasBlocks, long minEpochInResponse) {
    if (syncState.get() != SyncState.BINARY_SEARCH) {
      return;
    }

    long probe = binarySearchProbe.get();
    long low = binarySearchLow.get();
    long high = binarySearchHigh.get();

    if (hasBlocks) {
      // Found blocks at probe point, search earlier epochs
      if (minEpochInResponse > 0 && minEpochInResponse < minValidEpochFound.get()) {
        minValidEpochFound.set(minEpochInResponse);
      }
      // Search lower half
      binarySearchHigh.set(probe - 1);
      log.debug("Binary search: found blocks at epoch {}, narrowing to [{}, {}]",
          minEpochInResponse > 0 ? minEpochInResponse : probe, low, probe - 1);
    } else {
      // No blocks at probe point, search later epochs
      // Search upper half
      binarySearchLow.set(probe + MAX_EPOCHS_PER_REQUEST);
      log.debug("Binary search: no blocks at epoch {}, narrowing to [{}, {}]",
          probe, probe + MAX_EPOCHS_PER_REQUEST, high);
    }
  }

  /**
   * Transition from binary search to forward sync.
   */
  private void transitionToForwardSync() {
    long startEpoch = minValidEpochFound.get();
    // minValidEpochFound should already be set by performBinarySearch()
    // This fallback is just for safety
    if (startEpoch == Long.MAX_VALUE) {
      startEpoch = Math.max(getLocalTipEpoch() + 1, remoteTipEpoch.get() - MAX_EPOCHS_PER_REQUEST);
    }

    log.debug("Transitioning to forward sync from epoch {}", startEpoch);
    lastRequestedEpoch.set(startEpoch - 1);

    // Set forwardSyncStartEpoch to prevent re-triggering binary search
    // until local tip has actually advanced past this epoch
    forwardSyncStartEpoch.set(startEpoch);

    syncState.set(SyncState.FORWARD_SYNC);

    // Reset binary search state for potential future use
    binarySearchIterations.set(0);
    minValidEpochFound.set(Long.MAX_VALUE);
  }

  /**
   * Perform normal forward sync.
   */
  private void performForwardSync(List<Channel> channels) {
    long currentEpoch = dagChain.getCurrentEpoch();
    long localTipEpoch = getLocalTipEpoch();
    long knownRemoteTipEpoch = remoteTipEpoch.get();

    // Check if we need to start binary search
    // BUT don't re-initiate if we just completed binary search and haven't synced yet
    long syncStartEpoch = forwardSyncStartEpoch.get();
    boolean inCatchUpMode = syncStartEpoch > 0;
    if (inCatchUpMode) {
      // Binary search was completed, check if local tip has advanced
      if (localTipEpoch >= syncStartEpoch) {
        // Local tip has advanced past the binary search start point, clear the flag
        forwardSyncStartEpoch.set(-1);
        inCatchUpMode = false;
        log.debug("Local tip {} reached sync start epoch {}, resuming normal sync",
            localTipEpoch, syncStartEpoch);
      }
      // Skip binary search check while forwardSyncStartEpoch is active
    } else if (knownRemoteTipEpoch > 0 && localTipEpoch < knownRemoteTipEpoch - BINARY_SEARCH_THRESHOLD) {
      // Large gap detected, initiate binary search
      initiateBinarySearch(localTipEpoch, knownRemoteTipEpoch);
      return;
    }

    // Check for epoch gaps in the main chain
    // If there are missing epochs between genesis and tip, prioritize filling them
    long firstMissingEpoch = findFirstMissingEpoch();

    long startEpoch;
    if (firstMissingEpoch > 0 && firstMissingEpoch < localTipEpoch) {
      // There's a gap in the chain, sync from the missing epoch
      // Reset lastRequestedEpoch to force re-request of gap
      startEpoch = firstMissingEpoch;
      log.debug("Epoch gap detected, syncing from epoch {}", startEpoch);
      lastRequestedEpoch.set(firstMissingEpoch - 1);
    } else {
      // No gap, sync from tip as usual
      // When in catch-up mode after binary search, use lastRequestedEpoch directly
      if (!inCatchUpMode) {
        lastRequestedEpoch.updateAndGet(prev -> Math.max(prev, localTipEpoch));
      }

      if (localTipEpoch >= currentEpoch) {
        return;
      }

      // Skip pipeline gap check during catch-up mode
      // After binary search, we need to sync from the found epoch regardless of gap
      if (!inCatchUpMode) {
        long outstanding = lastRequestedEpoch.get() - localTipEpoch;
        if (outstanding > MAX_PIPELINE_GAP) {
          log.debug("FastDAG sync paused (pipeline gap {} epochs)", outstanding);
          return;
        }
      }

      startEpoch = Math.max(localTipEpoch + 1, lastRequestedEpoch.get() + 1);
    }

    if (startEpoch > currentEpoch) {
      return;
    }

    long endEpoch = Math.min(startEpoch + MAX_EPOCHS_PER_REQUEST - 1, currentEpoch);
    Channel channel = selectChannel(channels);
    sendEpochRequest(channel, startEpoch, endEpoch);
  }

  /**
   * Initiate binary search to find minimum valid epoch.
   */
  private void initiateBinarySearch(long localTipEpoch, long knownRemoteTipEpoch) {
    log.info("Sync: initiating binary search (local={}, remote={}, gap={})",
        localTipEpoch, knownRemoteTipEpoch, knownRemoteTipEpoch - localTipEpoch);

    // Set search range: from local tip + 1 to remote tip
    binarySearchLow.set(localTipEpoch + 1);
    binarySearchHigh.set(knownRemoteTipEpoch);
    binarySearchIterations.set(0);
    minValidEpochFound.set(Long.MAX_VALUE);
    binarySearchProbe.set(0);

    syncState.set(SyncState.BINARY_SEARCH);
  }

  /**
   * Check if currently in binary search mode.
   * Used by XdagP2pEventHandler to route responses correctly.
   */
  public boolean isInBinarySearch() {
    return syncState.get() == SyncState.BINARY_SEARCH;
  }

  // ==================== Empty epoch response handling ====================

  /**
   * Process EPOCH_HASHES_REPLY to detect confirmed empty epochs.
   *
   * <p>When we request a range of epochs and the response contains some epochs but not others,
   * we can determine which epochs are legitimately empty (no blocks on the network) vs
   * which epochs the peer simply doesn't have yet.
   *
   * <p>Logic:
   * <ul>
   *   <li>If epoch X is not in response, but epoch Y > X is in response, then X is empty</li>
   *   <li>If epoch X is not in response and no epoch > X is in response, X is unknown (peer might be behind)</li>
   * </ul>
   *
   * <p>Request-Response Matching:
   * Uses sequence IDs to ensure responses are matched to the correct request.
   * If a newer request was sent before the response arrived, the stale response is ignored.
   *
   * @param receivedEpochs the set of epochs that had blocks in the response
   */
  public void onEpochHashesResponse(Set<Long> receivedEpochs) {
    // Don't mark empty epochs during binary search (different handling)
    if (isInBinarySearch()) {
      return;
    }

    // Atomically get and clear the request context
    EpochRequestContext ctx = lastRequestContext.getAndSet(null);
    if (ctx == null) {
      log.debug("Ignoring orphan epoch response (no pending request context)");
      return;
    }

    // Verify this response matches the most recent request (sequence ID check)
    // If a newer request was sent, ctx.sequenceId < requestSequence.get()
    long currentSeq = requestSequence.get();
    if (ctx.sequenceId != currentSeq) {
      log.debug("Ignoring stale epoch response (response seq={}, current seq={})",
          ctx.sequenceId, currentSeq);
      return;
    }

    long requestStart = ctx.startEpoch;
    long requestEnd = ctx.endEpoch;

    // Find the maximum epoch in the response
    long maxReceivedEpoch = receivedEpochs.stream()
        .max(Long::compareTo)
        .orElse(-1L);

    if (maxReceivedEpoch < 0) {
      // Empty response - peer might be behind, don't mark any epochs as empty
      log.debug("Empty epoch response for range [{}, {}]", requestStart, requestEnd);
      return;
    }

    // Mark empty epochs: those in request range, not in response, but before maxReceivedEpoch
    int emptyCount = 0;
    for (long epoch = requestStart; epoch <= Math.min(requestEnd, maxReceivedEpoch); epoch++) {
      if (!receivedEpochs.contains(epoch)) {
        // This epoch was requested, not in response, but later epochs exist
        // Therefore this epoch is confirmed empty on the network
        if (confirmedEmptyEpochs.getIfPresent(epoch) == null) {
          confirmedEmptyEpochs.put(epoch, Boolean.TRUE);
          emptyCount++;
          log.trace("Epoch {} confirmed empty", epoch);
        }
      }
    }

    if (emptyCount > 0) {
      log.debug("Confirmed {} empty epochs in range [{}, {}]",
          emptyCount, requestStart, Math.min(requestEnd, maxReceivedEpoch));
    }
    // Note: context already cleared by getAndSet(null) above
  }

  /**
   * Check if an epoch has been confirmed as empty.
   *
   * @param epoch the epoch to check
   * @return true if the epoch is confirmed empty, false otherwise
   */
  public boolean isEpochConfirmedEmpty(long epoch) {
    return confirmedEmptyEpochs.getIfPresent(epoch) != null;
  }

  /**
   * Get the count of confirmed empty epochs (for monitoring/testing).
   *
   * @return the number of epochs currently marked as confirmed empty
   */
  public long getConfirmedEmptyEpochCount() {
    return confirmedEmptyEpochs.estimatedSize();
  }

  private void sendEpochRequest(Channel channel, long startEpoch, long endEpoch) {
    try {
      log.debug("Requesting epoch hashes from {}: [{}-{}]",
          channel.getRemoteAddress(), startEpoch, endEpoch);
      channel.send(new GetEpochHashesMessage(startEpoch, endEpoch));
      // Track request with unique sequence ID for response matching
      long seqId = requestSequence.incrementAndGet();
      lastRequestContext.set(new EpochRequestContext(seqId, startEpoch, endEpoch));
      lastRequestedEpoch.set(endEpoch);
    } catch (Exception e) {
      log.warn("Failed to send GET_EPOCH_HASHES to {}: {}",
          channel.getRemoteAddress(), e.getMessage());
    }
  }

  private Channel selectChannel(List<Channel> channels) {
    if (channels.size() == 1) {
      return channels.get(0);
    }
    int index = ThreadLocalRandom.current().nextInt(channels.size());
    return channels.get(index);
  }

  // ==================== Sync status tracking ====================

  /**
   * Update the known remote tip epoch from peer STATUS_REPLY.
   * Takes the maximum of all peer tips to ensure we sync to the latest state.
   *
   * @param epoch the tip epoch reported by a peer
   */
  public void updateRemoteTipEpoch(long epoch) {
    long prev = remoteTipEpoch.getAndUpdate(old -> Math.max(old, epoch));
    if (epoch > prev) {
      log.debug("Remote tip epoch updated: {} -> {}", prev, epoch);
    }
  }

  /**
   * Update the known remote main chain height from peer STATUS_REPLY.
   * Track height to ensure all blocks are synced.
   *
   * @param height the main chain height reported by a peer
   */
  public void updateRemoteTipHeight(long height) {
    long prev = remoteTipHeight.getAndUpdate(old -> Math.max(old, height));
    if (height > prev) {
      log.debug("Remote tip height updated: {} -> {}", prev, height);
    }
  }

  /**
   * Get the current known remote tip epoch.
   *
   * @return the maximum tip epoch from peers, or 0 if no peer has reported yet
   */
  public long getRemoteTipEpoch() {
    return remoteTipEpoch.get();
  }

  /**
   * Get the current known remote main chain height.
   *
   * @return the maximum height from peers, or 0 if no peer has reported yet
   */
  public long getRemoteTipHeight() {
    return remoteTipHeight.get();
  }

  /**
   * Check if local chain is synchronized with remote peers.
   *
   * <p>Synchronized means BOTH conditions must be met:
   * <ul>
   *   <li>Local tip epoch is within SYNC_TOLERANCE of remote tip epoch</li>
   *   <li>Local main chain height is within SYNC_TOLERANCE of remote height</li>
   * </ul>
   *
   * <p>This prevents mining before all historical blocks are synced. Previously,
   * only epoch was checked, which allowed a node to mine at current epoch before
   * syncing all historical blocks (causing chain divergence).
   *
   * @return true if synchronized, false if still catching up
   */
  public boolean isSynchronized() {
    long remoteEpoch = remoteTipEpoch.get();
    long remoteHeight = remoteTipHeight.get();

    // No peer info yet - first node scenario, allow mining
    if (remoteEpoch == 0 && remoteHeight == 0) {
      return true;
    }

    long localEpoch = getLocalTipEpoch();
    long localHeight = dagChain.getMainChainLength();

    // Check epoch proximity
    boolean epochSynced = (remoteEpoch == 0) || (localEpoch >= remoteEpoch - SYNC_TOLERANCE);

    // Check height proximity
    boolean heightSynced = (remoteHeight == 0) || (localHeight >= remoteHeight - SYNC_TOLERANCE);

    boolean synced = epochSynced && heightSynced;

    if (!synced) {
      log.debug("Sync status: localEpoch={}, remoteEpoch={}, epochSynced={}; " +
                "localHeight={}, remoteHeight={}, heightSynced={}; overall={}",
          localEpoch, remoteEpoch, epochSynced,
          localHeight, remoteHeight, heightSynced, synced);
    }

    return synced;
  }

  /**
   * Get the local chain's tip epoch.
   *
   * @return the epoch of the latest main chain block, or 0 if chain is empty
   */
  public long getLocalTipEpoch() {
    long height = dagChain.getMainChainLength();
    if (height <= 0) {
      return 0;
    }
    try {
      Block tip = dagChain.getMainBlockByHeight(height);
      if (tip != null) {
        return tip.getEpoch();
      }
    } catch (Exception e) {
      log.warn("Failed to read main block at height {}: {}", height, e.getMessage());
    }
    return 0;
  }

  /**
   * Find the first missing epoch in the main chain.
   *
   * <p>Detects gaps in the main chain where epochs are missing.
   * This can happen when a node receives a broadcast block before syncing all
   * historical blocks.
   *
   * <p>Only considers gaps of <= MAX_EXPECTED_EPOCH_GAP as potential missing blocks.
   * Larger gaps (e.g., genesis to first mined block) are considered normal.
   *
   * @return the first missing epoch, or -1 if no gaps found
   */
  private long findFirstMissingEpoch() {
    // Maximum expected epoch gap between consecutive main chain blocks
    // Gaps larger than this are considered normal (e.g., node offline period)
    // 16 epochs = ~17 minutes, reasonable for normal operation
    final long MAX_EXPECTED_EPOCH_GAP = 16;

    long height = dagChain.getMainChainLength();
    if (height <= 1) {
      return -1; // Only genesis or empty chain, no gaps possible
    }

    try {
      // Start from height 1 (genesis) and check for epoch gaps
      Block prevBlock = dagChain.getMainBlockByHeight(1);
      if (prevBlock == null) {
        return -1;
      }

      for (long h = 2; h <= height; h++) {
        Block currBlock = dagChain.getMainBlockByHeight(h);
        if (currBlock == null) {
          continue;
        }

        long prevEpoch = prevBlock.getEpoch();
        long currEpoch = currBlock.getEpoch();
        long gap = currEpoch - prevEpoch;

        // Check if there's a suspicious gap between epochs
        // Gap of 1 is normal (consecutive epochs)
        // Gap of 2-MAX_EXPECTED_EPOCH_GAP might indicate missing blocks
        // Gap > MAX_EXPECTED_EPOCH_GAP is likely normal (node was offline)
        if (gap > 1 && gap <= MAX_EXPECTED_EPOCH_GAP) {
          // Check each epoch in the gap, skip confirmed empty ones
          for (long epoch = prevEpoch + 1; epoch < currEpoch; epoch++) {
            if (confirmedEmptyEpochs.getIfPresent(epoch) == null) {
              // This epoch is not confirmed empty, might need to sync
              log.debug("Found epoch gap at height {}: {} -> {} (gap={})",
                  h, prevEpoch, currEpoch, gap);
              return epoch;
            }
          }
          // All epochs in this gap are confirmed empty, continue scanning
          log.trace("Epoch gap [{}, {}] confirmed empty, skipping", prevEpoch + 1, currEpoch - 1);
        }

        prevBlock = currBlock;
      }
    } catch (Exception e) {
      log.warn("Error checking for epoch gaps: {}", e.getMessage());
    }

    return -1; // No gaps found
  }

  private List<Channel> getActiveChannels(P2pService service) {
    try {
      ChannelManager manager = service.getChannelManager();
      if (manager == null) {
        return List.of();
      }
      var map = manager.getChannels();
      if (map == null || map.isEmpty()) {
        return List.of();
      }
      return new ArrayList<>(map.values());
    } catch (Exception e) {
      log.warn("Failed to enumerate P2P channels: {}", e.getMessage());
      return List.of();
    }
  }
}
