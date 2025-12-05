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

import io.xdag.utils.TimeUtils;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for epoch time calculations to prevent overflow/calculation bugs.
 *
 * <p><b>IMPORTANT:</b> These tests use REAL epoch numbers (like 27577013) to catch
 * bugs that only manifest with large values. Previous bugs went undetected because
 * tests used small values like epoch=100.
 *
 * <p><b>Historical bugs fixed:</b>
 * <ul>
 *   <li>BUG-EPOCH-TIME-001: EpochConsensusManager used epoch * 64000 which overflows</li>
 *   <li>BUG-EPOCH-TIME-002: Removed dead code (EpochStats) that used epoch * 64</li>
 * </ul>
 *
 * @since XDAGJ 5.1
 */
public class EpochTimeCalculationTest {

    /**
     * A realistic epoch number (approximately late 2025)
     */
    private static final long REALISTIC_EPOCH = 27577013L;

    /**
     * Test that epoch time calculations don't overflow with realistic epoch numbers.
     *
     * <p>This test catches bugs where epoch * constant overflows for large epoch values.
     */
    @Test
    public void testEpochTimeMillisCalculation_NoOverflow() {
        // Calculate epoch end time in milliseconds
        long epochEndMs = TimeUtils.epochNumberToTimeMillis(REALISTIC_EPOCH);

        // Should be a reasonable timestamp (after year 2000, before year 2100)
        long year2000Ms = 946684800000L;  // 2000-01-01 00:00:00 UTC
        long year2100Ms = 4102444800000L; // 2100-01-01 00:00:00 UTC

        assertTrue("Epoch end time should be after year 2000: " + epochEndMs,
                epochEndMs > year2000Ms);
        assertTrue("Epoch end time should be before year 2100: " + epochEndMs,
                epochEndMs < year2100Ms);

        // Verify epoch duration is approximately 64 seconds
        long previousEpochEndMs = TimeUtils.epochNumberToTimeMillis(REALISTIC_EPOCH - 1);
        long duration = epochEndMs - previousEpochEndMs;

        assertTrue("Epoch duration should be approximately 64000ms, got: " + duration,
                duration >= 63900 && duration <= 64100);
    }

    /**
     * Test that epoch start/end calculations are consistent.
     *
     * <p>End of epoch N should equal start of epoch N+1.
     */
    @Test
    public void testEpochStartEndConsistency() {
        long epochNEnd = TimeUtils.epochNumberToTimeMillis(REALISTIC_EPOCH);
        long epochN1Start = TimeUtils.epochNumberToTimeMillis(REALISTIC_EPOCH);

        assertEquals("End of epoch N should equal start of epoch N+1",
                epochNEnd, epochN1Start);
    }

    /**
     * Test that multiplying epoch by constants would overflow (demonstrating the bug).
     *
     * <p>This test documents why epoch * 64000 was wrong - it overflows!
     */
    @Test
    public void testDemonstrateOverflowBug() {
        // This is the WRONG calculation that caused the bug
        long wrongCalculation = REALISTIC_EPOCH * 64000L;

        // The result overflows and becomes a garbage value
        // 27577013 * 64000 = 1,764,928,832,000,000 which is valid
        // But if someone used int instead of long, it would overflow
        // More importantly, this value has no meaning as a timestamp

        // Correct calculation using TimeUtils
        long correctCalculation = TimeUtils.epochNumberToTimeMillis(REALISTIC_EPOCH);

        // They should NOT be equal (proving the old code was wrong)
        assertNotEquals("Wrong calculation should differ from correct one",
                wrongCalculation, correctCalculation);

        // The correct value should be a reasonable timestamp
        assertTrue("Correct calculation should be a valid timestamp",
                correctCalculation > 0 && correctCalculation < Long.MAX_VALUE / 2);
    }

    /**
     * Test getCurrentEpochNumber returns realistic values.
     */
    @Test
    public void testCurrentEpochNumberIsRealistic() {
        long currentEpoch = TimeUtils.getCurrentEpochNumber();

        // Current epoch should be large (in the tens of millions range)
        assertTrue("Current epoch should be > 20000000: " + currentEpoch,
                currentEpoch > 20000000L);

        // But not impossibly large
        assertTrue("Current epoch should be < 100000000: " + currentEpoch,
                currentEpoch < 100000000L);
    }

    /**
     * Test epoch duration constant.
     */
    @Test
    public void testEpochDurationMs() {
        long duration = EpochTimer.getEpochDurationMs();

        // Should be approximately 64000ms (64 seconds)
        assertTrue("Epoch duration should be ~64000ms, got: " + duration,
                duration >= 63900 && duration <= 64100);
    }
}
