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

import static io.xdag.config.RandomXConstants.RANDOMX_FORK_HEIGHT;
import static io.xdag.config.RandomXConstants.RANDOMX_TESTNET_FORK_HEIGHT;
import static io.xdag.config.RandomXConstants.SEEDHASH_EPOCH_BLOCKS;
import static io.xdag.config.RandomXConstants.SEEDHASH_EPOCH_LAG;
import static io.xdag.config.RandomXConstants.SEEDHASH_EPOCH_TESTNET_BLOCKS;
import static io.xdag.config.RandomXConstants.SEEDHASH_EPOCH_TESTNET_LAG;
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
 * <p>Professional seed and epoch management for RandomX mining.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Manage seed epochs and fork time calculation</li>
 *   <li>Handle dual-buffer memory slot rotation for seamless seed transitions</li>
 *   <li>Update RandomX templates when seed changes</li>
 *   <li>Provide active memory slot selection based on timestamp</li>
 * </ul>
 *
 * <h2>Dual-Buffer Architecture</h2>
 * <pre>
 * Memory Slots: [Slot 0] [Slot 1]
 *               Current   Next
 *
 * On Epoch Boundary:
 *   1. Prepare Slot 1 with new seed
 *   2. Set switchTime for Slot 1
 *   3. Rotate: Slot 1 becomes current
 *   4. Slot 0 becomes next (reusable)
 * </pre>
 *
 * @since XDAGJ v0.8.1
 */
@Slf4j
public class RandomXSeedManager {

    // ========== Configuration ==========

    private final Config config;
    private final boolean isTestNet;
    private final Set<RandomXFlag> flags;

    /** Fork height where RandomX activated */
    @Getter
    private final long forkSeedHeight;

    /** Lag period for seed calculation (blocks) */
    @Getter
    private final long forkLag;

    /** Epoch size in blocks */
    @Getter
    private final long seedEpochBlocks;

    // ========== State ==========

    /** Dual-buffer memory slots for seamless seed rotation */
    private final RandomXMemory[] memorySlots = new RandomXMemory[2];

    /** Current epoch index (used for slot selection) */
    @Getter
    private long currentEpochIndex;

    /** Pool memory index (for mining operations) */
    @Getter
    private long poolMemoryIndex;

    /** Fork activation time (epoch timestamp) */
    @Getter
    private long forkTime = Long.MAX_VALUE;

    /** Reference to blockchain for seed derivation */
    private DagChain dagChain;

    // ========== Constructor ==========

    /**
     * Creates a new RandomX seed manager.
     *
     * @param config System configuration
     * @param flags RandomX flags for template creation
     */
    public RandomXSeedManager(Config config, Set<RandomXFlag> flags) {
        this.config = config;
        this.flags = flags;
        this.isTestNet = !(config instanceof MainnetConfig);

        // Initialize configuration based on network type
        if (isTestNet) {
            this.forkSeedHeight = RANDOMX_TESTNET_FORK_HEIGHT;
            this.forkLag = SEEDHASH_EPOCH_TESTNET_LAG;
            this.seedEpochBlocks = SEEDHASH_EPOCH_TESTNET_BLOCKS;
        } else {
            this.forkSeedHeight = RANDOMX_FORK_HEIGHT;
            this.forkLag = SEEDHASH_EPOCH_LAG;
            this.seedEpochBlocks = SEEDHASH_EPOCH_BLOCKS;
        }

        log.info("RandomXSeedManager initialized: testnet={}, forkHeight={}, epochBlocks={}",
                isTestNet, forkSeedHeight, seedEpochBlocks);
    }

    // ========== Lifecycle ==========

    /**
     * Initialize the seed manager.
     * Validates configuration and allocates memory slots.
     *
     * @throws IllegalStateException if fork height is not aligned with epoch size
     */
    public void initialize() {
        // Validate fork height alignment
        if ((forkSeedHeight & (seedEpochBlocks - 1)) != 0) {
            String error = String.format(
                    "RandomX fork height %d is not aligned with seed epoch %d",
                    forkSeedHeight, seedEpochBlocks);
            log.error(error);
            throw new IllegalStateException(error);
        }

        // Initialize dual-buffer memory slots
        for (int i = 0; i < 2; i++) {
            memorySlots[i] = new RandomXMemory();
        }

        log.info("RandomXSeedManager initialized successfully");
    }

    /**
     * Set blockchain reference for seed derivation.
     *
     * @param dagChain Blockchain instance
     */
    public void setDagChain(DagChain dagChain) {
        this.dagChain = dagChain;
    }

    /**
     * Cleanup resources.
     * Closes all RandomX templates in memory slots.
     */
    public void cleanup() {
        for (RandomXMemory memory : memorySlots) {
            if (memory != null) {
                memory.cleanup();
            }
        }
        log.info("RandomXSeedManager cleaned up");
    }

    // ========== Fork Time Management ==========

    /**
     * Check if an epoch is after RandomX fork activation.
     *
     * @param epoch Epoch to check
     * @return true if epoch is after fork
     */
    public boolean isAfterFork(long epoch) {
        return epoch > forkTime;
    }

    /**
     * Update seed for a new block (called during block processing).
     * Manages fork time calculation and seed epoch transitions.
     *
     * @param block New block
     */
    public void updateSeedForBlock(Block block) {
        long height = block.getInfo().getHeight();
        long blockEpoch = block.getEpoch();

        if (height < forkSeedHeight) {
            return;
        }

        // Set fork time at fork height
        if (height == forkSeedHeight) {
            forkTime = blockEpoch + forkLag;
            log.info("RandomX fork activated: height={}, epoch={}, forkTime={}",
                    height, blockEpoch, forkTime);
        }

        // Check if this is an epoch boundary
        long epochMask = seedEpochBlocks - 1;
        if ((height & epochMask) == 0) {
            updateSeedAtEpochBoundary(block);
        }
    }

    /**
     * Revert seed changes for a block (called during block rollback).
     *
     * @param block Block being rolled back
     */
    public void revertSeedForBlock(Block block) {
        long height = block.getInfo().getHeight();

        if (height < forkSeedHeight) {
            return;
        }

        // Revert fork time
        if (height == forkSeedHeight) {
            forkTime = Long.MAX_VALUE;
            log.debug("Reverted fork time for block at height {}", height);
        }

        // Revert epoch if at boundary
        long epochMask = seedEpochBlocks - 1;
        if ((height & epochMask) == 0) {
            revertSeedAtEpochBoundary();
        }
    }

    // ========== Memory Slot Management ==========

    /**
     * Get the active memory slot for a given epoch.
     * Handles automatic slot switching based on switchTime (epoch index).
     *
     * @param epoch epoch number to query
     * @return Active memory slot for the epoch
     */
    public RandomXMemory getActiveMemory(long epoch) {
        if (currentEpochIndex == 0) {
            return null;  // No seed initialized yet
        }

        int currentSlot = (int) (currentEpochIndex & 1);
        RandomXMemory memory = memorySlots[currentSlot];

        // Check if we need to use previous slot
        if (epoch < memory.getSwitchTime() && currentEpochIndex > 1) {
            int previousSlot = (int) ((currentEpochIndex - 1) & 1);
            return memorySlots[previousSlot];
        }

        return memory;
    }

    /**
     * Get memory slot for pool mining operations.
     *
     * @param taskTime Mining task timestamp
     * @return Memory slot for pool mining
     */
    public RandomXMemory getPoolMemory(long taskTime) {
        if (poolMemoryIndex < 0) {
            return null;
        }

        int currentSlot = (int) (poolMemoryIndex & 1);
        RandomXMemory memory = memorySlots[currentSlot];

        // Check if task time is before switch time
        if (taskTime < memory.getSwitchTime() && poolMemoryIndex > 0) {
            int previousSlot = (int) ((poolMemoryIndex - 1) & 1);
            return memorySlots[previousSlot];
        }

        return memory;
    }

    // ========== Seed Update Operations ==========

    /**
     * Update seed at epoch boundary.
     * Prepares next memory slot with new seed and rotates buffers.
     *
     * @param block Block at epoch boundary
     */
    private void updateSeedAtEpochBoundary(Block block) {
        long height = block.getInfo().getHeight();
        long blockEpoch = block.getEpoch();
        long nextEpochIndex = currentEpochIndex + 1;
        int nextSlot = (int) (nextEpochIndex & 1);

        RandomXMemory nextMemory = memorySlots[nextSlot];

        // Calculate switch time
        nextMemory.setSwitchTime(blockEpoch + forkLag + 1);
        nextMemory.setSeedTime(XdagTime.epochNumberToMainTime(blockEpoch));
        nextMemory.setSeedHeight(height);

        log.debug("Epoch boundary: height={}, nextSlot={}, switchTime={}",
                height, nextSlot, Long.toHexString(nextMemory.getSwitchTime()));

        // Derive seed from block at (height - lag)
        byte[] seedHash = deriveSeedHash(height - forkLag);
        if (seedHash == null) {
            log.warn("Failed to derive seed hash for height {}", height);
            return;
        }

        // Update seed if changed
        if (nextMemory.getSeed() == null || !equalBytes(nextMemory.getSeed(), seedHash)) {
            nextMemory.setSeed(seedHash);
            log.info("New seed: epoch={}, hash={}", nextEpochIndex, Hex.toHexString(seedHash));

            // Update RandomX templates with new seed
            updateTemplatesForMemory(nextMemory, nextEpochIndex);
        }

        // Rotate to next epoch
        currentEpochIndex = nextEpochIndex;
        nextMemory.setIsSwitched(0);  // 0 = not switched out yet

        log.info("Epoch advanced: index={}, height={}", currentEpochIndex, height);
    }

    /**
     * Revert seed changes at epoch boundary (for block rollback).
     */
    private void revertSeedAtEpochBoundary() {
        if (currentEpochIndex == 0) {
            return;
        }

        int currentSlot = (int) (currentEpochIndex & 1);
        RandomXMemory memory = memorySlots[currentSlot];

        // Clear memory state
        memory.setSeedTime(-1);
        memory.setSeedHeight(-1);
        memory.setSwitchTime(-1);
        memory.setIsSwitched(1);  // 1 = switched out

        // Revert to previous epoch
        currentEpochIndex--;

        log.debug("Reverted to epoch {}", currentEpochIndex);
    }

    /**
     * Derive seed hash from blockchain at specified height.
     *
     * @param seedHeight Height to derive seed from
     * @return Seed hash (reversed), or null if block not available
     */
    private byte[] deriveSeedHash(long seedHeight) {
        if (dagChain == null) {
            log.warn("DagChain not set, cannot derive seed");
            return null;
        }

        Block seedBlock = dagChain.getMainBlockByHeight(seedHeight);
        if (seedBlock == null || seedBlock.getInfo() == null) {
            log.warn("Seed block not found at height {}", seedHeight);
            return null;
        }

        // Reverse hash for RandomX compatibility
        return Arrays.reverse(seedBlock.getInfo().getHash().toArray());
    }

    /**
     * Update RandomX templates for a memory slot with new seed.
     *
     * @param memory Memory slot to update
     * @param memoryIndex Memory slot index
     */
    private void updateTemplatesForMemory(RandomXMemory memory, long memoryIndex) {
        try {
            // Update pool template
            updateOrCreateTemplate(
                    memory,
                    true,  // is pool template
                    memoryIndex
            );

            // Update block template
            updateOrCreateTemplate(
                    memory,
                    false, // is block template
                    memoryIndex
            );

        } catch (Exception e) {
            log.error("Failed to update RandomX templates for memory index " + memoryIndex, e);
            throw new RuntimeException("Failed to update RandomX templates", e);
        }
    }

    /**
     * Update or create a RandomX template.
     *
     * @param memory Memory slot
     * @param isPoolTemplate true for pool template, false for block template
     * @param memoryIndex Memory index for logging
     */
    private void updateOrCreateTemplate(
            RandomXMemory memory,
            boolean isPoolTemplate,
            long memoryIndex) {

        RandomXTemplate existingTemplate = isPoolTemplate
                ? memory.getPoolTemplate()
                : memory.getBlockTemplate();

        if (existingTemplate == null) {
            // Create new template
            RandomXCache cache = new RandomXCache(flags);
            cache.init(memory.getSeed());

            RandomXTemplate template = RandomXTemplate.builder()
                    .cache(cache)
                    .miningMode(config.getRandomxSpec().getRandomxFlag())
                    .flags(flags)
                    .build();

            template.init();
            template.changeKey(memory.getSeed());

            if (isPoolTemplate) {
                memory.setPoolTemplate(template);
            } else {
                memory.setBlockTemplate(template);
            }

            log.debug("Created new {} template for memory index {}",
                    isPoolTemplate ? "pool" : "block", memoryIndex);

        } else {
            // Update existing template with new seed
            existingTemplate.changeKey(memory.getSeed());
            log.debug("Updated {} template for memory index {}",
                    isPoolTemplate ? "pool" : "block", memoryIndex);
        }
    }

    /**
     * Initialize seed from a preseed (for snapshot loading).
     *
     * @param preseed Preseed bytes
     * @param memoryIndex Memory index to initialize
     */
    public void initializeFromPreseed(byte[] preseed, long memoryIndex) {
        int slot = (int) (memoryIndex & 1);
        RandomXMemory memory = memorySlots[slot];

        memory.setSeed(preseed);
        updateTemplatesForMemory(memory, memoryIndex);

        this.currentEpochIndex = memoryIndex;
        this.poolMemoryIndex = -1;
        memory.setIsSwitched(0);  // 0 = active, not switched out

        log.info("Initialized from preseed: memoryIndex={}", memoryIndex);
    }
}
