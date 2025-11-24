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
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * EpochContext - Maintains state for a single epoch.
 *
 * <p>Each epoch has its own context that tracks:
 * <ul>
 *   <li>Epoch number and time boundaries</li>
 *   <li>Candidate block template</li>
 *   <li>Collected solutions from pools/miners</li>
 *   <li>Status flags (block produced, backup miner started)</li>
 * </ul>
 *
 * <p>The context is created at epoch start and cleaned up after block import.
 *
 * <p>Thread-safe for concurrent solution submission.
 *
 * @see EpochConsensusManager
 * @see SolutionCollector
 */
@Getter
public class EpochContext {

    /**
     * Epoch number.
     */
    private final long epochNumber;

    /**
     * Epoch start time (milliseconds since Unix epoch).
     */
    private final long epochStartTime;

    /**
     * Epoch end time (milliseconds since Unix epoch).
     */
    private final long epochEndTime;

    /**
     * Candidate block template for this epoch.
     * May be null if not yet created.
     */
    private final Block candidateBlock;

    /**
     * List of collected solutions.
     * Thread-safe for concurrent adds.
     */
    private final List<BlockSolution> solutions;

    /**
     * Whether a block has been produced for this epoch.
     */
    private final AtomicBoolean blockProduced;

    /**
     * Whether backup miner has been started for this epoch.
     */
    private final AtomicBoolean backupMinerStarted;

    /**
     * Create a new EpochContext.
     *
     * @param epochNumber     Epoch number
     * @param epochStartTime  Epoch start time (ms)
     * @param epochEndTime    Epoch end time (ms)
     * @param candidateBlock  Candidate block template (may be null)
     */
    public EpochContext(long epochNumber, long epochStartTime, long epochEndTime, Block candidateBlock) {
        this.epochNumber = epochNumber;
        this.epochStartTime = epochStartTime;
        this.epochEndTime = epochEndTime;
        this.candidateBlock = candidateBlock;
        this.solutions = new CopyOnWriteArrayList<>();
        this.blockProduced = new AtomicBoolean(false);
        this.backupMinerStarted = new AtomicBoolean(false);
    }

    /**
     * Add a solution to this epoch's collection.
     *
     * @param solution The solution to add
     */
    public void addSolution(BlockSolution solution) {
        solutions.add(solution);
    }

    /**
     * Get all solutions collected for this epoch.
     *
     * @return Unmodifiable list of solutions
     */
    public List<BlockSolution> getSolutions() {
        return Collections.unmodifiableList(new ArrayList<>(solutions));
    }

    /**
     * Get the number of solutions collected.
     *
     * @return Solution count
     */
    public int getSolutionsCount() {
        return solutions.size();
    }

    /**
     * Check if a block has been produced for this epoch.
     *
     * @return true if block produced, false otherwise
     */
    public boolean isBlockProduced() {
        return blockProduced.get();
    }

    /**
     * Mark that a block has been produced for this epoch.
     *
     * @return true if this call marked it (was false), false if already marked
     */
    public boolean markBlockProduced() {
        return blockProduced.compareAndSet(false, true);
    }

    /**
     * Check if backup miner has been started for this epoch.
     *
     * @return true if backup miner started, false otherwise
     */
    public boolean isBackupMinerStarted() {
        return backupMinerStarted.get();
    }

    /**
     * Mark that backup miner has been started for this epoch.
     *
     * @return true if this call marked it (was false), false if already marked
     */
    public boolean markBackupMinerStarted() {
        return backupMinerStarted.compareAndSet(false, true);
    }

    /**
     * Get the time remaining until epoch end.
     *
     * @return Milliseconds remaining, or 0 if epoch has ended
     */
    public long getTimeRemaining() {
        long remaining = epochEndTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    /**
     * Check if the epoch has ended.
     *
     * @return true if current time >= epoch end time
     */
    public boolean hasEnded() {
        return System.currentTimeMillis() >= epochEndTime;
    }

    @Override
    public String toString() {
        return String.format("EpochContext{epoch=%d, start=%d, end=%d, solutions=%d, blockProduced=%b, backupStarted=%b}",
                epochNumber, epochStartTime, epochEndTime, solutions.size(),
                blockProduced.get(), backupMinerStarted.get());
    }
}
