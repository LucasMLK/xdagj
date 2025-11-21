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

package io.xdag.core;

import lombok.Builder;
import lombok.Data;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Epoch statistics for XDAG
 *
 * <p>Contains comprehensive statistics for a specific epoch:
 * <ul>
 *   <li>Total candidate blocks in the epoch</li>
 *   <li>Winning block hash (smallest hash in epoch)</li>
 *   <li>Average block time within epoch</li>
 *   <li>Total difficulty added in this epoch</li>
 *   <li>Whether this epoch has a main block</li>
 * </ul>
 *
 * @since XDAGJ
 */
@Data
@Builder
public class EpochStats {

    /** Epoch number (timestamp / 64) */
    private final long epoch;

    /** Start time of epoch (epoch * 64) */
    private final long startTime;

    /** End time of epoch ((epoch + 1) * 64) */
    private final long endTime;

    /** Total candidate blocks in this epoch */
    private final int totalBlocks;

    /** Winning block hash (smallest hash in epoch), null if no winner */
    private final Bytes32 winningBlockHash;

    /** Average block time within epoch (seconds) */
    private final double averageBlockTime;

    /** Total difficulty added in this epoch (sum of all block work) */
    private final UInt256 totalDifficulty;

    /** Whether this epoch has a main block (winner that is not orphan) */
    private final boolean hasMainBlock;

    /**
     * Check if this is an empty epoch (no blocks)
     */
    public boolean isEmpty() {
        return totalBlocks == 0;
    }

}
