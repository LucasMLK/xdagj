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

package io.xdag.consensus;

import io.xdag.DagKernel;
import io.xdag.core.*;
import io.xdag.net.message.consensus.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HybridSyncManager - Hybrid Synchronization Protocol Manager
 *
 * <p><strong>Purpose</strong>:
 * Implements the hybrid sync protocol for XDAG v5.1, combining linear main chain
 * synchronization with DAG area synchronization for optimal performance.
 *
 * <p><strong>Protocol Overview</strong>:
 * <pre>
 * Phase 1: Linear Main Chain Sync
 * ├─ Query remote height
 * ├─ Batch download main blocks (1000 blocks/batch)
 * └─ Import blocks sequentially
 *
 * Phase 2: DAG Area Sync
 * ├─ Query epoch block hashes
 * ├─ Identify missing blocks
 * └─ Batch download missing blocks
 *
 * Phase 3: Solidification
 * ├─ Identify missing block references
 * ├─ Batch download missing blocks
 * ├─ Identify missing transactions
 * └─ Batch download missing transactions
 * </pre>
 *
 * <p><strong>Key Features</strong>:
 * <ul>
 *   <li>Batch synchronization (1000 blocks per request)</li>
 *   <li>Separate handling of finalized chain and active DAG</li>
 *   <li>Efficient transaction solidification</li>
 *   <li>Progress tracking and reporting</li>
 * </ul>
 *
 * <p><strong>Performance Targets</strong>:
 * <ul>
 *   <li>Sync 1M blocks in ~15-20 minutes (vs hours with legacy sync)</li>
 *   <li>~1000 network roundtrips (vs ~11000 with single-block requests)</li>
 *   <li>Network traffic reduction: ~30% (with compression)</li>
 * </ul>
 *
 * @see <a href="../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol Design</a>
 * @see <a href="../../../DAG_SYNC_PROTOCOL_GAP_ANALYSIS.md">Protocol Gap Analysis</a>
 * @since v5.1 Phase 1.5
 */
@Slf4j
public class HybridSyncManager {

    // ========== Configuration ==========

    /**
     * Finality boundary: blocks older than this are considered finalized
     * 16384 epochs ≈ 12.14 days
     */
    public static final int FINALITY_EPOCHS = 16384;

    /**
     * Batch size for main chain synchronization
     */
    public static final int MAIN_CHAIN_BATCH_SIZE = 1000;

    /**
     * Batch size for block requests (by hash)
     */
    public static final int BLOCKS_BATCH_SIZE = 1000;

    /**
     * Batch size for transaction requests
     */
    public static final int TRANSACTIONS_BATCH_SIZE = 5000;

    /**
     * Sync check interval (30 seconds)
     */
    public static final long SYNC_CHECK_INTERVAL_MS = 30_000;

    /**
     * Sync retry interval after failure (5 minutes)
     */
    public static final long SYNC_RETRY_INTERVAL_MS = 300_000;

    // ========== Dependencies ==========

    private final DagKernel dagKernel;  // v5.1 standalone DagKernel
    private final DagChain dagChain;  // v5.1 DagChain interface
    private final HybridSyncP2pAdapter p2pAdapter;

    // ========== State Tracking ==========

    /**
     * Current synchronization state
     */
    public enum SyncState {
        IDLE,                    // Not synchronizing
        QUERYING_HEIGHT,         // Querying remote height
        SYNCING_MAIN_CHAIN,      // Syncing linear main chain
        SYNCING_DAG_AREA,        // Syncing DAG area
        SOLIDIFYING,             // Solidifying missing blocks/transactions
        COMPLETED                // Synchronization completed
    }

    private volatile SyncState currentState = SyncState.IDLE;

    /**
     * Progress tracking
     */
    private final AtomicLong syncedBlocks = new AtomicLong(0);
    private final AtomicLong totalBlocks = new AtomicLong(0);
    private final AtomicLong syncedTransactions = new AtomicLong(0);

    // ========== Lifecycle Management ==========

    /**
     * Running state
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Sync scheduler for periodic sync checks
     */
    private ScheduledExecutorService syncScheduler;

    /**
     * Last sync attempt timestamp
     */
    private volatile long lastSyncAttemptTime = 0;

    /**
     * Last successful sync timestamp
     */
    private volatile long lastSuccessfulSyncTime = 0;

    // ========== Constructor ==========

    public HybridSyncManager(DagKernel dagKernel, DagChain dagChain) {
        this.dagKernel = dagKernel;
        this.dagChain = dagChain;
        this.p2pAdapter = new HybridSyncP2pAdapter();
    }

    public HybridSyncManager(DagKernel dagKernel, DagChain dagChain, HybridSyncP2pAdapter p2pAdapter) {
        this.dagKernel = dagKernel;
        this.dagChain = dagChain;
        this.p2pAdapter = p2pAdapter;
    }

    // ========== Public API ==========

    /**
     * Start hybrid synchronization with a remote peer
     *
     * <p>This is the main entry point for the hybrid sync protocol.
     * It will:
     * <ol>
     *   <li>Query remote height</li>
     *   <li>Sync finalized main chain (if behind)</li>
     *   <li>Sync active DAG area</li>
     *   <li>Solidify missing blocks and transactions</li>
     * </ol>
     *
     * @param remotePeerChannel P2P channel to sync from
     * @return true if sync completed successfully, false otherwise
     */
    public boolean startSync(Object remotePeerChannel) {
        log.info("Starting hybrid synchronization...");
        currentState = SyncState.QUERYING_HEIGHT;

        try {
            // Step 1: Query remote height
            RemoteHeightInfo remoteInfo = queryRemoteHeight(remotePeerChannel);
            if (remoteInfo == null) {
                log.error("Failed to query remote height");
                return false;
            }

            long localHeight = dagChain.getMainChainLength();  // v5.1: use getMainChainLength()
            long remoteHeight = remoteInfo.mainHeight;
            long finalizedHeight = remoteInfo.finalizedHeight;

            log.info("Height info: local={}, remote={}, finalized={}",
                    localHeight, remoteHeight, finalizedHeight);

            // Check if we're already synced
            if (localHeight >= remoteHeight) {
                log.info("Already synced (local {} >= remote {})", localHeight, remoteHeight);
                currentState = SyncState.COMPLETED;
                return true;
            }

            // Calculate total blocks to sync
            totalBlocks.set(remoteHeight - localHeight);

            // Step 2: Sync finalized main chain (if we're behind)
            if (localHeight < finalizedHeight) {
                currentState = SyncState.SYNCING_MAIN_CHAIN;
                log.info("Syncing finalized main chain: {} -> {}", localHeight, finalizedHeight);

                if (!syncFinalizedChain(remotePeerChannel, localHeight, finalizedHeight)) {
                    log.error("Failed to sync finalized chain");
                    return false;
                }
            }

            // Step 3: Sync active DAG area (recent blocks)
            currentState = SyncState.SYNCING_DAG_AREA;
            long dagStartHeight = Math.max(localHeight, finalizedHeight);

            if (dagStartHeight < remoteHeight) {
                log.info("Syncing active DAG area: {} -> {}", dagStartHeight, remoteHeight);

                if (!syncActiveDAG(remotePeerChannel, dagStartHeight, remoteHeight)) {
                    log.error("Failed to sync active DAG");
                    return false;
                }
            }

            // Step 4: Solidification - fill in missing blocks and transactions
            currentState = SyncState.SOLIDIFYING;
            log.info("Starting solidification phase...");

            if (!solidifyChain(remotePeerChannel)) {
                log.warn("Solidification phase completed with warnings");
            }

            currentState = SyncState.COMPLETED;
            log.info("Hybrid synchronization completed successfully!");
            log.info("Synced {} blocks, {} transactions", syncedBlocks.get(), syncedTransactions.get());

            return true;

        } catch (Exception e) {
            log.error("Hybrid sync failed", e);
            currentState = SyncState.IDLE;
            return false;
        }
    }

    /**
     * Get current synchronization progress (0.0 to 1.0)
     *
     * @return progress ratio
     */
    public double getProgress() {
        long total = totalBlocks.get();
        if (total == 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) syncedBlocks.get() / total);
    }

    /**
     * Get current synchronization state
     *
     * @return current state
     */
    public SyncState getCurrentState() {
        return currentState;
    }

    /**
     * Get P2P adapter for connecting to network layer
     *
     * @return HybridSyncP2pAdapter instance
     */
    public HybridSyncP2pAdapter getP2pAdapter() {
        return p2pAdapter;
    }

    /**
     * Check if sync manager is running
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }

    // ========== Lifecycle Management ==========

    /**
     * Start the HybridSyncManager
     *
     * <p>This method starts the periodic sync scheduler that will:
     * <ul>
     *   <li>Check for available peers every 30 seconds</li>
     *   <li>Automatically trigger sync when peers are available</li>
     *   <li>Retry failed syncs after 5 minutes</li>
     * </ul>
     *
     * <p>NOTE: This is a framework method that will work properly once P2P layer is integrated.
     * Currently it sets up the scheduler but won't trigger actual syncs until peers are available.
     */
    public synchronized void start() {
        if (running.getAndSet(true)) {
            log.warn("HybridSyncManager already started");
            return;
        }

        log.info("========================================");
        log.info("Starting HybridSyncManager");
        log.info("========================================");

        // Create scheduler for periodic sync checks
        syncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "HybridSync-Scheduler");
            thread.setDaemon(true);
            return thread;
        });

        // Schedule periodic sync checks (every 30 seconds)
        syncScheduler.scheduleWithFixedDelay(
                this::checkAndTriggerSync,
                10,  // Initial delay: 10 seconds
                SYNC_CHECK_INTERVAL_MS / 1000,  // Period: 30 seconds
                TimeUnit.SECONDS
        );

        log.info("✓ HybridSyncManager started");
        log.info("  - Periodic sync check: every {} seconds", SYNC_CHECK_INTERVAL_MS / 1000);
        log.info("  - Sync retry interval: {} seconds", SYNC_RETRY_INTERVAL_MS / 1000);
        log.info("========================================");
    }

    /**
     * Stop the HybridSyncManager
     *
     * <p>This method:
     * <ul>
     *   <li>Stops the periodic sync scheduler</li>
     *   <li>Waits for any ongoing sync to complete</li>
     *   <li>Cleans up resources</li>
     * </ul>
     */
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            log.warn("HybridSyncManager not running");
            return;
        }

        log.info("========================================");
        log.info("Stopping HybridSyncManager");
        log.info("========================================");

        // Shutdown scheduler
        if (syncScheduler != null) {
            syncScheduler.shutdown();
            try {
                // Wait up to 30 seconds for graceful shutdown
                if (!syncScheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    syncScheduler.shutdownNow();
                    log.warn("Sync scheduler forcefully terminated");
                }
            } catch (InterruptedException e) {
                syncScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            syncScheduler = null;
        }

        log.info("✓ HybridSyncManager stopped");
        log.info("========================================");
    }

    /**
     * Check if sync is needed and trigger if necessary
     *
     * <p>This method is called periodically by the sync scheduler.
     * It checks:
     * <ul>
     *   <li>If there are available peers</li>
     *   <li>If we're not currently syncing</li>
     *   <li>If enough time has passed since last sync attempt</li>
     * </ul>
     *
     * <p>NOTE: Currently this is a framework method. Once P2P layer is integrated,
     * it will check for actual peer connections and trigger sync.
     */
    private void checkAndTriggerSync() {
        try {
            // Skip if already syncing
            if (currentState != SyncState.IDLE && currentState != SyncState.COMPLETED) {
                log.debug("Sync already in progress (state: {}), skipping check", currentState);
                return;
            }

            // Check if enough time has passed since last attempt
            long now = System.currentTimeMillis();
            long timeSinceLastAttempt = now - lastSyncAttemptTime;

            // If last sync failed, wait longer before retrying
            if (lastSyncAttemptTime > 0 && timeSinceLastAttempt < SYNC_RETRY_INTERVAL_MS) {
                log.debug("Waiting for retry interval ({} ms remaining)",
                        SYNC_RETRY_INTERVAL_MS - timeSinceLastAttempt);
                return;
            }

            // TODO Phase 12.4: Check for available peers
            // Once P2P layer is integrated, this will:
            // 1. Get list of connected peers from DagKernel
            // 2. Select best peer based on height/latency
            // 3. Trigger sync with selected peer
            //
            // For now, just log that we're ready to sync
            log.debug("Sync check: No P2P integration yet, waiting for peers...");

            // Example of what will be implemented:
            // List<Channel> peers = getAvailablePeers();
            // if (!peers.isEmpty()) {
            //     Channel bestPeer = selectBestPeer(peers);
            //     log.info("Triggering sync with peer: {}", bestPeer.getRemoteAddress());
            //     lastSyncAttemptTime = now;
            //     boolean success = startSync(bestPeer);
            //     if (success) {
            //         lastSuccessfulSyncTime = System.currentTimeMillis();
            //     }
            // }

        } catch (Exception e) {
            log.error("Error in sync check", e);
        }
    }

    // ========== Phase 1: Query Remote Height ==========

    /**
     * Remote height information
     */
    private static class RemoteHeightInfo {
        final long mainHeight;
        final long finalizedHeight;
        final Bytes32 mainBlockHash;

        RemoteHeightInfo(long mainHeight, long finalizedHeight, Bytes32 mainBlockHash) {
            this.mainHeight = mainHeight;
            this.finalizedHeight = finalizedHeight;
            this.mainBlockHash = mainBlockHash;
        }
    }

    /**
     * Query remote peer's height information
     *
     * <p>Sends SyncHeightRequestMessage and waits for SyncHeightReplyMessage.
     *
     * @param channel P2P channel
     * @return remote height info, or null if failed
     */
    private RemoteHeightInfo queryRemoteHeight(Object channel) {
        log.debug("Querying remote height...");

        try {
            // Cast to P2P channel
            io.xdag.p2p.channel.Channel p2pChannel = (io.xdag.p2p.channel.Channel) channel;

            // Send request via adapter
            var future = p2pAdapter.requestHeight(p2pChannel);

            // Wait for reply (with 30 second timeout)
            SyncHeightReplyMessage reply = future.get(30, java.util.concurrent.TimeUnit.SECONDS);

            if (reply == null) {
                log.error("Received null height reply");
                return null;
            }

            log.debug("Received height reply: mainHeight={}, finalizedHeight={}, tipHash={}",
                    reply.getMainHeight(), reply.getFinalizedHeight(),
                    reply.getMainBlockHash().toHexString());

            return new RemoteHeightInfo(
                    reply.getMainHeight(),
                    reply.getFinalizedHeight(),
                    reply.getMainBlockHash()
            );

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Query remote height timed out", e);
            return null;
        } catch (Exception e) {
            log.error("Failed to query remote height", e);
            return null;
        }
    }

    // ========== Phase 2: Sync Finalized Main Chain ==========

    /**
     * Synchronize finalized main chain blocks in batches
     *
     * <p>This phase syncs the main chain up to the finalization boundary.
     * These blocks are considered immutable and can be synced linearly.
     *
     * @param channel P2P channel
     * @param fromHeight start height (inclusive)
     * @param toHeight end height (inclusive)
     * @return true if successful, false otherwise
     */
    private boolean syncFinalizedChain(Object channel, long fromHeight, long toHeight) {
        log.info("Syncing finalized chain: {} to {}", fromHeight, toHeight);

        long currentHeight = fromHeight;

        while (currentHeight < toHeight) {
            // Calculate batch range
            long batchEnd = Math.min(currentHeight + MAIN_CHAIN_BATCH_SIZE - 1, toHeight);

            log.debug("Requesting main blocks: {} to {}", currentHeight, batchEnd);

            // Request batch of main blocks
            List<Block> blocks = requestMainBlocks(channel, currentHeight, batchEnd);

            if (blocks == null || blocks.isEmpty()) {
                log.error("Failed to fetch main blocks [{}, {}]", currentHeight, batchEnd);
                return false;
            }

            // Import blocks sequentially
            for (int i = 0; i < blocks.size(); i++) {
                Block block = blocks.get(i);

                if (block != null) {
                    // Import block
                    boolean imported = importBlock(block);

                    if (imported) {
                        syncedBlocks.incrementAndGet();
                    } else {
                        log.warn("Failed to import block at height {}", currentHeight + i);
                    }
                } else {
                    log.warn("Missing block at height {}", currentHeight + i);
                }
            }

            currentHeight += blocks.size();

            // Log progress
            long progress = ((currentHeight - fromHeight) * 100) / (toHeight - fromHeight);
            log.info("Main chain sync progress: {}% ({}/{})", progress, currentHeight - fromHeight, toHeight - fromHeight);
        }

        log.info("Finalized chain sync completed");
        return true;
    }

    /**
     * Request a batch of main chain blocks by height range
     *
     * @param channel P2P channel
     * @param fromHeight start height
     * @param toHeight end height
     * @return list of blocks (may contain nulls for missing blocks)
     */
    private List<Block> requestMainBlocks(Object channel, long fromHeight, long toHeight) {
        log.debug("Requesting main blocks: {} to {}", fromHeight, toHeight);

        try {
            // Cast to P2P channel
            io.xdag.p2p.channel.Channel p2pChannel = (io.xdag.p2p.channel.Channel) channel;

            // Send request via adapter
            var future = p2pAdapter.requestMainBlocks(
                    p2pChannel, fromHeight, toHeight, MAIN_CHAIN_BATCH_SIZE, false);

            // Wait for reply (with 30 second timeout)
            SyncMainBlocksReplyMessage reply = future.get(30, java.util.concurrent.TimeUnit.SECONDS);

            if (reply == null) {
                log.error("Received null main blocks reply");
                return Collections.emptyList();
            }

            log.debug("Received {} main blocks", reply.getBlocks().size());
            return reply.getBlocks();

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Request main blocks timed out", e);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to request main blocks", e);
            return Collections.emptyList();
        }
    }

    // ========== Phase 3: Sync Active DAG Area ==========

    /**
     * Synchronize active DAG area (recent blocks)
     *
     * <p>This phase syncs the DAG area that's still being built.
     * Uses epoch-based sync to efficiently handle the DAG structure.
     *
     * @param channel P2P channel
     * @param fromHeight start height
     * @param toHeight end height
     * @return true if successful, false otherwise
     */
    private boolean syncActiveDAG(Object channel, long fromHeight, long toHeight) {
        log.info("Syncing active DAG: {} to {}", fromHeight, toHeight);

        // Convert heights to epochs
        long startEpoch = fromHeight / 64;  // timestamp / 64
        long endEpoch = toHeight / 64;

        for (long epoch = startEpoch; epoch <= endEpoch; epoch++) {
            log.debug("Syncing epoch: {}", epoch);

            // Request all block hashes in this epoch
            List<Bytes32> epochHashes = requestEpochBlocks(channel, epoch);

            if (epochHashes == null) {
                log.warn("Failed to fetch epoch {} blocks", epoch);
                continue;
            }

            log.debug("Epoch {} has {} blocks", epoch, epochHashes.size());

            // Filter out blocks we already have
            List<Bytes32> missingHashes = new ArrayList<>();
            for (Bytes32 hash : epochHashes) {
                if (dagChain.getBlockByHash(hash, false) == null) {  // v5.1: use dagChain
                    missingHashes.add(hash);
                }
            }

            if (missingHashes.isEmpty()) {
                log.debug("All blocks in epoch {} already present", epoch);
                continue;
            }

            log.debug("Requesting {} missing blocks from epoch {}", missingHashes.size(), epoch);

            // Batch request missing blocks
            List<Block> blocks = requestBlocks(channel, missingHashes);

            if (blocks != null) {
                // Import fetched blocks
                for (Block block : blocks) {
                    if (block != null) {
                        importBlock(block);
                        syncedBlocks.incrementAndGet();
                    }
                }
            }
        }

        log.info("Active DAG sync completed");
        return true;
    }

    /**
     * Request all block hashes in a specific epoch
     *
     * @param channel P2P channel
     * @param epoch epoch number
     * @return list of block hashes in this epoch
     */
    private List<Bytes32> requestEpochBlocks(Object channel, long epoch) {
        log.debug("Requesting epoch {} blocks", epoch);

        try {
            // Cast to P2P channel
            io.xdag.p2p.channel.Channel p2pChannel = (io.xdag.p2p.channel.Channel) channel;

            // Send request via adapter
            var future = p2pAdapter.requestEpochBlocks(p2pChannel, epoch);

            // Wait for reply (with 30 second timeout)
            SyncEpochBlocksReplyMessage reply = future.get(30, java.util.concurrent.TimeUnit.SECONDS);

            if (reply == null) {
                log.error("Received null epoch blocks reply");
                return Collections.emptyList();
            }

            log.debug("Received {} block hashes for epoch {}", reply.getHashes().size(), epoch);
            return reply.getHashes();

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Request epoch blocks timed out", e);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to request epoch blocks", e);
            return Collections.emptyList();
        }
    }

    /**
     * Request a batch of blocks by their hashes
     *
     * @param channel P2P channel
     * @param hashes list of block hashes
     * @return list of blocks (may contain nulls for missing blocks)
     */
    private List<Block> requestBlocks(Object channel, List<Bytes32> hashes) {
        log.debug("Requesting {} blocks by hash", hashes.size());

        try {
            // Cast to P2P channel
            io.xdag.p2p.channel.Channel p2pChannel = (io.xdag.p2p.channel.Channel) channel;

            // Send request via adapter
            var future = p2pAdapter.requestBlocks(p2pChannel, hashes, false);

            // Wait for reply (with 30 second timeout)
            SyncBlocksReplyMessage reply = future.get(30, java.util.concurrent.TimeUnit.SECONDS);

            if (reply == null) {
                log.error("Received null blocks reply");
                return Collections.emptyList();
            }

            log.debug("Received {} blocks", reply.getBlocks().size());
            return reply.getBlocks();

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Request blocks timed out", e);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to request blocks", e);
            return Collections.emptyList();
        }
    }

    // ========== Phase 4: Solidification ==========

    /**
     * Solidify the chain by filling in missing blocks and transactions
     *
     * <p>This phase identifies and fetches:
     * <ul>
     *   <li>Missing block references (parent blocks, referenced blocks)</li>
     *   <li>Missing transactions referenced by blocks</li>
     * </ul>
     *
     * @param channel P2P channel
     * @return true if successful, false otherwise
     */
    private boolean solidifyChain(Object channel) {
        log.info("Starting solidification phase...");

        // Step 1: Identify missing block references
        Set<Bytes32> missingBlockHashes = identifyMissingBlocks();

        if (!missingBlockHashes.isEmpty()) {
            log.info("Found {} missing block references", missingBlockHashes.size());

            // Batch request missing blocks
            List<Bytes32> hashList = new ArrayList<>(missingBlockHashes);

            for (int i = 0; i < hashList.size(); i += BLOCKS_BATCH_SIZE) {
                int end = Math.min(i + BLOCKS_BATCH_SIZE, hashList.size());
                List<Bytes32> batch = hashList.subList(i, end);

                List<Block> blocks = requestBlocks(channel, batch);

                if (blocks != null) {
                    for (Block block : blocks) {
                        if (block != null) {
                            importBlock(block);
                        }
                    }
                }
            }
        }

        // Step 2: Identify missing transactions
        Set<Bytes32> missingTxHashes = identifyMissingTransactions();

        if (!missingTxHashes.isEmpty()) {
            log.info("Found {} missing transaction references", missingTxHashes.size());

            // Batch request missing transactions
            List<Bytes32> txHashList = new ArrayList<>(missingTxHashes);

            for (int i = 0; i < txHashList.size(); i += TRANSACTIONS_BATCH_SIZE) {
                int end = Math.min(i + TRANSACTIONS_BATCH_SIZE, txHashList.size());
                List<Bytes32> batch = txHashList.subList(i, end);

                List<Transaction> transactions = requestTransactions(channel, batch);

                if (transactions != null) {
                    for (Transaction tx : transactions) {
                        if (tx != null) {
                            importTransaction(tx);
                            syncedTransactions.incrementAndGet();
                        }
                    }
                }
            }
        }

        log.info("Solidification completed");
        return true;
    }

    /**
     * Identify missing block references
     *
     * @return set of missing block hashes
     */
    private Set<Bytes32> identifyMissingBlocks() {
        log.debug("Identifying missing block references...");

        // TODO: Implement block reference scanning
        // Scan all blocks and check if referenced blocks exist
        // For now, return empty set (will be implemented later)

        return Collections.emptySet();
    }

    /**
     * Identify missing transaction references
     *
     * @return set of missing transaction hashes
     */
    private Set<Bytes32> identifyMissingTransactions() {
        log.debug("Identifying missing transaction references...");

        // TODO: Implement transaction reference scanning
        // Scan all blocks and check if referenced transactions exist
        // For now, return empty set (will be implemented later)

        return Collections.emptySet();
    }

    /**
     * Request a batch of transactions by their hashes
     *
     * @param channel P2P channel
     * @param hashes list of transaction hashes
     * @return list of transactions (may contain nulls for missing transactions)
     */
    private List<Transaction> requestTransactions(Object channel, List<Bytes32> hashes) {
        log.debug("Requesting {} transactions by hash", hashes.size());

        try {
            // Cast to P2P channel
            io.xdag.p2p.channel.Channel p2pChannel = (io.xdag.p2p.channel.Channel) channel;

            // Send request via adapter
            var future = p2pAdapter.requestTransactions(p2pChannel, hashes);

            // Wait for reply (with 30 second timeout)
            SyncTransactionsReplyMessage reply = future.get(30, java.util.concurrent.TimeUnit.SECONDS);

            if (reply == null) {
                log.error("Received null transactions reply");
                return Collections.emptyList();
            }

            log.debug("Received {} transactions", reply.getTransactions().size());
            return reply.getTransactions();

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Request transactions timed out", e);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to request transactions", e);
            return Collections.emptyList();
        }
    }

    // ========== Import Helpers ==========

    /**
     * Import a block into the blockchain
     *
     * @param block block to import
     * @return true if imported successfully, false otherwise
     */
    private boolean importBlock(Block block) {
        try {
            // v5.1: Use DagChain.tryToConnect() which returns DagImportResult
            DagImportResult result = dagChain.tryToConnect(block);

            return result != null &&
                   (result.getStatus() == DagImportResult.ImportStatus.SUCCESS);

        } catch (Exception e) {
            log.error("Failed to import block: {}", block.getHash(), e);
            return false;
        }
    }

    /**
     * Import a transaction
     *
     * @param transaction transaction to import
     * @return true if imported successfully, false otherwise
     */
    private boolean importTransaction(Transaction transaction) {
        try {
            // TODO: Implement transaction import
            // For now, just log (will be implemented later)
            log.debug("Importing transaction: {}", transaction.getHash());
            return true;

        } catch (Exception e) {
            log.error("Failed to import transaction: {}", transaction.getHash(), e);
            return false;
        }
    }
}
