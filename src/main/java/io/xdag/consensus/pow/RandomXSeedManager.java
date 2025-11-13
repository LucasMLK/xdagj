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
import static io.xdag.utils.BytesUtils.equalBytes;

import io.xdag.config.Config;
import io.xdag.config.MainnetConfig;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.crypto.randomx.RandomXCache;
import io.xdag.crypto.randomx.RandomXFlag;
import io.xdag.crypto.randomx.RandomXTemplate;
import io.xdag.utils.XdagTime;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;

/**
 * RandomX Seed Manager
 *
 * <p>Manages seed epochs, fork time, and memory slot rotation for RandomX mining.
 * This class is responsible for the critical task of maintaining two seed states
 * (current and next) to enable seamless transitions during epoch changes.
 *
 * <h2>Dual-Buffer Architecture</h2>
 * <p>Uses two memory slots for seamless seed transitions:
 * <ul>
 *   <li><strong>Current slot</strong> - Active seed for current epoch</li>
 *   <li><strong>Next slot</strong> - Pre-initialized seed for upcoming epoch</li>
 * </ul>
 *
 * <h2>Epoch Configuration</h2>
 * <ul>
 *   <li><strong>Mainnet</strong>: 4096 blocks per epoch</li>
 *   <li><strong>Testnet</strong>: 64 blocks per epoch</li>
 *   <li><strong>Lag</strong>: 64 blocks (seed propagation delay)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for read operations but not for write operations.
 * Seed updates should be synchronized by the caller if needed.
 *
 * @since XDAGJ 0.8.1
 */
@Slf4j
public class RandomXSeedManager {

    // ========== Configuration ==========

    private final Config config;
    private final DagChain dagChain;
    private final Set<RandomXFlag> flags;
    private final boolean isTestNet;

    // Network-specific parameters
    private final long seedEpochBlocks;
    private final long seedEpochLag;
    private final long forkSeedHeight;

    // ========== State ==========

    /**
     * Dual-buffer memory slots for current and next seeds
     */
    private final RandomXMemory[] memorySlots = new RandomXMemory[2];

    /**
     * Current epoch index (increments with each seed change)
     */
    @Getter
    private long currentEpochIndex = 0;

    /**
     * Fork activation time (epoch number when RandomX becomes active)
     */
    @Getter
    private long forkTime = Long.MAX_VALUE;

    /**
     * Pool memory index (for mining pool hash calculations)
     */
    @Getter
    private long poolMemoryIndex = -1;

    // ========== Constructor ==========

    /**
     * Creates a new RandomX seed manager
     *
     * @param config System configuration
     * @param dagChain Blockchain access for seed derivation
     * @param flags RandomX flags for template initialization
     */
    public RandomXSeedManager(Config config, DagChain dagChain, Set<RandomXFlag> flags) {
        this.config = config;
        this.dagChain = dagChain;
        this.flags = flags;
        this.isTestNet = !(config instanceof MainnetConfig);

        // Initialize network-specific parameters
        this.seedEpochBlocks = isTestNet ? SEEDHASH_EPOCH_TESTNET_BLOCKS : SEEDHASH_EPOCH_BLOCKS;
        this.seedEpochLag = isTestNet ? SEEDHASH_EPOCH_TESTNET_LAG : SEEDHASH_EPOCH_LAG;
        this.forkSeedHeight = isTestNet ? RANDOMX_TESTNET_FORK_HEIGHT : RANDOMX_FORK_HEIGHT;

        log.info("RandomXSeedManager initialized: network={}, epochBlocks={}, lag={}, forkHeight={}",
                isTestNet ? "testnet" : "mainnet", seedEpochBlocks, seedEpochLag, forkSeedHeight);
    }

    // ========== Lifecycle ==========

    /**
     * Initializes memory slots and validates configuration
     *
     * @throws IllegalStateException if fork height is misaligned with epoch boundaries
     */
    public void initialize() {
        // Validate fork height alignment
        if ((forkSeedHeight & (seedEpochBlocks - 1)) != 0) {
            String errorMsg = String.format(
                    "RandomX fork height %d is not aligned with seed epoch %d",
                    forkSeedHeight, seedEpochBlocks);
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        // Initialize memory slots
        for (int i = 0; i < 2; i++) {
            memorySlots[i] = new RandomXMemory();
        }

        log.info("RandomXSeedManager initialized successfully");
    }

    /**
     * Cleans up resources (closes templates)
     */
    public void cleanup() {
        for (RandomXMemory memory : memorySlots) {
            if (memory != null) {
                memory.cleanup();
            }
        }
        log.info("RandomXSeedManager cleaned up");
    }

    // ========== Fork Management ==========

    /**
     * Checks if RandomX fork is active for given epoch
     *
     * @param epoch Epoch to check
     * @return true if RandomX is active, false otherwise
     */
    public boolean isAfterFork(long epoch) {
        return epoch > forkTime;
    }

    /**
     * Updates seed state when a new block arrives
     *
     * <p>This method handles:
     * <ul>
     *   <li>Fork time activation</li>
     *   <li>Epoch boundary detection</li>
     *   <li>Seed rotation and template initialization</li>
     * </ul>
     *
     * @param block Newly connected block
     */
    public void updateSeedForBlock(Block block) {
        if (block == null || block.getInfo() == null) {
            return;
        }

        long height = block.getInfo().getHeight();

        // Only process blocks at or after fork height
        if (height < forkSeedHeight) {
            return;
        }

        // Set fork time at exact fork height
        if (height == forkSeedHeight) {
            forkTime = XdagTime.getEpoch(block.getTimestamp()) + seedEpochLag;
            log.info("RandomX fork activated: height={}, time={}, forkTime={}",
                    height, block.getTimestamp(), forkTime);
        }

        // Check if we're at an epoch boundary
        long seedEpochMask = seedEpochBlocks - 1;
        if ((height & seedEpochMask) == 0) {
            handleEpochBoundary(block);
        }
    }

    /**
     * Reverts seed state when a block is rolled back
     *
     * @param block Block being rolled back
     */
    public void revertSeedForBlock(Block block) {
        if (block == null || block.getInfo() == null) {
            return;
        }

        long height = block.getInfo().getHeight();

        if (height < forkSeedHeight) {
            return;
        }

        // Revert fork time
        if (height == forkSeedHeight) {
            forkTime = Long.MAX_VALUE;
            log.debug("Reverted fork time at height {}", height);
        }

        // Revert epoch state
        long seedEpochMask = seedEpochBlocks - 1;
        if ((height & seedEpochMask) == 0) {
            revertEpochBoundary();
        }
    }

    // ========== Memory Slot Access ==========

    /**
     * Gets the active memory slot for the given timestamp
     *
     * <p>This method implements the dual-buffer selection logic:
     * <ul>
     *   <li>If timestamp >= current slot's switch time, use current slot</li>
     *   <li>Otherwise, use previous slot (for blocks still using old seed)</li>
     * </ul>
     *
     * @param timestamp Block or task timestamp
     * @param isPoolMode true for pool mining, false for block validation
     * @return Active memory slot
     */
    public RandomXMemory getActiveMemory(long timestamp, boolean isPoolMode) {
        long index = isPoolMode ? poolMemoryIndex : currentEpochIndex;

        if (index <= 0) {
            log.warn("No active memory slot: index={}", index);
            return null;
        }

        RandomXMemory currentMemory = memorySlots[(int)(index & 1)];

        // Check if we need to use previous slot
        if (timestamp < currentMemory.getSwitchTime() && index > 1) {
            return memorySlots[(int)((index - 1) & 1)];
        }

        return currentMemory;
    }

    // ========== Seed Operations ==========

    /**
     * Updates seed for a specific memory slot
     *
     * <p>This creates or updates both pool and block templates with the new seed.
     *
     * @param memoryIndex Memory slot index
     */
    public void updateSeed(long memoryIndex) {
        RandomXMemory memory = memorySlots[(int)(memoryIndex & 1)];

        if (memory.seed == null) {
            log.warn("Cannot update seed: seed is null for memory index {}", memoryIndex);
            return;
        }

        log.debug("Updating seed for memory index {}: seed={}",
                memoryIndex, Hex.toHexString(memory.seed).substring(0, 16));

        // Update or create pool template
        memory.poolTemplate = createOrUpdateTemplate(memory.poolTemplate, memory.seed);

        // Update or create block template
        memory.blockTemplate = createOrUpdateTemplate(memory.blockTemplate, memory.seed);

        log.info("Seed updated successfully for memory index {}", memoryIndex);
    }

    /**
     * Initializes seed state from a snapshot
     *
     * @param preseed Pre-computed seed from snapshot
     * @param snapshotHeight Height of the snapshot
     */
    public void loadFromSnapshot(byte[] preseed, long snapshotHeight) {
        long firstMemIndex = currentEpochIndex + 1;
        poolMemoryIndex = -1;

        RandomXMemory firstMemory = memorySlots[(int)(firstMemIndex & 1)];
        firstMemory.seed = preseed;

        updateSeed(firstMemIndex);

        currentEpochIndex = firstMemIndex;
        firstMemory.isSwitched = 0;

        // Set fork time based on snapshot
        Block lagBlock = dagChain.getMainBlockByHeight(snapshotHeight - seedEpochLag);
        if (lagBlock != null) {
            forkTime = XdagTime.getEpoch(lagBlock.getTimestamp());
            log.info("Fork time set from snapshot: {}", forkTime);
        }
    }

    // ========== Private Helpers ==========

    /**
     * Handles seed rotation at epoch boundaries
     */
    private void handleEpochBoundary(Block block) {
        long height = block.getInfo().getHeight();
        long nextMemIndex = currentEpochIndex + 1;
        RandomXMemory nextMemory = memorySlots[(int)(nextMemIndex & 1)];

        // Calculate switch time (when new seed becomes active)
        nextMemory.switchTime = XdagTime.getEpoch(block.getTimestamp()) + seedEpochLag + 1;
        nextMemory.seedTime = block.getTimestamp();
        nextMemory.seedHeight = height;

        log.debug("Epoch boundary at height {}: switchTime={}",
                height, Long.toHexString(nextMemory.switchTime));

        // Derive seed from historical block
        Block seedBlock = dagChain.getMainBlockByHeight(height - seedEpochLag);
        if (seedBlock == null) {
            log.error("Failed to get seed block at height {}", height - seedEpochLag);
            return;
        }

        byte[] newSeed = Arrays.reverse(seedBlock.getInfo().getHash().toArray());

        // Only update if seed actually changed
        if (nextMemory.seed == null || !equalBytes(nextMemory.seed, newSeed)) {
            nextMemory.seed = newSeed;
            log.info("New seed derived: epoch={}, height={}, seed={}",
                    nextMemIndex, height, Hex.toHexString(newSeed).substring(0, 16));

            updateSeed(nextMemIndex);
        }

        currentEpochIndex = nextMemIndex;
        nextMemory.isSwitched = 0;
    }

    /**
     * Reverts epoch state during block rollback
     */
    private void revertEpochBoundary() {
        RandomXMemory memory = memorySlots[(int)(currentEpochIndex & 1)];
        currentEpochIndex -= 1;

        memory.seedTime = -1;
        memory.seedHeight = -1;
        memory.switchTime = -1;
        memory.isSwitched = -1;

        log.debug("Reverted epoch state to index {}", currentEpochIndex);
    }

    /**
     * Creates a new template or updates an existing one with a new seed
     *
     * @param existingTemplate Existing template (may be null)
     * @param seed New seed to use
     * @return Updated or newly created template
     */
    private RandomXTemplate createOrUpdateTemplate(RandomXTemplate existingTemplate, byte[] seed) {
        if (existingTemplate == null) {
            // Create new template
            RandomXCache cache = new RandomXCache(flags);
            cache.init(seed);

            RandomXTemplate template = RandomXTemplate.builder()
                    .cache(cache)
                    .miningMode(config.getRandomxSpec().getRandomxFlag())
                    .flags(flags)
                    .build();

            template.init();
            template.changeKey(seed);

            return template;
        } else {
            // Update existing template
            existingTemplate.changeKey(seed);
            return existingTemplate;
        }
    }
}
