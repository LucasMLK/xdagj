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

package io.xdag.serialization;

import io.xdag.core.*;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Test class for CompactSerializer
 */
public class CompactSerializerTest {

    // ========== BlockInfo Tests ==========

    @Test
    public void testSerializeDeserializeBlockInfo() throws IOException {
        BlockInfo original = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(1234567890L)
                .height(100L)
                .type(0x1234567812345678L)
                .flags(0x12345678)
                .difficulty(UInt256.valueOf(99999))
                .ref(Bytes32.random())
                .maxDiffLink(Bytes32.random())
                .amount(XAmount.of(1000, XUnit.XDAG))
                .fee(XAmount.of(10, XUnit.XDAG))
                .remark(Bytes.wrap("Test remark".getBytes()))
                .isSnapshot(true)
                .snapshotInfo(new SnapshotInfo(true, new byte[32]))
                .build();

        byte[] serialized = CompactSerializer.serialize(original);
        BlockInfo deserialized = CompactSerializer.deserializeBlockInfo(serialized);

        // Debug output
        if (!original.equals(deserialized)) {
            System.out.println("Original: " + original);
            System.out.println("Deserialized: " + deserialized);
            System.out.println("Original snapshotInfo: " + original.getSnapshotInfo());
            System.out.println("Deserialized snapshotInfo: " + deserialized.getSnapshotInfo());
            if (original.getSnapshotInfo() != null && deserialized.getSnapshotInfo() != null) {
                System.out.println("Original snapshot type: " + original.getSnapshotInfo().getType());
                System.out.println("Deserialized snapshot type: " + deserialized.getSnapshotInfo().getType());
                System.out.println("Original snapshot data: " + java.util.Arrays.toString(original.getSnapshotInfo().getData()));
                System.out.println("Deserialized snapshot data: " + java.util.Arrays.toString(deserialized.getSnapshotInfo().getData()));
            }
        }

        assertEquals(original, deserialized);
    }

    @Test
    public void testBlockInfoWithNulls() throws IOException {
        BlockInfo original = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(1000L)
                .height(50L)
                .type(0L)
                .flags(0)
                .difficulty(UInt256.ZERO)
                .ref(null)
                .maxDiffLink(null)
                .amount(XAmount.ZERO)
                .fee(null)
                .remark(null)
                .isSnapshot(false)
                .snapshotInfo(null)
                .build();

        byte[] serialized = CompactSerializer.serialize(original);
        BlockInfo deserialized = CompactSerializer.deserializeBlockInfo(serialized);

        assertEquals(original, deserialized);
    }

    @Test
    public void testBlockInfoSize() throws IOException {
        BlockInfo blockInfo = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(System.currentTimeMillis())
                .height(1000000L)
                .type(1L)
                .flags(0x0F)
                .difficulty(UInt256.valueOf(123456789))
                .ref(Bytes32.random())
                .maxDiffLink(Bytes32.random())
                .amount(XAmount.of(100, XUnit.XDAG))
                .fee(XAmount.of(1, XUnit.XDAG))
                .remark(Bytes.wrap("".getBytes()))
                .isSnapshot(false)
                .snapshotInfo(null)
                .build();

        byte[] serialized = CompactSerializer.serialize(blockInfo);

        // Target: ~180 bytes
        // Actual should be around 170-190 bytes
        assertTrue("Serialized size should be < 200 bytes, got: " + serialized.length,
                serialized.length < 200);

        System.out.println("BlockInfo serialized size: " + serialized.length + " bytes");
    }

    // ========== ChainStats Tests ==========

    @Test
    public void testSerializeDeserializeChainStats() throws IOException {
        ChainStats original = ChainStats.builder()
                .difficulty(UInt256.valueOf(5000))
                .maxDifficulty(UInt256.valueOf(10000))
                .blockCount(200)
                .totalBlockCount(250)
                .mainBlockCount(100)
                .totalMainBlockCount(120)
                .hostCount(8)
                .totalHostCount(15)
                .waitingSyncCount(10)
                .noRefCount(5)
                .extraCount(3)
                .mainBlockTime(1234567890L)
                .balance(XAmount.of(500, XUnit.XDAG))
                .globalMinerHash(Bytes32.random())
                .ourLastBlockHash(Bytes32.random())
                .build();

        byte[] serialized = CompactSerializer.serialize(original);
        ChainStats deserialized = CompactSerializer.deserializeChainStats(serialized);

        assertEquals(original, deserialized);
    }

    @Test
    public void testChainStatsWithNulls() throws IOException {
        ChainStats original = ChainStats.zero();

        byte[] serialized = CompactSerializer.serialize(original);
        ChainStats deserialized = CompactSerializer.deserializeChainStats(serialized);

        assertEquals(original.getDifficulty(), deserialized.getDifficulty());
        assertEquals(original.getMaxDifficulty(), deserialized.getMaxDifficulty());
        assertEquals(original.getBlockCount(), deserialized.getBlockCount());
    }

    @Test
    public void testChainStatsSize() throws IOException {
        ChainStats stats = ChainStats.builder()
                .difficulty(UInt256.valueOf(123456789))
                .maxDifficulty(UInt256.valueOf(987654321))
                .blockCount(1000000)
                .totalBlockCount(1500000)
                .mainBlockCount(500000)
                .totalMainBlockCount(600000)
                .hostCount(100)
                .totalHostCount(200)
                .waitingSyncCount(1000)
                .noRefCount(500)
                .extraCount(100)
                .mainBlockTime(System.currentTimeMillis())
                .balance(XAmount.of(1000000, XUnit.XDAG))
                .globalMinerHash(Bytes32.random())
                .ourLastBlockHash(Bytes32.random())
                .build();

        byte[] serialized = CompactSerializer.serialize(stats);

        System.out.println("ChainStats serialized size: " + serialized.length + " bytes");

        // Should be reasonable size (much less than Kryo)
        assertTrue("ChainStats should be < 200 bytes", serialized.length < 200);
    }

    // ========== Snapshot Tests ==========

    @Test
    public void testSerializeDeserializeSnapshot() throws IOException {
        Snapshot original = Snapshot.publicKey(Bytes.random(32));

        byte[] serialized = CompactSerializer.serialize(original);
        Snapshot deserialized = CompactSerializer.deserializeSnapshot(serialized);

        assertEquals(original, deserialized);
    }

    @Test
    public void testSnapshotBlockData() throws IOException {
        Snapshot original = Snapshot.blockData(Bytes.random(32));

        byte[] serialized = CompactSerializer.serialize(original);
        Snapshot deserialized = CompactSerializer.deserializeSnapshot(serialized);

        assertEquals(original, deserialized);
    }

    @Test
    public void testSnapshotSize() throws IOException {
        Snapshot snapshot = Snapshot.publicKey(Bytes.random(32));

        byte[] serialized = CompactSerializer.serialize(snapshot);

        // Target: ~34 bytes (1 type + 1 length + 32 data)
        assertEquals(34, serialized.length);

        System.out.println("Snapshot serialized size: " + serialized.length + " bytes (target: 34)");
    }

    @Test
    public void testSnapshotEmptyData() throws IOException {
        Snapshot original = Snapshot.publicKey(Bytes.EMPTY);

        byte[] serialized = CompactSerializer.serialize(original);
        Snapshot deserialized = CompactSerializer.deserializeSnapshot(serialized);

        assertEquals(original, deserialized);

        // Should be very small: 1 (type) + 1 (length=0) = 2 bytes
        assertEquals(2, serialized.length);
    }

    // ========== Round-trip Tests ==========

    @Test
    public void testBlockInfoRoundTrip() throws IOException {
        for (int i = 0; i < 100; i++) {
            BlockInfo original = createRandomBlockInfo();
            byte[] serialized = CompactSerializer.serialize(original);
            BlockInfo deserialized = CompactSerializer.deserializeBlockInfo(serialized);
            assertEquals("Round-trip failed at iteration " + i, original, deserialized);
        }
    }

    @Test
    public void testChainStatsRoundTrip() throws IOException {
        for (int i = 0; i < 100; i++) {
            ChainStats original = createRandomChainStats();
            byte[] serialized = CompactSerializer.serialize(original);
            ChainStats deserialized = CompactSerializer.deserializeChainStats(serialized);
            assertEquals("Round-trip failed at iteration " + i, original, deserialized);
        }
    }

    @Test
    public void testSnapshotRoundTrip() throws IOException {
        for (int i = 0; i < 100; i++) {
            Snapshot original = i % 2 == 0 ?
                Snapshot.publicKey(Bytes.random(32)) :
                Snapshot.blockData(Bytes.random(32));
            byte[] serialized = CompactSerializer.serialize(original);
            Snapshot deserialized = CompactSerializer.deserializeSnapshot(serialized);
            assertEquals("Round-trip failed at iteration " + i, original, deserialized);
        }
    }

    // ========== Helper Methods ==========

    private BlockInfo createRandomBlockInfo() {
        return BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(System.currentTimeMillis() + (long) (Math.random() * 1000000))
                .height((long) (Math.random() * 1000000))
                .type((long) (Math.random() * 1000))
                .flags((int) (Math.random() * 0xFFFF))
                .difficulty(UInt256.valueOf((long) (Math.random() * 1000000)))
                .ref(Math.random() > 0.5 ? Bytes32.random() : null)
                .maxDiffLink(Math.random() > 0.5 ? Bytes32.random() : null)
                .amount(XAmount.of((long) (Math.random() * 10000), XUnit.XDAG))
                .fee(Math.random() > 0.5 ? XAmount.of((long) (Math.random() * 100), XUnit.XDAG) : null)
                .remark(Math.random() > 0.5 ? Bytes.wrap(("Remark" + Math.random()).getBytes()) : null)
                .isSnapshot(Math.random() > 0.5)
                .snapshotInfo(Math.random() > 0.5 ? new SnapshotInfo(true, new byte[32]) : null)
                .build();
    }

    private ChainStats createRandomChainStats() {
        return ChainStats.builder()
                .difficulty(UInt256.valueOf((long) (Math.random() * 1000000)))
                .maxDifficulty(UInt256.valueOf((long) (Math.random() * 1000000)))
                .blockCount((long) (Math.random() * 1000000))
                .totalBlockCount((long) (Math.random() * 1000000))
                .mainBlockCount((long) (Math.random() * 500000))
                .totalMainBlockCount((long) (Math.random() * 500000))
                .hostCount((int) (Math.random() * 1000))
                .totalHostCount((int) (Math.random() * 1000))
                .waitingSyncCount((long) (Math.random() * 10000))
                .noRefCount((long) (Math.random() * 1000))
                .extraCount((long) (Math.random() * 1000))
                .mainBlockTime(System.currentTimeMillis())
                .balance(XAmount.of((long) (Math.random() * 100000), XUnit.XDAG))
                .globalMinerHash(Math.random() > 0.5 ? Bytes32.random() : null)
                .ourLastBlockHash(Math.random() > 0.5 ? Bytes32.random() : null)
                .build();
    }
}
