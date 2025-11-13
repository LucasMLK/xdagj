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

package io.xdag.consensus.pow;

import io.xdag.config.Config;
import io.xdag.config.MainnetConfig;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;

import static io.xdag.config.RandomXConstants.*;

/**
 * RandomX Snapshot Loader
 *
 * <p>Professional snapshot loading service for RandomX state recovery.
 * Provides unified snapshot loading with flexible strategy selection.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Load RandomX state from blockchain snapshots</li>
 *   <li>Initialize seeds from historical blocks</li>
 *   <li>Support multiple loading strategies for different scenarios</li>
 *   <li>Coordinate with seed manager for state reconstruction</li>
 * </ul>
 *
 * <h2>Loading Strategies</h2>
 * <table border="1">
 *   <tr>
 *     <th>Strategy</th>
 *     <th>Use Case</th>
 *     <th>Speed</th>
 *   </tr>
 *   <tr>
 *     <td>WITH_PRESEED</td>
 *     <td>Recent snapshot (same epoch)</td>
 *     <td>Fast</td>
 *   </tr>
 *   <tr>
 *     <td>FROM_CURRENT_STATE</td>
 *     <td>Old snapshot (past epoch)</td>
 *     <td>Medium</td>
 *   </tr>
 *   <tr>
 *     <td>FROM_FORK_HEIGHT</td>
 *     <td>No snapshot (full sync)</td>
 *     <td>Slow</td>
 *   </tr>
 *   <tr>
 *     <td>AUTO</td>
 *     <td>Default (automatic selection)</td>
 *     <td>Varies</td>
 *   </tr>
 * </table>
 *
 * <h2>Seed Reconstruction Process</h2>
 * <pre>
 * 1. Calculate memory index from snapshot height
 * 2. Initialize preseed in seed manager
 * 3. Replay blocks from snapshot to current
 * 4. Verify fork time and epoch boundaries
 * </pre>
 *
 * @since XDAGJ v0.8.1
 */
@Slf4j
public class RandomXSnapshotLoader {

    // ========== Configuration ==========

    private final Config config;
    private final DagChain dagChain;
    private final RandomXSeedManager seedManager;

    /** Is this a testnet configuration? */
    private final boolean isTestNet;

    /** Blocks per seed epoch */
    private final long seedEpochBlocks;

    /** Lag period for seed calculation */
    private final long seedEpochLag;

    /** Fork activation height */
    private final long forkHeight;

    // ========== Constructor ==========

    /**
     * Creates a new snapshot loader.
     *
     * @param config System configuration
     * @param dagChain Blockchain instance
     * @param seedManager Seed manager to initialize
     */
    public RandomXSnapshotLoader(Config config, DagChain dagChain, RandomXSeedManager seedManager) {
        this.config = config;
        this.dagChain = dagChain;
        this.seedManager = seedManager;
        this.isTestNet = !(config instanceof MainnetConfig);

        // Initialize network-specific parameters
        if (isTestNet) {
            this.seedEpochBlocks = SEEDHASH_EPOCH_TESTNET_BLOCKS;
            this.seedEpochLag = SEEDHASH_EPOCH_TESTNET_LAG;
            this.forkHeight = RANDOMX_TESTNET_FORK_HEIGHT;
        } else {
            this.seedEpochBlocks = SEEDHASH_EPOCH_BLOCKS;
            this.seedEpochLag = SEEDHASH_EPOCH_LAG;
            this.forkHeight = RANDOMX_FORK_HEIGHT;
        }

        log.info("RandomXSnapshotLoader initialized: testnet={}, forkHeight={}, epochBlocks={}, lag={}",
                isTestNet, forkHeight, seedEpochBlocks, seedEpochLag);
    }

    // ========== Public API ==========

    /**
     * Load RandomX state from snapshot using specified strategy.
     *
     * <p>Unified snapshot loading method that supports multiple strategies:
     * <ul>
     *   <li><strong>WITH_PRESEED</strong>: Use pre-computed seed (fastest)</li>
     *   <li><strong>FROM_CURRENT_STATE</strong>: Reconstruct from current chain state</li>
     *   <li><strong>FROM_FORK_HEIGHT</strong>: Initialize from fork activation</li>
     *   <li><strong>AUTO</strong>: Automatically choose best strategy</li>
     * </ul>
     *
     * <p><strong>Strategy Selection Guide:</strong>
     * <pre>
     * // Fast startup with recent snapshot
     * loader.load(SnapshotStrategy.WITH_PRESEED, preseed);
     *
     * // Accurate reconstruction for old snapshot
     * loader.load(SnapshotStrategy.FROM_CURRENT_STATE, null);
     *
     * // Full sync without snapshot
     * loader.load(SnapshotStrategy.FROM_FORK_HEIGHT, null);
     *
     * // Let system decide (recommended)
     * loader.load(SnapshotStrategy.AUTO, preseed);
     * </pre>
     *
     * @param strategy Loading strategy to use
     * @param preseed Pre-computed seed (required for WITH_PRESEED and AUTO, optional for others)
     * @throws IllegalArgumentException if strategy is null or preseed required but not provided
     * @throws IllegalStateException if chain state is invalid for selected strategy
     */
    public void load(SnapshotStrategy strategy, byte[] preseed) {
        if (strategy == null) {
            throw new IllegalArgumentException("SnapshotStrategy cannot be null");
        }

        log.info("Loading RandomX state with strategy: {}", strategy);

        switch (strategy) {
            case WITH_PRESEED:
                loadWithPreseedInternal(preseed);
                break;

            case FROM_CURRENT_STATE:
                loadFromCurrentStateInternal();
                break;

            case FROM_FORK_HEIGHT:
                loadFromForkHeightInternal();
                break;

            case AUTO:
                loadAutoInternal(preseed);
                break;

            default:
                throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }

        log.info("RandomX state loaded successfully with strategy: {}", strategy);
    }

    // ========== Private Loading Methods ==========

    /**
     * Load using WITH_PRESEED strategy.
     *
     * <p>Uses pre-computed seed from snapshot for fast startup.
     *
     * @param preseed Pre-computed seed from snapshot
     */
    private void loadWithPreseedInternal(byte[] preseed) {
        if (preseed == null || preseed.length == 0) {
            throw new IllegalArgumentException("Preseed cannot be null or empty for WITH_PRESEED strategy");
        }

        long snapshotHeight = config.getSnapshotSpec().getSnapshotHeight();

        if (snapshotHeight < forkHeight) {
            log.warn("Snapshot height {} is before fork height {}, skipping RandomX initialization",
                    snapshotHeight, forkHeight);
            return;
        }

        log.info("Loading RandomX state from snapshot: height={}, preseed={}",
                snapshotHeight, Hex.toHexString(preseed));

        // Calculate memory index from snapshot height
        long memoryIndex = calculateMemoryIndex(snapshotHeight);

        // Initialize seed manager with preseed
        seedManager.initializeFromPreseed(preseed, memoryIndex);

        // Replay blocks from snapshot to current to update state
        long currentHeight = dagChain.getChainStats().getMainBlockCount();
        if (currentHeight > snapshotHeight) {
            log.info("Replaying blocks from {} to {} to update RandomX state",
                    snapshotHeight, currentHeight);
            replayBlocks(snapshotHeight, currentHeight);
        }
    }

    /**
     * Load using AUTO strategy.
     *
     * <p>Automatically chooses between WITH_PRESEED and FROM_CURRENT_STATE
     * based on whether chain has progressed past next epoch boundary.
     *
     * @param preseed Pre-computed seed (may or may not be used)
     */
    private void loadAutoInternal(byte[] preseed) {
        long snapshotHeight = config.getSnapshotSpec().getSnapshotHeight();
        long currentHeight = dagChain.getChainStats().getMainBlockCount();

        // Find next epoch boundary after snapshot
        long epochMask = seedEpochBlocks - 1;
        long nextEpochBoundary = (snapshotHeight + seedEpochBlocks) & ~epochMask;

        log.info("AUTO strategy: snapshot={}, current={}, nextEpoch={}",
                snapshotHeight, currentHeight, nextEpochBoundary);

        if (currentHeight < nextEpochBoundary) {
            // Chain hasn't progressed to next epoch yet, use preseed
            log.info("AUTO → WITH_PRESEED (chain hasn't reached next epoch boundary)");
            loadWithPreseedInternal(preseed);
        } else {
            // Chain has progressed past next epoch, reconstruct from current state
            log.info("AUTO → FROM_CURRENT_STATE (chain past next epoch boundary)");
            loadFromCurrentStateInternal();
        }
    }

    /**
     * Load using FROM_FORK_HEIGHT strategy.
     *
     * <p>Initializes RandomX from fork activation block without using preseed.
     * Used when no snapshot is available (full sync from genesis).
     */
    private void loadFromForkHeightInternal() {
        long currentHeight = dagChain.getChainStats().getMainBlockCount();

        if (currentHeight < forkHeight) {
            log.warn("Cannot load from fork height: current={} < fork={}",
                    currentHeight, forkHeight);
            return;
        }

        log.info("Loading RandomX state from fork height: {}", forkHeight);

        // Replay all blocks from fork height to current
        replayBlocks(forkHeight, currentHeight);
    }

    /**
     * Load using FROM_CURRENT_STATE strategy.
     *
     * <p>Reconstructs RandomX state by examining current chain height
     * and replaying necessary epoch boundary blocks.
     */
    private void loadFromCurrentStateInternal() {
        long snapshotHeight = config.getSnapshotSpec().getSnapshotHeight();
        long currentHeight = dagChain.getChainStats().getMainBlockCount();

        if (currentHeight < snapshotHeight) {
            throw new IllegalStateException(
                    String.format("Current height %d < snapshot height %d",
                            currentHeight, snapshotHeight));
        }

        log.info("Loading RandomX state from current state: height={}", currentHeight);

        // Find the epoch boundaries we need to replay
        long epochMask = seedEpochBlocks - 1;
        long currentEpochStart = currentHeight & ~epochMask;
        long previousEpochStart = currentEpochStart - seedEpochBlocks;

        // Replay from the start of previous epoch to current
        // This ensures both current and previous seeds are initialized
        long startHeight = Math.max(previousEpochStart, forkHeight);

        log.info("Replaying blocks from {} to {} for seed reconstruction",
                startHeight, currentHeight);

        replayBlocks(startHeight, currentHeight);
    }

    // ========== Private Helpers ==========

    /**
     * Calculate memory index from block height.
     *
     * <p>Memory index = epoch number since fork = (height - forkHeight) / epochBlocks
     *
     * @param height Block height
     * @return Memory index
     */
    private long calculateMemoryIndex(long height) {
        if (height < forkHeight) {
            return 0;
        }

        // Calculate which epoch this height belongs to
        long heightSinceFork = height - forkHeight;
        long epochIndex = heightSinceFork / seedEpochBlocks;

        log.debug("Calculated memory index: height={}, fork={}, index={}",
                height, forkHeight, epochIndex);

        return epochIndex;
    }

    /**
     * Replay blocks to update seed state.
     *
     * <p>Calls updateSeedForBlock() on each block to properly handle
     * epoch boundaries and fork time calculation.
     *
     * @param fromHeight Starting height (inclusive)
     * @param toHeight Ending height (inclusive)
     */
    private void replayBlocks(long fromHeight, long toHeight) {
        log.debug("Replaying {} blocks from {} to {}",
                toHeight - fromHeight + 1, fromHeight, toHeight);

        long blocksReplayed = 0;
        long epochBoundariesProcessed = 0;

        for (long height = fromHeight; height <= toHeight; height++) {
            Block block = dagChain.getMainBlockByHeight(height);

            if (block == null) {
                log.warn("Block not found at height {}, stopping replay", height);
                break;
            }

            // Update seed manager (handles fork time and epoch boundaries)
            seedManager.updateSeedForBlock(block);

            blocksReplayed++;

            // Log progress at epoch boundaries
            long epochMask = seedEpochBlocks - 1;
            if ((height & epochMask) == 0) {
                epochBoundariesProcessed++;
                log.info("Processed epoch boundary at height {}", height);
            }
        }

        log.info("Replay complete: {} blocks replayed, {} epoch boundaries processed",
                blocksReplayed, epochBoundariesProcessed);
    }
}
