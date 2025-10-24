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

import io.xdag.config.Constants;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;

import java.math.BigInteger;

import static org.junit.Assert.*;

/**
 * 测试新的不可变 BlockInfo 类
 *
 * 测试内容：
 * 1. Builder 模式构建
 * 2. 不可变性验证
 * 3. 辅助方法（状态判断、计算值）
 * 4. 与 LegacyBlockInfo 的转换
 */
public class BlockInfoTest {

    @Test
    public void testBuilder() {
        // 测试基本 Builder 构建
        Bytes32 fullHash = Bytes32.random();
        long timestamp = 1234567890L;
        long height = 100L;
        XAmount amount = XAmount.of(1000, XUnit.MILLI_XDAG);
        UInt256 difficulty = UInt256.valueOf(12345);

        BlockInfo blockInfo = BlockInfo.builder()
                .hash(fullHash)
                .timestamp(timestamp)
                .height(height)
                .amount(amount)
                .difficulty(difficulty)
                .flags(Constants.BI_MAIN | Constants.BI_MAIN_CHAIN)
                .build();

        assertNotNull(blockInfo);
        assertEquals(fullHash, blockInfo.getHash());  // Test full hash
        assertNotNull(blockInfo.getHash());  // hash should be computed
        assertEquals(timestamp, blockInfo.getTimestamp());
        assertEquals(height, blockInfo.getHeight());
        assertEquals(amount, blockInfo.getAmount());
        assertEquals(difficulty, blockInfo.getDifficulty());
    }

    @Test
    public void testBuilderWithHelpers() {
        // 测试 Builder 辅助方法
        Bytes32 hash = Bytes32.random();

        BlockInfo blockInfo = BlockInfo.builder()
                .hash(hash)
                .timestamp(1000L)
                .height(50L)
                .mainBlock(true)          // 使用辅助方法
                .onMainChain(true)        // 使用辅助方法
                .ours(true)               // 使用辅助方法
                .applied(true)            // 使用辅助方法
                .difficulty(UInt256.ONE)
                .amount(XAmount.ZERO)
                .build();

        assertTrue(blockInfo.isMainBlock());
        assertTrue(blockInfo.isOnMainChain());
        assertTrue(blockInfo.isOurs());
        assertTrue(blockInfo.isApplied());
    }

    @Test
    public void testImmutability() {
        // 测试不可变性：使用 with 方法创建新对象
        Bytes32 hash = Bytes32.random();
        BlockInfo original = BlockInfo.builder()
                .hash(hash)
                .timestamp(1000L)
                .height(10L)
                .amount(XAmount.ZERO)
                .difficulty(UInt256.ONE)
                .flags(0)
                .build();

        // 使用 @With 注解生成的方法创建新对象
        BlockInfo modified = original.withHeight(20L);

        // 验证原对象未被修改
        assertEquals(10L, original.getHeight());
        // 验证新对象已修改
        assertEquals(20L, modified.getHeight());
        // 验证其他字段相同
        assertEquals(original.getHash(), modified.getHash());
        assertEquals(original.getTimestamp(), modified.getTimestamp());
    }

    @Test
    public void testToBuilder() {
        // 测试 toBuilder() 方法
        BlockInfo original = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(1000L)
                .height(10L)
                .amount(XAmount.ZERO)
                .difficulty(UInt256.ONE)
                .flags(Constants.BI_MAIN)
                .build();

        // 使用 toBuilder 创建修改后的副本
        BlockInfo modified = original.toBuilder()
                .height(20L)
                .onMainChain(true)
                .build();

        assertEquals(10L, original.getHeight());
        assertFalse(original.isOnMainChain());

        assertEquals(20L, modified.getHeight());
        assertTrue(modified.isMainBlock());
        assertTrue(modified.isOnMainChain());
    }

    @Test
    public void testFlagHelpers() {
        // 测试标志位辅助方法
        BlockInfo blockInfo = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(1000L)
                .height(10L)
                .flags(Constants.BI_MAIN | Constants.BI_OURS | Constants.BI_APPLIED)
                .difficulty(UInt256.ONE)
                .amount(XAmount.ZERO)
                .build();

        assertTrue(blockInfo.isMainBlock());
        assertTrue(blockInfo.isOurs());
        assertTrue(blockInfo.isApplied());
        assertFalse(blockInfo.isOnMainChain());
        assertFalse(blockInfo.isExtra());
    }

    @Test
    public void testGetEpoch() {
        // 测试 getEpoch() 计算
        BlockInfo blockInfo = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(640L)  // 640 / 64 = 10
                .height(10L)
                .difficulty(UInt256.ONE)
                .amount(XAmount.ZERO)
                .build();

        assertEquals(10L, blockInfo.getEpoch());

        blockInfo = blockInfo.withTimestamp(1280L);  // 1280 / 64 = 20
        assertEquals(20L, blockInfo.getEpoch());
    }

    @Test
    public void testHasRemark() {
        // 测试 hasRemark() 方法
        BlockInfo withoutRemark = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(1000L)
                .height(10L)
                .difficulty(UInt256.ONE)
                .amount(XAmount.ZERO)
                .build();

        assertFalse(withoutRemark.hasRemark());

        BlockInfo withEmptyRemark = withoutRemark.withRemark(Bytes.EMPTY);
        assertFalse(withEmptyRemark.hasRemark());

        BlockInfo withRemark = withoutRemark.withRemark(Bytes.wrap("test".getBytes()));
        assertTrue(withRemark.hasRemark());
    }

    @Test
    public void testFromLegacy() {
        // 测试从 LegacyBlockInfo 转换
        LegacyBlockInfo legacy = new LegacyBlockInfo();

        // Create legacy hash in correct format: 8 zero bytes + 24 hash bytes
        byte[] legacyHash = new byte[32];
        // First 8 bytes are zeros (already initialized to 0)
        // Set bytes [8-31] with deterministic values
        for (int i = 8; i < 32; i++) {
            legacyHash[i] = (byte) (i * 7); // Use deterministic values
        }

        legacy.setHashlow(legacyHash);
        legacy.setTimestamp(1234567890L);
        legacy.setHeight(100L);
        legacy.type = 0x1234567812345678L;
        legacy.flags = Constants.BI_MAIN | Constants.BI_APPLIED;
        legacy.setDifficulty(BigInteger.valueOf(99999));
        legacy.setAmount(XAmount.of(1000, XUnit.MILLI_XDAG));
        legacy.setFee(XAmount.of(1, XUnit.MILLI_XDAG));
        legacy.setSnapshot(true);

        // 转换为新的 BlockInfo
        BlockInfo blockInfo = BlockInfo.fromLegacy(legacy);

        assertNotNull(blockInfo);
        // Verify conversion from legacy hash format to full hash format
        // legacy hash format: [8 zeros, 24 hash bytes]
        // full hash format: [24 hash bytes, 8 zeros]
        byte[] fullHash = blockInfo.getHash().toArray();

        // Verify hash bytes [0-23] equal legacy hash bytes [8-31]
        for (int i = 0; i < 24; i++) {
            assertEquals("Hash byte " + i + " should equal legacy hash byte " + (i + 8),
                    legacyHash[i + 8], fullHash[i]);
        }

        // Verify hash bytes [24-31] are all zeros
        for (int i = 24; i < 32; i++) {
            assertEquals("Hash byte " + i + " should be zero", 0, fullHash[i]);
        }
        assertEquals(1234567890L, blockInfo.getTimestamp());
        assertEquals(100L, blockInfo.getHeight());
        assertEquals(0x1234567812345678L, blockInfo.getType());
        assertEquals(Constants.BI_MAIN | Constants.BI_APPLIED, blockInfo.getFlags());
        assertEquals(BigInteger.valueOf(99999), blockInfo.getDifficulty().toBigInteger());
        assertEquals(legacy.getAmount(), blockInfo.getAmount());
        assertEquals(legacy.getFee(), blockInfo.getFee());
        assertTrue(blockInfo.isSnapshot());
        assertTrue(blockInfo.isMainBlock());
        assertTrue(blockInfo.isApplied());
    }

    @Test
    public void testToLegacy() {
        // 测试转换为 LegacyBlockInfo
        // Create a full hash (24 hash bytes at beginning, 8 zeros at end)
        byte[] hashBytes = new byte[32];
        for (int i = 0; i < 24; i++) {
            hashBytes[i] = (byte) (i + 1);  // Use deterministic values
        }
        Bytes32 fullHash = Bytes32.wrap(hashBytes);

        XAmount amount = XAmount.of(2000, XUnit.MILLI_XDAG);
        XAmount fee = XAmount.of(10, XUnit.MILLI_XDAG);

        BlockInfo blockInfo = BlockInfo.builder()
                .hash(fullHash)
                .timestamp(9876543210L)
                .height(500L)
                .type(0xABCDEF0123456789L)
                .flags(Constants.BI_MAIN_CHAIN | Constants.BI_OURS)
                .difficulty(UInt256.valueOf(777777))
                .amount(amount)
                .fee(fee)
                .isSnapshot(false)
                .build();

        // 转换为 LegacyBlockInfo
        LegacyBlockInfo legacy = blockInfo.toLegacy();

        assertNotNull(legacy);
        // Verify conversion from full hash format to legacy hash format
        // full hash format: [24 hash bytes, 8 zeros]
        // legacy hash format: [8 zeros, 24 hash bytes]
        byte[] convertedLegacyHash = legacy.getHashlow();

        // Verify legacy hash bytes [0-7] are all zeros
        for (int i = 0; i < 8; i++) {
            assertEquals("Legacy hash byte " + i + " should be zero", 0, convertedLegacyHash[i]);
        }

        // Verify legacy hash bytes [8-31] equal fullHash bytes [0-23]
        for (int i = 0; i < 24; i++) {
            assertEquals("Legacy hash byte " + (i + 8) + " should equal hash byte " + i,
                    hashBytes[i], convertedLegacyHash[i + 8]);
        }
        assertEquals(9876543210L, legacy.getTimestamp());
        assertEquals(500L, legacy.getHeight());
        assertEquals(0xABCDEF0123456789L, legacy.type);
        assertEquals(Constants.BI_MAIN_CHAIN | Constants.BI_OURS, legacy.flags);
        assertEquals(BigInteger.valueOf(777777), legacy.getDifficulty());
        assertEquals(amount, legacy.getAmount());
        assertEquals(fee, legacy.getFee());
        assertFalse(legacy.isSnapshot());
    }

    @Test
    public void testRoundTripConversion() {
        // 测试双向转换的一致性
        LegacyBlockInfo originalLegacy = new LegacyBlockInfo();

        // Create legacy hash in correct format: 8 zero bytes + 24 hash bytes
        byte[] legacyHash = new byte[32];
        // First 8 bytes are zeros (already initialized to 0)
        // Set bytes [8-31] with deterministic values
        for (int i = 8; i < 32; i++) {
            legacyHash[i] = (byte) (i * 11);  // Use different seed than testFromLegacy
        }
        originalLegacy.setHashlow(legacyHash);

        originalLegacy.setTimestamp(1111111111L);
        originalLegacy.setHeight(888L);
        originalLegacy.type = 0x0102030405060708L;
        originalLegacy.flags = Constants.BI_MAIN | Constants.BI_MAIN_CHAIN | Constants.BI_APPLIED;
        originalLegacy.setDifficulty(BigInteger.valueOf(123456789));
        originalLegacy.setAmount(XAmount.of(5000, XUnit.MILLI_XDAG));
        originalLegacy.setFee(XAmount.of(50, XUnit.MILLI_XDAG));

        // Legacy -> BlockInfo -> Legacy
        BlockInfo blockInfo = BlockInfo.fromLegacy(originalLegacy);
        LegacyBlockInfo convertedLegacy = blockInfo.toLegacy();

        // 验证一致性
        assertArrayEquals(originalLegacy.getHashlow(), convertedLegacy.getHashlow());
        assertEquals(originalLegacy.getTimestamp(), convertedLegacy.getTimestamp());
        assertEquals(originalLegacy.getHeight(), convertedLegacy.getHeight());
        assertEquals(originalLegacy.type, convertedLegacy.type);
        assertEquals(originalLegacy.flags, convertedLegacy.flags);
        assertEquals(originalLegacy.getDifficulty(), convertedLegacy.getDifficulty());
        assertEquals(originalLegacy.getAmount(), convertedLegacy.getAmount());
        assertEquals(originalLegacy.getFee(), convertedLegacy.getFee());
    }

    @Test
    public void testWithRef() {
        // 测试 ref 字段
        Bytes32 ref = Bytes32.random();
        BlockInfo blockInfo = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(1000L)
                .height(10L)
                .ref(ref)
                .difficulty(UInt256.ONE)
                .amount(XAmount.ZERO)
                .build();

        assertEquals(ref, blockInfo.getRef());
    }

    @Test
    public void testWithMaxDiffLink() {
        // 测试 maxDiffLink 字段
        Bytes32 maxDiffLink = Bytes32.random();
        BlockInfo blockInfo = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(1000L)
                .height(10L)
                .maxDiffLink(maxDiffLink)
                .difficulty(UInt256.ONE)
                .amount(XAmount.ZERO)
                .build();

        assertEquals(maxDiffLink, blockInfo.getMaxDiffLink());
    }

    @Test
    public void testSnapshotInfo() {
        // 测试快照信息
        SnapshotInfo snapshotInfo = new SnapshotInfo(true, new byte[32]);

        BlockInfo blockInfo = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(1000L)
                .height(10L)
                .isSnapshot(true)
                .snapshotInfo(snapshotInfo)
                .difficulty(UInt256.ONE)
                .amount(XAmount.ZERO)
                .build();

        assertTrue(blockInfo.isSnapshot());
        assertNotNull(blockInfo.getSnapshotInfo());
        assertEquals(snapshotInfo, blockInfo.getSnapshotInfo());
    }

    @Test
    public void testToString() {
        // 测试 toString() 方法
        BlockInfo blockInfo = BlockInfo.builder()
                .hash(Bytes32.fromHexString("0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"))
                .timestamp(1234567890L)
                .height(999L)
                .flags(Constants.BI_MAIN)
                .difficulty(UInt256.ONE)
                .amount(XAmount.of(1500, XUnit.MILLI_XDAG))
                .build();

        String str = blockInfo.toString();

        assertNotNull(str);
        assertTrue(str.contains("height=999"));
        assertTrue(str.contains("isMain=true"));
        assertTrue(str.contains("hash="));
    }

    @Test
    public void testEquality() {
        // 测试相等性（由 Lombok @Value 自动生成）
        Bytes32 hash = Bytes32.random();

        BlockInfo blockInfo1 = BlockInfo.builder()
                .hash(hash)
                .timestamp(1000L)
                .height(10L)
                .flags(Constants.BI_MAIN)
                .difficulty(UInt256.ONE)
                .amount(XAmount.ZERO)
                .build();

        BlockInfo blockInfo2 = BlockInfo.builder()
                .hash(hash)
                .timestamp(1000L)
                .height(10L)
                .flags(Constants.BI_MAIN)
                .difficulty(UInt256.ONE)
                .amount(XAmount.ZERO)
                .build();

        assertEquals(blockInfo1, blockInfo2);
        assertEquals(blockInfo1.hashCode(), blockInfo2.hashCode());
    }

    @Test
    public void testInequality() {
        // 测试不相等
        BlockInfo blockInfo1 = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(1000L)
                .height(10L)
                .difficulty(UInt256.ONE)
                .amount(XAmount.ZERO)
                .build();

        BlockInfo blockInfo2 = blockInfo1.withHeight(20L);

        assertNotEquals(blockInfo1, blockInfo2);
        assertNotEquals(blockInfo1.hashCode(), blockInfo2.hashCode());
    }
}
