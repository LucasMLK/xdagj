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
import io.xdag.store.TransactionStore;
import io.xdag.utils.TimeUtils;
import java.util.Comparator;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Block Importer - Handles block import orchestration
 *
 * <p>Responsible for:
 * <ul>
 *   <li>Validating blocks (via BlockValidator)</li>
 *   <li>Calculating cumulative difficulty</li>
 *   <li>Determining epoch competition results</li>
 *   <li>Saving blocks to storage</li>
 *   <li>Coordinating block promotion/demotion</li>
 *   <li>Processing transactions atomically</li>
 * </ul>
 *
 * <p><strong>CRITICAL for BUG-CONSENSUS-002 fix:</strong>
 * This class provides the foundation for implementing epoch-based solution collection.
 * Future enhancement will add {@code EpochSolutionCollector} to collect multiple
 * solutions during an epoch and select the best one at epoch end.
 *
 * @since XDAGJ v1.0.0
 */
@Slf4j
public class BlockImporter {

  private final DagKernel dagKernel;
  private final DagStore dagStore;
  private final TransactionStore transactionStore;
  private final BlockValidator validator;

  /**
   * Creates a BlockImporter with required dependencies
   *
   * @param dagKernel       DAG kernel providing storage and services
   * @param validator       block validation service
   */
  public BlockImporter(
      DagKernel dagKernel,
      BlockValidator validator) {
    this.dagKernel = dagKernel;
    this.dagStore = dagKernel.getDagStore();
    this.transactionStore = dagKernel.getTransactionStore();
    this.validator = validator;
  }

  /**
   * Import a block into the blockchain
   *
   * <p>This is a simplified version of the original {@code tryToConnect} method,
   * focusing on core import logic while delegating validation to BlockValidator.
   *
   * <p><strong>Import Process:</strong>
   * <ol>
   *   <li>Validate block (delegated to BlockValidator)</li>
   *   <li>Calculate cumulative difficulty</li>
   *   <li>Save block with pending height (makes visible for epoch competition)</li>
   *   <li>Determine epoch competition result</li>
   *   <li>Update block height based on competition outcome</li>
   *   <li>Process transactions if block becomes main block</li>
   * </ol>
   *
   * @param block      block to import
   * @param chainStats current chain statistics
   * @return import result with details (epoch, height, difficulty, etc.)
   */
  public ImportResult importBlock(Block block, ChainStats chainStats) {
    try {
      log.debug("Importing block: {}", formatHash(block.getHash()));

      // Step 1: Validate block (delegated to BlockValidator)
      DagImportResult validationResult = validator.validate(block, chainStats);
      if (validationResult != null) {
        // Validation failed
        return ImportResult.fromDagImportResult(validationResult);
      }

      // Step 2: Calculate cumulative difficulty
      UInt256 cumulativeDifficulty;
      try {
        cumulativeDifficulty = calculateCumulativeDifficulty(block);
        log.debug("Block {} cumulative difficulty: {}",
            formatHash(block.getHash()), cumulativeDifficulty.toDecimalString());
      } catch (Exception e) {
        log.error("Failed to calculate cumulative difficulty for block {}",
            formatHash(block.getHash()), e);
        return ImportResult.error("Failed to calculate cumulative difficulty: " + e.getMessage());
      }

      // Step 3: Save block with PENDING height (makes visible for epoch competition)
      long blockEpoch = block.getEpoch();
      long pendingHeight = isGenesisBlock(block) ? 1 : 0;

      BlockInfo pendingInfo = BlockInfo.builder()
          .hash(block.getHash())
          .epoch(block.getEpoch())
          .height(pendingHeight)
          .difficulty(cumulativeDifficulty)
          .build();

      Block blockWithPendingInfo = block.toBuilder().info(pendingInfo).build();

      // Save to storage (makes visible in epoch index)
      dagStore.saveBlockInfo(pendingInfo);
      dagStore.saveBlock(blockWithPendingInfo);

      // Step 4: Determine epoch competition result
      EpochCompetitionResult competition = determineEpochWinner(block, blockEpoch, chainStats);

      // Step 4.5: Handle epoch competition demotion (if new block wins)
      if (competition.isWinner() && competition.getDemotedBlock() != null) {
        Block demotedBlock = competition.getDemotedBlock();
        demoteBlockToOrphan(demotedBlock);
        log.info("Demoted block {} to orphan (lost epoch {} competition)",
            formatHash(demotedBlock.getHash()), blockEpoch);
      }

      // Step 5: Update height based on competition
      long finalHeight = competition.getHeight();
      boolean isBestChain = competition.isWinner();

      BlockInfo finalInfo = pendingInfo.toBuilder()
          .height(finalHeight)
          .build();

      Block finalBlock = block.toBuilder().info(finalInfo).build();

      // Update storage if height changed
      if (finalHeight != pendingHeight) {
        dagStore.saveBlockInfo(finalInfo);
        dagStore.saveBlock(finalBlock);
        log.debug("Updated block {} with final height={}",
            formatHash(block.getHash()), finalHeight);
      }

      // Step 6: Process transactions if main block
      if (isBestChain) {
        processBlockTransactions(finalBlock);
      }

      log.info("Successfully imported block {}: height={}, difficulty={}, isBestChain={}",
          formatHash(block.getHash()), finalHeight,
          cumulativeDifficulty.toDecimalString(), isBestChain);

      return ImportResult.success(
          blockEpoch,
          finalHeight,
          cumulativeDifficulty,
          isBestChain,
          competition.isEpochWinner());

    } catch (Exception e) {
      log.error("Error importing block {}: {}",
          formatHash(block.getHash()), e.getMessage(), e);
      return ImportResult.error("Exception during import: " + e.getMessage());
    }
  }

  /**
   * Determine epoch competition winner
   *
   * <p>XDAG consensus rule: Within same epoch, block with smallest hash wins.
   * This method checks if the new block wins the epoch competition.
   *
   * <p><strong>Competition Logic:</strong>
   * <ul>
   *   <li>If no other blocks in epoch: New block wins</li>
   *   <li>If new block hash < current winner hash: New block wins, demote old winner</li>
   *   <li>If new block hash > current winner hash: New block loses, becomes orphan</li>
   * </ul>
   *
   * @param block       block to check
   * @param blockEpoch  block's epoch
   * @param chainStats  current chain statistics
   * @return competition result with height assignment
   */
  private EpochCompetitionResult determineEpochWinner(
      Block block,
      long blockEpoch,
      ChainStats chainStats) {

    // Get current winner in this epoch
    Block currentWinner = getWinnerBlockInEpoch(blockEpoch);

    // Check if new block wins epoch competition
    boolean isEpochWinner = (currentWinner == null) ||
        currentWinner.getHash().equals(block.getHash()) ||
        (block.getHash().compareTo(currentWinner.getHash()) < 0);

    long height;
    boolean isBestChain;
    Block demotedBlock = null;  // Track which block needs demotion

    if (isEpochWinner) {
      if (currentWinner != null && !currentWinner.getHash().equals(block.getHash())) {
        // Case 1: New block beats current winner
        long replacementHeight = currentWinner.getInfo().getHeight();

        log.info("Block {} wins epoch {} competition (hash {} < {})",
            formatHash(block.getHash()),
            blockEpoch,
            formatHash(block.getHash()),
            formatHash(currentWinner.getHash()));

        // Mark old winner for demotion
        demotedBlock = currentWinner;
        height = replacementHeight;
        isBestChain = true;

      } else if (currentWinner != null && currentWinner.getHash().equals(block.getHash())) {
        // Case 2: Block IS the winner, check for other main blocks in same epoch
        List<Block> allCandidates = dagStore.getCandidateBlocksInEpoch(blockEpoch);
        Block otherMainBlock = findOtherMainBlockInEpoch(allCandidates, block);

        if (otherMainBlock != null) {
          // Demote other main block, take its height
          demotedBlock = otherMainBlock;
          height = otherMainBlock.getInfo().getHeight();
          isBestChain = true;
        } else {
          // No other main blocks, assign new height
          height = isGenesisBlock(block) ? 1 : chainStats.getMainBlockCount() + 1;
          isBestChain = true;
        }

      } else {
        // Case 3: First block in this epoch
        height = isGenesisBlock(block) ? 1 : chainStats.getMainBlockCount() + 1;
        isBestChain = true;
      }

    } else {
      // Lost epoch competition - orphan
      log.debug("Block {} loses epoch {} competition (hash {} > {})",
          formatHash(block.getHash()),
          blockEpoch,
          formatHash(block.getHash()),
          formatHash(currentWinner.getHash()));
      height = 0;
      isBestChain = false;
    }

    return new EpochCompetitionResult(height, isBestChain, isEpochWinner, demotedBlock);
  }

  /**
   * Find other main blocks in the same epoch (excluding the specified block)
   */
  private Block findOtherMainBlockInEpoch(List<Block> candidates, Block excludeBlock) {
    for (Block candidate : candidates) {
      if (candidate.getInfo() != null &&
          candidate.getInfo().getHeight() > 0 &&
          !candidate.getHash().equals(excludeBlock.getHash())) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Demote a block to orphan status (height = 0)
   *
   * <p>This happens when:
   * <ul>
   *   <li>A new block with smaller hash wins epoch competition</li>
   *   <li>Chain reorganization makes blocks obsolete</li>
   * </ul>
   *
   * @param block block to demote
   */
  private void demoteBlockToOrphan(Block block) {
    if (block == null || block.getInfo() == null) {
      log.warn("Attempted to demote null block or block without info");
      return;
    }

    long previousHeight = block.getInfo().getHeight();
    if (previousHeight == 0) {
      log.debug("Block {} is already an orphan, skipping demotion",
          formatHash(block.getHash()));
      return;
    }

    log.debug("Demoting block {} from height {} to orphan",
        formatHash(block.getHash()), previousHeight);

    // Update BlockInfo with height=0
    BlockInfo orphanInfo = block.getInfo().toBuilder()
        .height(0)
        .build();

    Block orphanBlock = block.toBuilder()
        .info(orphanInfo)
        .build();

    // Save updated block to storage
    dagStore.saveBlockInfo(orphanInfo);
    dagStore.saveBlock(orphanBlock);

    log.info("Block {} demoted to orphan (previous height={})",
        formatHash(block.getHash()), previousHeight);
  }

  /**
   * Get winner block in epoch (smallest hash among all candidates)
   */
  private Block getWinnerBlockInEpoch(long epoch) {
    List<Block> candidates = dagStore.getCandidateBlocksInEpoch(epoch);

    if (candidates.isEmpty()) {
      return null;
    }

    // Find block with smallest hash
    return candidates.stream()
        .filter(block -> block.getInfo() != null)
        .min(Comparator.comparing(Block::getHash))
        .orElse(null);
  }

  /**
   * Calculate cumulative difficulty for a block
   *
   * <p>XDAG rule: Blocks in SAME epoch do NOT accumulate difficulty.
   * Only cross-epoch references accumulate difficulty.
   *
   * @param block block to calculate difficulty for
   * @return cumulative difficulty
   */
  private UInt256 calculateCumulativeDifficulty(Block block) {
    long blockEpoch = block.getEpoch();
    UInt256 maxParentDifficulty = UInt256.ZERO;

    for (Link link : block.getLinks()) {
      if (link.isBlock()) {
        Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
        if (parent != null && parent.getInfo() != null) {
          long parentEpoch = parent.getEpoch();

          if (parentEpoch < blockEpoch) {
            // Parent from PREVIOUS epoch - accumulate difficulty
            UInt256 parentDifficulty = parent.getInfo().getDifficulty();
            if (parentDifficulty.compareTo(maxParentDifficulty) > 0) {
              maxParentDifficulty = parentDifficulty;
            }
          } else {
            // Same epoch parent - skip (XDAG rule)
            log.debug("Skipping same-epoch parent {} (epoch {})",
                formatHash(parent.getHash()), parentEpoch);
          }
        }
      }
    }

    // Calculate this block's work
    UInt256 blockWork = calculateBlockWork(block.getHash());

    // Cumulative difficulty = parent max difficulty + block work
    UInt256 cumulativeDifficulty = maxParentDifficulty.add(blockWork);

    log.debug("Calculated cumulative difficulty for block {} (epoch {}): parent={}, work={}, cumulative={}",
        formatHash(block.getHash()),
        blockEpoch,
        maxParentDifficulty.toDecimalString(),
        blockWork.toDecimalString(),
        cumulativeDifficulty.toDecimalString());

    return cumulativeDifficulty;
  }

  /**
   * Calculate work for a single block (XDAG: work = MAX_UINT256 / hash)
   */
  private UInt256 calculateBlockWork(Bytes32 hash) {
    if (hash.isZero()) {
      log.warn("Block hash is zero, returning MAX_VALUE work");
      return UInt256.MAX_VALUE;
    }

    return UInt256.MAX_VALUE.divide(UInt256.fromBytes(hash));
  }

  /**
   * Process block transactions atomically
   *
   * <p>If TransactionManager is available, uses atomic RocksDB transaction.
   * Otherwise, falls back to non-atomic processing.
   *
   * @param block block whose transactions to process
   */
  private void processBlockTransactions(Block block) {
    var transactionManager = dagKernel.getTransactionManager();
    var blockProcessor = dagKernel.getDagBlockProcessor();

    if (blockProcessor == null) {
      log.warn("DagBlockProcessor not available, skipping transaction processing");
      return;
    }

    String txId = null;
    try {
      if (transactionManager != null) {
        // Atomic path: RocksDB transaction
        txId = transactionManager.beginTransaction();
        log.debug("Started atomic transaction {} for block {}",
            txId, formatHash(block.getHash()));

        var processResult = blockProcessor.processBlockInTransaction(txId, block);

        if (!processResult.isSuccess()) {
          transactionManager.rollbackTransaction(txId);
          log.error("Atomic transaction processing failed for block {}: {}, rolled back transaction {}",
              formatHash(block.getHash()), processResult.getError(), txId);
          return;
        }

        transactionManager.commitTransaction(txId);
        log.info("✓ Atomic commit successful for block {} (transaction {})",
            formatHash(block.getHash()), txId);

      } else {
        // Non-atomic fallback
        log.warn("TransactionManager not available, using non-atomic transaction processing for block {}",
            formatHash(block.getHash()));

        var processResult = blockProcessor.processBlock(block);

        if (!processResult.isSuccess()) {
          log.error("Block {} transaction processing failed: {}",
              formatHash(block.getHash()), processResult.getError());
        } else {
          log.info("Block {} transactions executed successfully",
              formatHash(block.getHash()));
        }
      }

    } catch (Exception e) {
      // Rollback on error
      if (txId != null && transactionManager != null) {
        try {
          transactionManager.rollbackTransaction(txId);
          log.error("Rolled back transaction {} for block {} due to error: {}",
              txId, formatHash(block.getHash()), e.getMessage());
        } catch (Exception rollbackError) {
          log.error("Failed to rollback transaction {}: {}", txId, rollbackError.getMessage());
        }
      }
      log.error("Error during atomic block import for {}: {}",
          formatHash(block.getHash()), e.getMessage(), e);
    }
  }

  /**
   * Check if block is genesis block
   */
  private boolean isGenesisBlock(Block block) {
    return block.getLinks().isEmpty() &&
        block.getHeader().getDifficulty() != null &&
        block.getHeader().getDifficulty().equals(UInt256.ONE);
  }

  /**
   * Format block hash for logging
   */
  private String formatHash(Bytes32 hash) {
    return hash.toHexString().substring(0, 16);
  }

  // ==================== Result Classes ====================

  /**
   * Result of block import operation
   */
  @Getter
  public static class ImportResult {
    private final boolean success;
    private final long epoch;
    private final long height;
    private final UInt256 cumulativeDifficulty;
    private final boolean isBestChain;
    private final boolean isEpochWinner;
    private final String errorMessage;

    private ImportResult(
        boolean success,
        long epoch,
        long height,
        UInt256 cumulativeDifficulty,
        boolean isBestChain,
        boolean isEpochWinner,
        String errorMessage) {
      this.success = success;
      this.epoch = epoch;
      this.height = height;
      this.cumulativeDifficulty = cumulativeDifficulty;
      this.isBestChain = isBestChain;
      this.isEpochWinner = isEpochWinner;
      this.errorMessage = errorMessage;
    }

    public static ImportResult success(
        long epoch,
        long height,
        UInt256 cumulativeDifficulty,
        boolean isBestChain,
        boolean isEpochWinner) {
      return new ImportResult(true, epoch, height, cumulativeDifficulty,
          isBestChain, isEpochWinner, null);
    }

    public static ImportResult error(String errorMessage) {
      return new ImportResult(false, 0, 0, UInt256.ZERO, false, false, errorMessage);
    }

    public static ImportResult fromDagImportResult(DagImportResult dagResult) {
      return error(dagResult.getErrorMessage());
    }
  }

  /**
   * Result of epoch competition check
   */
  @Getter
  private static class EpochCompetitionResult {
    private final long height;
    private final boolean winner;
    private final boolean epochWinner;
    private final Block demotedBlock;  // Block that was demoted (if any)

    public EpochCompetitionResult(long height, boolean winner, boolean epochWinner, Block demotedBlock) {
      this.height = height;
      this.winner = winner;
      this.epochWinner = epochWinner;
      this.demotedBlock = demotedBlock;
    }

    public long getHeight() {
      return height;
    }

    public boolean isWinner() {
      return winner;
    }

    public boolean isEpochWinner() {
      return epochWinner;
    }

    public Block getDemotedBlock() {
      return demotedBlock;
    }
  }
}
