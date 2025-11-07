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

package io.xdag.consensus.sync.protocol;

import org.junit.Test;

import static io.xdag.consensus.sync.protocol.FinalityBoundary.*;
import static org.junit.Assert.*;

/**
 * Unit tests for FinalityBoundary utility class
 */
public class FinalityBoundaryTest {

    @Test
    public void testGetFinalizedHeight_Normal() {
        // Current height 20000, finalized height should be 20000 - 16384 = 3616
        assertEquals(3616, getFinalizedHeight(20000));

        // Current height exactly at FINALITY_EPOCHS
        assertEquals(0, getFinalizedHeight(FINALITY_EPOCHS));

        // Current height 100000
        assertEquals(100000 - FINALITY_EPOCHS, getFinalizedHeight(100000));
    }

    @Test
    public void testGetFinalizedHeight_BelowFinality() {
        // Current height less than FINALITY_EPOCHS should return 0
        assertEquals(0, getFinalizedHeight(1000));
        assertEquals(0, getFinalizedHeight(100));
        assertEquals(0, getFinalizedHeight(0));
        assertEquals(0, getFinalizedHeight(FINALITY_EPOCHS - 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetFinalizedHeight_NegativeHeight() {
        getFinalizedHeight(-1);
    }

    @Test
    public void testIsFinalized_Normal() {
        long currentHeight = 20000;

        // Blocks before finalized height should be finalized
        assertTrue(isFinalized(3616, currentHeight));
        assertTrue(isFinalized(3615, currentHeight));
        assertTrue(isFinalized(1000, currentHeight));
        assertTrue(isFinalized(0, currentHeight));

        // Blocks at or after finalized height + 1 should not be finalized
        assertFalse(isFinalized(3617, currentHeight));
        assertFalse(isFinalized(10000, currentHeight));
        assertFalse(isFinalized(19999, currentHeight));
        assertFalse(isFinalized(20000, currentHeight));
    }

    @Test
    public void testIsFinalized_BelowFinalityEpochs() {
        long currentHeight = 1000;

        // When current height < FINALITY_EPOCHS, no blocks are finalized yet
        assertFalse(isFinalized(500, currentHeight));
        assertFalse(isFinalized(1000, currentHeight));
    }

    @Test
    public void testIsFinalized_EdgeCase() {
        long currentHeight = FINALITY_EPOCHS;

        // Height 0 should be finalized when current is exactly FINALITY_EPOCHS
        assertTrue(isFinalized(0, currentHeight));

        // Height 1 should not be finalized
        assertFalse(isFinalized(1, currentHeight));

        // Current height itself should not be finalized
        assertFalse(isFinalized(FINALITY_EPOCHS, currentHeight));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIsFinalized_NegativeHeight() {
        isFinalized(-1, 10000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIsFinalized_NegativeCurrentHeight() {
        isFinalized(1000, -1);
    }

    @Test
    public void testIsActiveDAG_Normal() {
        long currentHeight = 20000;

        // Blocks in active DAG region (finalized + 1 to current)
        assertTrue(isActiveDAG(3617, currentHeight));
        assertTrue(isActiveDAG(10000, currentHeight));
        assertTrue(isActiveDAG(19999, currentHeight));
        assertTrue(isActiveDAG(20000, currentHeight));

        // Finalized blocks are not in active DAG
        assertFalse(isActiveDAG(3616, currentHeight));
        assertFalse(isActiveDAG(1000, currentHeight));
        assertFalse(isActiveDAG(0, currentHeight));
    }

    @Test
    public void testIsActiveDAG_FutureHeight() {
        long currentHeight = 20000;

        // Future blocks (height > current) are not in active DAG
        assertFalse(isActiveDAG(20001, currentHeight));
        assertFalse(isActiveDAG(30000, currentHeight));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIsActiveDAG_NegativeHeight() {
        isActiveDAG(-1, 10000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIsActiveDAG_NegativeCurrentHeight() {
        isActiveDAG(1000, -1);
    }

    @Test
    public void testGetActiveDAGRange_Normal() {
        long currentHeight = 20000;
        long[] range = getActiveDAGRange(currentHeight);

        // Should return [3617, 20000]
        assertEquals(2, range.length);
        assertEquals(3617, range[0]); // finalizedHeight + 1
        assertEquals(20000, range[1]); // currentHeight
    }

    @Test
    public void testGetActiveDAGRange_BelowFinality() {
        long currentHeight = 1000;
        long[] range = getActiveDAGRange(currentHeight);

        // When current height < FINALITY_EPOCHS, finalized height is 0
        // So active DAG range is [1, 1000]
        assertEquals(2, range.length);
        assertEquals(1, range[0]);  // finalizedHeight (0) + 1
        assertEquals(1000, range[1]);  // currentHeight
    }

    @Test
    public void testGetActiveDAGRange_AtFinality() {
        long currentHeight = FINALITY_EPOCHS;
        long[] range = getActiveDAGRange(currentHeight);

        // Should return [1, 16384]
        assertEquals(2, range.length);
        assertEquals(1, range[0]);
        assertEquals(FINALITY_EPOCHS, range[1]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetActiveDAGRange_Negative() {
        getActiveDAGRange(-1);
    }

    @Test
    public void testGetActiveDAGSize_Normal() {
        // Current height 20000, active DAG size should be FINALITY_EPOCHS
        assertEquals(FINALITY_EPOCHS, getActiveDAGSize(20000));

        // Current height 100000, active DAG size should be FINALITY_EPOCHS
        assertEquals(FINALITY_EPOCHS, getActiveDAGSize(100000));
    }

    @Test
    public void testGetActiveDAGSize_BelowFinality() {
        // Current height 1000, active DAG size should be 1000
        assertEquals(1000, getActiveDAGSize(1000));

        // Current height 0, active DAG size should be 0
        assertEquals(0, getActiveDAGSize(0));

        // Current height FINALITY_EPOCHS - 1
        assertEquals(FINALITY_EPOCHS - 1, getActiveDAGSize(FINALITY_EPOCHS - 1));
    }

    @Test
    public void testGetActiveDAGSize_AtFinality() {
        // Current height exactly at FINALITY_EPOCHS
        assertEquals(FINALITY_EPOCHS, getActiveDAGSize(FINALITY_EPOCHS));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetActiveDAGSize_Negative() {
        getActiveDAGSize(-1);
    }

    @Test
    public void testGetEpochsUntilFinalized_Finalized() {
        long currentHeight = 20000;

        // Already finalized blocks should return 0
        assertEquals(0, getEpochsUntilFinalized(3616, currentHeight));
        assertEquals(0, getEpochsUntilFinalized(1000, currentHeight));
        assertEquals(0, getEpochsUntilFinalized(0, currentHeight));
    }

    @Test
    public void testGetEpochsUntilFinalized_ActiveDAG() {
        long currentHeight = 20000;

        // Block at height 3617 needs (3617 + 16384) - 20000 = 1 more epoch
        assertEquals(1, getEpochsUntilFinalized(3617, currentHeight));

        // Block at height 10000 needs (10000 + 16384) - 20000 = 6384 more epochs
        assertEquals(6384, getEpochsUntilFinalized(10000, currentHeight));

        // Block at current height needs 16384 more epochs
        assertEquals(FINALITY_EPOCHS, getEpochsUntilFinalized(20000, currentHeight));
    }

    @Test
    public void testGetEpochsUntilFinalized_EdgeCase() {
        long currentHeight = FINALITY_EPOCHS;

        // Block at height 0 is just finalized, should return 0
        assertEquals(0, getEpochsUntilFinalized(0, currentHeight));

        // Block at height 1 needs (1 + 16384) - 16384 = 1 more epoch
        assertEquals(1, getEpochsUntilFinalized(1, currentHeight));

        // Block at current height needs 16384 more epochs
        assertEquals(FINALITY_EPOCHS, getEpochsUntilFinalized(currentHeight, currentHeight));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetEpochsUntilFinalized_NegativeHeight() {
        getEpochsUntilFinalized(-1, 10000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetEpochsUntilFinalized_NegativeCurrentHeight() {
        getEpochsUntilFinalized(1000, -1);
    }

    @Test
    public void testFinalityEpochsConstant() {
        // Verify the constant value
        assertEquals(16384, FINALITY_EPOCHS);

        // Verify it's a power of 2 (2^14)
        assertEquals(1, Integer.bitCount(FINALITY_EPOCHS));
        assertEquals(14, Integer.numberOfTrailingZeros(FINALITY_EPOCHS));
    }

    @Test
    public void testComprehensiveScenario() {
        // Simulate blockchain at height 50000
        long currentHeight = 50000;
        long finalizedHeight = getFinalizedHeight(currentHeight); // 50000 - 16384 = 33616

        // Verify finalized region
        assertTrue(isFinalized(0, currentHeight));
        assertTrue(isFinalized(33616, currentHeight));
        assertFalse(isFinalized(33617, currentHeight));

        // Verify active DAG region
        assertFalse(isActiveDAG(33616, currentHeight));
        assertTrue(isActiveDAG(33617, currentHeight));
        assertTrue(isActiveDAG(50000, currentHeight));
        assertFalse(isActiveDAG(50001, currentHeight));

        // Verify active DAG range
        long[] range = getActiveDAGRange(currentHeight);
        assertEquals(33617, range[0]);
        assertEquals(50000, range[1]);

        // Verify active DAG size
        assertEquals(FINALITY_EPOCHS, getActiveDAGSize(currentHeight));

        // Verify epochs until finalized
        assertEquals(0, getEpochsUntilFinalized(33616, currentHeight));
        assertEquals(1, getEpochsUntilFinalized(33617, currentHeight));
        assertEquals(FINALITY_EPOCHS, getEpochsUntilFinalized(50000, currentHeight));
    }

    @Test
    public void testUtilityClassCannotBeInstantiated() throws Exception {
        // Use reflection to try to instantiate the private constructor
        java.lang.reflect.Constructor<FinalityBoundary> constructor =
                FinalityBoundary.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        try {
            constructor.newInstance();
            fail("Expected AssertionError to be thrown");
        } catch (java.lang.reflect.InvocationTargetException e) {
            // The InvocationTargetException wraps the AssertionError
            assertTrue("Expected cause to be AssertionError",
                    e.getCause() instanceof AssertionError);
            assertEquals("Utility class should not be instantiated",
                    e.getCause().getMessage());
        }
    }
}
