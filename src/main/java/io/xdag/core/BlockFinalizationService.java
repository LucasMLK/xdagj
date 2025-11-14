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

package io.xdag.core;

import io.xdag.DagKernel;
import io.xdag.db.DagStore;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for automatically finalizing old blocks
 *
 * <p><strong>STATUS: Work In Progress (Phase 2 Feature)</strong>
 *
 * <p>This service is part of a future storage optimization strategy where
 * blocks older than FINALIZATION_THRESHOLD epochs are considered finalized
 * and migrated to a specialized storage layer.
 *
 * <p><strong>Current Status:</strong>
 * <ul>
 *   <li>Core finalization logic: ✅ Implemented</li>
 *   <li>FinalizedBlockStore: ❌ Not yet implemented</li>
 *   <li>Service status: ⏸️ Temporarily disabled</li>
 * </ul>
 *
 * <p><strong>Design Goals:</strong>
 * <ul>
 *   <li>Optimize storage for blocks older than ~12 days (16384 epochs)</li>
 *   <li>Reduce active storage pressure</li>
 *   <li>Maintain fast access to recent blocks</li>
 * </ul>
 *
 * <p><strong>When Enabled:</strong>
 * This service will run periodic checks (every 60 minutes) to migrate
 * finalized blocks to optimized storage.
 *
 * @since XDAGJ 5.0 (Phase 2 - Planned)
 */
@Slf4j
public class BlockFinalizationService {

    /**
     * Finalization threshold in epochs (~12 days = 16384 epochs * 64 seconds)
     */
    public static final long FINALIZATION_THRESHOLD_EPOCHS = 16384;

    /**
     * Batch size for block migration (to avoid memory pressure)
     */
    private static final int MIGRATION_BATCH_SIZE = 1000;

    /**
     * Interval between finalization checks (in minutes)
     */
    private static final long CHECK_INTERVAL_MINUTES = 60;

    private final DagKernel kernel;
    private final DagStore dagStore;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicLong lastFinalizedEpoch = new AtomicLong(-1);
    private final AtomicLong totalFinalizedBlocks = new AtomicLong(0);

    public BlockFinalizationService(DagKernel kernel) {
        this.kernel = kernel;
        this.dagStore = kernel.getDagStore();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "BlockFinalizationService");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Start the finalization service
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            log.info("Starting BlockFinalizationService...");
            log.info("Finalization threshold: {} epochs (~{} days)",
                    FINALIZATION_THRESHOLD_EPOCHS,
                    FINALIZATION_THRESHOLD_EPOCHS * 64 / (24 * 3600));

            // Schedule periodic finalization checks
            scheduler.scheduleWithFixedDelay(
                    this::finalizeOldBlocks,
                    1, // Initial delay: 1 minute after startup
                    CHECK_INTERVAL_MINUTES,
                    TimeUnit.MINUTES
            );

            log.info("BlockFinalizationService started (check interval: {} minutes)", CHECK_INTERVAL_MINUTES);
        }
    }

    /**
     * Stop the finalization service
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            log.info("Stopping BlockFinalizationService...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("BlockFinalizationService stopped. Total blocks finalized: {}", totalFinalizedBlocks.get());
        }
    }

    // TODO: Phase 2 - Implement FinalizedBlockStore and enable block migration
    /*
    /**
     * Finalize blocks older than the threshold
     * <p>
     * This method will be called periodically to migrate old blocks from BlockStore
     * to a specialized FinalizedBlockStore (to be implemented).
     */
    /*
    private void finalizeOldBlocks() {
        if (!isRunning.get()) {
            return;
        }

        try {
            long currentEpoch = XdagTime.getCurrentEpoch();
            long finalizeThresholdEpoch = currentEpoch - FINALIZATION_THRESHOLD_EPOCHS;

            if (finalizeThresholdEpoch <= 0) {
                log.debug("Current epoch {} is too early for finalization", currentEpoch);
                return;
            }

            log.info("Starting finalization check (current epoch: {}, threshold: {})",
                    currentEpoch, finalizeThresholdEpoch);

            // Get the epoch to finalize
            // We start from the last finalized epoch + 1, or from epoch 0 if this is the first run
            long startEpoch = lastFinalizedEpoch.get() + 1;
            if (startEpoch < 0) {
                startEpoch = 0;
            }

            long endEpoch = finalizeThresholdEpoch;

            if (startEpoch >= endEpoch) {
                log.debug("No epochs to finalize (start: {}, end: {})", startEpoch, endEpoch);
                return;
            }

            log.info("Finalizing epochs {} to {}", startEpoch, endEpoch);

            long finalizedCount = 0;
            long skippedCount = 0;

            // Process epochs in batches to avoid memory pressure
            for (long epochBatch = startEpoch; epochBatch < endEpoch; epochBatch += 100) {
                long batchEnd = Math.min(epochBatch + 100, endEpoch);

                // Get blocks in this epoch range
                long startTime = epochBatch * 64;
                long endTime = batchEnd * 64;

                List<Block> blocks = blockStore.getBlocksUsedTime(startTime, endTime);

                if (blocks == null || blocks.isEmpty()) {
                    continue;
                }

                log.info("Processing {} blocks from epochs {} to {}", blocks.size(), epochBatch, batchEnd);

                // Migrate blocks in batches
                for (int i = 0; i < blocks.size(); i += MIGRATION_BATCH_SIZE) {
                    int batchEndIdx = Math.min(i + MIGRATION_BATCH_SIZE, blocks.size());
                    List<Block> batch = blocks.subList(i, batchEndIdx);

                    for (Block block : batch) {
                        try {
                            // Check if block already finalized
                            if (finalizedBlockStore.hasBlock(block.getHash())) {
                                skippedCount++;
                                continue;
                            }

                            // Save to finalized store
                            finalizedBlockStore.saveBlock(block);
                            finalizedCount++;

                            // TODO: Optionally delete from active BlockStore to save space
                            // This requires careful consideration of:
                            // 1. Recent block access patterns
                            // 2. Rollback scenarios
                            // 3. Node restart recovery
                            // For now, we keep blocks in both stores for safety

                        } catch (Exception e) {
                            log.error("Failed to finalize block {}: {}",
                                    block.getHash(), e.getMessage(), e);
                        }
                    }
                }

                lastFinalizedEpoch.set(batchEnd - 1);
            }

            totalFinalizedBlocks.addAndGet(finalizedCount);

            log.info("Finalization completed. Finalized: {}, Skipped (already finalized): {}, Total finalized so far: {}",
                    finalizedCount, skippedCount, totalFinalizedBlocks.get());

        } catch (Exception e) {
            log.error("Error during block finalization", e);
        }
    }
    */

    /**
     * Stub method - Block finalization temporarily disabled
     *
     * <p>Waiting for FinalizedBlockStore implementation (Phase 2).
     */
    private void finalizeOldBlocks() {
        log.debug("Block finalization temporarily disabled - waiting for FinalizedBlockStore implementation (Phase 2)");
    }

    /**
     * Manually trigger finalization (for testing or administrative purposes)
     *
     * @return Number of blocks finalized
     */
    public long manualFinalize() {
        long beforeCount = totalFinalizedBlocks.get();
        finalizeOldBlocks();
        return totalFinalizedBlocks.get() - beforeCount;
    }

    /**
     * Get finalization statistics
     *
     * @return Statistics string
     */
    public String getStatistics() {
        return String.format("""
                Block Finalization Service Statistics:
                ====================================
                Running:                %s
                Last finalized epoch:   %d
                Total blocks finalized: %d
                Finalization threshold: %d epochs (~%.1f days)
                Check interval:         %d minutes
                """,
                isRunning.get(),
                lastFinalizedEpoch.get(),
                totalFinalizedBlocks.get(),
                FINALIZATION_THRESHOLD_EPOCHS,
                FINALIZATION_THRESHOLD_EPOCHS * 64.0 / (24 * 3600),
                CHECK_INTERVAL_MINUTES
        );
    }

    /**
     * Check if service is running
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * Get total number of blocks finalized by this service
     */
    public long getTotalFinalizedBlocks() {
        return totalFinalizedBlocks.get();
    }

    /**
     * Get the last finalized epoch
     */
    public long getLastFinalizedEpoch() {
        return lastFinalizedEpoch.get();
    }
}
