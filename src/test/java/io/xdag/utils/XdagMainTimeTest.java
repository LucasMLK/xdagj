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

package io.xdag.utils;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test for XDAG mainblock time generation
 *
 * <p>XDAG uses "fixed-point block generation" - main blocks are generated
 * at the END of each epoch (when lower 16 bits = 0xffff), not at regular
 * intervals.
 */
public class XdagMainTimeTest {

    /**
     * Test 1: getMainTime returns end of current epoch
     */
    @Test
    public void testGetMainTime() {
        long currentTime = XdagTime.getCurrentTimestamp();
        long mainTime = XdagTime.getMainTime();

        // Main time should be in the same epoch
        assertEquals("Main time should be in same epoch as current time",
                     XdagTime.getEpoch(currentTime),
                     XdagTime.getEpoch(mainTime));

        // Main time should be at end of epoch
        assertTrue("Main time should be at end of epoch",
                   XdagTime.isEndOfEpoch(mainTime));

        // Main time should be >= current time (in same epoch)
        assertTrue("Main time should be >= current time",
                   mainTime >= currentTime);
    }

    /**
     * Test 2: Main blocks should be at epoch boundaries
     */
    @Test
    public void testMainBlockTiming() {
        // Epoch 0: time range [0, 65535], main block at 65535
        long epoch0Start = 0L;
        long epoch0MainTime = XdagTime.getEndOfEpoch(epoch0Start);

        assertEquals("Epoch 0 main time should be 65535", 65535L, epoch0MainTime);
        assertTrue("Should be end of epoch", XdagTime.isEndOfEpoch(epoch0MainTime));
        assertEquals("Should be in epoch 0", 0L, XdagTime.getEpoch(epoch0MainTime));

        // Epoch 1: time range [65536, 131071], main block at 131071
        long epoch1Start = 65536L;
        long epoch1MainTime = XdagTime.getEndOfEpoch(epoch1Start);

        assertEquals("Epoch 1 main time should be 131071", 131071L, epoch1MainTime);
        assertTrue("Should be end of epoch", XdagTime.isEndOfEpoch(epoch1MainTime));
        assertEquals("Should be in epoch 1", 1L, XdagTime.getEpoch(epoch1MainTime));

        // Epoch 2: time range [131072, 196607], main block at 196607
        long epoch2Start = 131072L;
        long epoch2MainTime = XdagTime.getEndOfEpoch(epoch2Start);

        assertEquals("Epoch 2 main time should be 196607", 196607L, epoch2MainTime);
        assertTrue("Should be end of epoch", XdagTime.isEndOfEpoch(epoch2MainTime));
        assertEquals("Should be in epoch 2", 2L, XdagTime.getEpoch(epoch2MainTime));
    }

    /**
     * Test 3: Fixed-point generation (not interval-based)
     */
    @Test
    public void testFixedPointGeneration() {
        // Test that getMainTime() from different points in same epoch
        // always returns the SAME end-of-epoch time

        long epoch1Start = 65536L;  // Start of epoch 1
        long epoch1Middle = 98000L;  // Middle of epoch 1
        long epoch1AlmostEnd = 131070L;  // Almost end of epoch 1

        long mainTime1 = XdagTime.getEndOfEpoch(epoch1Start);
        long mainTime2 = XdagTime.getEndOfEpoch(epoch1Middle);
        long mainTime3 = XdagTime.getEndOfEpoch(epoch1AlmostEnd);

        // All should return the same end-of-epoch time
        assertEquals("Main time should be same for any point in epoch",
                     mainTime1, mainTime2);
        assertEquals("Main time should be same for any point in epoch",
                     mainTime1, mainTime3);
        assertEquals("Should all be 131071 (end of epoch 1)",
                     131071L, mainTime1);
    }

    /**
     * Test 4: Epoch duration is exactly 64 seconds
     */
    @Test
    public void testEpochDuration() {
        // 1 epoch = 2^16 XDAG units = 65536 units
        // 1 second = 1024 units
        // Therefore: 1 epoch = 65536 / 1024 = 64 seconds

        long epoch0End = 65535L;  // End of epoch 0
        long epoch1End = 131071L;  // End of epoch 1

        long epochDurationInXdagUnits = epoch1End - epoch0End;
        assertEquals("Epoch duration should be 65536 XDAG units",
                     65536L, epochDurationInXdagUnits);

        long epochDurationInMs = XdagTime.xdagTimestampToMs(epochDurationInXdagUnits);
        long epochDurationInSeconds = epochDurationInMs / 1000;

        assertEquals("Epoch duration should be 64 seconds",
                     64L, epochDurationInSeconds);
    }

    /**
     * Test 5: Main blocks occur every 64 seconds at fixed points
     */
    @Test
    public void testMainBlockInterval() {
        long[] mainBlockTimes = {
            65535L,      // Epoch 0 end
            131071L,     // Epoch 1 end
            196607L,     // Epoch 2 end
            262143L,     // Epoch 3 end
            327679L      // Epoch 4 end
        };

        for (int i = 0; i < mainBlockTimes.length - 1; i++) {
            long duration = mainBlockTimes[i + 1] - mainBlockTimes[i];
            long durationInMs = XdagTime.xdagTimestampToMs(duration);
            long durationInSeconds = durationInMs / 1000;

            assertEquals("Main blocks should be 64 seconds apart",
                         64L, durationInSeconds);
        }
    }

    /**
     * Test 6: getEndOfEpoch with milliseconds is WRONG (common mistake)
     */
    @Test
    public void testCommonMistake() {
        // This is WRONG but appears in old test code
        long ms = System.currentTimeMillis();  // Milliseconds

        // WRONG: Passing milliseconds to getEndOfEpoch
        // This treats milliseconds as XDAG timestamp, which is completely wrong
        long wrongResult = XdagTime.getEndOfEpoch(ms);

        // Milliseconds are ~10^12 range, XDAG timestamps are ~10^9 range
        // So the result will be completely nonsensical

        // CORRECT: Must convert to XDAG timestamp first
        long xdagTime = XdagTime.msToXdagtimestamp(ms);
        long correctResult = XdagTime.getEndOfEpoch(xdagTime);

        // The two results should be VERY different
        // (unless by pure coincidence)
        assertNotEquals("Using milliseconds vs XDAG timestamp gives different results",
                        wrongResult, correctResult);

        // The correct result should be at end of epoch
        assertTrue("Correct result should be at end of epoch",
                   XdagTime.isEndOfEpoch(correctResult));
    }
}
