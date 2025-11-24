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
import lombok.Getter;

/**
 * BlockSolution - Represents a candidate solution submitted during an epoch.
 *
 * <p>Each solution consists of:
 * <ul>
 *   <li>block - The candidate block</li>
 *   <li>poolId - Identifier of the pool/miner that found this solution</li>
 *   <li>submitTime - Timestamp when the solution was submitted</li>
 *   <li>difficulty - The difficulty (inverse of hash) of this solution</li>
 * </ul>
 *
 * <p>Solutions are collected during an epoch, and at epoch end, the best solution
 * (highest difficulty) is selected for block import.
 *
 * <p>Part of BUG-CONSENSUS-002 fix - enables "best solution wins" instead of
 * "first solution wins".
 *
 * @see SolutionCollector
 * @see BestSolutionSelector
 */
@Getter
public class BlockSolution {

    /**
     * The candidate block.
     */
    private final Block block;

    /**
     * Pool/miner identifier (e.g., "pool1", "BACKUP_MINER").
     */
    private final String poolId;

    /**
     * Submission timestamp (milliseconds).
     */
    private final long submitTime;

    /**
     * Block difficulty (inverse of hash).
     * Higher difficulty = better solution.
     */
    private final UInt256 difficulty;

    /**
     * Create a new BlockSolution.
     *
     * @param block      The candidate block
     * @param poolId     Pool/miner identifier
     * @param submitTime Submission timestamp
     * @param difficulty Block difficulty
     */
    public BlockSolution(Block block, String poolId, long submitTime, UInt256 difficulty) {
        this.block = block;
        this.poolId = poolId;
        this.submitTime = submitTime;
        this.difficulty = difficulty;
    }

    /**
     * Check if this solution is valid.
     *
     * @param expectedEpoch   Expected epoch number
     * @param minimumDifficulty Minimum required difficulty
     * @return true if valid, false otherwise
     */
    public boolean isValid(long expectedEpoch, UInt256 minimumDifficulty) {
        if (block == null) {
            return false;
        }

        // Check epoch matches
        if (block.getEpoch() != expectedEpoch) {
            return false;
        }

        // Check minimum difficulty
        if (difficulty == null || difficulty.compareTo(minimumDifficulty) < 0) {
            return false;
        }

        return true;
    }

    @Override
    public String toString() {
        return String.format("BlockSolution{pool='%s', epoch=%d, difficulty=%s, submitTime=%d}",
                poolId,
                block != null ? block.getEpoch() : -1,
                difficulty != null ? difficulty.toHexString().substring(0, 18) : "null",
                submitTime);
    }
}
