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

import java.io.Serializable;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Immutable chain statistics for XDAG network (v5.1 optimized)
 *
 * This class replaces the mutable XdagStats with a type-safe, immutable implementation.
 * Uses UInt256 for difficulty values.
 *
 * Phase 7.3 Optimization: Removed 5 unused fields (blockCount, hostCount, mainBlockTime,
 * globalMinerHash, ourLastBlockHash) that were only used for legacy compatibility.
 *
 * Optimized size: ~120 bytes (down from ~180 bytes, 33% reduction)
 */
@Value
@Builder(toBuilder = true)
@With
public class ChainStats implements Serializable {

    // ========== Core Chain State ==========

    /**
     * Current chain difficulty
     */
    UInt256 difficulty;

    /**
     * Maximum difficulty seen in the network
     */
    UInt256 maxDifficulty;

    /**
     * Current number of main blocks in local chain
     */
    long mainBlockCount;

    /**
     * Total number of main blocks in the network
     */
    long totalMainBlockCount;

    /**
     * Total number of blocks in the network (including orphans)
     */
    long totalBlockCount;

    /**
     * Total number of hosts/peers seen in the network
     */
    int totalHostCount;

    // ========== Sync & Orphan Tracking ==========

    /**
     * Number of blocks waiting for synchronization (missing parent blocks)
     */
    long waitingSyncCount;

    /**
     * Number of blocks with no references (orphan blocks)
     */
    long noRefCount;

    /**
     * Number of extra blocks
     */
    long extraCount;

    // ========== Account State ==========

    /**
     * Current balance
     */
    XAmount balance;

    // ========== Top Block State (Phase 7.3.1: Merged from XdagTopStatus) ==========

    /**
     * Current top block hash (highest difficulty)
     */
    Bytes32 topBlock;

    /**
     * Current top block difficulty
     */
    UInt256 topDifficulty;

    /**
     * Previous top block hash
     */
    Bytes32 preTopBlock;

    /**
     * Previous top block difficulty
     */
    UInt256 preTopDifficulty;

    // ========== Helper Methods ==========

    /**
     * Create initial chain stats with zero values
     */
    public static ChainStats zero() {
        return ChainStats.builder()
                .difficulty(UInt256.ZERO)
                .maxDifficulty(UInt256.ZERO)
                .mainBlockCount(0)
                .totalMainBlockCount(0)
                .totalBlockCount(0)
                .totalHostCount(0)
                .waitingSyncCount(0)
                .noRefCount(0)
                .extraCount(0)
                .balance(XAmount.ZERO)
                .topBlock(null)
                .topDifficulty(UInt256.ZERO)
                .preTopBlock(null)
                .preTopDifficulty(UInt256.ZERO)
                .build();
    }

    /**
     * Update stats with data from remote node (returns new instance)
     */
    public ChainStats mergeWithRemote(ChainStats remote) {
        return this.toBuilder()
                .totalHostCount(Math.max(this.totalHostCount, remote.totalHostCount))
                .totalBlockCount(Math.max(this.totalBlockCount, remote.totalBlockCount))
                .totalMainBlockCount(Math.max(this.totalMainBlockCount, remote.totalMainBlockCount))
                .maxDifficulty(this.maxDifficulty.compareTo(remote.maxDifficulty) > 0 ?
                    this.maxDifficulty : remote.maxDifficulty)
                .build();
    }

    /**
     * Update maximum difficulty if new value is higher (returns new instance)
     */
    public ChainStats updateMaxDifficulty(UInt256 newMaxDifficulty) {
        if (this.maxDifficulty.compareTo(newMaxDifficulty) < 0) {
            return this.withMaxDifficulty(newMaxDifficulty);
        }
        return this;
    }

    /**
     * Update current difficulty if new value is higher (returns new instance)
     */
    public ChainStats updateDifficulty(UInt256 newDifficulty) {
        if (this.difficulty.compareTo(newDifficulty) < 0) {
            return this.withDifficulty(newDifficulty);
        }
        return this;
    }

    /**
     * Increment main block count (returns new instance)
     */
    public ChainStats incrementMainBlockCount() {
        return this.toBuilder()
                .mainBlockCount(this.mainBlockCount + 1)
                .totalMainBlockCount(this.totalMainBlockCount + 1)
                .build();
    }

    /**
     * Update balance (returns new instance)
     */
    public ChainStats updateBalance(XAmount newBalance) {
        return this.withBalance(newBalance);
    }

    // ========== Computed Properties ==========

    /**
     * Get orphan block percentage
     */
    public double getOrphanPercentage() {
        if (totalBlockCount == 0) {
            return 0.0;
        }
        return (double) noRefCount / totalBlockCount * 100.0;
    }

    /**
     * Get main block percentage
     */
    public double getMainBlockPercentage() {
        if (totalBlockCount == 0) {
            return 0.0;
        }
        return (double) totalMainBlockCount / totalBlockCount * 100.0;
    }

    /**
     * Check if syncing is needed
     */
    public boolean needsSync() {
        return waitingSyncCount > 0;
    }

    /**
     * Get sync completion percentage
     */
    public double getSyncProgress() {
        if (totalBlockCount == 0) {
            return 100.0;
        }
        long syncedCount = totalBlockCount - waitingSyncCount;
        return (double) syncedCount / totalBlockCount * 100.0;
    }

    @Override
    public String toString() {
        return String.format("ChainStats[mainBlocks=%d/%d, totalBlocks=%d, difficulty=%s, maxDiff=%s, hosts=%d, balance=%s, orphans=%d, sync=%.1f%%]",
                mainBlockCount, totalMainBlockCount,
                totalBlockCount,
                difficulty.toDecimalString(),
                maxDifficulty.toDecimalString(),
                totalHostCount,
                balance.toDecimal(9, XUnit.XDAG).toPlainString(),
                noRefCount,
                getSyncProgress());
    }
}
