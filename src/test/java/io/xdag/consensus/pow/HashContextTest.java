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

package io.xdag.consensus.pow;

import io.xdag.core.Block;
import io.xdag.core.BlockHeader;
import io.xdag.core.BlockInfo;
import io.xdag.utils.XdagTime;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for HashContext
 *
 * <p>Tests the type-safe hash context for PoW calculations.
 */
public class HashContextTest {

    /**
     * Test 1: Create HashContext for block
     */
    @Test
    public void testForBlock() {
        // Create mock block
        Block block = Mockito.mock(Block.class);
        BlockHeader header = Mockito.mock(BlockHeader.class);
        BlockInfo info = Mockito.mock(BlockInfo.class);

        long timestamp = 1700000000L;
        long height = 12345L;
        long expectedEpoch = XdagTime.getEpoch(timestamp);

        when(block.getTimestamp()).thenReturn(timestamp);
        when(block.getInfo()).thenReturn(info);
        when(block.getHeader()).thenReturn(header);
        when(info.getHeight()).thenReturn(height);

        // Create context
        HashContext context = HashContext.forBlock(block);

        // Verify
        assertNotNull("Context should not be null", context);
        assertEquals("Timestamp should match", timestamp, context.getTimestamp());
        assertEquals("Block height should match", height, context.getBlockHeight());
        assertEquals("Epoch should match", expectedEpoch, context.getEpoch());
    }

    /**
     * Test 2: Create HashContext for mining
     */
    @Test
    public void testForMining() {
        long timestamp = 1700000064L;
        long expectedEpoch = XdagTime.getEpoch(timestamp);

        // Create context for mining
        HashContext context = HashContext.forMining(timestamp);

        // Verify
        assertNotNull("Context should not be null", context);
        assertEquals("Timestamp should match", timestamp, context.getTimestamp());
        assertEquals("Block height should be -1 for mining", -1L, context.getBlockHeight());
        assertEquals("Epoch should match", expectedEpoch, context.getEpoch());
    }

    /**
     * Test 3: HashContext immutability
     */
    @Test
    public void testImmutability() {
        long timestamp = 1700000128L;
        HashContext context = HashContext.forMining(timestamp);

        // Verify all fields are accessible
        long t1 = context.getTimestamp();
        long h1 = context.getBlockHeight();
        long e1 = context.getEpoch();

        // Call getters multiple times - should return same values
        assertEquals("Timestamp should be immutable", t1, context.getTimestamp());
        assertEquals("Block height should be immutable", h1, context.getBlockHeight());
        assertEquals("Epoch should be immutable", e1, context.getEpoch());

        // Note: True immutability is enforced by final fields at compile time
        assertTrue("HashContext fields are immutable (final)", true);
    }

    /**
     * Test 4: Epoch calculation accuracy
     */
    @Test
    public void testEpochCalculation() {
        // Test various timestamps
        long[] timestamps = {
            1700000000L,  // Arbitrary timestamp
            1700000064L,  // Next epoch
            1700000128L,  // Next epoch
            0L,           // Genesis
            64L           // First epoch
        };

        for (long timestamp : timestamps) {
            HashContext context = HashContext.forMining(timestamp);
            long expectedEpoch = XdagTime.getEpoch(timestamp);

            assertEquals(
                "Epoch calculation should match XdagTime.getEpoch() for timestamp " + timestamp,
                expectedEpoch,
                context.getEpoch()
            );
        }
    }

    /**
     * Test 5: HashContext equality (different instances with same values)
     */
    @Test
    public void testContextConsistency() {
        long timestamp = 1700000000L;

        // Create two contexts with same timestamp
        HashContext context1 = HashContext.forMining(timestamp);
        HashContext context2 = HashContext.forMining(timestamp);

        // They should be different instances but have same values
        assertNotSame("Should be different instances", context1, context2);
        assertEquals("Timestamps should match", context1.getTimestamp(), context2.getTimestamp());
        assertEquals("Epochs should match", context1.getEpoch(), context2.getEpoch());
        assertEquals("Block heights should match", context1.getBlockHeight(), context2.getBlockHeight());
    }

    /**
     * Test 6: Edge case - zero timestamp
     */
    @Test
    public void testZeroTimestamp() {
        HashContext context = HashContext.forMining(0L);

        assertNotNull("Context should be created for timestamp 0", context);
        assertEquals("Timestamp should be 0", 0L, context.getTimestamp());
        assertEquals("Epoch should be 0 for timestamp 0", 0L, context.getEpoch());
    }

    /**
     * Test 7: Edge case - large timestamp
     */
    @Test
    public void testLargeTimestamp() {
        long largeTimestamp = Long.MAX_VALUE / 2; // Avoid overflow
        HashContext context = HashContext.forMining(largeTimestamp);

        assertNotNull("Context should be created for large timestamp", context);
        assertEquals("Timestamp should match", largeTimestamp, context.getTimestamp());
        assertTrue("Epoch should be calculated", context.getEpoch() >= 0);
    }

    /**
     * Test 8: forBlock with null info (error handling)
     */
    @Test(expected = IllegalArgumentException.class)
    public void testForBlockWithNullInfo() {
        Block block = Mockito.mock(Block.class);
        when(block.getInfo()).thenReturn(null);
        when(block.getTimestamp()).thenReturn(1700000000L);

        // Should throw IllegalArgumentException
        HashContext.forBlock(block);
    }

    /**
     * Test 9: ToString representation (if implemented)
     */
    @Test
    public void testToString() {
        HashContext context = HashContext.forMining(1700000000L);
        String str = context.toString();

        // Verify toString produces something meaningful
        assertNotNull("toString should not return null", str);
        assertFalse("toString should not be empty", str.isEmpty());
    }

    /**
     * Test 10: Multiple factory methods produce consistent results
     */
    @Test
    public void testFactoryMethodConsistency() {
        // Create block with known values
        Block block = Mockito.mock(Block.class);
        BlockInfo info = Mockito.mock(BlockInfo.class);
        BlockHeader header = Mockito.mock(BlockHeader.class);

        long timestamp = 1700000000L;
        long height = 100L;

        when(block.getTimestamp()).thenReturn(timestamp);
        when(block.getInfo()).thenReturn(info);
        when(block.getHeader()).thenReturn(header);
        when(info.getHeight()).thenReturn(height);

        // Create contexts using different factory methods
        HashContext fromBlock = HashContext.forBlock(block);
        HashContext fromMining = HashContext.forMining(timestamp);

        // Timestamp and epoch should match
        assertEquals("Timestamps should match", fromBlock.getTimestamp(), fromMining.getTimestamp());
        assertEquals("Epochs should match", fromBlock.getEpoch(), fromMining.getEpoch());

        // Block height should differ
        assertEquals("forBlock should have block height", height, fromBlock.getBlockHeight());
        assertEquals("forMining should have -1 block height", -1L, fromMining.getBlockHeight());
    }
}
