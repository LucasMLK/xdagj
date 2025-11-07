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

package io.xdag.consensus.sync.protocol;

/**
 * Finality Boundary Utility Class
 *
 * <p>This class provides utility methods to calculate and verify the finality boundary
 * in the XDAG blockchain. The finality boundary separates the finalized chain region
 * from the active DAG region, enabling hybrid synchronization protocols.
 *
 * <p><strong>Core Concept</strong>:
 * <ul>
 *   <li>Blocks older than FINALITY_EPOCHS are considered finalized and immutable</li>
 *   <li>Finalized blocks form a linear chain that can be synchronized efficiently</li>
 *   <li>Active DAG region contains recent blocks that may still undergo reorganization</li>
 * </ul>
 *
 * <p><strong>Design Parameters</strong>:
 * <ul>
 *   <li>FINALITY_EPOCHS = 16384 (2^14) ≈ 12 days</li>
 *   <li>Provides sufficient time for the XDAG community to respond to network issues</li>
 *   <li>Balances between safety and synchronization efficiency</li>
 * </ul>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Check if a block is finalized
 * long currentHeight = blockchain.getMainHeight();
 * long blockHeight = 1000000;
 * boolean isFinalized = FinalityBoundary.isFinalized(blockHeight, currentHeight);
 *
 * // Get the finalized height boundary
 * long finalizedHeight = FinalityBoundary.getFinalizedHeight(currentHeight);
 * }</pre>
 *
 * @see <a href="docs/refactor-design/HYBRID_SYNC_PROTOCOL.md">Hybrid Sync Protocol Design</a>
 * @since v5.1
 */
public class FinalityBoundary {

    /**
     * Number of epochs after which a block is considered finalized
     *
     * <p>16384 epochs ≈ 12.14 days (assuming 64 seconds per epoch)
     *
     * <p><strong>Rationale</strong>:
     * <ul>
     *   <li>Small community: needs sufficient coordination time</li>
     *   <li>Conservative approach: prevents premature finalization</li>
     *   <li>Power of 2: efficient for bitwise operations if needed</li>
     * </ul>
     */
    public static final int FINALITY_EPOCHS = 16384;  // 2^14

    /**
     * Private constructor to prevent instantiation
     */
    private FinalityBoundary() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Calculate the finalized height boundary
     *
     * <p>Blocks at or before this height are considered finalized and immutable.
     * They can be synchronized using the linear chain sync protocol.
     *
     * @param currentMainHeight Current main block height
     * @return Finalized height (currentMainHeight - FINALITY_EPOCHS), minimum 0
     * @throws IllegalArgumentException if currentMainHeight is negative
     */
    public static long getFinalizedHeight(long currentMainHeight) {
        if (currentMainHeight < 0) {
            throw new IllegalArgumentException(
                    "Current main height cannot be negative: " + currentMainHeight);
        }

        return Math.max(0, currentMainHeight - FINALITY_EPOCHS);
    }

    /**
     * Check if a block at the given height is finalized
     *
     * <p>A block is considered finalized if it has been confirmed by at least
     * FINALITY_EPOCHS subsequent main blocks.
     *
     * <p><strong>Finalized blocks guarantee</strong>:
     * <ul>
     *   <li>Cannot be reorganized (practically immutable)</li>
     *   <li>Form a unique linear main chain via maxDiffLink</li>
     *   <li>All referenced blocks are also finalized</li>
     *   <li>All balances and transactions are settled</li>
     * </ul>
     *
     * @param height Block height to check
     * @param currentMainHeight Current main block height
     * @return true if the block is finalized, false otherwise
     * @throws IllegalArgumentException if any parameter is negative
     */
    public static boolean isFinalized(long height, long currentMainHeight) {
        if (height < 0) {
            throw new IllegalArgumentException("Block height cannot be negative: " + height);
        }
        if (currentMainHeight < 0) {
            throw new IllegalArgumentException(
                    "Current main height cannot be negative: " + currentMainHeight);
        }

        return height <= getFinalizedHeight(currentMainHeight);
    }

    /**
     * Check if a block at the given height is in the active DAG region
     *
     * <p>The active DAG region contains recent blocks that may still undergo
     * reorganization. These blocks require DAG-based synchronization.
     *
     * @param height Block height to check
     * @param currentMainHeight Current main block height
     * @return true if the block is in active DAG region, false otherwise
     * @throws IllegalArgumentException if any parameter is negative
     */
    public static boolean isActiveDAG(long height, long currentMainHeight) {
        if (height < 0) {
            throw new IllegalArgumentException("Block height cannot be negative: " + height);
        }
        if (currentMainHeight < 0) {
            throw new IllegalArgumentException(
                    "Current main height cannot be negative: " + currentMainHeight);
        }

        return !isFinalized(height, currentMainHeight) && height <= currentMainHeight;
    }

    /**
     * Get the height range for the active DAG region
     *
     * <p>Returns [finalizedHeight + 1, currentMainHeight]
     *
     * @param currentMainHeight Current main block height
     * @return Array [fromHeight, toHeight] representing the active DAG range.
     *         Returns [0, 0] if currentMainHeight < FINALITY_EPOCHS.
     * @throws IllegalArgumentException if currentMainHeight is negative
     */
    public static long[] getActiveDAGRange(long currentMainHeight) {
        if (currentMainHeight < 0) {
            throw new IllegalArgumentException(
                    "Current main height cannot be negative: " + currentMainHeight);
        }

        long finalizedHeight = getFinalizedHeight(currentMainHeight);

        if (finalizedHeight >= currentMainHeight) {
            // No active DAG region yet
            return new long[]{0, 0};
        }

        return new long[]{finalizedHeight + 1, currentMainHeight};
    }

    /**
     * Get the size of the active DAG region in epochs
     *
     * @param currentMainHeight Current main block height
     * @return Number of epochs in the active DAG region (0 to FINALITY_EPOCHS)
     * @throws IllegalArgumentException if currentMainHeight is negative
     */
    public static long getActiveDAGSize(long currentMainHeight) {
        if (currentMainHeight < 0) {
            throw new IllegalArgumentException(
                    "Current main height cannot be negative: " + currentMainHeight);
        }

        return Math.min(currentMainHeight, FINALITY_EPOCHS);
    }

    /**
     * Calculate the number of epochs remaining until a block is finalized
     *
     * @param height Block height to check
     * @param currentMainHeight Current main block height
     * @return Number of epochs remaining (0 if already finalized)
     * @throws IllegalArgumentException if any parameter is negative
     */
    public static long getEpochsUntilFinalized(long height, long currentMainHeight) {
        if (height < 0) {
            throw new IllegalArgumentException("Block height cannot be negative: " + height);
        }
        if (currentMainHeight < 0) {
            throw new IllegalArgumentException(
                    "Current main height cannot be negative: " + currentMainHeight);
        }

        if (isFinalized(height, currentMainHeight)) {
            return 0;
        }

        long requiredHeight = height + FINALITY_EPOCHS;
        return requiredHeight - currentMainHeight;
    }
}
