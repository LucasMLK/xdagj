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

import io.xdag.core.XAmount;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Chain statistics information DTO
 * Used by both CLI and RPC to return blockchain statistics
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainStatsInfo {

    /**
     * Main chain block count
     */
    private long mainBlockCount;

    /**
     * Total block count
     */
    private long totalBlockCount;

    /**
     * Top block height
     */
    private Long topBlockHeight;

    /**
     * Top block hash (full hex string)
     */
    private String topBlockHash;

    /**
     * Current epoch
     */
    private long currentEpoch;

    /**
     * Current difficulty
     */
    private UInt256 currentDifficulty;

    /**
     * Max difficulty
     */
    private UInt256 maxDifficulty;

    /**
     * Orphan block count
     */
    private long orphanCount;

    /**
     * Waiting sync count
     */
    private long waitingSyncCount;

    /**
     * Sync progress (percentage)
     */
    private double syncProgress;

    /**
     * Connected peer count
     */
    private int connectedPeers;

    /**
     * Total host count
     */
    private int totalHosts;

    /**
     * Total wallet balance
     */
    private XAmount totalWalletBalance;

    /**
     * Wallet account count
     */
    private int accountCount;
}
