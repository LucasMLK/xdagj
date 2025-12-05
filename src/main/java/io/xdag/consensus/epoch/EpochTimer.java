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

import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.xdag.utils.TimeUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * EpochTimer - Precisely schedules epoch boundary callbacks using Netty's HashedWheelTimer.
 *
 * <p>This timer calculates the exact time until each epoch ends and schedules callbacks
 * at those precise boundaries. It ensures every epoch is processed without gaps.
 *
 * <p><b>Scheduling Strategy:</b>
 * <ol>
 *   <li>Calculate target wake time = epochEndTime - buffer (to account for timer precision)</li>
 *   <li>Schedule timer to wake at target time</li>
 *   <li>When timer fires, verify epoch transition by comparing epoch numbers</li>
 *   <li>If epoch hasn't changed yet (early wake), wait briefly and re-check</li>
 *   <li>Trigger callback for ended epoch, then reschedule for next epoch</li>
 * </ol>
 *
 * <p><b>XDAG Time System:</b>
 * <ul>
 *   <li>XDAG uses 1/1024 second precision (NOT milliseconds)</li>
 *   <li>Epoch number = full_epoch >> 16, representing 65536 XDAG time units</li>
 *   <li>1 epoch = 65536 XDAG time units = 64 seconds</li>
 *   <li>Millisecond conversion: epochEndMs = (((epochNum << 16) | 0xffff) * 1000) >> 10</li>
 * </ul>
 *
 * @see TimeUtils
 * @see HashedWheelTimer
 */
@Slf4j
public class EpochTimer {

    /**
     * Default tick duration for the HashedWheelTimer (10ms).
     * Smaller tick = more precise scheduling but slightly higher CPU usage.
     */
    public static final long DEFAULT_TICK_DURATION_MS = 10;

    /**
     * Buffer time before epoch end to wake up (50ms).
     * We wake up slightly early to ensure we don't miss the boundary.
     */
    public static final long WAKEUP_BUFFER_MS = 50;

    /**
     * Maximum wait time when checking for epoch transition (200ms).
     * If epoch doesn't change within this time, something is wrong.
     */
    public static final long MAX_WAIT_FOR_TRANSITION_MS = 200;

    /**
     * Poll interval when waiting for epoch transition (5ms).
     */
    public static final long TRANSITION_POLL_INTERVAL_MS = 5;

    /**
     * Netty's HashedWheelTimer for efficient scheduling.
     * Uses time-wheel algorithm with O(1) add/cancel operations.
     */
    private HashedWheelTimer timer;

    /**
     * Current scheduled timeout, used for cancellation.
     */
    private volatile Timeout currentTimeout;

    /**
     * Callback to invoke when an epoch ends.
     */
    private Consumer<Long> onEpochEnd;

    /**
     * Last observed epoch number for change detection.
     */
    private volatile long lastEpochNumber;

    /**
     * Tick duration for HashedWheelTimer.
     */
    private final long tickDurationMs;

    /**
     * Flag indicating if the timer is running.
     */
    @Getter
    private volatile boolean running;

    /**
     * Create EpochTimer with default tick duration (10ms).
     */
    public EpochTimer() {
        this(DEFAULT_TICK_DURATION_MS);
    }

    /**
     * Create EpochTimer with custom tick duration.
     *
     * @param tickDurationMs HashedWheelTimer tick duration in milliseconds (min: 1ms)
     */
    public EpochTimer(long tickDurationMs) {
        this.tickDurationMs = Math.max(1, tickDurationMs);
        this.running = false;
        this.lastEpochNumber = -1;
    }

    /**
     * For backward compatibility - second parameter is ignored, use tick duration only.
     */
    public EpochTimer(long tickDurationMs, long ignored) {
        this(tickDurationMs);
    }

    /**
     * Start the epoch timer with the given callback.
     *
     * <p>The timer calculates the precise time until each epoch ends and schedules
     * callbacks at those boundaries. Every epoch transition triggers exactly one callback.
     *
     * @param onEpochEnd Callback to invoke when an epoch ends. Receives the ended epoch number.
     * @throws NullPointerException if onEpochEnd is null
     */
    public void start(Consumer<Long> onEpochEnd) {
        Objects.requireNonNull(onEpochEnd, "Epoch callback cannot be null");

        if (running) {
            log.warn("EpochTimer is already running");
            return;
        }

        this.onEpochEnd = onEpochEnd;

        // Create HashedWheelTimer with configured tick duration
        this.timer = new HashedWheelTimer(
                r -> {
                    Thread t = new Thread(r, "EpochTimer-HashedWheel");
                    t.setDaemon(true);
                    return t;
                },
                tickDurationMs,
                TimeUnit.MILLISECONDS,
                512  // wheel size
        );

        // Initialize with current epoch
        this.lastEpochNumber = TimeUtils.getCurrentEpochNumber();
        this.running = true;

        log.info("EpochTimer starting: tick={}ms, current_epoch={}", tickDurationMs, lastEpochNumber);

        // Schedule for next epoch boundary
        scheduleForNextEpoch();

        log.info("EpochTimer started (precise epoch boundary scheduling)");
    }

    /**
     * Calculate delay until next epoch ends and schedule wake-up.
     */
    private void scheduleForNextEpoch() {
        if (!running || timer == null) {
            return;
        }

        long currentEpochNum = TimeUtils.getCurrentEpochNumber();
        long epochEndMs = TimeUtils.epochNumberToTimeMillis(currentEpochNum);
        long now = System.currentTimeMillis();

        // Calculate delay: wake up slightly before epoch end
        long delay = epochEndMs - now - WAKEUP_BUFFER_MS;

        if (delay < 0) {
            // Epoch end is imminent or passed, check immediately
            delay = 0;
        }

        log.debug("Scheduling for epoch {} end: delay={}ms (end_time={}, now={})",
                currentEpochNum, delay, epochEndMs, now);

        try {
            currentTimeout = timer.newTimeout(new EpochBoundaryTask(currentEpochNum), delay, TimeUnit.MILLISECONDS);
        } catch (IllegalStateException e) {
            // Timer was stopped between running check and newTimeout call
            log.debug("Timer stopped during scheduling, ignoring");
        }
    }

    /**
     * Task that handles epoch boundary transition.
     */
    private class EpochBoundaryTask implements TimerTask {
        private final long expectedEpochNum;

        EpochBoundaryTask(long expectedEpochNum) {
            this.expectedEpochNum = expectedEpochNum;
        }

        @Override
        public void run(Timeout timeout) {
            if (!running || timeout.isCancelled()) {
                return;
            }

            try {
                handleEpochTransition();
            } catch (Exception e) {
                log.error("Error handling epoch transition", e);
            } finally {
                // Always schedule for next epoch
                scheduleForNextEpoch();
            }
        }

        private void handleEpochTransition() {
            long currentEpochNum = TimeUtils.getCurrentEpochNumber();

            // If we're still in the expected epoch, wait for transition
            if (currentEpochNum == expectedEpochNum) {
                currentEpochNum = waitForEpochTransition(expectedEpochNum);
            }

            // Now process the transition
            if (currentEpochNum != lastEpochNumber) {
                processEpochTransition(currentEpochNum);
            }
        }

        /**
         * Wait briefly for epoch transition (handles timer precision issues).
         */
        private long waitForEpochTransition(long expectedEpoch) {
            long startWait = System.currentTimeMillis();
            long currentEpoch = expectedEpoch;

            while (currentEpoch == expectedEpoch && running) {
                long elapsed = System.currentTimeMillis() - startWait;
                if (elapsed > MAX_WAIT_FOR_TRANSITION_MS) {
                    log.warn("Timeout waiting for epoch {} transition after {}ms",
                            expectedEpoch, elapsed);
                    break;
                }

                // Brief sleep then re-check
                try {
                    Thread.sleep(TRANSITION_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                currentEpoch = TimeUtils.getCurrentEpochNumber();
            }

            return currentEpoch;
        }
    }

    /**
     * Process epoch transition, handling any skipped epochs.
     */
    private void processEpochTransition(long currentEpochNumber) {
        long endedEpoch = lastEpochNumber;
        long skippedCount = currentEpochNumber - lastEpochNumber - 1;

        if (skippedCount > 0) {
            // Multiple epochs passed (rare - happens during long GC pause or system hibernation)
            log.warn("Detected {} skipped epoch(s): {} -> {}",
                    skippedCount, lastEpochNumber, currentEpochNumber);

            // Process all epochs that ended
            for (long epoch = endedEpoch; epoch < currentEpochNumber; epoch++) {
                log.debug("Processing epoch {} end{}", epoch,
                        epoch > endedEpoch ? " (late)" : "");
                invokeCallback(epoch);
            }
        } else {
            // Normal case: single epoch transition
            log.debug("Epoch {} ended, now in epoch {}", endedEpoch, currentEpochNumber);
            invokeCallback(endedEpoch);
        }

        lastEpochNumber = currentEpochNumber;
    }

    /**
     * Invoke the epoch end callback safely.
     */
    private void invokeCallback(long epoch) {
        try {
            onEpochEnd.accept(epoch);
        } catch (Exception e) {
            log.error("Error in epoch {} end callback", epoch, e);
        }
    }

    /**
     * Stop the epoch timer.
     */
    public void stop() {
        if (!running) {
            return;
        }

        log.info("Stopping EpochTimer...");
        running = false;

        // Cancel pending timeout
        if (currentTimeout != null) {
            currentTimeout.cancel();
            currentTimeout = null;
        }

        // Stop the timer
        if (timer != null) {
            timer.stop();
            timer = null;
        }

        log.info("EpochTimer stopped");
    }

    /**
     * Get the current epoch number using XDAG time system.
     *
     * @return Current epoch number (64-second period)
     */
    public long getCurrentEpoch() {
        return TimeUtils.getCurrentEpochNumber();
    }

    /**
     * Get the last processed epoch number.
     *
     * @return Last epoch number that was processed, or -1 if not started
     */
    public long getLastProcessedEpoch() {
        return lastEpochNumber;
    }

    /**
     * Get the time remaining until the current epoch ends.
     *
     * @return Approximate time in milliseconds until epoch end
     */
    public long getTimeUntilEpochEnd() {
        long now = System.currentTimeMillis();
        long currentEpochNum = TimeUtils.getCurrentEpochNumber();
        long epochEndMs = TimeUtils.epochNumberToTimeMillis(currentEpochNum);
        return Math.max(0, epochEndMs - now);
    }

    /**
     * Get the start time of the current epoch.
     *
     * @return Epoch start time in milliseconds (approximate)
     */
    public long getCurrentEpochStartTime() {
        long currentEpochNum = TimeUtils.getCurrentEpochNumber();
        long xdagEpochStart = TimeUtils.epochNumberToEpoch(currentEpochNum);
        return TimeUtils.epochToTimeMillis(xdagEpochStart);
    }

    /**
     * Get the end time of the current epoch.
     *
     * @return Epoch end time in milliseconds (approximate)
     */
    public long getCurrentEpochEndTime() {
        long currentEpochNum = TimeUtils.getCurrentEpochNumber();
        return TimeUtils.epochNumberToTimeMillis(currentEpochNum);
    }

    /**
     * Get the epoch duration in milliseconds.
     *
     * <p>This is approximately 64,000ms but calculated from XDAG time system.
     *
     * @return Epoch duration in milliseconds
     */
    public static long getEpochDurationMs() {
        long currentEpochNum = TimeUtils.getCurrentEpochNumber();
        long currentEpochEndMs = TimeUtils.epochNumberToTimeMillis(currentEpochNum);
        long previousEpochEndMs = TimeUtils.epochNumberToTimeMillis(currentEpochNum - 1);
        return currentEpochEndMs - previousEpochEndMs;
    }

    /**
     * Get the configured tick duration.
     *
     * @return Tick duration in milliseconds
     */
    public long getTickDurationMs() {
        return tickDurationMs;
    }

    /**
     * Get check interval (for backward compatibility).
     * Returns tick duration since we no longer use polling.
     *
     * @return Tick duration in milliseconds
     */
    public long getCheckIntervalMs() {
        return tickDurationMs;
    }
}
