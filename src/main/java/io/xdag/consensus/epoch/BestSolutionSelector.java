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

import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * BestSolutionSelector - Selects the best solution from collected candidates.
 *
 * <p>Selection criteria (in priority order):
 * <ol>
 *   <li>Highest difficulty (smallest hash)</li>
 *   <li>If difficulty equal, earliest submission time</li>
 * </ol>
 *
 * <p>This implements the "competition-based mining" mechanism from original XDAG,
 * where miners compete to find the smallest hash during the 64-second epoch period.
 *
 * <p>Part of BUG-CONSENSUS-002 fix - ensures "best solution wins" instead of
 * "first solution wins".
 *
 * @see SolutionCollector
 * @see EpochConsensusManager
 */
@Slf4j
public class BestSolutionSelector {

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

    /**
     * Select the best solution from a list of candidates.
     *
     * <p>Selection algorithm:
     * <pre>
     * 1. Sort by difficulty (descending) - higher difficulty wins
     * 2. If difficulty equal, sort by submit time (ascending) - earlier wins
     * 3. Return the first solution after sorting
     * </pre>
     *
     * @param solutions List of candidate solutions
     * @return Best solution, or null if list is empty
     */
    public BlockSolution selectBest(List<BlockSolution> solutions) {
        if (solutions == null || solutions.isEmpty()) {
            return null;
        }

        // Sort by: 1) difficulty (descending), 2) submit time (ascending)
        return solutions.stream()
                .max(Comparator
                        .comparing(BlockSolution::getDifficulty)
                        .thenComparing(Comparator.comparing(BlockSolution::getSubmitTime).reversed())
                )
                .orElse(null);
    }

    /**
     * Log the selection process and result.
     *
     * <p>Displays all candidate solutions sorted by difficulty, with the selected
     * solution marked.
     *
     * @param epoch     Epoch number
     * @param solutions All candidate solutions
     * @param selected  The selected solution
     */
    public void logSelection(long epoch, List<BlockSolution> solutions, BlockSolution selected) {
        log.info("═══════════ Epoch {} Solution Selection ═══════════", epoch);
        log.info("Total solutions collected: {}", solutions.size());

        if (solutions.isEmpty()) {
            log.warn("No solutions to select from!");
            return;
        }

        // Sort by difficulty (descending) for display
        solutions.stream()
                .sorted(Comparator.comparing(BlockSolution::getDifficulty).reversed())
                .forEach(s -> {
                    boolean isSelected = s == selected;
                    String marker = isSelected ? "✓" : " ";
                    String diffStr = s.getDifficulty().toHexString().substring(0, 18) + "...";
                    String timeStr = formatTime(s.getSubmitTime());

                    log.info("  {} Pool '{}': difficulty={}, submit_time={}",
                            marker, s.getPoolId(), diffStr, timeStr);
                });

        if (selected != null) {
            log.info("════════════════════════════════════════════════");
            log.info("Selected: Pool '{}' with difficulty {}",
                    selected.getPoolId(),
                    selected.getDifficulty().toHexString().substring(0, 18) + "...");
        } else {
            log.error("ERROR: No solution selected (should not happen!)");
        }
    }

  /**
     * Format timestamp for display.
     *
     * @param timestampMs Timestamp in milliseconds
     * @return Formatted time string (HH:mm:ss.SSS)
     */
    private String formatTime(long timestampMs) {
        return TIME_FORMAT.format(new Date(timestampMs));
    }
}
