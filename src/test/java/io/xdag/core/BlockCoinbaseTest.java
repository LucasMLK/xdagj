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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * Test to reproduce and validate coinbase size issue fix
 */
public class BlockCoinbaseTest {

    @Test
    public void testBlockCreationWith20ByteCoinbase() {
        // 20-byte coinbase (correct - Ethereum-style address)
        Bytes coinbase = Bytes.random(20);

        Block block = Block.createCandidate(
            1000L,
            UInt256.ONE,
            coinbase,
            new ArrayList<>()
        );

        assertNotNull(block);
        assertEquals(20, block.getHeader().getCoinbase().size());

        // Should calculate hash without error
        Bytes32 hash = block.getHash();
        assertNotNull(hash);

        System.out.println("✓ 20-byte coinbase works correctly");
    }

    @Test
    public void testBlockCreationWith32ByteCoinbaseFails() {
        // 32-byte coinbase (incorrect - should fail)
        Bytes coinbase = Bytes.random(32);

        try {
            Block.createCandidate(
                1000L,
                UInt256.ONE,
                coinbase,
                new ArrayList<>()
            );
            fail("Should have thrown IllegalArgumentException for 32-byte coinbase");
        } catch (IllegalArgumentException ex) {
            assertTrue("Error message should mention '20 bytes'",
                    ex.getMessage().contains("20 bytes"));
            assertTrue("Error message should mention '32 bytes'",
                    ex.getMessage().contains("32 bytes"));

            System.out.println("✓ 32-byte coinbase rejected as expected");
            System.out.println("  Error message: " + ex.getMessage());
        }
    }

    @Test
    public void testBlockCreationWithNullCoinbaseFails() {
        try {
            Block.createCandidate(
                1000L,
                UInt256.ONE,
                null,
                new ArrayList<>()
            );
            fail("Should have thrown IllegalArgumentException for null coinbase");
        } catch (IllegalArgumentException ex) {
            assertTrue("Error message should mention 'null'",
                    ex.getMessage().toLowerCase().contains("null"));

            System.out.println("✓ Null coinbase rejected as expected");
            System.out.println("  Error message: " + ex.getMessage());
        }
    }

    @Test
    public void testCreateWithNonceRejects32ByteCoinbase() {
        // Test createWithNonce() also validates coinbase
        Bytes coinbase = Bytes.random(32);

        try {
            Block.createWithNonce(
                1000L,
                UInt256.ONE,
                Bytes32.ZERO,
                coinbase,
                new ArrayList<>()
            );
            fail("createWithNonce should have thrown IllegalArgumentException for 32-byte coinbase");
        } catch (IllegalArgumentException ex) {
            assertTrue("Error message should mention '20 bytes'",
                    ex.getMessage().contains("20 bytes"));

            System.out.println("✓ createWithNonce() also rejects 32-byte coinbase");
            System.out.println("  Error message: " + ex.getMessage());
        }
    }
}
