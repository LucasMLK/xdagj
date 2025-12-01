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
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * EpochTimer - Manages epoch timing with precise 64-second boundary alignment using XDAG time system.
 *
 * <p>This timer ensures that epochs are triggered exactly at 64-second boundaries
 * matching the XDAG consensus mechanism. It uses {@link TimeUtils} to properly
 * handle XDAG's unique time representation (1/1024 second precision).
 *
 * <p><b>XDAG Time System:</b>
 * <ul>
 *   <li>XDAG epoch: 1/1024 second precision (not milliseconds)</li>
 *   <li>Epoch number: 64-second period number (epoch >> 16)</li>
 *   <li>64 seconds = 65536 XDAG epoch units (2^16)</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Uses TimeUtils for XDAG-compliant epoch calculations</li>
 *   <li>Precise epoch boundary alignment using scheduleAtFixedRate</li>
 *   <li>Calculates initial delay to sync with next epoch boundary</li>
 *   <li>Recreates scheduler on each start for thread pool reuse safety</li>
 * </ul>
 *
 * <p>Part of BUG-CONSENSUS-001 and BUG-CONSENSUS-002 unified fix.
 *
 * @see EpochConsensusManager
 * @see TimeUtils
 */
@Slf4j
public class EpochTimer {

    /**
     * Scheduler for epoch boundary triggers.
     * Recreated on each start() to avoid RejectedExecutionException.
     */
    private ScheduledExecutorService epochScheduler;

    /**
     * Flag indicating if the timer is running.
     * -- GETTER --
     *  Check if the timer is running.
     *
     * @return true if running, false otherwise

     */
    @Getter
    private volatile boolean running;

    public EpochTimer() {
        this.running = false;
    }

    /**
     * Start the epoch timer with the given callback.
     *
     * <p>The timer will trigger the callback at every epoch boundary (every 64 seconds).
     * The first trigger is scheduled at the next epoch boundary to ensure alignment.
     *
     * <p>Uses XDAG time system via {@link TimeUtils} to calculate precise epoch boundaries.
     *
     * @param onEpochEnd Callback to invoke when an epoch ends. Receives the epoch number.
     * @throws NullPointerException if onEpochEnd is null
     */
    public void start(Consumer<Long> onEpochEnd) {
        if (onEpochEnd == null) {
            throw new NullPointerException("Epoch callback cannot be null");
        }

        if (running) {
            log.warn("EpochTimer is already running");
            return;
        }

        // Create new scheduler to avoid RejectedExecutionException from reused terminated pool
        this.epochScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EpochTimer");
            t.setDaemon(true);
            return t;
        });

        // Calculate the next epoch boundary using XDAG time system
        long now = System.currentTimeMillis();
        long currentEpochNum = TimeUtils.getCurrentEpochNumber();

        // Get current epoch boundary in milliseconds
        long currentEpochEndMs = TimeUtils.epochNumberToTimeMillis(currentEpochNum);

        // Calculate initial delay to current epoch end
        long initialDelay = currentEpochEndMs - now;

        // If current epoch already ended, move to next epoch
        long targetEpochNum = currentEpochNum;
        if (initialDelay <= 0) {
            targetEpochNum = currentEpochNum + 1;
            long nextEpochEndMs = TimeUtils.epochNumberToTimeMillis(targetEpochNum);
            initialDelay = nextEpochEndMs - now;
            log.info("EpochTimer: Current epoch {} already ended, targeting next epoch {}",
                    currentEpochNum, targetEpochNum);
        }

        // Calculate epoch duration (should be ~64000ms)
        long epochDurationMs = TimeUtils.epochNumberToTimeMillis(targetEpochNum) -
                               TimeUtils.epochNumberToTimeMillis(targetEpochNum - 1);

        log.info("EpochTimer starting: current_epoch={}, target_epoch={}, target_end={}ms, initial_delay={}ms, epoch_duration={}ms",
                currentEpochNum, targetEpochNum, TimeUtils.epochNumberToTimeMillis(targetEpochNum),
                initialDelay, epochDurationMs);

        // Track last processed epoch to detect skipped epochs
        final long[] lastProcessedEpoch = {currentEpochNum - 1};

        // Schedule at fixed rate to maintain epoch boundary alignment
        epochScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        // BUG FIX (BUG-CONSENSUS-006): Recalculate actual epoch on every trigger
                        // to detect timer drift/pause (e.g., GC pauses, thread starvation)
                        long actualCurrentEpoch = getCurrentEpoch();
                        long endedEpochNum = actualCurrentEpoch - 1;

                        // Detect skipped epochs (timer was paused/delayed)
                        long expectedEpoch = lastProcessedEpoch[0] + 1;
                        if (endedEpochNum > expectedEpoch) {
                            long skippedCount = endedEpochNum - expectedEpoch;
                            log.error("⚠️ EPOCH TIMER DRIFT DETECTED! Expected epoch {}, but actual is {}. Skipped {} epochs!",
                                    expectedEpoch, endedEpochNum, skippedCount);
                            log.error("This can happen due to: JVM GC pause, thread starvation, or system hibernation");

                            // Process all skipped epochs to clean up their contexts
                            for (long skipped = expectedEpoch; skipped < endedEpochNum; skipped++) {
                                log.warn("Processing skipped epoch {} (late processing)", skipped);
                                onEpochEnd.accept(skipped);
                            }
                        } else if (endedEpochNum < expectedEpoch) {
                            // Timer fired early (rare, but possible with system clock adjustments)
                            log.warn("⚠️ Timer fired early! Expected epoch {}, but actual is {}",
                                    expectedEpoch, endedEpochNum);
                            return; // Skip this trigger
                        }

                        log.info("═══════════ Epoch {} ended ═══════════", endedEpochNum);
                        onEpochEnd.accept(endedEpochNum);
                        lastProcessedEpoch[0] = endedEpochNum;

                    } catch (Exception e) {
                        log.error("Error processing epoch end", e);
                    }
                },
                initialDelay,
                epochDurationMs,
                TimeUnit.MILLISECONDS
        );

        running = true;
        log.info("✓ EpochTimer started (epoch_duration={}ms, initial_delay={}ms)",
                epochDurationMs, initialDelay);
    }

    /**
     * Stop the epoch timer.
     */
    public void stop() {
        if (!running) {
            return;
        }

        log.info("Stopping EpochTimer...");
        if (epochScheduler != null) {
            epochScheduler.shutdown();
            try {
                if (!epochScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    epochScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                epochScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        running = false;
        log.info("✓ EpochTimer stopped");
    }

    /**
     * Get the current epoch number using XDAG time system.
     *
     * <p>Uses {@link TimeUtils#getCurrentEpochNumber()} for XDAG-compliant calculation.
     *
     * @return Current epoch number (64-second period)
     */
    public long getCurrentEpoch() {
        return TimeUtils.getCurrentEpochNumber();
    }

    /**
     * Get the time remaining until the current epoch ends.
     *
     * <p>Uses XDAG time system to calculate epoch boundaries.
     *
     * @return Time in milliseconds until epoch end
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
     * <p>Uses XDAG time system: start of epoch = epoch_number << 16 (converted to milliseconds).
     *
     * @return Epoch start time in milliseconds
     */
    public long getCurrentEpochStartTime() {
        long currentEpochNum = TimeUtils.getCurrentEpochNumber();
        long xdagEpochStart = TimeUtils.epochNumberToEpoch(currentEpochNum);
        return TimeUtils.epochToTimeMillis(xdagEpochStart);
    }

    /**
     * Get the end time of the current epoch.
     *
     * <p>Uses XDAG time system: end of epoch = (epoch_number << 16) | 0xffff (converted to milliseconds).
     *
     * @return Epoch end time in milliseconds
     */
    public long getCurrentEpochEndTime() {
        long currentEpochNum = TimeUtils.getCurrentEpochNumber();
        return TimeUtils.epochNumberToTimeMillis(currentEpochNum);
    }

  /**
     * Get the epoch duration in milliseconds.
     *
     * <p>Calculates the actual duration dynamically from XDAG time system.
     * Should be approximately 64,000ms, but calculated precisely each time.
     *
     * @return Epoch duration in milliseconds (approximately 64,000ms)
     */
    public static long getEpochDurationMs() {
        // Calculate epoch duration from XDAG time system
        // Epoch N end - Epoch N-1 end = one epoch duration
        long currentEpochNum = TimeUtils.getCurrentEpochNumber();
        long currentEpochEndMs = TimeUtils.epochNumberToTimeMillis(currentEpochNum);
        long previousEpochEndMs = TimeUtils.epochNumberToTimeMillis(currentEpochNum - 1);
        return currentEpochEndMs - previousEpochEndMs;
    }
}
