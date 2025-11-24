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

import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.core.DagImportResult;
import org.apache.tuweni.units.bigints.UInt256;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EpochConsensusManager - Central coordinator for epoch-based consensus.
 *
 * <p>This manager implements the unified fix for BUG-CONSENSUS-001 and BUG-CONSENSUS-002:
 * <ul>
 *   <li><b>BUG-001 Fix</b>: Backup mining ensures every epoch produces a block</li>
 *   <li><b>BUG-002 Fix</b>: Solution collection enables "best solution wins" at epoch end</li>
 * </ul>
 *
 * <p>Architecture:
 * <pre>
 * EpochConsensusManager
 * ├── EpochTimer (precise 64-second boundaries)
 * ├── SolutionCollector (collect multiple solutions per epoch)
 * ├── BestSolutionSelector (select highest difficulty)
 * └── BackupMiner (force block if no solutions)
 * </pre>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Epoch N starts at T=0s</li>
 *   <li>Create EpochContext for epoch N</li>
 *   <li>Collect solutions from T=0s to T=64s</li>
 *   <li>If no solutions by T=59s, trigger BackupMiner</li>
 *   <li>At T=64s, select best solution and import to blockchain</li>
 *   <li>Epoch N+1 starts at T=64s</li>
 * </ol>
 *
 * @see EpochTimer
 * @see SolutionCollector
 * @see BestSolutionSelector
 * @see BackupMiner
 */
@Slf4j
public class EpochConsensusManager {

    // ========== Configuration ==========

    /**
     * Epoch duration in milliseconds (64 seconds).
     */
    private static final long EPOCH_DURATION_MS = 64_000L;

    /**
     * Trigger backup mining when this much time remains in epoch (5 seconds).
     */
    private static final long FORCED_MINING_THRESHOLD_MS = 5_000L;

    // ========== State ==========

    /**
     * Current epoch number.
     */
    private final AtomicLong currentEpoch;

    /**
     * Epoch contexts map (epoch number → context).
     * Shared with SolutionCollector.
     */
    private final ConcurrentHashMap<Long, EpochContext> epochContexts;

    /**
     * Flag indicating if the manager is running.
     */
    private volatile boolean running;

    // ========== Dependencies ==========

    /**
     * DagChain for block import.
     */
    private final DagChain dagChain;

    /**
     * Minimum difficulty requirement for solutions.
     */
    private final UInt256 minimumDifficulty;

    // ========== Sub-components ==========

    /**
     * Epoch timer for 64-second boundary triggers.
     */
    private final EpochTimer epochTimer;

    /**
     * Solution collector for gathering candidate blocks.
     */
    private final SolutionCollector solutionCollector;

    /**
     * Best solution selector for choosing winner.
     */
    private final BestSolutionSelector bestSolutionSelector;

    /**
     * Backup miner for forced block generation.
     */
    private final BackupMiner backupMiner;

    /**
     * Scheduler for backup miner trigger.
     */
    private final ScheduledExecutorService backupMinerScheduler;

    /**
     * Create a new EpochConsensusManager.
     *
     * @param dagChain            DagChain instance
     * @param backupMiningThreads Number of threads for backup mining
     * @param minimumDifficulty   Minimum difficulty for solutions
     */
    public EpochConsensusManager(DagChain dagChain, int backupMiningThreads, UInt256 minimumDifficulty) {
        this.dagChain = dagChain;
        this.minimumDifficulty = minimumDifficulty;

        this.currentEpoch = new AtomicLong(0);
        this.epochContexts = new ConcurrentHashMap<>();
        this.running = false;

        // Initialize sub-components
        this.epochTimer = new EpochTimer();
        this.solutionCollector = new SolutionCollector(minimumDifficulty, epochContexts);
        this.bestSolutionSelector = new BestSolutionSelector();
        this.backupMiner = new BackupMiner(backupMiningThreads);
        this.backupMinerScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BackupMinerScheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the epoch consensus manager.
     */
    public void start() {
        if (running) {
            log.warn("EpochConsensusManager already running");
            return;
        }

        log.info("Starting EpochConsensusManager...");

        // Initialize current epoch
        long epoch = getCurrentEpoch();
        currentEpoch.set(epoch);

        // Create context for current epoch
        createEpochContext(epoch);

        // Start epoch timer (triggers onEpochEnd at 64-second boundaries)
        epochTimer.start(this::onEpochEnd);

        // Schedule backup miner trigger for current epoch
        scheduleBackupMinerTrigger(epoch);

        running = true;
        log.info("✓ EpochConsensusManager started (epoch={}, duration={}ms, backup_threads={})",
                epoch, EPOCH_DURATION_MS, backupMiner.getMiningThreads());
    }

    /**
     * Stop the epoch consensus manager.
     */
    public void stop() {
        if (!running) {
            log.warn("EpochConsensusManager not running");
            return;
        }

        log.info("Stopping EpochConsensusManager...");

        epochTimer.stop();
        backupMiner.stop();
        backupMinerScheduler.shutdown();

        running = false;
        log.info("✓ EpochConsensusManager stopped");
    }

    /**
     * Submit a solution for the current epoch.
     *
     * <p>Called by MiningApiService when a pool submits a mined block.
     *
     * @param block   The candidate block
     * @param poolId  Pool/miner identifier
     * @return SubmitResult indicating acceptance or rejection
     */
    public SubmitResult submitSolution(Block block, String poolId) {
        if (!running) {
            return SubmitResult.rejected("EpochConsensusManager not running");
        }

        long current = getCurrentEpoch();
        return solutionCollector.submitSolution(block, poolId, current);
    }

    /**
     * Get the current epoch number.
     *
     * @return Current epoch (based on system time)
     */
    public long getCurrentEpoch() {
        return System.currentTimeMillis() / EPOCH_DURATION_MS;
    }

    // ========== Private Methods ==========

    /**
     * Create an epoch context for the given epoch.
     *
     * @param epoch Epoch number
     */
    private void createEpochContext(long epoch) {
        long epochStartTime = epoch * EPOCH_DURATION_MS;
        long epochEndTime = epochStartTime + EPOCH_DURATION_MS;

        EpochContext context = new EpochContext(
                epoch,
                epochStartTime,
                epochEndTime,
                null  // candidateBlock will be set later if needed
        );

        epochContexts.put(epoch, context);
        log.debug("Created epoch context: {}", context);
    }

    /**
     * Schedule backup miner trigger for an epoch.
     *
     * <p>Backup miner is triggered at T=59s (5 seconds before epoch end)
     * if no solutions have been collected.
     *
     * @param epoch Epoch number
     */
    private void scheduleBackupMinerTrigger(long epoch) {
        long epochEndTime = (epoch + 1) * EPOCH_DURATION_MS;
        long triggerTime = epochEndTime - FORCED_MINING_THRESHOLD_MS;
        long delay = triggerTime - System.currentTimeMillis();

        if (delay > 0) {
            backupMinerScheduler.schedule(
                    () -> triggerBackupMinerIfNeeded(epoch),
                    delay,
                    TimeUnit.MILLISECONDS
            );
            log.debug("Scheduled backup miner trigger for epoch {} in {}ms", epoch, delay);
        }
    }

    /**
     * Trigger backup miner if no solutions have been collected.
     *
     * @param epoch Epoch number
     */
    private void triggerBackupMinerIfNeeded(long epoch) {
        EpochContext context = epochContexts.get(epoch);
        if (context == null) {
            log.warn("Cannot trigger backup miner: epoch context not found for epoch {}", epoch);
            return;
        }

        if (context.getSolutionsCount() == 0 && !context.isBlockProduced()) {
            log.warn("⚠ No solutions for epoch {}, triggering backup miner", epoch);
            backupMiner.startBackupMining(context);
        } else {
            log.debug("Backup miner not needed for epoch {}: {} solutions already collected",
                    epoch, context.getSolutionsCount());
        }
    }

    /**
     * Handle epoch end event.
     *
     * <p>This method is called by EpochTimer at precise 64-second boundaries.
     *
     * @param epoch The epoch that just ended
     */
    private void onEpochEnd(long epoch) {
        log.info("═══════════ Processing Epoch {} End ═══════════", epoch);

        // 1. Get epoch context
        EpochContext context = epochContexts.remove(epoch);
        if (context == null) {
            log.error("✗ Epoch context not found for epoch {}", epoch);
            // Create context for next epoch
            createEpochContext(epoch + 1);
            scheduleBackupMinerTrigger(epoch + 1);
            return;
        }

        // 2. Check if block already produced
        if (context.isBlockProduced()) {
            log.info("✓ Block already produced for epoch {}, skipping", epoch);
            // Create context for next epoch
            createEpochContext(epoch + 1);
            scheduleBackupMinerTrigger(epoch + 1);
            return;
        }

        // 3. Get all solutions
        List<BlockSolution> solutions = context.getSolutions();

        // 4. Wait briefly for backup miner if no solutions yet
        if (solutions.isEmpty()) {
            log.warn("⚠ No solutions collected for epoch {}, waiting for backup miner", epoch);
            waitForBackupMiner(context);
            solutions = context.getSolutions();

            if (solutions.isEmpty()) {
                log.error("✗ No backup solution for epoch {}, skipping block generation", epoch);
                // Create context for next epoch
                createEpochContext(epoch + 1);
                scheduleBackupMinerTrigger(epoch + 1);
                return;
            }
        }

        // 5. Select best solution
        BlockSolution bestSolution = bestSolutionSelector.selectBest(solutions);
        if (bestSolution == null) {
            log.error("✗ Failed to select best solution for epoch {}", epoch);
            // Create context for next epoch
            createEpochContext(epoch + 1);
            scheduleBackupMinerTrigger(epoch + 1);
            return;
        }

        // Log selection details
        bestSolutionSelector.logSelection(epoch, solutions, bestSolution);

        // 6. Import best solution
        Block blockToImport = bestSolution.getBlock();
        log.info("Importing block for epoch {}: hash={}, pool='{}'",
                epoch,
                blockToImport.getHash().toHexString().substring(0, 16) + "...",
                bestSolution.getPoolId());

        DagImportResult importResult = dagChain.tryToConnect(blockToImport);

        if (importResult.isSuccess()) {
            context.markBlockProduced();
            log.info("✓ Epoch {} block imported successfully", epoch);
        } else {
            log.error("✗ Failed to import epoch {} block: {}", epoch, importResult.getErrorMessage());
        }

        // 7. Create context for next epoch
        createEpochContext(epoch + 1);
        scheduleBackupMinerTrigger(epoch + 1);
    }

    /**
     * Wait for backup miner to complete.
     *
     * @param context Epoch context
     */
    private void waitForBackupMiner(EpochContext context) {
        long timeout = 2000;  // Wait up to 2 seconds
        long start = System.currentTimeMillis();

        while (context.getSolutions().isEmpty() &&
                System.currentTimeMillis() - start < timeout) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (context.getSolutions().isEmpty()) {
            log.warn("Backup miner did not produce solution within timeout");
        }
    }

    /**
     * Check if the manager is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }
}
