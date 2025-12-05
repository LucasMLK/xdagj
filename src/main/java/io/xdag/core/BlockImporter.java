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
import java.util.ArrayList;
import java.util.Collections;
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
  private final PendingBlockManager pendingBlockManager;

  /**
   * Creates a BlockImporter with required dependencies
   *
   * @param dagKernel           DAG kernel providing storage and services
   * @param validator           block validation service
   * @param pendingBlockManager manager for blocks with missing dependencies
   */
  public BlockImporter(
      DagKernel dagKernel,
      BlockValidator validator,
      PendingBlockManager pendingBlockManager) {
    this.dagKernel = dagKernel;
    this.dagStore = dagKernel.getDagStore();
    this.transactionStore = dagKernel.getTransactionStore();
    this.validator = validator;
    this.pendingBlockManager = pendingBlockManager;
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
      Bytes32 blockHash = block.getHash();
      log.debug("Importing block: {}", formatHash(blockHash));

      // Step 0: Check if block already exists (BUG-P2P-001 fix)
      // Prevent redundant imports that waste CPU and cause log pollution
      Block existingBlock = dagStore.getBlockByHash(blockHash);
      if (existingBlock != null && existingBlock.getInfo() != null) {
        BlockInfo info = existingBlock.getInfo();
        log.debug("Block {} already exists (height={}), skipping import",
            formatHash(blockHash), info.getHeight());

        // Return success with existing block's info
        // For existing blocks, chainLength = height (no new block added to chain)
        return ImportResult.success(
            info.getEpoch(),
            info.getHeight(),
            info.getHeight(),  // chainLength same as height for existing block
            info.getDifficulty(),
            info.getHeight() > 0,  // isBestChain
            false);  // not newly imported
      }

      // Step 1: Validate block (delegated to BlockValidator)
      DagImportResult validationResult = validator.validate(block, chainStats);
      if (validationResult != null) {
        if (validationResult.getStatus() == DagImportResult.ImportStatus.MISSING_DEPENDENCY) {
          List<Bytes32> missingParents = extractMissingParents(validationResult);
          pendingBlockManager.registerMissingDependency(block, missingParents);
          return ImportResult.fromDagImportResult(validationResult, missingParents);
        }
        pendingBlockManager.clearMissingDependency(block.getHash());
        return ImportResult.fromDagImportResult(validationResult, null);
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

      // Step 5: Update height based on competition
      long finalHeight = competition.getHeight();
      boolean isBestChain = competition.isWinner();

      // Step 4.5: Handle epoch competition demotion (if new block wins)
      // BUG-CONSENSUS-007 Fix: Handle multiple demoted blocks
      if (competition.isWinner() && !competition.getDemotedBlocks().isEmpty()) {
        List<Block> demotedBlocks = competition.getDemotedBlocks();
        long blockEpochForLog = block.getEpoch();

        log.warn("DEMOTION: {} block(s) being demoted from epoch {} (lost competition to {})",
            demotedBlocks.size(), blockEpochForLog, formatHash(block.getHash()));

        for (Block demotedBlock : demotedBlocks) {
          long oldHeight = demotedBlock.getInfo() != null ? demotedBlock.getInfo().getHeight() : 0;

          log.warn("   - Demoting block {} from height {} to orphan",
              formatHash(demotedBlock.getHash()), oldHeight);

          demoteBlockToOrphan(demotedBlock);
        }

        log.warn("DEMOTION COMPLETE: {} block(s) now orphan, winner {} takes height {}",
            demotedBlocks.size(),
            formatHash(block.getHash()),
            finalHeight);
      }

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

        // BUG-ORPHAN-001 fix: Delete orphan reason when block becomes main
        if (finalHeight > 0) {
          dagStore.deleteOrphanReason(block.getHash());
        }
      }
      // Block successfully stored in main DAG - remove any missing-dependency artifacts
      pendingBlockManager.clearMissingDependency(block.getHash());

      // Step 6: Process transactions if main block
      if (isBestChain) {
        processBlockTransactions(finalBlock);
      }

      // Step 7: Verify epoch integrity (BUG-CONSENSUS-008 dual verification)
      // Quick verification without flush - catches most issues immediately
      // Epoch-end verification (in EpochTimer) provides guaranteed cleanup
      if (isBestChain) {
        verifyEpochSingleWinner(blockEpoch, finalBlock);
      }

      log.info("Successfully imported block {}: height={}, chainLength={}, difficulty={}, isBestChain={}",
          formatHash(block.getHash()), finalHeight, competition.getChainLength(),
          cumulativeDifficulty.toDecimalString(), isBestChain);

      return ImportResult.success(
          blockEpoch,
          finalHeight,
          competition.getChainLength(),  // BUG-HEIGHT-002 fix: use actual chain length
          cumulativeDifficulty,
          isBestChain,
          competition.isEpochWinner());

    } catch (Exception e) {
      pendingBlockManager.clearMissingDependency(block.getHash());
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
    long chainLength = chainStats.getMainBlockCount();  // BUG-HEIGHT-002: track actual chain length
    boolean isBestChain;
    Block demotedBlock = null;  // Track which block needs demotion

    if (isEpochWinner) {
      if (currentWinner != null && !currentWinner.getHash().equals(block.getHash())) {
        // Case 1: New block beats current winner
        long replacementHeight = currentWinner.getInfo().getHeight();

        log.debug("Epoch {} competition: block {} wins (smaller hash)",
            blockEpoch, formatHash(block.getHash()));
        log.debug("  Winner: {}, Loser: {} (demoted from height {})",
            formatHash(block.getHash()),
            formatHash(currentWinner.getHash()), replacementHeight);

        // Mark old winner for demotion
        demotedBlock = currentWinner;
        height = replacementHeight;
        // BUG-HEIGHT-002: chainLength stays the same (replacing, not adding)
        isBestChain = true;

      } else if (currentWinner != null && currentWinner.getHash().equals(block.getHash())) {
        // Case 2: Block IS the winner, check for other main blocks in same epoch
        // BUG-CONSENSUS-007 Fix: Find ALL other main blocks, not just the first one
        List<Block> allCandidates = dagStore.getCandidateBlocksInEpoch(blockEpoch);
        List<Block> otherMainBlocks = findAllOtherMainBlocksInEpoch(allCandidates, block);

        if (!otherMainBlocks.isEmpty()) {
          // Found other main blocks that need to be demoted
          log.warn("Block {} is epoch {} winner, found {} other main block(s) to demote",
              formatHash(block.getHash()), blockEpoch, otherMainBlocks.size());

          // Take height from the first one (they should all be in sequence)
          Block firstDemoted = otherMainBlocks.getFirst();
          height = firstDemoted.getInfo().getHeight();

          // Return ALL demoted blocks (BUG-CONSENSUS-007 fix)
          // BUG-HEIGHT-002: chainLength stays the same (replacing, not adding)
          isBestChain = true;
          return new EpochCompetitionResult(height, chainLength, isBestChain, isEpochWinner, otherMainBlocks);

        } else {
          // No other main blocks, assign new height
          // BUG-HEIGHT-001 fix: Assign height based on epoch order
          log.debug("Block {} is first winner in epoch {}, assigning height by epoch order",
              formatHash(block.getHash()), blockEpoch);
          if (isGenesisBlock(block)) {
            height = 1;
            chainLength = 1;
          } else {
            HeightCalculationResult heightResult = calculateHeightByEpochOrder(blockEpoch, chainStats);
            height = heightResult.getBlockHeight();
            chainLength = heightResult.getChainLength();
          }
          isBestChain = true;
        }

      } else {
        // Case 3: First block in this epoch
        // BUG-HEIGHT-001 fix: Assign height based on epoch order, not arrival order
        if (isGenesisBlock(block)) {
          height = 1;
          chainLength = 1;
        } else {
          HeightCalculationResult heightResult = calculateHeightByEpochOrder(blockEpoch, chainStats);
          height = heightResult.getBlockHeight();
          chainLength = heightResult.getChainLength();
        }
        log.debug("Block {} is first in epoch {}, assigned height {}, chainLength {}",
            formatHash(block.getHash()), blockEpoch, height, chainLength);
        isBestChain = true;
      }

    } else {
      // Lost epoch competition - orphan (BUG-LOGGING-001 fix: use DEBUG instead of WARN)
      log.debug("Epoch {} competition: block {} loses (larger hash)",
          blockEpoch, formatHash(block.getHash()));
      log.debug("  Loser: {}, Winner: {} (height {})",
          formatHash(block.getHash()),
          formatHash(currentWinner.getHash()),
          currentWinner.getInfo() != null ? currentWinner.getInfo().getHeight() : 0);
      height = 0;
      // BUG-HEIGHT-002: chainLength stays the same for orphan blocks
      isBestChain = false;

      // BUG-ORPHAN-001 fix: Record orphan reason for selective retry
      // This block lost competition and will never become main (unless chain reorganization)
      dagStore.saveOrphanReason(block.getHash(), OrphanReason.LOST_COMPETITION);
    }

    return new EpochCompetitionResult(height, chainLength, isBestChain, isEpochWinner, demotedBlock);
  }

  /**
   * Find ALL other main blocks in the same epoch (excluding the specified block)
   *
   * <p><strong>BUG-CONSENSUS-007 Fix:</strong>
   * Returns ALL main blocks (height > 0) in the epoch, not just the first one.
   * This ensures complete cleanup when epoch competition determines a new winner.
   *
   * @param candidates all blocks in the epoch
   * @param excludeBlock the block to exclude from results (typically the new winner)
   * @return List of all main blocks (height > 0) in this epoch, excluding excludeBlock
   */
  private List<Block> findAllOtherMainBlocksInEpoch(List<Block> candidates, Block excludeBlock) {
    List<Block> otherMainBlocks = new ArrayList<>();
    for (Block candidate : candidates) {
      if (candidate.getInfo() != null &&
          candidate.getInfo().getHeight() > 0 &&
          !candidate.getHash().equals(excludeBlock.getHash())) {
        otherMainBlocks.add(candidate);
      }
    }
    return otherMainBlocks;
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

    // Reload full block data to ensure we have the complete block
    Bytes32 blockHash = block.getInfo().getHash();
    Block fullBlock = dagStore.getBlockByHash(blockHash);
    if (fullBlock == null) {
      log.error("Failed to reload full block {} for demotion", formatHash(blockHash));
      return;
    }

    log.debug("Demoting block {} from height {} to orphan",
        formatHash(blockHash), previousHeight);

    // Update BlockInfo with height=0
    BlockInfo orphanInfo = fullBlock.getInfo().toBuilder()
        .height(0)
        .build();

    Block orphanBlock = fullBlock.toBuilder()
        .info(orphanInfo)
        .build();

    // Save updated block to storage (preserving original header/links)
    dagStore.saveBlockInfo(orphanInfo);
    dagStore.saveBlock(orphanBlock);

    // BUG-HEIGHT-INDEX-001 fix: Delete height-to-hash mapping when demoting block
    // This prevents orphan blocks from appearing in height queries
    dagStore.deleteHeightMapping(previousHeight);
    log.debug("Deleted height mapping for demoted block at height {}", previousHeight);

    // BUG-ORPHAN-001 fix: Record orphan reason
    // Demoted blocks lost epoch competition (to a better block that arrived later)
    dagStore.saveOrphanReason(block.getHash(), OrphanReason.LOST_COMPETITION);

    log.info("Block {} demoted to orphan (previous height={})",
        formatHash(block.getHash()), previousHeight);
  }

  /**
   * Verify that an epoch has only one main block (height > 0)
   *
   * <p><strong>BUG-CONSENSUS-007 Fix:</strong>
   * This method ensures epoch integrity by verifying that only one block
   * in the epoch has height > 0 (is a main block). If multiple main blocks
   * are found, it automatically demotes all except the expected winner.
   *
   * @param epoch Epoch to verify
   * @param expectedWinner The block that should be the only main block
   */
  private void verifyEpochSingleWinner(long epoch, Block expectedWinner) {
    List<Block> allCandidates = dagStore.getCandidateBlocksInEpoch(epoch);
    List<Block> mainBlocks = new ArrayList<>();

    for (Block candidate : allCandidates) {
      if (candidate.getInfo() != null && candidate.getInfo().getHeight() > 0) {
        mainBlocks.add(candidate);
      }
    }

    if (mainBlocks.size() > 1) {
      log.error("EPOCH INTEGRITY VIOLATION: Epoch {} has {} main blocks (expected 1):",
          epoch, mainBlocks.size());
      for (Block mainBlock : mainBlocks) {
        log.error("  - Block {} at height {}",
            formatHash(mainBlock.getHash()),
            mainBlock.getInfo().getHeight());
      }

      // Auto-fix: Demote all except the winner
      for (Block mainBlock : mainBlocks) {
        if (!mainBlock.getHash().equals(expectedWinner.getHash())) {
          log.warn("  Auto-demoting block {} to fix integrity",
              formatHash(mainBlock.getHash()));
          demoteBlockToOrphan(mainBlock);
        }
      }

      log.info("Epoch {} integrity restored: winner {} remains as main block",
          epoch, formatHash(expectedWinner.getHash()));

    } else if (mainBlocks.size() == 1) {
      if (!mainBlocks.getFirst().getHash().equals(expectedWinner.getHash())) {
        log.error("EPOCH INTEGRITY VIOLATION: Epoch {} main block mismatch:",
            epoch);
        log.error("  Expected: {}", formatHash(expectedWinner.getHash()));
        log.error("  Actual: {}", formatHash(mainBlocks.getFirst().getHash()));
      } else {
        log.trace("Epoch {} integrity verified: winner {}",
            epoch, formatHash(expectedWinner.getHash()));
      }
    } else if (mainBlocks.isEmpty()) {
      // This shouldn't happen since expectedWinner should be in mainBlocks
      log.error("EPOCH INTEGRITY WARNING: Epoch {} has no main blocks after import of {}",
          epoch, formatHash(expectedWinner.getHash()));
    }
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

  private List<Bytes32> extractMissingParents(DagImportResult dagResult) {
    if (dagResult == null || dagResult.getErrorDetails() == null) {
      return Collections.emptyList();
    }

    Bytes32 missing = dagResult.getErrorDetails().getMissingDependency();
    if (missing == null) {
      return Collections.emptyList();
    }

    return Collections.singletonList(missing);
  }

  /**
   * Public hook for asynchronous epoch verification (epoch timer).
   *
   * <p><strong>BUG-CONSENSUS-008 Fix:</strong>
   * Provides guaranteed cleanup of epoch integrity violations by flushing
   * MemTable before verification. This ensures all writes are visible.
   *
   * <p>This method is called at epoch boundaries (every 64 seconds) to:
   * <ul>
   *   <li>Force all pending writes to be visible (flushMemTable)</li>
   *   <li>Verify epoch has only one main block</li>
   *   <li>Auto-demote any redundant winners that slipped through</li>
   * </ul>
   *
   * <p>Complements the immediate verification in importBlock() which catches
   * most issues quickly but may miss race conditions due to RocksDB snapshot
   * isolation. This delayed verification provides 100% guarantee.
   *
   * @param epoch epoch number that just finished
   */
  public void verifyEpochIntegrity(long epoch) {
    // Force MemTable flush to ensure visibility of all recent writes
    // This is the critical fix for BUG-CONSENSUS-008 (RocksDB snapshot isolation)
    log.debug("Epoch {} integrity check: flushing MemTable", epoch);
    dagStore.flushMemTable();

    Block expectedWinner = getWinnerBlockInEpoch(epoch);
    if (expectedWinner == null) {
      log.warn("Epoch {} integrity check: no winner found, skipping", epoch);
      return;
    }

    log.debug("Epoch {} integrity check: verifying winner {}",
        epoch, formatHash(expectedWinner.getHash()));
    verifyEpochSingleWinner(epoch, expectedWinner);
    log.debug("Epoch {} integrity verification complete", epoch);
  }

  /**
   * Calculate cumulative difficulty for a block
   *
   * <p>XDAG GHOST Protocol Implementation:
   * <ul>
   *   <li>Blocks in SAME epoch do NOT accumulate difficulty</li>
   *   <li>Cross-epoch references accumulate difficulty</li>
   *   <li>BUG-CONSENSUS-009: Orphan block work is accumulated to prevent wasted hashpower</li>
   * </ul>
   *
   * <p>Formula: cumulative = maxPreviousEpochDifficulty + orphanWorkSum + thisBlockWork
   *
   * @param block block to calculate difficulty for
   * @return cumulative difficulty
   */
  private UInt256 calculateCumulativeDifficulty(Block block) {
    long blockEpoch = block.getEpoch();
    UInt256 maxPreviousEpochDifficulty = UInt256.ZERO;
    UInt256 orphanWorkSum = UInt256.ZERO;

    for (Link link : block.getLinks()) {
      if (!link.isBlock()) {
        continue;
      }

      Block parent = dagStore.getBlockByHash(link.getTargetHash());
      if (parent == null || parent.getInfo() == null) {
        continue;
      }

      long parentEpoch = parent.getEpoch();

      if (parentEpoch < blockEpoch) {
        // Parent from PREVIOUS epoch - process according to GHOST protocol
        long parentHeight = parent.getInfo().getHeight();

        if (parentHeight > 0) {
          // Main block (has height): take max cumulative difficulty as base
          UInt256 parentDifficulty = parent.getInfo().getDifficulty();
          if (parentDifficulty.compareTo(maxPreviousEpochDifficulty) > 0) {
            maxPreviousEpochDifficulty = parentDifficulty;
          }
        } else {
          // Orphan block (height=0): accumulate its work (GHOST protocol)
          // This prevents orphan hashpower from being wasted
          UInt256 orphanWork = calculateBlockWork(parent.getHash());
          orphanWorkSum = orphanWorkSum.add(orphanWork);
          log.debug("GHOST: Accumulating orphan work from {} (work={})",
              formatHash(parent.getHash()), orphanWork.toDecimalString());
        }
      } else {
        // Same epoch parent - skip (XDAG rule: same-epoch refs don't accumulate)
        log.debug("Skipping same-epoch parent {} (epoch {})",
            formatHash(parent.getHash()), parentEpoch);
      }
    }

    // Calculate this block's work
    UInt256 blockWork = calculateBlockWork(block.getHash());

    // GHOST: Cumulative = max previous epoch difficulty + orphan work sum + this block's work
    UInt256 cumulativeDifficulty = maxPreviousEpochDifficulty
        .add(orphanWorkSum)
        .add(blockWork);

    log.debug("Calculated cumulative difficulty for block {} (epoch {}): " +
            "previousMax={}, orphanWork={}, blockWork={}, cumulative={}",
        formatHash(block.getHash()),
        blockEpoch,
        maxPreviousEpochDifficulty.toDecimalString(),
        orphanWorkSum.toDecimalString(),
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
        log.debug("Atomic commit successful for block {} (transaction {})",
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
   * Result of height calculation including both block height and chain length.
   *
   * <p><strong>BUG-HEIGHT-002 Fix:</strong>
   * When height shifting occurs, the chain length increases by 1 even though
   * the new block might get a lower height. This class tracks both values.
   */
  @Getter
  private static class HeightCalculationResult {
    private final long blockHeight;     // Height for the new block
    private final long chainLength;     // Actual chain length after operation

    HeightCalculationResult(long blockHeight, long chainLength) {
      this.blockHeight = blockHeight;
      this.chainLength = chainLength;
    }
  }

  /**
   * Calculate the correct height for a block based on epoch order.
   *
   * <p><strong>BUG-HEIGHT-001 Fix:</strong>
   * Heights must be assigned based on epoch order, not arrival order.
   * This ensures consistent height assignment across all nodes regardless
   * of the order blocks arrive during sync.
   *
   * <p><strong>BUG-HEIGHT-002 Fix:</strong>
   * Returns both block height AND actual chain length. When height shifting
   * occurs, chain length = mainBlockCount + 1, which may be different from
   * the new block's height.
   *
   * <p>Algorithm:
   * <ol>
   *   <li>Get all main blocks (height > 0)</li>
   *   <li>Find the correct position based on epoch order</li>
   *   <li>If inserting in the middle, shift all subsequent blocks' heights</li>
   * </ol>
   *
   * @param blockEpoch epoch of the block being inserted
   * @param chainStats current chain statistics
   * @return HeightCalculationResult with block height and chain length
   */
  private HeightCalculationResult calculateHeightByEpochOrder(long blockEpoch, ChainStats chainStats) {
    long mainBlockCount = chainStats.getMainBlockCount();

    log.debug("calculateHeightByEpochOrder called for epoch {}, mainBlockCount={}",
        blockEpoch, mainBlockCount);

    if (mainBlockCount == 0) {
      // First main block (after genesis)
      return new HeightCalculationResult(1, 1);
    }

    // Get all main blocks and find insertion position
    List<Block> mainBlocks = dagStore.getMainBlocksByHeightRange(1, mainBlockCount);

    log.debug("Fetched {} blocks from range [1, {}]",
        mainBlocks.size(), mainBlockCount);

    // Find the correct position based on epoch order
    // Height should be assigned such that epochs are in increasing order
    int insertPosition = 0;
    int nullBlockCount = 0;
    for (int i = 0; i < mainBlocks.size(); i++) {
      Block existingBlock = mainBlocks.get(i);
      if (existingBlock == null || existingBlock.getInfo() == null) {
        nullBlockCount++;
        continue;
      }

      long existingEpoch = existingBlock.getEpoch();
      if (blockEpoch > existingEpoch) {
        insertPosition = i + 1;
      } else {
        log.debug("Found insert point at index {}, existing epoch {} >= new epoch {}",
            i, existingEpoch, blockEpoch);
        break;
      }
    }

    log.debug("Height calculation: insertPosition={}, mainBlocks.size()={}, nullBlockCount={}",
        insertPosition, mainBlocks.size(), nullBlockCount);

    // The new height is insertPosition + 1 (heights are 1-based)
    long newHeight = insertPosition + 1;

    // Check if we need to shift subsequent blocks
    if (insertPosition < mainBlocks.size()) {
      // Inserting in the middle - need to shift all subsequent blocks
      log.info("Height shifting required: epoch {} needs height {}, shifting {} blocks",
          blockEpoch, newHeight, mainBlocks.size() - insertPosition);

      // Shift all blocks from insertPosition to the end
      // Process in reverse order to avoid conflicts
      for (int i = mainBlocks.size() - 1; i >= insertPosition; i--) {
        Block blockToShift = mainBlocks.get(i);
        if (blockToShift == null || blockToShift.getInfo() == null) {
          log.debug("Block at index {} is null, skipping", i);
          continue;
        }

        // Reload full block data for height shifting
        Bytes32 blockHash = blockToShift.getInfo().getHash();
        Block fullBlockToShift = dagStore.getBlockByHash(blockHash);
        if (fullBlockToShift == null) {
          log.error("Failed to reload full block {} for height shifting", formatHash(blockHash));
          continue;
        }

        long oldHeight = fullBlockToShift.getInfo().getHeight();
        long shiftedHeight = oldHeight + 1;

        log.debug("Shifting block {} (epoch {}) from height {} to {}",
            formatHash(fullBlockToShift.getHash()),
            fullBlockToShift.getEpoch(),
            oldHeight,
            shiftedHeight);

        // Update BlockInfo with new height
        BlockInfo shiftedInfo = fullBlockToShift.getInfo().toBuilder()
            .height(shiftedHeight)
            .build();

        Block shiftedBlock = fullBlockToShift.toBuilder()
            .info(shiftedInfo)
            .build();

        // Delete old height mapping first
        dagStore.deleteHeightMapping(oldHeight);

        // Save with new height (preserving original header/links)
        dagStore.saveBlockInfo(shiftedInfo);
        dagStore.saveBlock(shiftedBlock);
      }

      log.info("Height shifting complete: shifted {} blocks, new block at height {}, new chain length {}",
          mainBlocks.size() - insertPosition, newHeight, mainBlockCount + 1);

      // BUG-HEIGHT-002 fix: Return both block height AND actual chain length
      // When shifting occurs, chain length = mainBlockCount + 1
      return new HeightCalculationResult(newHeight, mainBlockCount + 1);
    } else {
      // Appending at the end - normal case
      log.debug("No shifting needed, appending at height {}",
          newHeight);

      // No shifting: new chain length = newHeight (since we're appending)
      return new HeightCalculationResult(newHeight, newHeight);
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
    private final long chainLength;  // BUG-HEIGHT-002 fix: actual chain length for mainBlockCount update
    private final UInt256 cumulativeDifficulty;
    private final boolean isBestChain;
    private final boolean isEpochWinner;
    private final String errorMessage;
    private final DagImportResult failureResult;
    private final List<Bytes32> missingParents;

    private ImportResult(
        boolean success,
        long epoch,
        long height,
        long chainLength,
        UInt256 cumulativeDifficulty,
        boolean isBestChain,
        boolean isEpochWinner,
        String errorMessage,
        DagImportResult failureResult,
        List<Bytes32> missingParents) {
      this.success = success;
      this.epoch = epoch;
      this.height = height;
      this.chainLength = chainLength;
      this.cumulativeDifficulty = cumulativeDifficulty;
      this.isBestChain = isBestChain;
      this.isEpochWinner = isEpochWinner;
      this.errorMessage = errorMessage;
      this.failureResult = failureResult;
      this.missingParents = missingParents != null ? missingParents : List.of();
    }

    public static ImportResult success(
        long epoch,
        long height,
        long chainLength,
        UInt256 cumulativeDifficulty,
        boolean isBestChain,
        boolean isEpochWinner) {
      return new ImportResult(true, epoch, height, chainLength, cumulativeDifficulty,
          isBestChain, isEpochWinner, null, null, null);
    }

    public static ImportResult error(String errorMessage) {
      DagImportResult failure = DagImportResult.error(new Exception(errorMessage), errorMessage);
      return new ImportResult(false, 0, 0, 0, UInt256.ZERO, false, false, errorMessage, failure, null);
    }

    public static ImportResult fromDagImportResult(DagImportResult dagResult, List<Bytes32> missingParents) {
      String message = dagResult != null ? dagResult.getErrorMessage() : "Import failed";
      return new ImportResult(false, 0, 0, 0, UInt256.ZERO, false, false, message, dagResult, missingParents);
    }
  }

  /**
   * Result of epoch competition check
   */
  @Getter
  private static class EpochCompetitionResult {
    private final long height;
    private final long chainLength;  // BUG-HEIGHT-002 fix: actual chain length after operation
    private final boolean winner;
    private final boolean epochWinner;
    private final Block demotedBlock;         // Single block (for backward compatibility)
    /**
     * -- GETTER --
     *  Get ALL blocks that need demotion (BUG-CONSENSUS-007 fix)
     */
    private final List<Block> demotedBlocks;  // ALL blocks that need demotion (BUG-CONSENSUS-007 fix)

    /**
     * Constructor for single block demotion (backward compatible)
     */
    public EpochCompetitionResult(long height, long chainLength, boolean winner, boolean epochWinner, Block demotedBlock) {
      this(height, chainLength, winner, epochWinner, demotedBlock,
           demotedBlock != null ? Collections.singletonList(demotedBlock) : Collections.emptyList());
    }

    /**
     * Constructor for multiple blocks demotion (BUG-CONSENSUS-007 fix)
     */
    public EpochCompetitionResult(long height, long chainLength, boolean winner, boolean epochWinner, List<Block> demotedBlocks) {
      this(height, chainLength, winner, epochWinner,
           demotedBlocks != null && !demotedBlocks.isEmpty() ? demotedBlocks.getFirst() : null,
           demotedBlocks != null ? demotedBlocks : Collections.emptyList());
    }

    /**
     * Private constructor with all fields
     */
    private EpochCompetitionResult(long height, long chainLength, boolean winner, boolean epochWinner,
                                    Block demotedBlock, List<Block> demotedBlocks) {
      this.height = height;
      this.chainLength = chainLength;
      this.winner = winner;
      this.epochWinner = epochWinner;
      this.demotedBlock = demotedBlock;
      this.demotedBlocks = demotedBlocks;
    }

  }
}
