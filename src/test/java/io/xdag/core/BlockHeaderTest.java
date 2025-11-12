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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;

/**
 * Unit tests for BlockHeader (v5.1)
 */
public class BlockHeaderTest {

    @Test
    public void testSerializedSize() {
        assertEquals(92, BlockHeader.getSerializedSize());
    }

    @Test
    public void testEpochCalculation() {
        BlockHeader header1 = BlockHeader.builder()
                .timestamp(0)
                .difficulty(UInt256.ONE)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.wrap(new byte[20]))
                .build();
        assertEquals(0, header1.getEpoch());

        BlockHeader header2 = BlockHeader.builder()
                .timestamp(63)
                .difficulty(UInt256.ONE)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.wrap(new byte[20]))
                .build();
        assertEquals(0, header2.getEpoch());

        BlockHeader header3 = BlockHeader.builder()
                .timestamp(64)
                .difficulty(UInt256.ONE)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.wrap(new byte[20]))
                .build();
        assertEquals(1, header3.getEpoch());

        BlockHeader header4 = BlockHeader.builder()
                .timestamp(128)
                .difficulty(UInt256.ONE)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.wrap(new byte[20]))
                .build();
        assertEquals(2, header4.getEpoch());
    }

    @Test
    public void testDifficultyValidation() {
        // Create header with difficulty target
        UInt256 difficulty = UInt256.fromHexString("0x00000000FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");

        BlockHeader header = BlockHeader.builder()
                .timestamp(100)
                .difficulty(difficulty)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.wrap(new byte[20]))
                .build();

        // Hash that satisfies difficulty (smaller than target)
        Bytes32 validHash = Bytes32.fromHexString("0x00000000AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        BlockHeader headerWithValidHash = header.toBuilder().hash(validHash).build();
        assertTrue(headerWithValidHash.satisfiesDifficulty());

        // Hash that doesn't satisfy difficulty (larger than target)
        Bytes32 invalidHash = Bytes32.fromHexString("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        BlockHeader headerWithInvalidHash = header.toBuilder().hash(invalidHash).build();
        assertFalse(headerWithInvalidHash.satisfiesDifficulty());
    }

    @Test
    public void testDifficultyValidationThrowsWhenHashNotSet() {
        BlockHeader header = BlockHeader.builder()
                .timestamp(100)
                .difficulty(UInt256.ONE)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.wrap(new byte[20]))
                .hash(null)
                .build();

        assertThrows(IllegalStateException.class, header::satisfiesDifficulty);
    }

    @Test
    public void testImmutability() {
        Bytes32 nonce1 = Bytes32.random();
        Bytes coinbase1 = Bytes.random(20);

        BlockHeader header1 = BlockHeader.builder()
                .timestamp(100)
                .difficulty(UInt256.ONE)
                .nonce(nonce1)
                .coinbase(coinbase1)
                .build();

        // Modify via builder
        Bytes32 nonce2 = Bytes32.random();
        BlockHeader header2 = header1.toBuilder()
                .nonce(nonce2)
                .build();

        // Original should be unchanged
        assertEquals(nonce1, header1.getNonce());
        assertEquals(nonce2, header2.getNonce());
        assertNotEquals(header1, header2);
    }

    @Test
    public void testToString() {
        BlockHeader header = BlockHeader.builder()
                .timestamp(128)
                .difficulty(UInt256.ONE)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.wrap(new byte[20]))
                .build();

        String str = header.toString();
        assertTrue(str.contains("epoch=2"));
        assertTrue(str.contains("timestamp=128"));
    }

    @Test
    public void testHashCaching() {
        Bytes32 hash1 = Bytes32.random();

        BlockHeader header = BlockHeader.builder()
                .timestamp(100)
                .difficulty(UInt256.ONE)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.wrap(new byte[20]))
                .hash(hash1)
                .build();

        assertEquals(hash1, header.getHash());

        // Update hash via builder
        Bytes32 hash2 = Bytes32.random();
        BlockHeader headerWithNewHash = header.toBuilder().hash(hash2).build();

        // Original unchanged
        assertEquals(hash1, header.getHash());
        // New header has new hash
        assertEquals(hash2, headerWithNewHash.getHash());
    }
}
