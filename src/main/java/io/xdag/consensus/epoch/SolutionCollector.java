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
package io.xdag.consensus.epoch;

import io.xdag.core.Block;
import org.apache.tuweni.units.bigints.UInt256;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SolutionCollector - Collects candidate solutions during epochs.
 *
 * <p>This component implements the "collect and select" pattern instead of "first come first served":
 * <ul>
 *   <li>Multiple pools can submit solutions for the same epoch</li>
 *   <li>Solutions are validated but not immediately imported</li>
 *   <li>At epoch end, the best solution (highest difficulty) is selected</li>
 * </ul>
 *
 * <p>Key validations:
 * <ul>
 *   <li>Epoch number must match current epoch</li>
 *   <li>Difficulty must meet minimum threshold</li>
 *   <li>Block must not have been already produced for this epoch</li>
 * </ul>
 *
 * <p>Part of BUG-CONSENSUS-002 fix - enables "best solution wins" consensus.
 *
 * @see EpochConsensusManager
 * @see BestSolutionSelector
 */
@Slf4j
public class SolutionCollector {

    /**
     * Minimum difficulty requirement for solutions.
     */
    private final UInt256 minimumDifficulty;

    /**
     * Map of epoch contexts (epoch number → context).
     */
    private final ConcurrentHashMap<Long, EpochContext> epochContexts;

    /**
     * Create a new SolutionCollector.
     *
     * @param minimumDifficulty  Minimum required difficulty
     * @param epochContexts      Epoch contexts map (shared with EpochConsensusManager)
     */
    public SolutionCollector(UInt256 minimumDifficulty, ConcurrentHashMap<Long, EpochContext> epochContexts) {
        this.minimumDifficulty = minimumDifficulty;
        this.epochContexts = epochContexts;
    }

    /**
     * Submit a candidate solution for collection.
     *
     * <p>The solution is validated and, if accepted, added to the epoch's collection
     * for later selection.
     *
     * @param block         The candidate block
     * @param poolId        Pool/miner identifier
     * @param currentEpoch  Current epoch number
     * @return SubmitResult indicating acceptance or rejection
     */
    public SubmitResult submitSolution(Block block, String poolId, long currentEpoch) {
        long blockEpoch = block.getEpoch();

        // 1. Check epoch match
        if (blockEpoch != currentEpoch) {
            return SubmitResult.rejected(
                    String.format("Epoch mismatch: expected %d, got %d", currentEpoch, blockEpoch)
            );
        }

        // 2. Calculate and verify difficulty
        UInt256 blockDifficulty = calculateDifficulty(block.getHash());
        if (blockDifficulty.compareTo(minimumDifficulty) < 0) {
            return SubmitResult.rejected(
                    String.format("Insufficient difficulty: %s < %s (minimum)",
                            blockDifficulty.toHexString().substring(0, 18),
                            minimumDifficulty.toHexString().substring(0, 18))
            );
        }

        // 3. Get epoch context
        EpochContext context = epochContexts.get(blockEpoch);
        if (context == null) {
            return SubmitResult.rejected(
                    String.format("Epoch context not found for epoch %d (may have already ended)", blockEpoch)
            );
        }

        // 4. Check if block already produced
        if (context.isBlockProduced()) {
            return SubmitResult.rejected(
                    String.format("Block already produced for epoch %d", blockEpoch)
            );
        }

        // 5. Add to solution collection
        BlockSolution solution = new BlockSolution(block, poolId, System.currentTimeMillis(), blockDifficulty);
        context.addSolution(solution);

        log.info("✓ Solution collected: epoch={}, pool='{}', difficulty={}, solutions_count={}",
                blockEpoch, poolId, blockDifficulty.toHexString().substring(0, 18),
                context.getSolutionsCount());

        return SubmitResult.accepted(
                String.format("Solution collected for epoch %d, will be processed at epoch end", blockEpoch)
        );
    }

    /**
     * Get all solutions collected for an epoch.
     *
     * @param epoch Epoch number
     * @return List of solutions, or empty list if epoch not found
     */
    public List<BlockSolution> getSolutions(long epoch) {
        EpochContext context = epochContexts.get(epoch);
        return context != null ? context.getSolutions() : Collections.emptyList();
    }

    /**
     * Calculate difficulty from block hash.
     *
     * <p>Difficulty is the inverse of the hash value:
     * Higher hash = lower difficulty
     * Lower hash = higher difficulty
     *
     * <p>This matches the original XDAG C code's minhash mechanism.
     *
     * @param hash The block hash (32 bytes)
     * @return Difficulty (inverse of hash)
     */
    private UInt256 calculateDifficulty(org.apache.tuweni.bytes.Bytes32 hash) {
        // In XDAG, difficulty is inversely proportional to hash
        // Lower hash = higher difficulty = better solution
        // We return the hash inverted: difficulty = MAX_UINT256 - hash
        //
        // However, for simplicity in comparison, we can just use the hash directly
        // and compare: lower hash = better solution
        // So we return the inverse to make "higher difficulty = better"

        if (hash == null || hash.equals(org.apache.tuweni.bytes.Bytes32.ZERO)) {
            return UInt256.ZERO;
        }

        // Convert Bytes32 to UInt256
        UInt256 hashValue = UInt256.fromBytes(hash);

        // Return inverse: MAX - hash
        // This makes smaller hashes produce larger difficulty values
        return UInt256.MAX_VALUE.subtract(hashValue);
    }

    /**
     * Get the minimum difficulty requirement.
     *
     * @return Minimum difficulty
     */
    public UInt256 getMinimumDifficulty() {
        return minimumDifficulty;
    }
}
