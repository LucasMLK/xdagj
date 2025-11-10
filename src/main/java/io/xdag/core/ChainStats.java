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

import io.xdag.p2p.utils.SimpleDecoder;
import io.xdag.p2p.utils.SimpleEncoder;
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

    // ========== Serialization Methods ==========

    /**
     * Serialize ChainStats to bytes for network transmission
     *
     * Format:
     * [32 bytes] difficulty
     * [32 bytes] maxDifficulty
     * [8 bytes] mainBlockCount
     * [8 bytes] totalMainBlockCount
     * [8 bytes] totalBlockCount
     * [4 bytes] totalHostCount
     * [8 bytes] waitingSyncCount
     * [8 bytes] noRefCount
     * [8 bytes] extraCount
     * [8 bytes] balance (nano)
     * [1 byte] topBlock null flag (0=null, 1=not null)
     * [32 bytes] topBlock (if not null)
     * [32 bytes] topDifficulty
     * [1 byte] preTopBlock null flag
     * [32 bytes] preTopBlock (if not null)
     * [32 bytes] preTopDifficulty
     */
    public byte[] toBytes() {
        SimpleEncoder enc = new SimpleEncoder();

        // Write UInt256 fields (32 bytes each)
        enc.write(difficulty.toBytes().toArray());
        enc.write(maxDifficulty.toBytes().toArray());

        // Write long fields
        enc.writeLong(mainBlockCount);
        enc.writeLong(totalMainBlockCount);
        enc.writeLong(totalBlockCount);

        // Write int field
        enc.writeInt(totalHostCount);

        // Write long fields
        enc.writeLong(waitingSyncCount);
        enc.writeLong(noRefCount);
        enc.writeLong(extraCount);

        // Write balance as long (nano)
        enc.writeLong(balance.toXAmount().toLong());

        // Write topBlock (nullable)
        if (topBlock == null) {
            enc.writeBoolean(false);
        } else {
            enc.writeBoolean(true);
            enc.write(topBlock.toArray());
        }

        // Write topDifficulty
        enc.write(topDifficulty.toBytes().toArray());

        // Write preTopBlock (nullable)
        if (preTopBlock == null) {
            enc.writeBoolean(false);
        } else {
            enc.writeBoolean(true);
            enc.write(preTopBlock.toArray());
        }

        // Write preTopDifficulty
        enc.write(preTopDifficulty.toBytes().toArray());

        return enc.toBytes();
    }

    /**
     * Deserialize ChainStats from bytes received from network
     */
    public static ChainStats fromBytes(byte[] data) {
        SimpleDecoder dec = new SimpleDecoder(data);

        // Read UInt256 fields (32 bytes each)
        byte[] difficultyBytes = new byte[32];
        dec.readBytes(difficultyBytes);
        UInt256 difficulty = UInt256.fromBytes(org.apache.tuweni.bytes.Bytes.wrap(difficultyBytes));

        byte[] maxDifficultyBytes = new byte[32];
        dec.readBytes(maxDifficultyBytes);
        UInt256 maxDifficulty = UInt256.fromBytes(org.apache.tuweni.bytes.Bytes.wrap(maxDifficultyBytes));

        // Read long fields
        long mainBlockCount = dec.readLong();
        long totalMainBlockCount = dec.readLong();
        long totalBlockCount = dec.readLong();

        // Read int field
        int totalHostCount = dec.readInt();

        // Read long fields
        long waitingSyncCount = dec.readLong();
        long noRefCount = dec.readLong();
        long extraCount = dec.readLong();

        // Read balance
        long balanceNano = dec.readLong();
        XAmount balance = XAmount.of(balanceNano);

        // Read topBlock (nullable)
        Bytes32 topBlock = null;
        boolean hasTopBlock = dec.readBoolean();
        if (hasTopBlock) {
            byte[] topBlockBytes = new byte[32];
            dec.readBytes(topBlockBytes);
            topBlock = Bytes32.wrap(topBlockBytes);
        }

        // Read topDifficulty
        byte[] topDifficultyBytes = new byte[32];
        dec.readBytes(topDifficultyBytes);
        UInt256 topDifficulty = UInt256.fromBytes(org.apache.tuweni.bytes.Bytes.wrap(topDifficultyBytes));

        // Read preTopBlock (nullable)
        Bytes32 preTopBlock = null;
        boolean hasPreTopBlock = dec.readBoolean();
        if (hasPreTopBlock) {
            byte[] preTopBlockBytes = new byte[32];
            dec.readBytes(preTopBlockBytes);
            preTopBlock = Bytes32.wrap(preTopBlockBytes);
        }

        // Read preTopDifficulty
        byte[] preTopDifficultyBytes = new byte[32];
        dec.readBytes(preTopDifficultyBytes);
        UInt256 preTopDifficulty = UInt256.fromBytes(org.apache.tuweni.bytes.Bytes.wrap(preTopDifficultyBytes));

        // Build ChainStats using builder
        return ChainStats.builder()
                .difficulty(difficulty)
                .maxDifficulty(maxDifficulty)
                .mainBlockCount(mainBlockCount)
                .totalMainBlockCount(totalMainBlockCount)
                .totalBlockCount(totalBlockCount)
                .totalHostCount(totalHostCount)
                .waitingSyncCount(waitingSyncCount)
                .noRefCount(noRefCount)
                .extraCount(extraCount)
                .balance(balance)
                .topBlock(topBlock)
                .topDifficulty(topDifficulty)
                .preTopBlock(preTopBlock)
                .preTopDifficulty(preTopDifficulty)
                .build();
    }
}
