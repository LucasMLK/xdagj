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

import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.*;

/**
 * Test class for ChainStats
 */
public class ChainStatsTest {

    @Test
    public void testBuilderPattern() {
        ChainStats stats = ChainStats.builder()
                .difficulty(UInt256.valueOf(1000))
                .maxDifficulty(UInt256.valueOf(2000))
                .blockCount(100)
                .totalBlockCount(150)
                .mainBlockCount(50)
                .totalMainBlockCount(60)
                .hostCount(5)
                .totalHostCount(10)
                .balance(XAmount.of(100, XUnit.XDAG))
                .build();

        assertNotNull(stats);
        assertEquals(UInt256.valueOf(1000), stats.getDifficulty());
        assertEquals(UInt256.valueOf(2000), stats.getMaxDifficulty());
        assertEquals(100, stats.getBlockCount());
        assertEquals(150, stats.getTotalBlockCount());
        assertEquals(50, stats.getMainBlockCount());
        assertEquals(60, stats.getTotalMainBlockCount());
        assertEquals(5, stats.getHostCount());
        assertEquals(10, stats.getTotalHostCount());
    }

    @Test
    public void testZeroInitialization() {
        ChainStats stats = ChainStats.zero();

        assertNotNull(stats);
        assertEquals(UInt256.ZERO, stats.getDifficulty());
        assertEquals(UInt256.ZERO, stats.getMaxDifficulty());
        assertEquals(0, stats.getBlockCount());
        assertEquals(0, stats.getTotalBlockCount());
        assertEquals(0, stats.getMainBlockCount());
        assertEquals(0, stats.getTotalMainBlockCount());
        assertEquals(XAmount.ZERO, stats.getBalance());
    }

    @Test
    public void testImmutability() {
        ChainStats original = ChainStats.builder()
                .difficulty(UInt256.valueOf(1000))
                .blockCount(100)
                .mainBlockCount(50)
                .balance(XAmount.of(100, XUnit.XDAG))
                .build();

        // Use @With to create modified copy
        ChainStats modified = original.withBlockCount(200);

        // Verify original unchanged
        assertEquals(100, original.getBlockCount());
        // Verify new instance modified
        assertEquals(200, modified.getBlockCount());
        // Verify other fields same
        assertEquals(original.getDifficulty(), modified.getDifficulty());
        assertEquals(original.getMainBlockCount(), modified.getMainBlockCount());
    }

    @Test
    public void testToBuilder() {
        ChainStats original = ChainStats.builder()
                .difficulty(UInt256.valueOf(1000))
                .blockCount(100)
                .mainBlockCount(50)
                .build();

        ChainStats modified = original.toBuilder()
                .blockCount(200)
                .mainBlockCount(100)
                .build();

        assertEquals(100, original.getBlockCount());
        assertEquals(50, original.getMainBlockCount());

        assertEquals(200, modified.getBlockCount());
        assertEquals(100, modified.getMainBlockCount());
        assertEquals(original.getDifficulty(), modified.getDifficulty());
    }

    @Test
    public void testFromLegacy() {
        XdagStats legacy = new XdagStats();
        legacy.setDifficulty(BigInteger.valueOf(5000));
        legacy.setMaxdifficulty(BigInteger.valueOf(10000));
        legacy.setNblocks(200);
        legacy.setTotalnblocks(250);
        legacy.setNmain(100);
        legacy.setTotalnmain(120);
        legacy.setNhosts(8);
        legacy.setTotalnhosts(15);
        legacy.setNwaitsync(10);
        legacy.setNnoref(5);
        legacy.setNextra(3);
        legacy.setMaintime(1234567890L);
        legacy.setBalance(XAmount.of(500, XUnit.XDAG));
        legacy.setGlobalMiner(Bytes32.random().toArray());
        legacy.setOurLastBlockHash(Bytes32.random().toArray());

        ChainStats stats = ChainStats.fromLegacy(legacy);

        assertEquals(BigInteger.valueOf(5000), stats.getDifficulty().toBigInteger());
        assertEquals(BigInteger.valueOf(10000), stats.getMaxDifficulty().toBigInteger());
        assertEquals(200, stats.getBlockCount());
        assertEquals(250, stats.getTotalBlockCount());
        assertEquals(100, stats.getMainBlockCount());
        assertEquals(120, stats.getTotalMainBlockCount());
        assertEquals(8, stats.getHostCount());
        assertEquals(15, stats.getTotalHostCount());
        assertEquals(10, stats.getWaitingSyncCount());
        assertEquals(5, stats.getNoRefCount());
        assertEquals(3, stats.getExtraCount());
        assertEquals(1234567890L, stats.getMainBlockTime());
        assertEquals(legacy.getBalance(), stats.getBalance());
        assertNotNull(stats.getGlobalMinerHash());
        assertNotNull(stats.getOurLastBlockHash());
    }

    @Test
    public void testToLegacy() {
        Bytes32 minerHash = Bytes32.random();
        Bytes32 lastBlockHash = Bytes32.random();

        ChainStats stats = ChainStats.builder()
                .difficulty(UInt256.valueOf(3000))
                .maxDifficulty(UInt256.valueOf(6000))
                .blockCount(150)
                .totalBlockCount(180)
                .mainBlockCount(75)
                .totalMainBlockCount(90)
                .hostCount(6)
                .totalHostCount(12)
                .waitingSyncCount(8)
                .noRefCount(4)
                .extraCount(2)
                .mainBlockTime(9876543210L)
                .balance(XAmount.of(300, XUnit.XDAG))
                .globalMinerHash(minerHash)
                .ourLastBlockHash(lastBlockHash)
                .build();

        XdagStats legacy = stats.toLegacy();

        assertEquals(BigInteger.valueOf(3000), legacy.getDifficulty());
        assertEquals(BigInteger.valueOf(6000), legacy.getMaxdifficulty());
        assertEquals(150, legacy.getNblocks());
        assertEquals(180, legacy.getTotalnblocks());
        assertEquals(75, legacy.getNmain());
        assertEquals(90, legacy.getTotalnmain());
        assertEquals(6, legacy.getNhosts());
        assertEquals(12, legacy.getTotalnhosts());
        assertEquals(8, legacy.getNwaitsync());
        assertEquals(4, legacy.getNnoref());
        assertEquals(2, legacy.getNextra());
        assertEquals(9876543210L, legacy.getMaintime());
        assertEquals(stats.getBalance(), legacy.getBalance());
        assertArrayEquals(minerHash.toArray(), legacy.getGlobalMiner());
        assertArrayEquals(lastBlockHash.toArray(), legacy.getOurLastBlockHash());
    }

    @Test
    public void testRoundTripConversion() {
        XdagStats originalLegacy = new XdagStats();
        originalLegacy.setDifficulty(BigInteger.valueOf(7777));
        originalLegacy.setMaxdifficulty(BigInteger.valueOf(9999));
        originalLegacy.setNblocks(300);
        originalLegacy.setTotalnblocks(350);
        originalLegacy.setNmain(150);
        originalLegacy.setTotalnmain(175);
        originalLegacy.setBalance(XAmount.of(1000, XUnit.XDAG));

        // Legacy -> ChainStats -> Legacy
        ChainStats stats = ChainStats.fromLegacy(originalLegacy);
        XdagStats convertedLegacy = stats.toLegacy();

        // Verify consistency
        assertEquals(originalLegacy.getDifficulty(), convertedLegacy.getDifficulty());
        assertEquals(originalLegacy.getMaxdifficulty(), convertedLegacy.getMaxdifficulty());
        assertEquals(originalLegacy.getNblocks(), convertedLegacy.getNblocks());
        assertEquals(originalLegacy.getTotalnblocks(), convertedLegacy.getTotalnblocks());
        assertEquals(originalLegacy.getNmain(), convertedLegacy.getNmain());
        assertEquals(originalLegacy.getTotalnmain(), convertedLegacy.getTotalnmain());
        assertEquals(originalLegacy.getBalance(), convertedLegacy.getBalance());
    }

    @Test
    public void testMergeWithRemote() {
        ChainStats local = ChainStats.builder()
                .maxDifficulty(UInt256.valueOf(5000))
                .totalBlockCount(100)
                .totalMainBlockCount(50)
                .totalHostCount(10)
                .build();

        ChainStats remote = ChainStats.builder()
                .maxDifficulty(UInt256.valueOf(7000))  // Higher
                .totalBlockCount(120)                  // Higher
                .totalMainBlockCount(45)               // Lower
                .totalHostCount(8)                     // Lower
                .build();

        ChainStats merged = local.mergeWithRemote(remote);

        // Should take max values
        assertEquals(UInt256.valueOf(7000), merged.getMaxDifficulty());
        assertEquals(120, merged.getTotalBlockCount());
        assertEquals(50, merged.getTotalMainBlockCount());  // Keep local (higher)
        assertEquals(10, merged.getTotalHostCount());        // Keep local (higher)
    }

    @Test
    public void testUpdateMaxDifficulty() {
        ChainStats original = ChainStats.builder()
                .maxDifficulty(UInt256.valueOf(1000))
                .build();

        // Update with higher difficulty
        ChainStats updated = original.updateMaxDifficulty(UInt256.valueOf(2000));
        assertEquals(UInt256.valueOf(2000), updated.getMaxDifficulty());

        // Update with lower difficulty (should not change)
        ChainStats notUpdated = updated.updateMaxDifficulty(UInt256.valueOf(1500));
        assertEquals(UInt256.valueOf(2000), notUpdated.getMaxDifficulty());
    }

    @Test
    public void testUpdateDifficulty() {
        ChainStats original = ChainStats.builder()
                .difficulty(UInt256.valueOf(500))
                .build();

        // Update with higher difficulty
        ChainStats updated = original.updateDifficulty(UInt256.valueOf(1000));
        assertEquals(UInt256.valueOf(1000), updated.getDifficulty());

        // Update with lower difficulty (should not change)
        ChainStats notUpdated = updated.updateDifficulty(UInt256.valueOf(700));
        assertEquals(UInt256.valueOf(1000), notUpdated.getDifficulty());
    }

    @Test
    public void testIncrementBlockCount() {
        ChainStats original = ChainStats.builder()
                .blockCount(100)
                .totalBlockCount(150)
                .build();

        ChainStats incremented = original.incrementBlockCount();

        assertEquals(101, incremented.getBlockCount());
        assertEquals(151, incremented.getTotalBlockCount());
        // Verify original unchanged
        assertEquals(100, original.getBlockCount());
        assertEquals(150, original.getTotalBlockCount());
    }

    @Test
    public void testIncrementMainBlockCount() {
        ChainStats original = ChainStats.builder()
                .mainBlockCount(50)
                .totalMainBlockCount(60)
                .build();

        ChainStats incremented = original.incrementMainBlockCount();

        assertEquals(51, incremented.getMainBlockCount());
        assertEquals(61, incremented.getTotalMainBlockCount());
        // Verify original unchanged
        assertEquals(50, original.getMainBlockCount());
        assertEquals(60, original.getTotalMainBlockCount());
    }

    @Test
    public void testUpdateBalance() {
        ChainStats original = ChainStats.builder()
                .balance(XAmount.of(100, XUnit.XDAG))
                .build();

        XAmount newBalance = XAmount.of(200, XUnit.XDAG);
        ChainStats updated = original.updateBalance(newBalance);

        assertEquals(newBalance, updated.getBalance());
        // Verify original unchanged
        assertEquals(XAmount.of(100, XUnit.XDAG), original.getBalance());
    }

    @Test
    public void testGetOrphanPercentage() {
        ChainStats stats = ChainStats.builder()
                .totalBlockCount(1000)
                .noRefCount(50)
                .build();

        assertEquals(5.0, stats.getOrphanPercentage(), 0.01);

        // Test zero blocks
        ChainStats zeroStats = ChainStats.zero();
        assertEquals(0.0, zeroStats.getOrphanPercentage(), 0.01);
    }

    @Test
    public void testGetMainBlockPercentage() {
        ChainStats stats = ChainStats.builder()
                .totalBlockCount(1000)
                .totalMainBlockCount(300)
                .build();

        assertEquals(30.0, stats.getMainBlockPercentage(), 0.01);

        // Test zero blocks
        ChainStats zeroStats = ChainStats.zero();
        assertEquals(0.0, zeroStats.getMainBlockPercentage(), 0.01);
    }

    @Test
    public void testNeedsSync() {
        ChainStats synced = ChainStats.builder()
                .waitingSyncCount(0)
                .build();
        assertFalse(synced.needsSync());

        ChainStats needsSync = ChainStats.builder()
                .waitingSyncCount(10)
                .build();
        assertTrue(needsSync.needsSync());
    }

    @Test
    public void testGetSyncProgress() {
        ChainStats stats = ChainStats.builder()
                .totalBlockCount(1000)
                .waitingSyncCount(100)
                .build();

        assertEquals(90.0, stats.getSyncProgress(), 0.01);

        // Test fully synced
        ChainStats synced = ChainStats.builder()
                .totalBlockCount(1000)
                .waitingSyncCount(0)
                .build();
        assertEquals(100.0, synced.getSyncProgress(), 0.01);

        // Test zero blocks
        ChainStats zeroStats = ChainStats.zero();
        assertEquals(100.0, zeroStats.getSyncProgress(), 0.01);
    }

    @Test
    public void testToString() {
        ChainStats stats = ChainStats.builder()
                .difficulty(UInt256.valueOf(1000))
                .maxDifficulty(UInt256.valueOf(2000))
                .blockCount(100)
                .totalBlockCount(150)
                .mainBlockCount(50)
                .totalMainBlockCount(60)
                .hostCount(5)
                .totalHostCount(10)
                .balance(XAmount.of(100, XUnit.XDAG))
                .noRefCount(5)
                .waitingSyncCount(10)
                .build();

        String str = stats.toString();

        assertNotNull(str);
        assertTrue(str.contains("ChainStats"));
        assertTrue(str.contains("mainBlocks=50/60"));
        assertTrue(str.contains("blocks=100/150"));
        assertTrue(str.contains("hosts=5/10"));
        assertTrue(str.contains("orphans=5"));
    }

    @Test
    public void testEquality() {
        ChainStats stats1 = ChainStats.builder()
                .difficulty(UInt256.valueOf(1000))
                .blockCount(100)
                .mainBlockCount(50)
                .balance(XAmount.of(100, XUnit.XDAG))
                .build();

        ChainStats stats2 = ChainStats.builder()
                .difficulty(UInt256.valueOf(1000))
                .blockCount(100)
                .mainBlockCount(50)
                .balance(XAmount.of(100, XUnit.XDAG))
                .build();

        assertEquals(stats1, stats2);
        assertEquals(stats1.hashCode(), stats2.hashCode());
    }

    @Test
    public void testInequality() {
        ChainStats stats1 = ChainStats.builder()
                .difficulty(UInt256.valueOf(1000))
                .blockCount(100)
                .build();

        ChainStats stats2 = stats1.withBlockCount(200);

        assertNotEquals(stats1, stats2);
        assertNotEquals(stats1.hashCode(), stats2.hashCode());
    }
}
