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
import lombok.Value;
import lombok.With;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

import java.io.Serializable;

/**
 * Immutable chain statistics for XDAG network
 *
 * This class replaces the mutable XdagStats with a type-safe, immutable implementation.
 * Uses UInt256 for difficulty values and Bytes32 for hashes.
 *
 * Target size: ~180 bytes (compared to ~220 bytes in XdagStats)
 */
@Value
@Builder(toBuilder = true)
@With
public class ChainStats implements Serializable {

    // ========== Current State ==========

    /**
     * Current chain difficulty
     */
    UInt256 difficulty;

    /**
     * Maximum difficulty seen in the network
     */
    UInt256 maxDifficulty;

    /**
     * Current number of blocks in local chain
     */
    long blockCount;

    /**
     * Total number of blocks (including orphans)
     */
    long totalBlockCount;

    /**
     * Current number of main blocks
     */
    long mainBlockCount;

    /**
     * Total number of main blocks
     */
    long totalMainBlockCount;

    /**
     * Number of connected hosts/peers
     */
    int hostCount;

    /**
     * Total number of hosts seen
     */
    int totalHostCount;

    /**
     * Number of blocks waiting for synchronization
     */
    long waitingSyncCount;

    /**
     * Number of blocks with no references (orphans)
     */
    long noRefCount;

    /**
     * Number of extra blocks
     */
    long extraCount;

    /**
     * Timestamp of the latest main block
     */
    long mainBlockTime;

    /**
     * Current balance
     */
    XAmount balance;

    // ========== Identifiers ==========

    /**
     * Global miner address hash
     */
    Bytes32 globalMinerHash;

    /**
     * Hash of our last created block
     */
    Bytes32 ourLastBlockHash;

    // ========== Helper Methods ==========

    /**
     * Create initial chain stats with zero values
     */
    public static ChainStats zero() {
        return ChainStats.builder()
                .difficulty(UInt256.ZERO)
                .maxDifficulty(UInt256.ZERO)
                .blockCount(0)
                .totalBlockCount(0)
                .mainBlockCount(0)
                .totalMainBlockCount(0)
                .hostCount(0)
                .totalHostCount(0)
                .waitingSyncCount(0)
                .noRefCount(0)
                .extraCount(0)
                .mainBlockTime(0)
                .balance(XAmount.ZERO)
                .build();
    }

    /**
     * Create chain stats from legacy XdagStats
     */
    public static ChainStats fromLegacy(XdagStats legacy) {
        return ChainStats.builder()
                .difficulty(legacy.getDifficulty() != null ?
                    UInt256.valueOf(legacy.getDifficulty()) : UInt256.ZERO)
                .maxDifficulty(legacy.getMaxdifficulty() != null ?
                    UInt256.valueOf(legacy.getMaxdifficulty()) : UInt256.ZERO)
                .blockCount(legacy.getNblocks())
                .totalBlockCount(legacy.getTotalnblocks())
                .mainBlockCount(legacy.getNmain())
                .totalMainBlockCount(legacy.getTotalnmain())
                .hostCount(legacy.getNhosts())
                .totalHostCount(legacy.getTotalnhosts())
                .waitingSyncCount(legacy.getNwaitsync())
                .noRefCount(legacy.getNnoref())
                .extraCount(legacy.getNextra())
                .mainBlockTime(legacy.getMaintime())
                .balance(legacy.getBalance() != null ? legacy.getBalance() : XAmount.ZERO)
                .globalMinerHash(legacy.getGlobalMiner() != null ?
                    Bytes32.wrap(legacy.getGlobalMiner()) : null)
                .ourLastBlockHash(legacy.getOurLastBlockHash() != null ?
                    Bytes32.wrap(legacy.getOurLastBlockHash()) : null)
                .build();
    }

    /**
     * Convert to legacy XdagStats (for backward compatibility)
     */
    public XdagStats toLegacy() {
        XdagStats legacy = new XdagStats();
        legacy.setDifficulty(difficulty.toBigInteger());
        legacy.setMaxdifficulty(maxDifficulty.toBigInteger());
        legacy.setNblocks(blockCount);
        legacy.setTotalnblocks(totalBlockCount);
        legacy.setNmain(mainBlockCount);
        legacy.setTotalnmain(totalMainBlockCount);
        legacy.setNhosts(hostCount);
        legacy.setTotalnhosts(totalHostCount);
        legacy.setNwaitsync(waitingSyncCount);
        legacy.setNnoref(noRefCount);
        legacy.setNextra(extraCount);
        legacy.setMaintime(mainBlockTime);
        legacy.setBalance(balance);
        if (globalMinerHash != null) {
            legacy.setGlobalMiner(globalMinerHash.toArray());
        }
        if (ourLastBlockHash != null) {
            legacy.setOurLastBlockHash(ourLastBlockHash.toArray());
        }
        return legacy;
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
     * Increment block count (returns new instance)
     */
    public ChainStats incrementBlockCount() {
        return this.toBuilder()
                .blockCount(this.blockCount + 1)
                .totalBlockCount(this.totalBlockCount + 1)
                .build();
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
        return String.format("ChainStats[mainBlocks=%d/%d, blocks=%d/%d, difficulty=%s, maxDiff=%s, hosts=%d/%d, balance=%s, orphans=%d, sync=%.1f%%]",
                mainBlockCount, totalMainBlockCount,
                blockCount, totalBlockCount,
                difficulty.toDecimalString(),
                maxDifficulty.toDecimalString(),
                hostCount, totalHostCount,
                balance.toDecimal(9, XUnit.XDAG).toPlainString(),
                noRefCount,
                getSyncProgress());
    }
}
