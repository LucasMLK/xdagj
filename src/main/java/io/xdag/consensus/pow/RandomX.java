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

import static io.xdag.config.RandomXConstants.XDAG_RANDOMX;

import io.xdag.config.Config;
import io.xdag.core.AbstractXdagLifecycle;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.crypto.randomx.RandomXFlag;
import io.xdag.crypto.randomx.RandomXUtils;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * RandomX - Proof of Work Implementation
 *
 * <p>This class serves as the main facade for RandomX mining operations in XDAGJ.
 * It coordinates three specialized services to provide a clean and maintainable API:
 * <ul>
 *   <li>{@link RandomXSeedManager} - Manages seed epochs and memory slot rotation</li>
 *   <li>{@link RandomXHashService} - Provides hash calculation services</li>
 *   <li>{@link RandomXSnapshotLoader} - Handles snapshot loading and state recovery</li>
 * </ul>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>Dual-buffer seed management for seamless epoch transitions</li>
 *   <li>Separate hash modes for pool mining and block validation</li>
 *   <li>Efficient snapshot loading and state reconstruction</li>
 *   <li>Thread-safe hash calculations</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * RandomX randomX = new RandomX(config);
 * randomX.setDagchain(dagChain);
 * randomX.start();
 *
 * // For mining
 * Bytes32 poolHash = randomX.randomXPoolCalcHash(data, taskTime);
 *
 * // For validation
 * byte[] blockHash = randomX.randomXBlockHash(data, blockTime);
 * }</pre>
 *
 * @since XDAGJ 0.8.1
 */
@Slf4j
@Getter
@Setter
public class RandomX extends AbstractXdagLifecycle {

    // ========== Configuration ==========

    private final Config config;
    private final int mineType = XDAG_RANDOMX;

    @Setter
    private DagChain dagchain;

    // ========== Service Components ==========

    private final Set<RandomXFlag> flags;
    private RandomXSeedManager seedManager;
    private RandomXHashService hashService;
    private RandomXSnapshotLoader snapshotLoader;

    // ========== Constructor ==========

    /**
     * Creates a new RandomX instance
     *
     * @param config System configuration
     */
    public RandomX(Config config) {
        this.config = config;

        // Initialize RandomX flags
        this.flags = RandomXUtils.getRecommendedFlags();
        if (config.getRandomxSpec().getRandomxFlag()) {
            flags.add(RandomXFlag.LARGE_PAGES);
            flags.add(RandomXFlag.FULL_MEM);
        }

        log.info("RandomX created: mineType={}, flags={}", mineType, flags);
    }

    // ========== Lifecycle ==========

    @Override
    protected void doStart() {
        if (dagchain == null) {
            throw new IllegalStateException("DagChain must be set before starting RandomX");
        }

        // Initialize service components
        seedManager = new RandomXSeedManager(config, dagchain, flags);
        hashService = new RandomXHashService(seedManager);
        snapshotLoader = new RandomXSnapshotLoader(config, dagchain, seedManager);

        // Initialize seed manager
        seedManager.initialize();

        log.info("RandomX started successfully");
    }

    @Override
    protected void doStop() {
        if (seedManager != null) {
            seedManager.cleanup();
        }
        log.info("RandomX stopped");
    }

    // ========== Public API - Fork Management ==========

    /**
     * Checks if RandomX fork is active for the given epoch
     *
     * @param epoch Epoch number to check
     * @return true if RandomX is active, false otherwise
     */
    public boolean isRandomxFork(long epoch) {
        return mineType == XDAG_RANDOMX && seedManager != null && seedManager.isAfterFork(epoch);
    }

    /**
     * Updates RandomX state when a new block is connected
     *
     * @param block Newly connected block
     */
    public void randomXSetForkTime(Block block) {
        if (seedManager != null) {
            seedManager.updateSeedForBlock(block);
        }
    }

    /**
     * Reverts RandomX state when a block is disconnected
     *
     * @param block Block being disconnected
     */
    public void randomXUnsetForkTime(Block block) {
        if (seedManager != null) {
            seedManager.revertSeedForBlock(block);
        }
    }

    // ========== Public API - Hash Calculation ==========

    /**
     * Calculates a hash for mining pool share validation
     *
     * @param data Input data to hash
     * @param taskTime Mining task timestamp
     * @return 32-byte hash result
     * @throws IllegalArgumentException if data is null
     * @throws IllegalStateException if hash service is not initialized
     */
    public Bytes32 randomXPoolCalcHash(Bytes data, long taskTime) {
        if (hashService == null) {
            throw new IllegalStateException("RandomX not started");
        }
        return hashService.calculatePoolHash(data, taskTime);
    }

    /**
     * Calculates a hash for block proof-of-work validation
     *
     * @param data Raw block data to hash
     * @param blockTime Block timestamp
     * @return 32-byte hash result, or null if no seed is available
     * @throws IllegalArgumentException if data is null
     * @throws IllegalStateException if hash service is not initialized
     */
    public byte[] randomXBlockHash(byte[] data, long blockTime) {
        if (hashService == null) {
            throw new IllegalStateException("RandomX not started");
        }
        return hashService.calculateBlockHash(data, blockTime);
    }

    // ========== Public API - Snapshot Loading ==========

    /**
     * Loads RandomX state from a snapshot with a pre-computed seed
     *
     * @param preseed Pre-computed seed from snapshot
     */
    public void randomXLoadingSnapshot(byte[] preseed) {
        if (snapshotLoader == null) {
            throw new IllegalStateException("RandomX not started");
        }
        snapshotLoader.loadWithPreseed(preseed);
    }

    /**
     * Loads RandomX state conditionally based on chain state
     *
     * @param preseed Pre-computed seed (may be used or ignored)
     */
    public void randomXLoadingForkTimeSnapshot(byte[] preseed) {
        if (snapshotLoader == null) {
            throw new IllegalStateException("RandomX not started");
        }
        snapshotLoader.loadConditional(preseed);
    }

    /**
     * Loads RandomX state from current blockchain state
     */
    public void randomXLoadingSnapshot() {
        if (snapshotLoader == null) {
            throw new IllegalStateException("RandomX not started");
        }
        snapshotLoader.loadFromCurrentState();
    }

    /**
     * Loads RandomX state from the fork activation block
     */
    public void randomXLoadingForkTime() {
        if (snapshotLoader == null) {
            throw new IllegalStateException("RandomX not started");
        }
        snapshotLoader.loadFromForkHeight();
    }

    // ========== Public API - Advanced (for compatibility) ==========

    /**
     * Updates seed for a specific memory index
     *
     * <p><strong>Deprecated:</strong> This method is kept for backward compatibility.
     * Prefer using the facade methods instead.
     *
     * @param memIndex Memory index to update
     * @deprecated Use facade methods for seed management
     */
    @Deprecated
    public void randomXPoolUpdateSeed(long memIndex) {
        if (seedManager != null) {
            seedManager.updateSeed(memIndex);
        }
    }

    // ========== Status ==========

    /**
     * Gets current RandomX status information
     *
     * @return Status string for debugging
     */
    public String getStatus() {
        if (hashService == null) {
            return "RandomX[NOT_STARTED]";
        }
        return hashService.getStatus();
    }

    /**
     * Checks if RandomX is ready for hash calculations
     *
     * @return true if ready, false otherwise
     */
    public boolean isReady() {
        return hashService != null && hashService.isReady();
    }
}
