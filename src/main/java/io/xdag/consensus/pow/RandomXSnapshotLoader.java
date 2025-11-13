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

import static io.xdag.config.RandomXConstants.*;

import io.xdag.config.Config;
import io.xdag.config.MainnetConfig;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.utils.XdagTime;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.Arrays;

/**
 * RandomX Snapshot Loader
 *
 * <p>Handles loading of RandomX state from blockchain snapshots. This class is responsible
 * for initializing seeds from historical blocks and catching up to the current chain state
 * after loading a snapshot.
 *
 * <h2>Snapshot Loading Strategies</h2>
 * <ul>
 *   <li><strong>With Preseed</strong> - Load from a pre-computed seed</li>
 *   <li><strong>From Fork Height</strong> - Initialize from RandomX fork activation</li>
 *   <li><strong>From Current State</strong> - Reconstruct from current blockchain</li>
 * </ul>
 *
 * <h2>Seed Reconstruction</h2>
 * <p>After loading a snapshot, this class walks through historical blocks to:
 * <ul>
 *   <li>Set the fork activation time</li>
 *   <li>Initialize current and previous epoch seeds</li>
 *   <li>Prepare templates for hash calculations</li>
 * </ul>
 *
 * @since XDAGJ 0.8.1
 */
@Slf4j
public class RandomXSnapshotLoader {

    private final Config config;
    private final DagChain dagChain;
    private final RandomXSeedManager seedManager;
    private final boolean isTestNet;

    // Network-specific parameters
    private final long seedEpochBlocks;
    private final long seedEpochLag;
    private final long forkHeight;

    /**
     * Creates a new snapshot loader
     *
     * @param config System configuration
     * @param dagChain Blockchain access
     * @param seedManager Seed manager to initialize
     */
    public RandomXSnapshotLoader(Config config, DagChain dagChain, RandomXSeedManager seedManager) {
        this.config = config;
        this.dagChain = dagChain;
        this.seedManager = seedManager;
        this.isTestNet = !(config instanceof MainnetConfig);

        // Initialize network-specific parameters
        this.seedEpochBlocks = isTestNet ? SEEDHASH_EPOCH_TESTNET_BLOCKS : SEEDHASH_EPOCH_BLOCKS;
        this.seedEpochLag = isTestNet ? SEEDHASH_EPOCH_TESTNET_LAG : SEEDHASH_EPOCH_LAG;
        this.forkHeight = isTestNet ? RANDOMX_TESTNET_FORK_HEIGHT : RANDOMX_FORK_HEIGHT;

        log.info("RandomXSnapshotLoader initialized: network={}, epochBlocks={}, lag={}, forkHeight={}",
                isTestNet ? "testnet" : "mainnet", seedEpochBlocks, seedEpochLag, forkHeight);
    }

    // ========== Public API ==========

    /**
     * Loads RandomX state from a snapshot with a pre-computed seed
     *
     * <p>This is the primary method for snapshot loading. It:
     * <ol>
     *   <li>Initializes the first memory slot with the preseed</li>
     *   <li>Calculates fork time from snapshot height</li>
     *   <li>Replays blocks to update seed state</li>
     * </ol>
     *
     * @param preseed Pre-computed seed from snapshot
     * @throws IllegalArgumentException if preseed is null or empty
     * @throws IllegalStateException if snapshot height is invalid
     */
    public void loadWithPreseed(byte[] preseed) {
        if (preseed == null || preseed.length == 0) {
            throw new IllegalArgumentException("Preseed cannot be null or empty");
        }

        long snapshotHeight = config.getSnapshotSpec().getSnapshotHeight();
        log.info("Loading RandomX state from snapshot: height={}, preseed={}...",
                snapshotHeight, bytesToHex(preseed, 16));

        // Initialize first seed
        seedManager.loadFromSnapshot(preseed, snapshotHeight);

        // Replay blocks from (snapshotHeight - lag) to snapshotHeight
        replayBlocks(snapshotHeight - seedEpochLag, snapshotHeight);

        log.info("RandomX state loaded successfully from snapshot");
    }

    /**
     * Loads RandomX state conditioned on current chain state
     *
     * <p>This method decides whether to use the preseed or reconstruct from scratch
     * based on how far the chain has progressed past the snapshot height.
     *
     * @param preseed Pre-computed seed (may be used or ignored)
     */
    public void loadConditional(byte[] preseed) {
        long snapshotHeight = config.getSnapshotSpec().getSnapshotHeight();
        long currentHeight = dagChain.getChainStats().getMainBlockCount();
        long nextEpochBoundary = snapshotHeight + seedEpochBlocks;

        log.info("Conditional snapshot load: snapshot={}, current={}, nextEpoch={}",
                snapshotHeight, currentHeight, nextEpochBoundary);

        if (currentHeight < nextEpochBoundary) {
            // Chain hasn't progressed to next epoch yet, use preseed
            log.info("Using preseed (chain hasn't reached next epoch)");
            loadWithPreseed(preseed);
        } else {
            // Chain has progressed, reconstruct from current state
            log.info("Reconstructing from current state (chain past next epoch)");
            loadFromCurrentState();
        }
    }

    /**
     * Loads RandomX state from the fork height
     *
     * <p>This method is used when no snapshot is available. It initializes
     * the RandomX state from the fork activation block.
     */
    public void loadFromForkHeight() {
        long currentHeight = dagChain.getChainStats().getMainBlockCount();

        if (currentHeight < forkHeight) {
            log.warn("Cannot load from fork height: current height {} < fork height {}",
                    currentHeight, forkHeight);
            return;
        }

        log.info("Loading RandomX state from fork height: {}", forkHeight);

        // Get fork activation block
        Block forkBlock = dagChain.getMainBlockByHeight(forkHeight);
        if (forkBlock == null) {
            throw new IllegalStateException("Fork block not found at height: " + forkHeight);
        }

        // Calculate fork time
        long forkTime = XdagTime.getEpoch(forkBlock.getTimestamp()) + seedEpochLag;
        log.info("Fork time calculated: {}", forkTime);

        // Reconstruct seed state from current chain
        reconstructSeedState();

        log.info("RandomX state loaded successfully from fork height");
    }

    /**
     * Loads RandomX state from current blockchain state
     *
     * <p>This method reconstructs the current and previous epoch seeds
     * by examining the current chain height and deriving seeds from
     * the appropriate historical blocks.
     */
    public void loadFromCurrentState() {
        long snapshotHeight = config.getSnapshotSpec().getSnapshotHeight();
        long currentHeight = dagChain.getChainStats().getMainBlockCount();

        if (currentHeight < snapshotHeight) {
            throw new IllegalStateException(
                    String.format("Current height %d < snapshot height %d", currentHeight, snapshotHeight));
        }

        log.info("Loading RandomX state from current state: height={}", currentHeight);

        // Get snapshot block for fork time
        Block snapshotBlock = dagChain.getMainBlockByHeight(snapshotHeight);
        if (snapshotBlock == null) {
            throw new IllegalStateException("Snapshot block not found at height: " + snapshotHeight);
        }

        // Calculate fork time
        long forkTime = XdagTime.getEpoch(snapshotBlock.getTimestamp()) + seedEpochLag;
        log.info("Fork time set from snapshot: {}", forkTime);

        // Reconstruct seed state
        reconstructSeedState();

        log.info("RandomX state loaded successfully from current state");
    }

    // ========== Private Helpers ==========

    /**
     * Replays blocks to update seed state
     */
    private void replayBlocks(long fromHeight, long toHeight) {
        log.debug("Replaying blocks from {} to {}", fromHeight, toHeight);

        for (long height = fromHeight; height <= toHeight; height++) {
            Block block = dagChain.getMainBlockByHeight(height);
            if (block != null) {
                seedManager.updateSeedForBlock(block);
            }
        }
    }

    /**
     * Reconstructs seed state from current blockchain
     */
    private void reconstructSeedState() {
        long currentHeight = dagChain.getChainStats().getMainBlockCount();
        long seedEpochMask = seedEpochBlocks - 1;

        // Find current and previous epoch boundaries
        long currentEpochStart = currentHeight & ~seedEpochMask;
        long previousEpochStart = currentEpochStart - seedEpochBlocks;

        log.debug("Reconstructing seed state: currentEpoch={}, previousEpoch={}",
                currentEpochStart, previousEpochStart);

        // Initialize previous epoch seed (if exists)
        if (previousEpochStart >= forkHeight) {
            initializeSeedForEpoch(previousEpochStart);
        }

        // Initialize current epoch seed
        if (currentEpochStart >= forkHeight) {
            initializeSeedForEpoch(currentEpochStart);
        }
    }

    /**
     * Initializes seed for a specific epoch
     */
    private void initializeSeedForEpoch(long epochStart) {
        Block epochBlock = dagChain.getMainBlockByHeight(epochStart);
        if (epochBlock == null) {
            log.warn("Epoch block not found at height: {}", epochStart);
            return;
        }

        // Derive seed from block (epochStart - lag)
        Block seedBlock = dagChain.getMainBlockByHeight(epochStart - seedEpochLag);
        if (seedBlock == null) {
            log.warn("Seed block not found at height: {}", epochStart - seedEpochLag);
            return;
        }

        byte[] seed = Arrays.reverse(seedBlock.getInfo().getHash().toArray());

        log.info("Initializing seed for epoch starting at height {}: seed={}...",
                epochStart, bytesToHex(seed, 16));

        // Create memory slot and initialize
        long memoryIndex = seedManager.getCurrentEpochIndex() + 1;
        RandomXMemory memory = seedManager.getActiveMemory(epochBlock.getTimestamp(), false);

        if (memory == null) {
            log.error("Failed to get memory slot for epoch {}", epochStart);
            return;
        }

        memory.seed = seed;
        memory.seedHeight = epochStart;
        memory.seedTime = epochBlock.getTimestamp();
        memory.switchTime = XdagTime.getEpoch(epochBlock.getTimestamp()) + seedEpochLag + 1;

        seedManager.updateSeed(memoryIndex);

        // Check if seed is currently active
        long currentTime = XdagTime.getEpoch(
                dagChain.getMainBlockByHeight(dagChain.getChainStats().getMainBlockCount()).getTimestamp());

        memory.isSwitched = (currentTime >= memory.switchTime) ? 1 : 0;
    }

    /**
     * Converts byte array to hex string (for logging)
     */
    private String bytesToHex(byte[] bytes, int length) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(length, bytes.length);
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}
