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
import java.util.Map;
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
import org.apache.tuweni.bytes.Bytes32;

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
   * Interval for historical epoch verification.
   * Check for better blocks in historical epochs every 30 seconds.
   */
  private static final long HISTORICAL_VERIFY_INTERVAL_MS = 30_000;

  /**
   * Number of recent epochs to verify for better blocks.
   * After initial full historical scan, verify only recent epochs periodically.
   */
  private static final long HISTORICAL_VERIFY_DEPTH = 10;

  /**
   * Batch size for fork detection scan.
   * Process epochs in batches to avoid requesting too many at once.
   */
  private static final long FORK_DETECTION_BATCH_SIZE = 256;

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
    BINARY_SEARCH_COMPLETE,
    /** Fork detection: scanning history to find fork point */
    FORK_DETECTION,
    /** Reorganization: syncing from fork point */
    CHAIN_REORGANIZATION
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

  // ==================== Historical epoch verification ====================

  /**
   * Last time historical epoch verification was performed.
   * Used to rate-limit historical verification.
   */
  private final AtomicLong lastHistoricalVerifyTime = new AtomicLong(0);

  /**
   * Flag indicating historical verification is in progress.
   * Prevents concurrent verifications.
   */
  private final AtomicBoolean historicalVerifyInProgress = new AtomicBoolean(false);

  // ==================== Fork Detection ====================

  /**
   * Flag indicating if initial full historical scan has been completed.
   * On first peer connection, we scan all epochs from genesis to tip.
   */
  private final AtomicBoolean initialHistoricalScanDone = new AtomicBoolean(false);

  /**
   * Current epoch being scanned during fork detection.
   * Starts from genesis and advances forward until tip.
   */
  private final AtomicLong forkDetectionCurrentEpoch = new AtomicLong(-1);

  /**
   * Fork point epoch: the last epoch where local and peer chains agree.
   * Set to -1 when not in fork detection mode.
   * All epochs after this point need to be re-synced.
   */
  private final AtomicLong forkPointEpoch = new AtomicLong(-1);

  /**
   * Target epoch for chain reorganization.
   * Sync from forkPointEpoch to this epoch.
   */
  private final AtomicLong reorgTargetEpoch = new AtomicLong(-1);

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

    // On first peer connection, initiate full historical scan
    maybeInitiateForkDetection(channels);

    // Periodically verify historical epochs for better blocks
    maybePerformHistoricalVerification(channels);

    // Handle different sync states
    SyncState currentState = syncState.get();
    switch (currentState) {
      case FORK_DETECTION:
        performForkDetection(channels);
        return;
      case CHAIN_REORGANIZATION:
        performChainReorganization(channels);
        return;
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

  // ==================== Historical Epoch Verification ====================

  /**
   * Maybe perform historical epoch verification.
   *
   * <p>Periodically request historical epoch hashes from peers
   * to detect if peers have better (smaller hash) blocks for epochs we already have.
   *
   * <p>This is needed because normal forward sync only syncs from localTipEpoch + 1,
   * never checking if historical epochs have better blocks on other nodes.
   *
   * @param channels active P2P channels
   */
  private void maybePerformHistoricalVerification(List<Channel> channels) {
    // Rate limit: only verify every HISTORICAL_VERIFY_INTERVAL_MS
    long now = System.currentTimeMillis();
    long lastVerify = lastHistoricalVerifyTime.get();
    if (now - lastVerify < HISTORICAL_VERIFY_INTERVAL_MS) {
      return;
    }

    // Only verify when in forward sync mode (not during binary search)
    if (syncState.get() != SyncState.FORWARD_SYNC) {
      return;
    }

    // Only one verification at a time
    if (!historicalVerifyInProgress.compareAndSet(false, true)) {
      return;
    }

    try {
      long localTipEpoch = getLocalTipEpoch();
      if (localTipEpoch <= 1) {
        return; // Only genesis, nothing to verify
      }

      // Calculate verification range: last HISTORICAL_VERIFY_DEPTH epochs
      long genesisEpoch = getGenesisEpoch();
      long startEpoch = Math.max(genesisEpoch + 1, localTipEpoch - HISTORICAL_VERIFY_DEPTH);
      long endEpoch = localTipEpoch;

      if (startEpoch >= endEpoch) {
        return; // Nothing to verify
      }

      log.debug("Historical verification: requesting epochs [{}-{}]", startEpoch, endEpoch);

      // Request historical epoch hashes
      // The response handler will compare with local and request missing/better blocks
      Channel channel = selectChannel(channels);
      channel.send(new GetEpochHashesMessage(startEpoch, endEpoch));

      lastHistoricalVerifyTime.set(now);

    } catch (Exception e) {
      log.warn("Historical verification failed: {}", e.getMessage());
    } finally {
      historicalVerifyInProgress.set(false);
    }
  }

  // Fork Detection

  /**
   * Initiate fork detection on first peer connection.
   *
   * <p>When nodes start simultaneously, they mine independently
   * before P2P sync kicks in. This creates divergent chains for early epochs.
   *
   * <p>On first peer connection, we scan from genesis to find the fork point
   * (last common epoch), then reorganize from there.
   *
   * @param channels active P2P channels
   */
  private void maybeInitiateForkDetection(List<Channel> channels) {
    // Only run once per session
    if (initialHistoricalScanDone.get()) {
      return;
    }

    // Skip if in other sync states
    SyncState currentState = syncState.get();
    if (currentState != SyncState.FORWARD_SYNC) {
      return;
    }

    // Need at least genesis + 1 block
    long mainChainLength = dagChain.getMainChainLength();
    if (mainChainLength <= 1) {
      return;
    }

    // Mark as done immediately to prevent re-triggering
    if (!initialHistoricalScanDone.compareAndSet(false, true)) {
      return;
    }

    long genesisEpoch = getGenesisEpoch();
    long localTipEpoch = getLocalTipEpoch();

    log.info("Initiating fork detection scan from epoch {} to {}",
        genesisEpoch, localTipEpoch);

    // Start fork detection from genesis
    forkDetectionCurrentEpoch.set(genesisEpoch);
    forkPointEpoch.set(genesisEpoch); // Assume genesis is common ancestor
    reorgTargetEpoch.set(localTipEpoch);
    syncState.set(SyncState.FORK_DETECTION);
  }

  /**
   * Perform fork detection by scanning epochs from genesis forward.
   *
   * <p>Request epochs in batches and compare local winner hashes with peer hashes.
   * The fork point is the last epoch where both chains have the same winner.
   *
   * @param channels active P2P channels
   */
  private void performForkDetection(List<Channel> channels) {
    long currentEpoch = forkDetectionCurrentEpoch.get();
    long targetEpoch = reorgTargetEpoch.get();

    if (currentEpoch < 0 || currentEpoch > targetEpoch) {
      // Fork detection complete - no divergence found
      log.info("Fork detection complete, no divergence found");
      transitionFromForkDetection();
      return;
    }

    // Request next batch of epochs
    long endEpoch = Math.min(currentEpoch + FORK_DETECTION_BATCH_SIZE - 1, targetEpoch);

    log.debug("Fork detection: scanning epochs [{}-{}]", currentEpoch, endEpoch);

    Channel channel = selectChannel(channels);
    try {
      channel.send(new GetEpochHashesMessage(currentEpoch, endEpoch));
    } catch (Exception e) {
      log.warn("Failed to send fork detection request: {}", e.getMessage());
    }
  }

  /**
   * Handle fork detection response.
   *
   * <p>Called from XdagP2pEventHandler when receiving EPOCH_HASHES_REPLY during fork detection.
   * Compares each epoch's winner hash with peer's hash to find divergence point.
   *
   * @param peerEpochHashes map of epoch -> list of block hashes from peer
   */
  public void onForkDetectionResponse(Map<Long, List<Bytes32>> peerEpochHashes) {
    if (syncState.get() != SyncState.FORK_DETECTION) {
      return;
    }

    long scanStart = forkDetectionCurrentEpoch.get();
    long targetEpoch = reorgTargetEpoch.get();
    long endOfBatch = Math.min(scanStart + FORK_DETECTION_BATCH_SIZE - 1, targetEpoch);

    // Check each epoch in the batch
    for (long epoch = scanStart; epoch <= endOfBatch; epoch++) {
      Block localWinner = dagChain.getWinnerBlockInEpoch(epoch);
      List<Bytes32> peerHashes = peerEpochHashes.get(epoch);

      if (localWinner == null) {
        // No local winner for this epoch - this is fine, continue
        continue;
      }

      Bytes32 localWinnerHash = localWinner.getHash();

      if (peerHashes == null || peerHashes.isEmpty()) {
        // Peer doesn't have blocks for this epoch - possible divergence
        // Keep scanning to find where peer's chain actually starts
        continue;
      }

      // Check if peer has the same winner
      boolean peerHasLocalWinner = peerHashes.contains(localWinnerHash);

      if (!peerHasLocalWinner) {
        // Found divergence! Peer has different blocks for this epoch
        // Check if peer has a BETTER (smaller hash) block
        Bytes32 peerBestHash = peerHashes.stream()
            .min(Bytes32::compareTo)
            .orElse(null);

        if (peerBestHash != null && peerBestHash.compareTo(localWinnerHash) < 0) {
          // Peer has better block - this is the fork point
          log.info("Found fork at epoch {}. Peer has better hash {} (local: {})",
              epoch,
              peerBestHash.toHexString().substring(0, 18),
              localWinnerHash.toHexString().substring(0, 18));

          // The fork point is the previous epoch (last common ancestor)
          long lastCommonEpoch = findLastCommonEpoch(epoch - 1, scanStart);
          forkPointEpoch.set(lastCommonEpoch);

          // Transition to chain reorganization
          transitionToChainReorganization();
          return;
        }
      } else {
        // Peer has our winner - update fork point to this epoch
        forkPointEpoch.set(epoch);
      }
    }

    // Advance to next batch
    long nextStart = endOfBatch + 1;
    if (nextStart > targetEpoch) {
      // Scan complete - no divergence requiring reorganization
      log.info("Fork detection complete, chains are consistent");
      transitionFromForkDetection();
    } else {
      forkDetectionCurrentEpoch.set(nextStart);
    }
  }

  /**
   * Find the last common epoch before the divergence point.
   *
   * @param startEpoch epoch to start searching backwards from
   * @param minEpoch minimum epoch to search (genesis)
   * @return last common epoch
   */
  private long findLastCommonEpoch(long startEpoch, long minEpoch) {
    // For now, return the epoch just before divergence
    // In a more sophisticated implementation, we could walk backwards
    // comparing hashes until we find a match
    return Math.max(startEpoch, minEpoch);
  }

  /**
   * Transition from fork detection to chain reorganization.
   */
  private void transitionToChainReorganization() {
    long forkPoint = forkPointEpoch.get();
    long target = reorgTargetEpoch.get();

    log.info("Starting chain reorganization from epoch {} to {}",
        forkPoint + 1, target);

    // Reset forward sync to start from fork point
    lastRequestedEpoch.set(forkPoint);

    syncState.set(SyncState.CHAIN_REORGANIZATION);
  }

  /**
   * Transition from fork detection back to normal forward sync.
   */
  private void transitionFromForkDetection() {
    log.debug("Transitioning from fork detection to forward sync");

    // Reset fork detection state
    forkDetectionCurrentEpoch.set(-1);
    forkPointEpoch.set(-1);
    reorgTargetEpoch.set(-1);

    syncState.set(SyncState.FORWARD_SYNC);
  }

  /**
   * Perform chain reorganization by syncing from fork point.
   *
   * <p>Requests blocks for all epochs from fork point + 1 to target epoch.
   * When complete, transitions back to forward sync.
   *
   * @param channels active P2P channels
   */
  private void performChainReorganization(List<Channel> channels) {
    long forkPoint = forkPointEpoch.get();
    long target = reorgTargetEpoch.get();
    long lastRequested = lastRequestedEpoch.get();

    // Calculate next request range
    long startEpoch = Math.max(forkPoint + 1, lastRequested + 1);
    long endEpoch = Math.min(startEpoch + MAX_EPOCHS_PER_REQUEST - 1, target);

    if (startEpoch > target) {
      // Reorganization complete
      log.info("Chain reorganization complete");
      transitionFromChainReorganization();
      return;
    }

    log.debug("Chain reorganization: requesting epochs [{}-{}]", startEpoch, endEpoch);

    Channel channel = selectChannel(channels);
    sendEpochRequest(channel, startEpoch, endEpoch);
  }

  /**
   * Transition from chain reorganization back to forward sync.
   */
  private void transitionFromChainReorganization() {
    log.debug("Transitioning from chain reorganization to forward sync");

    // Reset state
    forkPointEpoch.set(-1);
    reorgTargetEpoch.set(-1);

    syncState.set(SyncState.FORWARD_SYNC);
  }

  /**
   * Check if currently in fork detection mode.
   *
   * @return true if in fork detection mode
   */
  public boolean isInForkDetection() {
    return syncState.get() == SyncState.FORK_DETECTION;
  }

  /**
   * Check if currently in chain reorganization mode.
   *
   * @return true if in chain reorganization mode
   */
  public boolean isInChainReorganization() {
    return syncState.get() == SyncState.CHAIN_REORGANIZATION;
  }

  /**
   * Mark initial historical scan as done.
   * Also resets historical verification timer to prevent immediate verification.
   * Package-private for testing only.
   */
  void markInitialHistoricalScanDone() {
    initialHistoricalScanDone.set(true);
    // Also set last verify time to prevent historical verification from running immediately
    lastHistoricalVerifyTime.set(System.currentTimeMillis());
  }

  /**
   * Get the genesis epoch from the chain.
   *
   * @return genesis epoch, or 0 if not available
   */
  private long getGenesisEpoch() {
    try {
      Block genesis = dagChain.getMainBlockByHeight(1);
      if (genesis != null) {
        return genesis.getEpoch();
      }
    } catch (Exception e) {
      log.debug("Failed to get genesis epoch: {}", e.getMessage());
    }
    return 0;
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

    // Single-epoch response - can't reliably determine if earlier epochs are empty
    // The peer might be syncing or only have partial data
    if (receivedEpochs.size() == 1) {
      log.debug("Single epoch response ({}), skipping empty epoch detection", maxReceivedEpoch);
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
