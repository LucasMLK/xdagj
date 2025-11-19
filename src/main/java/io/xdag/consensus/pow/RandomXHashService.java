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

import io.xdag.crypto.randomx.RandomXTemplate;
import io.xdag.utils.XdagTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.encoders.Hex;

/**
 * RandomX Hash Service
 *
 * <p>Professional hash calculation service for RandomX mining and validation.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Calculate hashes for mining pool operations</li>
 *   <li>Calculate hashes for block validation</li>
 *   <li>Manage RandomX template selection based on time</li>
 *   <li>Handle null checks and error cases gracefully</li>
 * </ul>
 *
 * <h2>Hash Types</h2>
 * <ul>
 *   <li><strong>Pool Hash</strong>: For mining pool share validation (uses pool template)</li>
 *   <li><strong>Block Hash</strong>: For blockchain block validation (uses block template)</li>
 * </ul>
 *
 * @since XDAGJ v0.8.1
 */
@Slf4j
public class RandomXHashService {

    private final RandomXSeedManager seedManager;

    /**
     * Creates a new RandomX hash service.
     *
     * @param seedManager Seed manager for memory slot selection
     */
    public RandomXHashService(RandomXSeedManager seedManager) {
        this.seedManager = seedManager;
    }

    // ========== Pool Mining Hash ==========

    /**
     * Calculate hash for mining pool operations.
     *
     * <p>Used by mining pools to validate shares submitted by miners.
     * Selects the appropriate memory slot based on task time.
     *
     * @param data Input data to hash
     * @param taskTime Task timestamp (for memory slot selection)
     * @return Calculated hash, or null if not available
     * @throws IllegalArgumentException if data is null
     */
    public Bytes32 calculatePoolHash(Bytes data, long taskTime) {
        if (data == null) {
            throw new IllegalArgumentException("Input data cannot be null");
        }

        // Get active memory slot for pool mining
        RandomXMemory memory = seedManager.getPoolMemory(taskTime);
        if (memory == null) {
            log.debug("Pool memory not initialized for taskTime: {}", taskTime);
            return null;
        }

        // Get pool template
        RandomXTemplate poolTemplate = memory.getPoolTemplate();
        if (poolTemplate == null) {
            log.warn("Pool template not available in memory slot");
            return null;
        }

        // Calculate hash
        try {
            byte[] hash = poolTemplate.calculateHash(data.toArray());
            return Bytes32.wrap(hash);

        } catch (Exception e) {
            log.error("Failed to calculate pool hash for taskTime: " + taskTime, e);
            return null;
        }
    }

    // ========== Block Validation Hash ==========

    /**
     * Calculate hash for block validation.
     *
     * <p>Used by the blockchain to validate blocks and verify PoW.
     * Selects the appropriate memory slot based on block time.
     *
     * @param data Block data to hash
     * @param blockTime Block timestamp (for memory slot selection)
     * @return Calculated hash, or null if not available
     * @throws IllegalArgumentException if data is null
     */
    public byte[] calculateBlockHash(byte[] data, long blockTime) {
        if (data == null) {
            throw new IllegalArgumentException("Input data cannot be null");
        }

        // Get active memory slot for block validation
        long epoch = XdagTime.getEpochNumber(blockTime);
        RandomXMemory memory = seedManager.getActiveMemory(epoch);
        if (memory == null) {
            log.debug("No seed available for epoch {} (blockTime: 0x{})",
                    epoch, Long.toHexString(blockTime));
            return null;
        }

        // Get block template
        RandomXTemplate blockTemplate = memory.getBlockTemplate();
        if (blockTemplate == null) {
            log.warn("Block template not available in memory slot");
            return null;
        }

        // Calculate hash
        try {
            if (log.isDebugEnabled()) {
                log.debug("Using seed {} for block hash calculation",
                        Hex.toHexString(org.bouncycastle.util.Arrays.reverse(memory.getSeed())));
            }

            return blockTemplate.calculateHash(data);

        } catch (Exception e) {
            log.error("Failed to calculate block hash for blockTime: " + blockTime, e);
            return null;
        }
    }

    // ========== Helper Methods ==========

    /**
     * Check if RandomX is ready for hash calculations.
     *
     * @return true if at least one seed epoch is initialized
     */
    public boolean isReady() {
        return seedManager.getCurrentEpochIndex() > 0;
    }

    /**
     * Get diagnostic information about current state.
     *
     * @return Diagnostic string
     */
    public String getDiagnostics() {
        return String.format(
                "RandomXHashService[epochIndex=%d, poolIndex=%d, ready=%s]",
                seedManager.getCurrentEpochIndex(),
                seedManager.getPoolMemoryIndex(),
                isReady()
        );
    }
}
