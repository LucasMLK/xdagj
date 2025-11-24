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
package io.xdag.consensus.epoch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for EpochTimer
 *
 * <p>Tests the precise 64-second epoch boundary timing mechanism.
 *
 * <p>Note: Real-time epoch callback tests are in integration tests.
 * Unit tests focus on calculation correctness and lifecycle management.
 */
public class EpochTimerTest {

    private EpochTimer epochTimer;

    @Before
    public void setUp() {
        epochTimer = new EpochTimer();
    }

    @After
    public void tearDown() {
        if (epochTimer != null && epochTimer.isRunning()) {
            epochTimer.stop();
        }
    }

    /**
     * Test 1: getCurrentEpoch() returns correct epoch number
     */
    @Test
    public void testGetCurrentEpoch() {
        long now = System.currentTimeMillis();
        long expected = now / 64000;

        long actual = epochTimer.getCurrentEpoch();

        assertEquals("Epoch number should match current time / 64000", expected, actual);
    }

    /**
     * Test 2: getEpochDurationMs() returns 64000
     */
    @Test
    public void testGetEpochDurationMs() {
        assertEquals("Epoch duration should be 64000ms", 64000, EpochTimer.getEpochDurationMs());
    }

    /**
     * Test 3: getCurrentEpochStartTime() returns correct start time
     */
    @Test
    public void testGetCurrentEpochStartTime() {
        long now = System.currentTimeMillis();
        long expected = (now / 64000) * 64000;

        long actual = epochTimer.getCurrentEpochStartTime();

        assertEquals("Epoch start time should be aligned to 64s boundary", expected, actual);
    }

    /**
     * Test 4: getCurrentEpochEndTime() returns correct end time
     */
    @Test
    public void testGetCurrentEpochEndTime() {
        long now = System.currentTimeMillis();
        long expectedStart = (now / 64000) * 64000;
        long expectedEnd = expectedStart + 64000;

        long actual = epochTimer.getCurrentEpochEndTime();

        assertEquals("Epoch end time should be start + 64000", expectedEnd, actual);
    }

    /**
     * Test 5: getTimeUntilEpochEnd() returns value between 0 and 64000
     */
    @Test
    public void testGetTimeUntilEpochEnd() {
        long remaining = epochTimer.getTimeUntilEpochEnd();

        assertTrue("Time remaining should be positive", remaining > 0);
        assertTrue("Time remaining should be less than epoch duration",
            remaining <= 64000);
    }

    /**
     * Test 6: getTimeUntilEpochEnd() calculation is correct
     */
    @Test
    public void testGetTimeUntilEpochEndCalculation() {
        long now = System.currentTimeMillis();
        long epochStart = (now / 64000) * 64000;
        long epochEnd = epochStart + 64000;
        long expected = epochEnd - now;

        long actual = epochTimer.getTimeUntilEpochEnd();

        // Allow 10ms tolerance for execution time
        assertTrue("Time remaining calculation should be accurate",
            Math.abs(actual - expected) < 10);
    }

    /**
     * Test 7: isRunning() returns false before start
     */
    @Test
    public void testIsRunningBeforeStart() {
        assertFalse("Timer should not be running before start", epochTimer.isRunning());
    }

    /**
     * Test 8: isRunning() returns true after start
     */
    @Test
    public void testIsRunningAfterStart() {
        epochTimer.start(epoch -> {
            // Empty callback
        });

        assertTrue("Timer should be running after start", epochTimer.isRunning());
    }

    /**
     * Test 9: isRunning() returns false after stop
     */
    @Test
    public void testIsRunningAfterStop() {
        epochTimer.start(epoch -> {
            // Empty callback
        });

        epochTimer.stop();

        assertFalse("Timer should not be running after stop", epochTimer.isRunning());
    }

    /**
     * Test 10: Cannot start twice
     */
    @Test
    public void testCannotStartTwice() {
        epochTimer.start(epoch -> {
            // Empty callback
        });

        // Try to start again (should be ignored)
        epochTimer.start(epoch -> {
            // Empty callback
        });

        assertTrue("Timer should still be running", epochTimer.isRunning());
    }

    /**
     * Test 11: Stop is idempotent
     */
    @Test
    public void testStopIsIdempotent() {
        epochTimer.start(epoch -> {
            // Empty callback
        });

        epochTimer.stop();
        assertFalse("Timer should not be running after first stop", epochTimer.isRunning());

        epochTimer.stop();  // Second stop should not throw exception
        assertFalse("Timer should still not be running after second stop", epochTimer.isRunning());
    }

    /**
     * Test 12: Null callback throws NullPointerException
     */
    @Test(expected = NullPointerException.class)
    public void testNullCallbackThrowsException() {
        epochTimer.start(null);
    }

    /**
     * Test 13: Stop when not running does not throw exception
     */
    @Test
    public void testStopWhenNotRunning() {
        assertFalse("Timer should not be running initially", epochTimer.isRunning());

        epochTimer.stop();  // Should not throw exception

        assertFalse("Timer should still not be running", epochTimer.isRunning());
    }

    /**
     * Test 14: Multiple epoch calculations are consistent
     */
    @Test
    public void testMultipleEpochCalculationsConsistent() {
        long epoch1 = epochTimer.getCurrentEpoch();
        long startTime1 = epochTimer.getCurrentEpochStartTime();
        long endTime1 = epochTimer.getCurrentEpochEndTime();

        // Sleep a tiny bit to ensure we're still in same epoch
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long epoch2 = epochTimer.getCurrentEpoch();
        long startTime2 = epochTimer.getCurrentEpochStartTime();
        long endTime2 = epochTimer.getCurrentEpochEndTime();

        // All should be same (we're in the same epoch)
        assertEquals("Epoch number should be same", epoch1, epoch2);
        assertEquals("Start time should be same", startTime1, startTime2);
        assertEquals("End time should be same", endTime1, endTime2);
    }

    /**
     * Test 15: Epoch start and end times are 64000ms apart
     */
    @Test
    public void testEpochStartEndTimeDifference() {
        long startTime = epochTimer.getCurrentEpochStartTime();
        long endTime = epochTimer.getCurrentEpochEndTime();

        assertEquals("Epoch should be exactly 64000ms long",
            64000, endTime - startTime);
    }
}
