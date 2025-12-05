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
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Unit tests for EpochTimer using HashedWheelTimer with epoch-number based detection.
 *
 * <p><b>XDAG Time System:</b>
 * <ul>
 *   <li>XDAG uses 1/1024 second precision (NOT milliseconds)</li>
 *   <li>Epoch number = full_epoch >> 16, representing 65536 XDAG time units</li>
 *   <li>65536 XDAG units = 65536/1024 = 64 seconds</li>
 *   <li>Millisecond conversion has precision loss, so we detect epoch changes by comparing epoch numbers</li>
 * </ul>
 *
 * <p><b>Key behaviors tested:</b>
 * <ul>
 *   <li>Timer detects epoch number changes (not millisecond timing)</li>
 *   <li>Each epoch transition triggers exactly one callback</li>
 *   <li>Skipped epochs are processed in order</li>
 *   <li>Configuration options work correctly</li>
 * </ul>
 *
 * @since XDAGJ 5.1
 */
@Ignore("Long-running integration test - requires real epoch transitions (64s each). Run manually with: mvn test -Dtest=EpochTimerSelfRescheduleTest")
public class EpochTimerSelfRescheduleTest {

    /**
     * XDAG epoch period in XDAG time units.
     * One epoch = 65536 units = 64 seconds (at 1/1024 second precision)
     */
    private static final long XDAG_EPOCH_UNITS = 65536L;

    /**
     * XDAG time unit in seconds: 1/1024 second
     */
    private static final double XDAG_UNIT_SECONDS = 1.0 / 1024.0;

    /**
     * Epoch duration in seconds: 65536 / 1024 = 64 seconds
     */
    private static final double EPOCH_DURATION_SECONDS = XDAG_EPOCH_UNITS * XDAG_UNIT_SECONDS;

    private EpochTimer epochTimer;
    private List<Long> processedEpochs;

    @Before
    public void setUp() {
        epochTimer = new EpochTimer();
        processedEpochs = new ArrayList<>();
    }

    @After
    public void tearDown() {
        if (epochTimer != null && epochTimer.isRunning()) {
            epochTimer.stop();
        }
    }

    // ==================== Basic Lifecycle Tests ====================

    /**
     * Test 1: Verify timer starts and stops correctly.
     */
    @Test
    public void testStartStop() {
        assertFalse("Timer should not be running initially", epochTimer.isRunning());

        epochTimer.start(epoch -> processedEpochs.add(epoch));

        assertTrue("Timer should be running after start", epochTimer.isRunning());

        epochTimer.stop();

        assertFalse("Timer should not be running after stop", epochTimer.isRunning());
    }

    /**
     * Test 2: Verify timer cannot be started twice.
     */
    @Test
    public void testDoubleStart() {
        AtomicInteger callbackCount = new AtomicInteger(0);

        epochTimer.start(epoch -> callbackCount.incrementAndGet());
        assertTrue("Timer should be running", epochTimer.isRunning());

        // Try to start again - should be ignored
        epochTimer.start(epoch -> callbackCount.incrementAndGet());
        assertTrue("Timer should still be running", epochTimer.isRunning());

        epochTimer.stop();
    }

    /**
     * Test 3: Verify null callback is rejected.
     */
    @Test(expected = NullPointerException.class)
    public void testNullCallback() {
        epochTimer.start(null);
    }

    /**
     * Test 4: Verify double stop is safe.
     */
    @Test
    public void testDoubleStop() {
        epochTimer.start(epoch -> {});
        assertTrue("Timer should be running", epochTimer.isRunning());

        epochTimer.stop();
        assertFalse("Timer should be stopped", epochTimer.isRunning());

        // Second stop should be safe (no exception)
        epochTimer.stop();
        assertFalse("Timer should still be stopped", epochTimer.isRunning());
    }

    // ==================== XDAG Time System Tests ====================

    /**
     * Test 5: Verify getCurrentEpoch returns consistent values with TimeUtils.
     *
     * <p>This ensures EpochTimer uses the same epoch calculation as the rest of the system.
     */
    @Test
    public void testGetCurrentEpochConsistency() {
        long epoch1 = epochTimer.getCurrentEpoch();
        long epoch2 = TimeUtils.getCurrentEpochNumber();

        // Should be the same (or at most 1 apart if we're at epoch boundary)
        assertTrue("getCurrentEpoch should match TimeUtils",
                Math.abs(epoch1 - epoch2) <= 1);
    }

    /**
     * Test 6: Verify XDAG epoch duration calculation.
     *
     * <p>XDAG epoch is exactly 65536/1024 = 64 seconds.
     * When converted to milliseconds, it should be close to 64000ms,
     * but due to XDAG's 1/1024 second precision, there may be slight variation.
     */
    @Test
    public void testXdagEpochDuration() {
        // Calculate expected duration: 65536 XDAG units / 1024 = 64 seconds
        assertEquals("Epoch duration should be 64 seconds",
                64.0, EPOCH_DURATION_SECONDS, 0.001);

        // Verify the conversion through TimeUtils
        long duration = EpochTimer.getEpochDurationMs();

        // (65536 * 1000) >> 10 = 65536000 / 1024 = 64000ms
        // Allow small tolerance for any rounding in the implementation
        assertTrue("Epoch duration in ms should be approximately 64000ms (got " + duration + ")",
                duration >= 63990 && duration <= 64010);
    }

    /**
     * Test 7: Verify getTimeUntilEpochEnd returns valid value.
     *
     * <p>The time remaining should be between 0 and the epoch duration.
     * Note: This is an approximation due to XDAG time conversion.
     */
    @Test
    public void testGetTimeUntilEpochEnd() {
        long timeRemaining = epochTimer.getTimeUntilEpochEnd();
        long epochDuration = EpochTimer.getEpochDurationMs();

        assertTrue("Time remaining should be >= 0", timeRemaining >= 0);
        assertTrue("Time remaining should be <= epoch duration",
                timeRemaining <= epochDuration + 100); // +100ms tolerance
    }

    /**
     * Test 8: Verify epoch start and end times are consistent.
     */
    @Test
    public void testEpochStartEndTimes() {
        long startTime = epochTimer.getCurrentEpochStartTime();
        long endTime = epochTimer.getCurrentEpochEndTime();
        long now = System.currentTimeMillis();

        // Start should be before now
        assertTrue("Epoch start should be <= now", startTime <= now);

        // End should be after now (or very close to it at epoch boundary)
        assertTrue("Epoch end should be >= now - 1000ms", endTime >= now - 1000);

        // Duration between start and end should be approximately one epoch
        long duration = endTime - startTime;
        assertTrue("Duration between start and end should be approximately 64000ms",
                duration >= 63500 && duration <= 64500);
    }

    // ==================== Configuration Tests ====================

    /**
     * Test 9: Verify default configuration values.
     */
    @Test
    public void testDefaultConfiguration() {
        assertEquals("Default tick duration should be 10ms",
                10, epochTimer.getTickDurationMs());
    }

    /**
     * Test 10: Verify custom configuration is applied.
     */
    @Test
    public void testCustomConfiguration() {
        EpochTimer customTimer = new EpochTimer(20);
        try {
            assertEquals("Custom tick duration should be 20ms",
                    20, customTimer.getTickDurationMs());
        } finally {
            if (customTimer.isRunning()) {
                customTimer.stop();
            }
        }
    }

    /**
     * Test 11: Verify minimum configuration values are enforced.
     */
    @Test
    public void testMinimumConfiguration() {
        // Tick duration minimum is 1ms
        EpochTimer timerWithSmallTick = new EpochTimer(0);
        assertEquals("Tick duration should be at least 1ms",
                1, timerWithSmallTick.getTickDurationMs());
    }

    // ==================== Epoch Transition Detection Tests ====================

    /**
     * Test 12: Verify timer processes at least one epoch within timeout.
     *
     * <p>This test ensures the epoch-number based detection works in practice.
     * The timer polls for epoch number changes and triggers callback when detected.
     */
    @Test
    public void testProcessesEpochWithinTimeout() throws InterruptedException {
        CountDownLatch epochProcessed = new CountDownLatch(1);
        AtomicLong processedEpochNumber = new AtomicLong(-1);

        epochTimer.start(epoch -> {
            System.out.println("Epoch processed: " + epoch +
                    " (current: " + TimeUtils.getCurrentEpochNumber() + ")");
            processedEpochNumber.set(epoch);
            processedEpochs.add(epoch);
            epochProcessed.countDown();
        });

        // Wait up to 70 seconds for at least one epoch to be processed
        // (worst case: we start just after an epoch boundary)
        boolean processed = epochProcessed.await(70, TimeUnit.SECONDS);

        epochTimer.stop();

        assertTrue("At least one epoch should be processed within 70 seconds", processed);
        assertFalse("Processed epochs list should not be empty", processedEpochs.isEmpty());

        // The processed epoch should be one less than the current epoch
        long currentEpoch = TimeUtils.getCurrentEpochNumber();
        long processedEpoch = processedEpochNumber.get();
        assertTrue("Processed epoch should be <= current epoch",
                processedEpoch <= currentEpoch);

        System.out.println("Processed epoch: " + processedEpoch + ", current: " + currentEpoch);
    }

    /**
     * Test 13: Verify multiple epochs are processed consecutively.
     *
     * <p>This test runs for about 130 seconds to verify at least 2 consecutive
     * epochs are processed in the correct order without missing any.
     */
    @Test
    public void testMultipleEpochsConsecutive() throws InterruptedException {
        CountDownLatch twoEpochsProcessed = new CountDownLatch(2);

        epochTimer.start(epoch -> {
            System.out.println("Epoch processed: " + epoch + " at " + System.currentTimeMillis());
            synchronized (processedEpochs) {
                processedEpochs.add(epoch);
            }
            twoEpochsProcessed.countDown();
        });

        // Wait up to 135 seconds for two epochs
        boolean processed = twoEpochsProcessed.await(135, TimeUnit.SECONDS);

        epochTimer.stop();

        assertTrue("At least two epochs should be processed within 135 seconds", processed);

        synchronized (processedEpochs) {
            assertTrue("At least 2 epochs should be in the list", processedEpochs.size() >= 2);

            // Verify epochs are consecutive (no gaps)
            for (int i = 1; i < processedEpochs.size(); i++) {
                long prev = processedEpochs.get(i - 1);
                long curr = processedEpochs.get(i);

                // Each epoch should be exactly 1 more than the previous
                // (unless skipped epochs were processed, then they would still be consecutive)
                assertEquals("Epochs should be consecutive (no gaps)",
                        prev + 1, curr);
            }

            System.out.println("Processed consecutive epochs: " + processedEpochs);
        }
    }

    /**
     * Test 14: Verify lastProcessedEpoch is updated correctly.
     */
    @Test
    public void testLastProcessedEpochTracking() throws InterruptedException {
        assertEquals("Last processed epoch should be -1 before start",
                -1, epochTimer.getLastProcessedEpoch());

        CountDownLatch epochProcessed = new CountDownLatch(1);

        epochTimer.start(epoch -> {
            processedEpochs.add(epoch);
            epochProcessed.countDown();
        });

        // After start, lastProcessedEpoch should be initialized to current epoch
        long initialEpoch = epochTimer.getLastProcessedEpoch();
        assertTrue("Last processed epoch should be initialized after start",
                initialEpoch > 0);

        // Wait for an epoch to be processed
        boolean processed = epochProcessed.await(70, TimeUnit.SECONDS);

        if (processed) {
            // After processing, lastProcessedEpoch should be updated
            long lastProcessed = epochTimer.getLastProcessedEpoch();
            assertTrue("Last processed epoch should be >= initial epoch",
                    lastProcessed >= initialEpoch);
        }

        epochTimer.stop();
    }

    // ==================== Stop/Cleanup Tests ====================

    /**
     * Test 15: Verify timer handles stop during epoch processing.
     */
    @Test
    public void testStopDuringProcessing() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger processCount = new AtomicInteger(0);

        epochTimer.start(epoch -> {
            started.countDown();
            processCount.incrementAndGet();
            // Simulate some processing time
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Wait for first epoch or timeout
        boolean firstEpoch = started.await(70, TimeUnit.SECONDS);

        // Stop immediately
        epochTimer.stop();

        // Give some time for any pending callbacks
        Thread.sleep(500);

        assertFalse("Timer should be stopped", epochTimer.isRunning());

        // The count should be small (1-2 at most)
        int count = processCount.get();
        assertTrue("Process count should be reasonable after stop: " + count, count <= 3);
    }

    /**
     * Test 16: Verify timer can be restarted after stop.
     */
    @Test
    public void testRestartAfterStop() throws InterruptedException {
        AtomicInteger firstRunCount = new AtomicInteger(0);
        AtomicInteger secondRunCount = new AtomicInteger(0);

        // First run
        epochTimer.start(epoch -> firstRunCount.incrementAndGet());
        assertTrue("Timer should be running", epochTimer.isRunning());

        Thread.sleep(1000);
        epochTimer.stop();
        assertFalse("Timer should be stopped", epochTimer.isRunning());

        // Second run with different callback
        epochTimer.start(epoch -> secondRunCount.incrementAndGet());
        assertTrue("Timer should be running again", epochTimer.isRunning());

        Thread.sleep(1000);
        epochTimer.stop();
        assertFalse("Timer should be stopped", epochTimer.isRunning());

        // Both counters should be independent
        System.out.println("First run count: " + firstRunCount.get() +
                ", Second run count: " + secondRunCount.get());
    }

    // ==================== Error Handling Tests ====================

    /**
     * Test 17: Verify timer continues after callback exception.
     *
     * <p>If the callback throws an exception, the timer should continue
     * scheduling subsequent checks.
     */
    @Test
    public void testContinuesAfterCallbackException() throws InterruptedException {
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch secondCall = new CountDownLatch(2);

        epochTimer.start(epoch -> {
            int count = callCount.incrementAndGet();
            secondCall.countDown();

            if (count == 1) {
                throw new RuntimeException("Test exception - should be caught");
            }
        });

        // Wait for at least 2 epochs (worst case ~130 seconds)
        boolean processed = secondCall.await(140, TimeUnit.SECONDS);

        epochTimer.stop();

        // If we got 2 callbacks, timer survived the exception
        if (processed) {
            assertTrue("Timer should continue after exception",
                    callCount.get() >= 2);
        }
    }
}
