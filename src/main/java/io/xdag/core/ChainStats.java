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
 * Immutable chain statistics for XDAG network (optimized)
 * <p>
 * This class replaces the mutable XdagStats with a type-safe, immutable implementation.
 * Uses UInt256 for difficulty values.
 * <p>
 * 3 Optimization: Removed 5 unused fields (blockCount, hostCount, mainBlockTime,
 * globalMinerHash, ourLastBlockHash) that were only used for legacy compatibility.
 * <p>
 * Optimized size: ~120 bytes (down from ~180 bytes, 33% reduction)
 */
@Value
@Builder(toBuilder = true)
@With
public class ChainStats implements Serializable {

    // ========== Core Chain State (Cumulative Difficulty for Chain Selection) ==========
    // NOTE: These cumulative difficulty fields are used for CHAIN SELECTION (heaviest chain wins)
    // They are DIFFERENT from baseDifficultyTarget which is used for PoW VALIDATION

    /**
     * Current chain cumulative difficulty (sum of all block difficulties from genesis)
     * <p>
     * This is used for chain selection (heaviest chain wins), NOT for PoW validation.
     * For PoW validation, see baseDifficultyTarget below.
     */
    UInt256 difficulty;

    /**
     * Maximum cumulative difficulty seen in the network
     * <p>
     * This is used to determine which fork is the main chain (heaviest chain wins).
     * Higher cumulative difficulty = main chain.
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

    // ========== Difficulty Management (New Consensus) ==========

    /**
     * Base difficulty target for PoW validation
     * <p>
     * All blocks must satisfy: hash <= baseDifficultyTarget
     * This prevents spam blocks and ensures minimum proof of work.
     * <p>
     * Adjusted periodically based on network hashrate to maintain
     * target blocks per epoch (see OPTIMAL_CONSENSUS_DESIGN.md)
     */
    UInt256 baseDifficultyTarget;

    /**
     * Last epoch when difficulty was adjusted
     * <p>
     * Used to determine when next difficulty adjustment should occur.
     * Adjustment happens every DIFFICULTY_ADJUSTMENT_INTERVAL epochs.
     */
    long lastDifficultyAdjustmentEpoch;

    /**
     * Last epoch when orphan blocks were cleaned up
     * <p>
     * Used to trigger periodic orphan block cleanup.
     * Cleanup removes orphan blocks older than ORPHAN_RETENTION_WINDOW.
     */
    long lastOrphanCleanupEpoch;

    // ========== Top Block State (Merged from XdagTopStatus) ==========
    // NOTE: These are also CUMULATIVE difficulties, not PoW validation targets

    /**
     * Current top block hash (highest cumulative difficulty)
     */
    Bytes32 topBlock;

    /**
     * Current top block cumulative difficulty
     */
    UInt256 topDifficulty;

    /**
     * Previous top block hash
     */
    Bytes32 preTopBlock;

    /**
     * Previous top block cumulative difficulty
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
                .baseDifficultyTarget(null)  // Will be set during initialization
                .lastDifficultyAdjustmentEpoch(0)
                .lastOrphanCleanupEpoch(0)
                .topBlock(null)
                .topDifficulty(UInt256.ZERO)
                .preTopBlock(null)
                .preTopDifficulty(UInt256.ZERO)
                .build();
    }

  // ========== Computed Properties ==========

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
     * <p>
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
     * [1 byte] baseDifficultyTarget null flag
     * [32 bytes] baseDifficultyTarget (if not null)
     * [8 bytes] lastDifficultyAdjustmentEpoch
     * [8 bytes] lastOrphanCleanupEpoch
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

        // Write baseDifficultyTarget (nullable)
        if (baseDifficultyTarget == null) {
            enc.writeBoolean(false);
        } else {
            enc.writeBoolean(true);
            enc.write(baseDifficultyTarget.toBytes().toArray());
        }

        // Write difficulty adjustment epochs
        enc.writeLong(lastDifficultyAdjustmentEpoch);
        enc.writeLong(lastOrphanCleanupEpoch);

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

        // Read baseDifficultyTarget (nullable)
        UInt256 baseDifficultyTarget = null;
        boolean hasBaseDifficultyTarget = dec.readBoolean();
        if (hasBaseDifficultyTarget) {
            byte[] baseDifficultyBytes = new byte[32];
            dec.readBytes(baseDifficultyBytes);
            baseDifficultyTarget = UInt256.fromBytes(org.apache.tuweni.bytes.Bytes.wrap(baseDifficultyBytes));
        }

        // Read difficulty adjustment epochs
        long lastDifficultyAdjustmentEpoch = dec.readLong();
        long lastOrphanCleanupEpoch = dec.readLong();

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
                .baseDifficultyTarget(baseDifficultyTarget)
                .lastDifficultyAdjustmentEpoch(lastDifficultyAdjustmentEpoch)
                .lastOrphanCleanupEpoch(lastOrphanCleanupEpoch)
                .topBlock(topBlock)
                .topDifficulty(topDifficulty)
                .preTopBlock(preTopBlock)
                .preTopDifficulty(preTopDifficulty)
                .build();
    }
}
