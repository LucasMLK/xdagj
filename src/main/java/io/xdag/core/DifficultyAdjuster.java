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
import io.xdag.store.DagStore;
import java.math.BigInteger;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Difficulty Adjuster - adjusts the base target at fixed intervals.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Monitor block production rate</li>
 *   <li>Calculate the adjustment factor</li>
 *   <li>Update the network difficulty target</li>
 * </ul>
 *
 * <p>Extracted from {@code DagChainImpl} during the Phase 1 refactor.</p>
 */
@Slf4j
public class DifficultyAdjuster {

  /**
   * Target blocks per epoch for difficulty adjustment
   * <p>
   * Set equal to MAX_BLOCKS_PER_EPOCH - target is to fill the capacity
   * Difficulty will decrease if actual blocks < TARGET * 0.5 (8 blocks)
   * Difficulty will increase if actual blocks > TARGET * 1.5 (24 blocks, but capped at 16)
   */
  private static final int TARGET_BLOCKS_PER_EPOCH = 16;

  /**
   * Difficulty adjustment interval (in epochs)
   * <p>
   * Adjust every 1000 epochs (~17.7 hours) Balances stability with adaptiveness to hashrate
   * changes
   */
  private static final int DIFFICULTY_ADJUSTMENT_INTERVAL = 1000;

  /**
   * Maximum and minimum difficulty adjustment factors
   * <p>
   * Prevents drastic difficulty swings
   */
  private static final double MAX_ADJUSTMENT_FACTOR = 2.0;   // Max 2x increase
  private static final double MIN_ADJUSTMENT_FACTOR = 0.5;   // Max 50% decrease

  private final DagKernel dagKernel;
  private final DagStore dagStore;

  /**
   * Create a new adjuster.
   *
   * @param dagKernel DAG kernel that provides access to shared components
   */
  public DifficultyAdjuster(DagKernel dagKernel) {
    this.dagKernel = dagKernel;
    this.dagStore = dagKernel.getDagStore();
  }

  /**
   * Check and adjust difficulty target if needed
   * <p>
   * Adjusts baseDifficultyTarget every DIFFICULTY_ADJUSTMENT_INTERVAL (1000) epochs based on
   * average blocks per epoch to maintain TARGET_BLOCKS_PER_EPOCH (16) blocks/epoch.
   * <p>
   * Algorithm:
   * - If avgBlocksPerEpoch > TARGET * 1.5 → increase difficulty (lower target)
   * - If avgBlocksPerEpoch < TARGET * 0.5 → decrease difficulty (raise target)
   * - Adjustment limited to MIN_ADJUSTMENT_FACTOR (0.5x) to MAX_ADJUSTMENT_FACTOR (2x)
   *
   * @param chainStats    current chain statistics
   * @param currentHeight current main block height
   * @param currentEpoch  current epoch number
   * @param getCandidateBlocksInEpoch function to get candidate blocks in an epoch
   * @return updated chain statistics
   */
  public ChainStats checkAndAdjustDifficulty(
      ChainStats chainStats,
      long currentHeight,
      long currentEpoch,
      java.util.function.LongFunction<List<Block>> getCandidateBlocksInEpoch) {

    long lastAdjustmentEpoch = chainStats.getLastDifficultyAdjustmentEpoch();

    // Check if adjustment interval reached
    if (currentEpoch - lastAdjustmentEpoch < DIFFICULTY_ADJUSTMENT_INTERVAL) {
      return chainStats;  // Not time yet
    }

    log.info("Difficulty adjustment triggered at epoch {} (last adjustment: epoch {})",
        currentEpoch, lastAdjustmentEpoch);

    // Calculate average blocks per epoch in the adjustment period
    long totalBlocks = 0;
    long epochCount = 0;

    // BUGFIX: Limit scan range to prevent scanning too many epochs
    // Only scan the adjustment interval window, not the entire gap
    long scanStartEpoch = Math.max(lastAdjustmentEpoch,
        currentEpoch - DIFFICULTY_ADJUSTMENT_INTERVAL);
    long epochsToScan = currentEpoch - scanStartEpoch;

    if (epochsToScan > DIFFICULTY_ADJUSTMENT_INTERVAL * 2) {
      // Gap too large (likely test scenario or chain restart) - use recent window only
      log.warn("Large epoch gap detected: {} epochs between {} and {}",
          currentEpoch - lastAdjustmentEpoch, lastAdjustmentEpoch, currentEpoch);
      log.warn("Limiting difficulty calculation to recent {} epochs", DIFFICULTY_ADJUSTMENT_INTERVAL);
      scanStartEpoch = currentEpoch - DIFFICULTY_ADJUSTMENT_INTERVAL;
    }

    for (long epoch = scanStartEpoch; epoch < currentEpoch; epoch++) {
      List<Block> blocks = getCandidateBlocksInEpoch.apply(epoch);
      // Count ALL candidate blocks (main + orphan) per epoch
      // This reflects the actual block production rate that difficulty should regulate
      totalBlocks += blocks.size();
      epochCount++;
    }

    double avgBlocksPerEpoch = epochCount > 0 ? (double) totalBlocks / epochCount : 0;

    log.info("Average blocks per epoch in last {} epochs: {} (target: {})",
        epochCount, String.format("%.2f", avgBlocksPerEpoch), TARGET_BLOCKS_PER_EPOCH);

    // Calculate adjustment factor
    double adjustmentFactor = 1.0;

    if (avgBlocksPerEpoch > TARGET_BLOCKS_PER_EPOCH * 1.5) {
      // Too many blocks → increase difficulty (lower target)
      adjustmentFactor = TARGET_BLOCKS_PER_EPOCH / avgBlocksPerEpoch;
      log.info("Too many blocks, increasing difficulty (lowering target) by factor {}",
          String.format("%.2f", adjustmentFactor));
    } else if (avgBlocksPerEpoch < TARGET_BLOCKS_PER_EPOCH * 0.5) {
      // Too few blocks → decrease difficulty (raise target)
      adjustmentFactor = TARGET_BLOCKS_PER_EPOCH / avgBlocksPerEpoch;
      log.info("Too few blocks, decreasing difficulty (raising target) by factor {}",
          String.format("%.2f", adjustmentFactor));
    } else {
      log.info("Block count in acceptable range, no adjustment needed");
      // Update last adjustment epoch even if no change
      chainStats = chainStats.toBuilder()
          .lastDifficultyAdjustmentEpoch(currentEpoch)
          .build();
      dagStore.saveChainStats(chainStats);
      return chainStats;
    }

    // Limit adjustment factor
    adjustmentFactor = Math.max(MIN_ADJUSTMENT_FACTOR,
        Math.min(MAX_ADJUSTMENT_FACTOR, adjustmentFactor));

    log.info("Limited adjustment factor: {} (range: {} - {})",
        String.format("%.2f", adjustmentFactor),
        String.format("%.2f", MIN_ADJUSTMENT_FACTOR),
        String.format("%.2f", MAX_ADJUSTMENT_FACTOR));

    // Calculate new target
    UInt256 currentTarget = chainStats.getBaseDifficultyTarget();
    BigInteger newTargetBigInt = currentTarget.toBigInteger()
        .multiply(BigInteger.valueOf((long) (adjustmentFactor * 1000)))
        .divide(BigInteger.valueOf(1000));

    //  Cap target at UInt256.MAX_VALUE to prevent overflow
    // This can happen when:
    // 1. DEVNET mode starts with baseDifficultyTarget = MAX_VALUE
    // 2. Too few blocks are produced, causing adjustmentFactor = 2.0
    // 3. MAX_VALUE * 2 would overflow UInt256
    BigInteger maxValue = UInt256.MAX_VALUE.toBigInteger();
    if (newTargetBigInt.compareTo(maxValue) > 0) {
      log.info("New target would exceed MAX_VALUE, capping at MAX_VALUE");
      newTargetBigInt = maxValue;
    }

    UInt256 newTarget = UInt256.valueOf(newTargetBigInt);

    log.info("Difficulty adjusted: old target={}, new target={}, factor={}",
        currentTarget.toHexString().substring(0, 16) + "...",
        newTarget.toHexString().substring(0, 16) + "...",
        String.format("%.2f", adjustmentFactor));

    // Update chain stats
    chainStats = chainStats.toBuilder()
        .baseDifficultyTarget(newTarget)
        .lastDifficultyAdjustmentEpoch(currentEpoch)
        .build();
    dagStore.saveChainStats(chainStats);

    return chainStats;
  }
}
