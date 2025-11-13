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
 * Unit tests for XdagTime
 *
 * <p>XDAG uses a timestamp format with 1/1024 second precision (not 1/1000).
 * This is 2^10 = 1024 units per second.
 */
public class XdagTimeTest {

    /**
     * Test 1: Milliseconds to XDAG timestamp conversion
     */
    @Test
    public void testMsToXdagTimestamp() {
        // 1000ms = 1 second = 1024 XDAG units
        long ms = 1000L;
        long xdagTime = XdagTime.msToXdagtimestamp(ms);

        assertEquals("1 second should be 1024 XDAG units", 1024L, xdagTime);
    }

    /**
     * Test 2: XDAG timestamp to milliseconds conversion
     */
    @Test
    public void testXdagTimestampToMs() {
        // 1024 XDAG units = 1 second = 1000ms
        long xdagTime = 1024L;
        long ms = XdagTime.xdagTimestampToMs(xdagTime);

        assertEquals("1024 XDAG units should be 1000ms", 1000L, ms);
    }

    /**
     * Test 3: Round-trip conversion (ms → XDAG → ms)
     */
    @Test
    public void testRoundTripConversion() {
        long[] testValues = {
            0L,
            1000L,      // 1 second
            60000L,     // 1 minute
            3600000L,   // 1 hour
            86400000L,  // 1 day
            System.currentTimeMillis()
        };

        for (long originalMs : testValues) {
            long xdagTime = XdagTime.msToXdagtimestamp(originalMs);
            long convertedMs = XdagTime.xdagTimestampToMs(xdagTime);

            // Allow small rounding error (< 1ms due to precision difference)
            long diff = Math.abs(convertedMs - originalMs);
            assertTrue("Round-trip conversion error should be < 1ms for " + originalMs +
                       ", but was " + diff + "ms", diff < 2);
        }
    }

    /**
     * Test 4: Precision verification (1/1024 second)
     */
    @Test
    public void testPrecision() {
        // 1024 XDAG units per second
        long oneSecondMs = 1000L;
        long xdagUnitsPerSecond = XdagTime.msToXdagtimestamp(oneSecondMs);

        assertEquals("XDAG should use 1024 units per second", 1024L, xdagUnitsPerSecond);

        // 1 XDAG unit = 1000/1024 ms ≈ 0.9765625 ms
        long oneXdagUnitInMs = XdagTime.xdagTimestampToMs(1L);
        assertEquals("1 XDAG unit should be 0ms (rounded down)", 0L, oneXdagUnitInMs);

        // 2 XDAG units ≈ 1.953ms → 1ms (rounded down)
        long twoXdagUnitsInMs = XdagTime.xdagTimestampToMs(2L);
        assertEquals("2 XDAG units should be 1ms", 1L, twoXdagUnitsInMs);
    }

    /**
     * Test 5: Epoch calculation
     */
    @Test
    public void testGetEpoch() {
        // Epoch is calculated by right-shifting 16 bits
        // 1 epoch = 2^16 XDAG units = 65536 units

        long xdagTime = 0L;
        assertEquals("Time 0 should be epoch 0", 0L, XdagTime.getEpoch(xdagTime));

        xdagTime = 65535L;  // Max value in epoch 0
        assertEquals("Time 65535 should be epoch 0", 0L, XdagTime.getEpoch(xdagTime));

        xdagTime = 65536L;  // First value in epoch 1
        assertEquals("Time 65536 should be epoch 1", 1L, XdagTime.getEpoch(xdagTime));

        xdagTime = 131071L;  // Max value in epoch 1
        assertEquals("Time 131071 should be epoch 1", 1L, XdagTime.getEpoch(xdagTime));

        xdagTime = 131072L;  // First value in epoch 2
        assertEquals("Time 131072 should be epoch 2", 2L, XdagTime.getEpoch(xdagTime));
    }

    /**
     * Test 6: Epoch duration in real time
     */
    @Test
    public void testEpochDuration() {
        // 1 epoch = 2^16 XDAG units = 65536 / 1024 seconds = 64 seconds
        long epochInXdagUnits = 65536L;
        long epochInMs = XdagTime.xdagTimestampToMs(epochInXdagUnits);
        long epochInSeconds = epochInMs / 1000;

        assertEquals("1 epoch should be 64 seconds", 64L, epochInSeconds);
    }

    /**
     * Test 7: End of epoch calculation
     */
    @Test
    public void testGetEndOfEpoch() {
        long time = 65536L;  // Start of epoch 1
        long endOfEpoch = XdagTime.getEndOfEpoch(time);

        // End of epoch should have all lower 16 bits set (0xffff)
        assertEquals("End of epoch should be time | 0xffff",
                     time | 0xffff, endOfEpoch);

        // Should still be in the same epoch
        assertEquals("End time should be in same epoch",
                     XdagTime.getEpoch(time), XdagTime.getEpoch(endOfEpoch));
    }

    /**
     * Test 8: Check if timestamp is at end of epoch
     */
    @Test
    public void testIsEndOfEpoch() {
        long endTime = 0xffffL;  // End of epoch 0
        assertTrue("Time 0xffff should be end of epoch",
                   XdagTime.isEndOfEpoch(endTime));

        long notEndTime = 0xfffeL;
        assertFalse("Time 0xfffe should not be end of epoch",
                    XdagTime.isEndOfEpoch(notEndTime));

        long epoch1End = 0x1ffffL;  // End of epoch 1
        assertTrue("Time 0x1ffff should be end of epoch",
                   XdagTime.isEndOfEpoch(epoch1End));
    }

    /**
     * Test 9: Current timestamp is valid
     */
    @Test
    public void testGetCurrentTimestamp() {
        long currentXdagTime = XdagTime.getCurrentTimestamp();
        long currentMs = System.currentTimeMillis();

        // Convert back to verify
        long convertedMs = XdagTime.xdagTimestampToMs(currentXdagTime);

        // Should be close to current time (within 2ms)
        long diff = Math.abs(convertedMs - currentMs);
        assertTrue("Current timestamp conversion should be accurate (diff=" + diff + "ms)",
                   diff < 2);
    }

    /**
     * Test 10: Zero timestamp
     */
    @Test
    public void testZeroTimestamp() {
        long ms = 0L;
        long xdagTime = XdagTime.msToXdagtimestamp(ms);
        assertEquals("Zero ms should be zero XDAG time", 0L, xdagTime);

        long convertedMs = XdagTime.xdagTimestampToMs(0L);
        assertEquals("Zero XDAG time should be zero ms", 0L, convertedMs);
    }

    /**
     * Test 11: Large timestamp values
     */
    @Test
    public void testLargeTimestamps() {
        // Year 2024 timestamp
        long ms2024 = 1704067200000L; // 2024-01-01 00:00:00 UTC
        long xdagTime = XdagTime.msToXdagtimestamp(ms2024);
        long convertedMs = XdagTime.xdagTimestampToMs(xdagTime);

        // Should round-trip accurately
        long diff = Math.abs(convertedMs - ms2024);
        assertTrue("Large timestamp should round-trip accurately", diff < 2);
    }

    /**
     * Test 12: Conversion formula verification
     */
    @Test
    public void testConversionFormula() {
        // Verify the formula: XDAG_time = (ms * 1024) / 1000
        long ms = 5000L;  // 5 seconds

        long expectedXdagTime = (ms * 1024L) / 1000L;
        long actualXdagTime = XdagTime.msToXdagtimestamp(ms);

        assertEquals("XDAG time should follow formula (ms * 1024) / 1000",
                     expectedXdagTime, actualXdagTime);

        // Verify reverse: ms = (XDAG_time * 1000) / 1024
        long expectedMs = (actualXdagTime * 1000L) / 1024L;
        long actualMs = XdagTime.xdagTimestampToMs(actualXdagTime);

        assertEquals("Ms should follow formula (XDAG_time * 1000) / 1024",
                     expectedMs, actualMs);
    }
}
