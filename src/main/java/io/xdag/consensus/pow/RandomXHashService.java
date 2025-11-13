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

import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;

/**
 * RandomX Hash Service
 *
 * <p>Provides hash calculation services for RandomX mining and block validation.
 * This service acts as a facade over the seed manager, selecting the appropriate
 * memory slot and delegating hash calculations to RandomX templates.
 *
 * <h2>Hash Modes</h2>
 * <ul>
 *   <li><strong>Pool Hash</strong> - Used by mining pools for share validation</li>
 *   <li><strong>Block Hash</strong> - Used for block PoW validation</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Hash calculations can be performed concurrently
 * from multiple threads.
 *
 * @since XDAGJ 0.8.1
 */
@Slf4j
public class RandomXHashService {

    private final RandomXSeedManager seedManager;

    /**
     * Creates a new hash service
     *
     * @param seedManager Seed manager for memory slot access
     */
    public RandomXHashService(RandomXSeedManager seedManager) {
        this.seedManager = seedManager;
    }

    // ========== Pool Mining ==========

    /**
     * Calculates a hash for mining pool share validation
     *
     * <p>This method is optimized for high-frequency hash calculations in mining pools.
     * It uses the pool-specific memory slot and template.
     *
     * @param data Input data to hash
     * @param taskTime Timestamp of the mining task
     * @return 32-byte hash result
     * @throws IllegalArgumentException if data is null
     * @throws IllegalStateException if no active memory slot is available
     */
    public Bytes32 calculatePoolHash(Bytes data, long taskTime) {
        if (data == null) {
            throw new IllegalArgumentException("Input data cannot be null");
        }

        // Get active memory slot for pool mode
        RandomXMemory memory = seedManager.getActiveMemory(taskTime, true);

        if (memory == null || memory.getPoolTemplate() == null) {
            throw new IllegalStateException(
                    String.format("No active pool template available for taskTime=%d", taskTime));
        }

        // Calculate hash using pool template
        byte[] hash = memory.getPoolTemplate().calculateHash(data.toArray());

        if (log.isTraceEnabled()) {
            log.trace("Pool hash calculated: taskTime={}, hash={}",
                    taskTime, Hex.toHexString(hash).substring(0, 16));
        }

        return Bytes32.wrap(hash);
    }

    // ========== Block Validation ==========

    /**
     * Calculates a hash for block PoW validation
     *
     * <p>This method is used during block validation to verify the proof-of-work.
     * It uses the block-specific memory slot and template.
     *
     * @param data Raw block data to hash
     * @param blockTime Block timestamp
     * @return 32-byte hash result, or null if no seed is available
     * @throws IllegalArgumentException if data is null
     */
    public byte[] calculateBlockHash(byte[] data, long blockTime) {
        if (data == null) {
            throw new IllegalArgumentException("Input data cannot be null");
        }

        // Check if any seed is available
        if (seedManager.getCurrentEpochIndex() == 0) {
            log.debug("Cannot calculate block hash: no seed available yet");
            return null;
        }

        // Get active memory slot for block mode
        RandomXMemory memory = seedManager.getActiveMemory(blockTime, false);

        if (memory == null) {
            log.warn("No active memory slot for blockTime={}", blockTime);
            return null;
        }

        // Special handling for first seed
        if (seedManager.getCurrentEpochIndex() == 1) {
            if (blockTime < memory.getSwitchTime()) {
                log.debug("Block time {} less than switch time {}, seed not active yet",
                        Long.toHexString(blockTime), Long.toHexString(memory.getSwitchTime()));
                return null;
            }
        }

        // Calculate hash using block template
        if (memory.getBlockTemplate() == null) {
            log.error("Block template is null for memory at blockTime={}", blockTime);
            return null;
        }

        byte[] hash = memory.getBlockTemplate().calculateHash(data);

        if (log.isDebugEnabled()) {
            log.debug("Block hash calculated: blockTime={}, seed={}, hash={}",
                    blockTime,
                    Hex.toHexString(Arrays.reverse(memory.seed)).substring(0, 16),
                    Hex.toHexString(hash).substring(0, 16));
        }

        return hash;
    }

    // ========== Status ==========

    /**
     * Checks if the hash service is ready for calculations
     *
     * @return true if at least one seed is initialized, false otherwise
     */
    public boolean isReady() {
        return seedManager.getCurrentEpochIndex() > 0;
    }

    /**
     * Gets current status information for debugging
     *
     * @return Status string
     */
    public String getStatus() {
        return String.format("RandomXHashService[epochIndex=%d, poolIndex=%d, forkTime=%d]",
                seedManager.getCurrentEpochIndex(),
                seedManager.getPoolMemoryIndex(),
                seedManager.getForkTime());
    }
}
