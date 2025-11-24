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

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * EpochTimer - Manages epoch timing with precise 64-second boundary alignment.
 *
 * <p>This timer ensures that epochs are triggered exactly at 64-second boundaries
 * (T=0s, T=64s, T=128s, etc.), matching the original XDAG consensus mechanism.
 *
 * <p>Key features:
 * <ul>
 *   <li>Precise epoch boundary alignment using scheduleAtFixedRate</li>
 *   <li>Calculates initial delay to sync with next epoch boundary</li>
 *   <li>Provides utilities to query current epoch and remaining time</li>
 * </ul>
 *
 * <p>Part of BUG-CONSENSUS-001 and BUG-CONSENSUS-002 unified fix.
 *
 * @see EpochConsensusManager
 */
@Slf4j
public class EpochTimer {

    /**
     * Epoch duration in milliseconds (64 seconds = 64,000 ms).
     */
    private static final long EPOCH_DURATION_MS = 64_000L;

    /**
     * Scheduler for epoch boundary triggers.
     */
    private final ScheduledExecutorService epochScheduler;

    /**
     * Flag indicating if the timer is running.
     */
    private volatile boolean running;

    public EpochTimer() {
        this.epochScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EpochTimer");
            t.setDaemon(true);
            return t;
        });
        this.running = false;
    }

    /**
     * Start the epoch timer with the given callback.
     *
     * <p>The timer will trigger the callback at every epoch boundary (every 64 seconds).
     * The first trigger is scheduled at the next epoch boundary to ensure alignment.
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

        // Calculate the next epoch boundary
        long now = System.currentTimeMillis();
        long epochStart = (now / EPOCH_DURATION_MS) * EPOCH_DURATION_MS;
        long nextEpochStart = epochStart + EPOCH_DURATION_MS;
        long initialDelay = nextEpochStart - now;

        log.info("EpochTimer starting: current_time={}ms, next_epoch_boundary={}ms, initial_delay={}ms",
                now, nextEpochStart, initialDelay);

        // Schedule at fixed rate to maintain epoch boundary alignment
        epochScheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        long epoch = getCurrentEpoch();
                        log.info("═══════════ Epoch {} ended ═══════════", epoch);
                        onEpochEnd.accept(epoch);
                    } catch (Exception e) {
                        log.error("Error processing epoch end", e);
                    }
                },
                initialDelay,
                EPOCH_DURATION_MS,
                TimeUnit.MILLISECONDS
        );

        running = true;
        log.info("✓ EpochTimer started (epoch_duration={}ms, initial_delay={}ms)",
                EPOCH_DURATION_MS, initialDelay);
    }

    /**
     * Stop the epoch timer.
     */
    public void stop() {
        if (!running) {
            log.warn("EpochTimer is not running");
            return;
        }

        log.info("Stopping EpochTimer...");
        epochScheduler.shutdown();
        try {
            if (!epochScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                epochScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            epochScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        running = false;
        log.info("✓ EpochTimer stopped");
    }

    /**
     * Get the current epoch number.
     *
     * <p>Epoch number is calculated as: current_time_ms / EPOCH_DURATION_MS
     *
     * @return Current epoch number
     */
    public long getCurrentEpoch() {
        return System.currentTimeMillis() / EPOCH_DURATION_MS;
    }

    /**
     * Get the time remaining until the current epoch ends.
     *
     * @return Time in milliseconds until epoch end
     */
    public long getTimeUntilEpochEnd() {
        long now = System.currentTimeMillis();
        long epochStart = (now / EPOCH_DURATION_MS) * EPOCH_DURATION_MS;
        long epochEnd = epochStart + EPOCH_DURATION_MS;
        return epochEnd - now;
    }

    /**
     * Get the start time of the current epoch.
     *
     * @return Epoch start time in milliseconds
     */
    public long getCurrentEpochStartTime() {
        long now = System.currentTimeMillis();
        return (now / EPOCH_DURATION_MS) * EPOCH_DURATION_MS;
    }

    /**
     * Get the end time of the current epoch.
     *
     * @return Epoch end time in milliseconds
     */
    public long getCurrentEpochEndTime() {
        return getCurrentEpochStartTime() + EPOCH_DURATION_MS;
    }

    /**
     * Check if the timer is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the epoch duration in milliseconds.
     *
     * @return Epoch duration (64,000 ms)
     */
    public static long getEpochDurationMs() {
        return EPOCH_DURATION_MS;
    }
}
