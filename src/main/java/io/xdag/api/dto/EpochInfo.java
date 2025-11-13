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

package io.xdag.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Epoch information DTO
 * Used by both CLI and RPC to return epoch data
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EpochInfo {

    /**
     * Epoch number
     */
    private long epochNumber;

    /**
     * Is current epoch
     */
    private boolean isCurrent;

    /**
     * Epoch start time (seconds)
     */
    private long startTime;

    /**
     * Epoch end time (seconds)
     */
    private long endTime;

    /**
     * Epoch duration (seconds)
     */
    private long duration;

    /**
     * Elapsed time (seconds, only for current epoch)
     */
    private Long elapsed;

    /**
     * Progress percentage (only for current epoch)
     */
    private Double progress;

    /**
     * Time until next epoch (seconds, only for current epoch)
     */
    private Long timeToNext;

    /**
     * Total candidate blocks in this epoch
     */
    private int candidateCount;

    /**
     * Winner block hash (hex string, truncated)
     */
    private String winnerHash;

    /**
     * Winner block height
     */
    private Long winnerHeight;

    /**
     * Orphan blocks count
     */
    private int orphanCount;

    /**
     * Average difficulty of candidates
     */
    private UInt256 averageDifficulty;

    /**
     * Total difficulty of candidates
     */
    private UInt256 totalDifficulty;
}
