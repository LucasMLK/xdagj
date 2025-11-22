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

import static io.xdag.config.Constants.MAIN_CHAIN_PERIOD;
import static io.xdag.config.Constants.MIN_GAS;

import io.xdag.DagKernel;
import io.xdag.config.Constants.MessageType;
import io.xdag.core.listener.BlockMessage;
import io.xdag.core.listener.Listener;
import io.xdag.store.DagStore;
import io.xdag.store.TransactionStore;
import io.xdag.store.cache.DagEntityResolver;
import io.xdag.store.cache.ResolvedLinks;
import io.xdag.utils.TimeUtils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * DagChain implementation for XDAG epoch-based DAG consensus
 *
 * <p>Implements epoch-based consensus with cumulative difficulty calculation.
 * Uses DagKernel exclusively for all storage operations.
 *
 * @since XDAGJ
 */
@Slf4j
public class DagChainImpl implements DagChain {

  // ==================== Consensus Parameters ====================

  /**
   * Initial base difficulty target for PoW validation
   * <p>
   * Requires hash to have approximately 8 zero bytes (hash < 2^192) Average mining time with 1
   * GH/s: ~5 hours per block Network effect (1000 miners @ 10 GH/s): ~346 blocks/epoch
   */
  private static final UInt256 INITIAL_BASE_DIFFICULTY_TARGET =
      UInt256.valueOf(BigInteger.valueOf(2).pow(192));

  /**
   * DEVNET difficulty target - accepts any block (no PoW required)
   * <p>
   * Used in development/testing environments where PoW validation would prevent tests from running
   * (random blocks won't pass real PoW).
   */
  private static final UInt256 DEVNET_DIFFICULTY_TARGET = UInt256.MAX_VALUE;

  /**
   * Maximum blocks accepted per epoch (64 seconds)
   * <p>
   * Limits storage growth by only keeping top 16 blocks per epoch
   * Rationale: New blocks reference top 16 from previous epoch (MAX_BLOCK_LINKS)
   * Effect: 10,000 nodes → 16 accepted blocks → ~4 GB/year storage
   */
  private static final int MAX_BLOCKS_PER_EPOCH = 16;

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
   * Orphan block retention window (in epochs)
   * <p>
   * XDAG rule: blocks can only reference blocks within 12 days (16384 epochs) After this window,
   * orphan blocks cannot become main blocks anymore
   */
  private static final long ORPHAN_RETENTION_WINDOW = 16384;

  /**
   * Node sync lag threshold (in epochs)
   * <p>
   * If local main chain epoch is behind current time epoch by more than this value, the node is
   * considered "behind" and should sync before mining.
   * <p>
   * 100 epochs ≈ 1.78 hours
   */
  private static final long SYNC_LAG_THRESHOLD = 100;

  /**
   * Maximum reference depth for normal mining (in epochs)
   * <p>
   * When node is up-to-date (not behind), new blocks can only reference blocks within the last 16
   * epochs (≈17 minutes).
   * <p>
   * This prevents "ancient reference" attacks where malicious nodes reference very old blocks to
   * create fake chains.
   */
  private static final long MINING_MAX_REFERENCE_DEPTH = 16;

  /**
   * Maximum reference depth for sync mode (in epochs)
   * <p>
   * When node is behind and syncing, blocks can reference up to 1000 epochs back. This allows
   * importing historical blocks with reasonable parent references.
   */
  private static final long SYNC_MAX_REFERENCE_DEPTH = 1000;

  /**
   * Orphan cleanup interval (in epochs)
   * <p>
   * Run cleanup every 100 epochs (~1.78 hours)
   */
  private static final long ORPHAN_CLEANUP_INTERVAL = 100;

  /**
   * Maximum and minimum difficulty adjustment factors
   * <p>
   * Prevents drastic difficulty swings
   */
  private static final double MAX_ADJUSTMENT_FACTOR = 2.0;   // Max 2x increase
  private static final double MIN_ADJUSTMENT_FACTOR = 0.5;   // Max 50% decrease

  // ==================== Instance Fields ====================

  private final DagKernel dagKernel;
  private final DagStore dagStore;
  private final DagEntityResolver entityResolver;
  private final TransactionStore transactionStore;

  private volatile ChainStats chainStats;
  private final List<Listener> listeners = new ArrayList<>();
  private final List<DagchainListener> dagchainListeners = new ArrayList<>();
  private final ThreadLocal<Boolean> isRetryingOrphans = ThreadLocal.withInitial(() -> false);
  private volatile Bytes miningCoinbase = Bytes.wrap(new byte[20]);

  /**
   * Creates DagChain with DagKernel dependencies
   *
   * @param dagKernel kernel providing storage and entity resolution
   */
  public DagChainImpl(DagKernel dagKernel) {
    this.dagKernel = dagKernel;
    this.dagStore = dagKernel.getDagStore();
    this.entityResolver = dagKernel.getEntityResolver();
    this.transactionStore = dagKernel.getTransactionStore();

    this.chainStats = dagStore.getChainStats();
    if (this.chainStats == null) {
      long currentEpoch = TimeUtils.getCurrentEpochNumber();

      // DEVNET: Use relaxed difficulty target (no PoW required)
      // MAINNET/TESTNET: Use real difficulty target (requires actual mining)
      boolean isDevnet = dagKernel.getConfig().getNodeSpec().getNetwork().toString().toLowerCase()
          .contains("devnet");
      UInt256 initialTarget = isDevnet ? DEVNET_DIFFICULTY_TARGET : INITIAL_BASE_DIFFICULTY_TARGET;

      if (isDevnet) {
        log.info("DEVNET mode detected - using relaxed difficulty target (no PoW required)");
      }

      this.chainStats = ChainStats.builder()
          .mainBlockCount(0)
          .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
          .baseDifficultyTarget(initialTarget)
          .lastDifficultyAdjustmentEpoch(
              currentEpoch)  // Start from current epoch to prevent immediate adjustment
          .lastOrphanCleanupEpoch(currentEpoch)          // Start from current epoch
          .build();
      dagStore.saveChainStats(this.chainStats);
    }

    // Initialize new consensus fields for existing chains
    if (this.chainStats.getBaseDifficultyTarget() == null) {
      long currentEpoch = TimeUtils.getCurrentEpochNumber();

      // DEVNET: Use relaxed difficulty target (no PoW required)
      // MAINNET/TESTNET: Use real difficulty target (requires actual mining)
      boolean isDevnet = dagKernel.getConfig().getNodeSpec().getNetwork().toString().toLowerCase()
          .contains("devnet");
      UInt256 initialTarget = isDevnet ? DEVNET_DIFFICULTY_TARGET : INITIAL_BASE_DIFFICULTY_TARGET;

      log.info("Initializing baseDifficultyTarget for existing chain (devnet={}, target={})",
          isDevnet, initialTarget.toHexString().substring(0, 16) + "...");

      this.chainStats = this.chainStats.toBuilder()
          .baseDifficultyTarget(initialTarget)
          .lastDifficultyAdjustmentEpoch(currentEpoch)
          .lastOrphanCleanupEpoch(currentEpoch)
          .build();
      dagStore.saveChainStats(this.chainStats);
    }

    log.info("DagChainImpl initialized with DagKernel");
    log.info("  - DagStore: {}", dagStore.getClass().getSimpleName());
    log.info("  - DagEntityResolver: {}", entityResolver.getClass().getSimpleName());
    log.info("  - TransactionStore: {}", transactionStore.getClass().getSimpleName());
  }

  // ==================== Block Import Operations ====================

  @Override
  public synchronized DagImportResult tryToConnect(Block block) {
    try {
      log.debug("Attempting to connect block: {}", block.getHash().toHexString());

      // Basic validation
      DagImportResult basicValidation = validateBasicRules(block);
      if (basicValidation != null) {
        return basicValidation;
      }

      // NEW CONSENSUS: Minimum PoW validation
      DagImportResult powValidation = validateMinimumPoW(block);
      if (powValidation != null) {
        return powValidation;
      }

      // NEW CONSENSUS: Epoch limit validation
      DagImportResult epochLimitValidation = validateEpochLimit(block);
      if (epochLimitValidation != null) {
        return epochLimitValidation;
      }

      // Link validation (Transaction and Block references)
      DagImportResult linkValidation = validateLinks(block);
      if (linkValidation != null) {
        return linkValidation;
      }

      // DAG rules validation
      DAGValidationResult dagValidation = validateDAGRules(block);
      if (!dagValidation.isValid()) {
        log.debug("Block {} failed DAG validation: {}",
            block.getHash().toHexString(), dagValidation.getErrorMessage());
        return DagImportResult.invalidDAG(dagValidation);
      }

      // Calculate cumulative difficulty
      UInt256 cumulativeDifficulty;
      try {
        cumulativeDifficulty = calculateCumulativeDifficulty(block);
        log.debug("Block {} cumulative difficulty: {}",
            block.getHash().toHexString(), cumulativeDifficulty.toDecimalString());
      } catch (Exception e) {
        log.error("Failed to calculate cumulative difficulty for block {}",
            block.getHash().toHexString(), e);
        return DagImportResult.error(e,
            "Failed to calculate cumulative difficulty: " + e.getMessage());
      }

      // CONSENSUS REFACTORING (2025-11-21): FIXED EPOCH COMPETITION TIMING
      // BUGFIX: Save block with pending height=0 FIRST to make it visible in epoch index
      // THEN check epoch competition to see if other blocks exist in the same epoch
      // This ensures proper epoch competition when blocks arrive rapidly in sequence

      long blockEpoch = block.getEpoch();

      // Step 1: Save block with PENDING height=0 to make it visible in epoch index
      // Special case: Genesis blocks get height=1 immediately
      long pendingHeight = isGenesisBlock(block) ? 1 : 0;

      BlockInfo pendingInfo = BlockInfo.builder()
          .hash(block.getHash())
          .epoch(block.getEpoch())
          .height(pendingHeight)
          .difficulty(cumulativeDifficulty)
          .build();

      Block blockWithPendingInfo = block.toBuilder().info(pendingInfo).build();

      // Save block with pending height FIRST (makes it visible for epoch competition)
      dagStore.saveBlockInfo(pendingInfo);
      dagStore.saveBlock(blockWithPendingInfo);

      // Step 2: NOW check epoch competition (block is visible in epoch index)
      Block currentWinner = getWinnerBlockInEpoch(blockEpoch);

      // BUGFIX: Check if currentWinner is self (block we just saved)
      // When a block is saved first and then found by getWinnerBlockInEpoch(),
      // comparing hash to itself returns 0 (equal), not < 0, causing false negative
      boolean isEpochWinner = (currentWinner == null) ||
          currentWinner.getHash().equals(block.getHash()) ||  // Winner is self
          (block.getHash().compareTo(currentWinner.getHash()) < 0);

      // Step 3: Update height based on epoch competition result
      boolean isBestChain;
      long height;

      if (isEpochWinner) {
        if (currentWinner != null && !currentWinner.getHash().equals(block.getHash())) {
          // Case 1: This block beats the current winner
          // Replace current winner at SAME height (replacement, not addition)
          long replacementHeight = currentWinner.getInfo().getHeight();

          log.info("Block {} wins epoch {} competition (hash {} < {})",
              block.getHash().toHexString().substring(0, 16),
              blockEpoch,
              block.getHash().toHexString().substring(0, 16),
              currentWinner.getHash().toHexString().substring(0, 16));

          demoteBlockToOrphan(currentWinner);

          // New winner takes the SAME height (replacement at same position)
          height = replacementHeight;
          isBestChain = true;
        } else if (currentWinner != null && currentWinner.getHash().equals(block.getHash())) {
          // Case 2: This block IS the winner, but other main blocks may exist in same epoch
          // Check for other main blocks (height > 0) in this epoch that need demotion
          log.debug("Block {} is winner, checking for other main blocks in epoch {}",
              block.getHash().toHexString().substring(0, 16), blockEpoch);

          List<Block> allCandidates = getCandidateBlocksInEpoch(blockEpoch);
          log.debug("Found {} total candidates in epoch {}",
              allCandidates.size(), blockEpoch);

          Block otherMainBlock = null;

          for (Block candidate : allCandidates) {
            log.debug("Checking candidate {}: height={}, isSelf={}",
                candidate.getHash().toHexString().substring(0, 16),
                candidate.getInfo() != null ? candidate.getInfo().getHeight() : "null",
                candidate.getHash().equals(block.getHash()));

            if (candidate.getInfo() != null &&
                candidate.getInfo().getHeight() > 0 &&
                !candidate.getHash().equals(block.getHash())) {
              // Found another main block in this epoch - needs demotion
              otherMainBlock = candidate;
              log.debug("Found other main block {} at height {}",
                  otherMainBlock.getHash().toHexString().substring(0, 16),
                  otherMainBlock.getInfo().getHeight());
              break;
            }
          }

          if (otherMainBlock != null) {
            // Demote the other main block and take its height
            long replacementHeight = otherMainBlock.getInfo().getHeight();

            log.info("Block {} wins epoch {} competition, demoting previous main block {}",
                block.getHash().toHexString().substring(0, 16),
                blockEpoch,
                otherMainBlock.getHash().toHexString().substring(0, 16));

            demoteBlockToOrphan(otherMainBlock);

            // Take the demoted block's height
            height = replacementHeight;
            isBestChain = true;
          } else {
            // No other main blocks, assign new height
            if (isGenesisBlock(block)) {
              height = 1;  // Genesis always gets height=1
            } else {
              height = chainStats.getMainBlockCount() + 1;
              log.debug("Block {} is first winner in epoch {}, assigned height {}",
                  block.getHash().toHexString().substring(0, 16),
                  blockEpoch, height);
            }
            isBestChain = true;
          }
        } else {
          // Case 3: First block in this epoch (currentWinner == null)
          if (isGenesisBlock(block)) {
            height = 1;  // Genesis always gets height=1
          } else {
            // Assign next available height
            height = chainStats.getMainBlockCount() + 1;
            log.debug("Block {} is first winner in epoch {}, assigned height {}",
                block.getHash().toHexString().substring(0, 16),
                blockEpoch, height);
          }
          isBestChain = true;
        }
      } else {
        // Lost epoch competition - orphan
        log.debug("Block {} loses epoch {} competition (hash {} > {})",
            block.getHash().toHexString().substring(0, 16),
            blockEpoch,
            block.getHash().toHexString().substring(0, 16),
            currentWinner.getHash().toHexString().substring(0, 16));
        height = 0;
        isBestChain = false;
      }

      // Update BlockInfo with final height (may be same as pending for orphans/pending blocks)
      BlockInfo blockInfo = pendingInfo.toBuilder()
          .height(height)
          .build();

      Block blockWithInfo = block.toBuilder().info(blockInfo).build();

      // ========== ATOMIC BLOCK PROCESSING ==========
      // Update block with final height if different from pending height
      if (height != pendingHeight) {
        dagStore.saveBlockInfo(blockInfo);
        dagStore.saveBlock(blockWithInfo);
        log.debug("Updated block {} with final height={}",
            block.getHash().toHexString().substring(0, 16), height);
      }

      // Process transactions atomically if this is a main block
      String txId = null;
      try {
        // Get transaction manager from DagKernel
        io.xdag.store.rocksdb.transaction.RocksDBTransactionManager transactionManager =
            dagKernel.getTransactionManager();

        log.debug(
            "Transaction execution check: block={}, height={}, isBestChain={}, hasTransactionManager={}",
            block.getHash().toHexString().substring(0, 16),
            height,
            isBestChain,
            transactionManager != null);

        if (transactionManager != null && isBestChain) {
          // === ATOMIC PATH: Transaction processing in single RocksDB transaction ===
          txId = transactionManager.beginTransaction();
          log.debug("Started atomic transaction {} for block {}",
              txId, block.getHash().toHexString().substring(0, 16));

          // Process block transactions (indexing + execution) IN TRANSACTION
          DagBlockProcessor blockProcessor = dagKernel.getDagBlockProcessor();
          if (blockProcessor != null) {
            DagBlockProcessor.ProcessingResult processResult =
                blockProcessor.processBlockInTransaction(txId, blockWithInfo);

            if (!processResult.isSuccess()) {
              // Transaction processing failed - rollback entire transaction
              transactionManager.rollbackTransaction(txId);
              log.error(
                  "Atomic transaction processing failed for block {}: {}, rolled back transaction {}",
                  block.getHash().toHexString().substring(0, 16),
                  processResult.getError(), txId);
              return DagImportResult.error(new Exception(processResult.getError()),
                  "Atomic block processing failed: " + processResult.getError());
            }
            log.debug("Buffered transaction processing in transaction {}", txId);
          }

          // COMMIT - all buffered operations execute atomically
          transactionManager.commitTransaction(txId);
          log.info("✓ Atomic commit successful for block {} (transaction {})",
              block.getHash().toHexString().substring(0, 16), txId);

          // Update chain stats AFTER commit (non-transactional derived data)
          updateChainStatsForNewMainBlock(blockInfo);
          checkAndAdjustDifficulty(blockInfo.getHeight(), block.getEpoch());
          cleanupOldOrphans(block.getEpoch());

        } else if (isBestChain) {
          // === FALLBACK: Transaction manager not available, use non-atomic transaction execution ===
          log.warn(
              "TransactionManager not available, using non-atomic transaction processing for block {}",
              block.getHash().toHexString().substring(0, 16));

          DagBlockProcessor blockProcessor = dagKernel.getDagBlockProcessor();
          if (blockProcessor != null) {
            DagBlockProcessor.ProcessingResult processResult =
                blockProcessor.processBlock(blockWithInfo);

            if (!processResult.isSuccess()) {
              log.error("Block {} transaction processing failed: {}",
                  block.getHash().toHexString(), processResult.getError());
              // Transaction execution failed but block is valid, continue
            } else {
              log.info("Block {} transactions executed successfully",
                  block.getHash().toHexString());
            }
          }

          updateChainStatsForNewMainBlock(blockInfo);
          checkAndAdjustDifficulty(blockInfo.getHeight(), block.getEpoch());
          cleanupOldOrphans(block.getEpoch());
        }

      } catch (Exception e) {
        // Rollback transaction on any error
        if (txId != null && dagKernel.getTransactionManager() != null) {
          try {
            dagKernel.getTransactionManager().rollbackTransaction(txId);
            log.error("Rolled back transaction {} for block {} due to error: {}",
                txId, block.getHash().toHexString().substring(0, 16), e.getMessage());
          } catch (Exception rollbackError) {
            log.error("Failed to rollback transaction {}: {}", txId, rollbackError.getMessage());
          }
        }
        log.error("Error during atomic block import for {}: {}",
            block.getHash().toHexString().substring(0, 16), e.getMessage(), e);
        return DagImportResult.error(e, "Atomic import failed: " + e.getMessage());
      }
      // ========== END ATOMIC BLOCK PROCESSING ==========

      // Notify listeners
      notifyListeners(blockWithInfo);

      // Notify DAG chain listeners (only for main blocks)
      if (isBestChain) {
        notifyDagchainListeners(blockWithInfo);
      }

      // Retry orphan blocks (dependency resolution)
      retryOrphanBlocks();

      log.info("Successfully imported block {}: height={}, difficulty={}",
          block.getHash().toHexString(), height, cumulativeDifficulty.toDecimalString());

      // Return detailed result
      if (isBestChain) {
        return DagImportResult.mainBlock(blockEpoch, height, cumulativeDifficulty, isEpochWinner);
      } else {
        // Orphan blocks (height=0) are automatically stored in DagStore
        // They can be queried via dagStore.getPendingBlocks() for retry/reorganization
        log.debug("Block {} is orphan (epoch {}, cumDiff {})",
            block.getHash().toHexString().substring(0, 16),
            blockEpoch,
            cumulativeDifficulty.toDecimalString());

        return DagImportResult.orphan(blockEpoch, cumulativeDifficulty, isEpochWinner);
      }

    } catch (Exception e) {
      log.error("Error importing block {}: {}", block.getHash().toHexString(), e.getMessage(), e);
      return DagImportResult.error(e, "Exception during import: " + e.getMessage());
    }
  }

  /**
   * Validate basic block rules
   */
  private DagImportResult validateBasicRules(Block block) {
    // IMPORTANT: Check for genesis block FIRST, before timestamp validation
    // Genesis blocks use configured epoch timestamps that may not match current time
    // and should be exempt from general timestamp checks
    if (isGenesisBlock(block)) {
      // SECURITY: Genesis blocks can only be accepted if the chain is empty
      if (chainStats.getMainBlockCount() > 0) {
        log.warn("SECURITY: Rejecting genesis block {} - chain already initialized with {} blocks",
            block.getHash().toHexString(), chainStats.getMainBlockCount());
        return DagImportResult.invalidBasic(
            "Genesis block rejected: chain already has main blocks");
      }

      // Genesis block validation passed - skip timestamp checks
      // Genesis epoch comes from genesis.json configuration and is deterministic
      log.info("Accepting genesis block {} at epoch {} - deterministic from genesis.json",
          block.getHash().toHexString(), block.getEpoch());

      // Continue to other validations (structure, coinbase, etc.) but skip timestamp checks
      // by setting a flag or directly jumping to structure validation
    } else {
      // Regular blocks: Apply timestamp validation
      long currentTimestamp = TimeUtils.getCurrentEpoch();
      long blockTimestamp = TimeUtils.epochNumberToMainTime(block.getEpoch());
      if (blockTimestamp > (currentTimestamp + MAIN_CHAIN_PERIOD)) {
        log.debug("Block {} has invalid timestamp: {} (current: {})",
            block.getHash().toHexString(), blockTimestamp, currentTimestamp);
        return DagImportResult.invalidBasic("Block timestamp is too far in the future");
      }

      // BUGFIX: Convert xdagEra from Unix seconds to XDAG timestamp for comparison
      // Config stores Unix seconds, but Block uses XDAG timestamp (1/1024 second precision)
      long xdagEra = dagKernel.getConfig().getXdagEra();
      long xdagEraTimestamp = xdagEra * 1024;

      if (blockTimestamp < xdagEraTimestamp) {
        log.debug("Block {} timestamp {} is before XDAG era {} (Unix: {})",
            block.getHash().toHexString(), blockTimestamp, xdagEraTimestamp, xdagEra);
        return DagImportResult.invalidBasic("Block timestamp is before XDAG era");
      }
    }

    // Check if block already exists (applies to all blocks)
    if (dagStore.hasBlock(block.getHash())) {
      Block existingBlock = dagStore.getBlockByHash(block.getHash(), false);
      if (existingBlock != null && existingBlock.getInfo() != null
          && existingBlock.getInfo().getHeight() == 0) {
        // Orphan block exists - allow re-processing
        // When dependencies arrive, orphan blocks should be re-evaluated for main chain inclusion
        log.debug("Block {} exists as orphan, allowing re-processing",
            block.getHash().toHexString());
        // Continue with validation
      } else {
        log.debug("Block {} already exists as non-orphan", block.getHash().toHexString());
        return DagImportResult.duplicate();
      }
    }

    // Validate block structure
    if (!block.isValid()) {
      log.debug("Block {} failed structure validation", block.getHash().toHexString());
      return DagImportResult.invalidBasic("Block structure validation failed");
    }

    // SECURITY: Validate coinbase field length (must be exactly 20 bytes)
    // This prevents BufferOverflowException during hash calculation
    // and ensures all blocks follow the Ethereum-style 20-byte address format
    Bytes coinbase = block.getHeader().getCoinbase();
    if (coinbase == null) {
      log.warn("SECURITY: Rejecting block {} - null coinbase", block.getHash().toHexString());
      return DagImportResult.invalidBasic("Block coinbase is null");
    }
    if (coinbase.size() != 20) {
      log.warn("SECURITY: Rejecting block {} - invalid coinbase length: {} bytes (expected 20)",
          block.getHash().toHexString(), coinbase.size());
      return DagImportResult.invalidBasic(String.format(
          "Block coinbase must be exactly 20 bytes, got %d bytes", coinbase.size()));
    }

    return null;  // Validation passed
  }

  /**
   * Check if a block is a genesis block
   * <p>
   * SECURITY: Genesis block identification - Empty links (no parent blocks) - Difficulty exactly
   * equals 1 (minimal difficulty)
   *
   * @param block block to check
   * @return true if genesis block
   */
  private boolean isGenesisBlock(Block block) {
    return block.getLinks().isEmpty() &&
        block.getHeader().getDifficulty() != null &&
        block.getHeader().getDifficulty().equals(UInt256.ONE);
  }

  /**
   * Validate minimum PoW requirement (NEW CONSENSUS)
   * <p>
   * Ensures block hash satisfies the base difficulty target. This prevents spam blocks and ensures
   * basic work was done.
   * <p>
   * Rule: hash <= baseDifficultyTarget
   * <p>
   * Genesis blocks are exempt from this check.
   *
   * @param block block to validate
   * @return null if valid, error result if invalid
   */
  private DagImportResult validateMinimumPoW(Block block) {
    // Skip genesis blocks
    if (isGenesisBlock(block)) {
      return null;
    }

    UInt256 baseDifficultyTarget = chainStats.getBaseDifficultyTarget();

    // Skip validation if target not initialized (backward compatibility)
    if (baseDifficultyTarget == null) {
      log.warn("Base difficulty target not initialized, skipping PoW validation");
      return null;
    }

    // Calculate block hash as UInt256
    UInt256 blockHash = UInt256.fromBytes(block.getHash());

    // Check: hash <= baseDifficultyTarget
    if (blockHash.compareTo(baseDifficultyTarget) > 0) {
      log.debug("Block {} rejected: insufficient PoW (hash {} > target {})",
          block.getHash().toHexString().substring(0, 16),
          blockHash.toHexString().substring(0, 16),
          baseDifficultyTarget.toHexString().substring(0, 16));

      return DagImportResult.invalidBasic(String.format(
          "Insufficient proof of work: hash exceeds difficulty target (hash=%s, target=%s)",
          blockHash.toHexString().substring(0, 16) + "...",
          baseDifficultyTarget.toHexString().substring(0, 16) + "..."
      ));
    }

    log.debug("Block {} passed minimum PoW check (hash {} <= target {})",
        block.getHash().toHexString().substring(0, 16),
        blockHash.toHexString().substring(0, 16),
        baseDifficultyTarget.toHexString().substring(0, 16));

    return null;  // Validation passed
  }

  /**
   * Validate epoch block limit (NEW CONSENSUS)
   * <p>
   * Limits the number of blocks accepted per epoch to control orphan block growth. If epoch already
   * has MAX_BLOCKS_PER_EPOCH blocks, only accept new blocks if they have better difficulty (smaller
   * hash) than the worst existing block.
   * <p>
   * This implements a competitive admission policy: - First MAX_BLOCKS_PER_EPOCH blocks are always
   * accepted - Additional blocks must beat the weakest accepted block - Maintains top N blocks per
   * epoch
   *
   * @param block block to validate
   * @return null if valid, error result if should be rejected
   */
  private DagImportResult validateEpochLimit(Block block) {
    long epoch = block.getEpoch();
    List<Block> candidates = getCandidateBlocksInEpoch(epoch);

    // Count ALL candidate blocks in this epoch (both main and orphan blocks)
    // Each epoch should have at most MAX_BLOCKS_PER_EPOCH candidate blocks
    // (1 winner with height > 0, and up to 15 losers with height = 0)
    int candidateCount = candidates.size();

    // If under limit, accept
    if (candidateCount < MAX_BLOCKS_PER_EPOCH) {
      log.debug("Block {} accepted: epoch {} has {} < {} candidate blocks",
          block.getHash().toHexString().substring(0, 16),
          epoch, candidateCount, MAX_BLOCKS_PER_EPOCH);
      return null;  // Accept
    }

    // Epoch is full, check if this block is better than the worst one
    UInt256 thisBlockWork = calculateBlockWork(block.getHash());

    // Find the worst block (smallest work = largest hash)
    Block worstBlock = null;
    UInt256 worstWork = UInt256.MAX_VALUE;

    for (Block candidate : candidates) {
      UInt256 candidateWork = calculateBlockWork(candidate.getHash());
      if (candidateWork.compareTo(worstWork) < 0) {
        worstWork = candidateWork;
        worstBlock = candidate;
      }
    }

    // Compare with worst block
    if (thisBlockWork.compareTo(worstWork) > 0) {
      // This block is better, will replace the worst one
      log.info("Block {} will replace worse block {} in epoch {} (work {} > {})",
          block.getHash().toHexString().substring(0, 16),
          worstBlock.getHash().toHexString().substring(0, 16),
          epoch,
          thisBlockWork.toHexString().substring(0, 16),
          worstWork.toHexString().substring(0, 16));

      // Demote worst block to orphan
      demoteBlockToOrphan(worstBlock);

      return null;  // Accept this block
    } else {
      // This block is not better than worst, reject
      log.debug("Block {} rejected: epoch {} full and work {} <= worst {}",
          block.getHash().toHexString().substring(0, 16),
          epoch,
          thisBlockWork.toHexString().substring(0, 16),
          worstWork.toHexString().substring(0, 16));

      return DagImportResult.invalidBasic(String.format(
          "Epoch %d full (%d blocks) and this block's work not in top %d",
          epoch, MAX_BLOCKS_PER_EPOCH, MAX_BLOCKS_PER_EPOCH
      ));
    }
  }

  /**
   * Validate block links (Transaction and Block references)
   */
  private DagImportResult validateLinks(Block block) {
    ResolvedLinks resolved = entityResolver.resolveAllLinks(block);

    // Check for missing references
    if (!resolved.hasAllReferences()) {
      boolean allowBootstrap = chainStats.getMainBlockCount() <= 1;
      if (!allowBootstrap) {
        Bytes32 missing = resolved.getMissingReferences().getFirst();
        log.debug("Block {} has missing dependency: {}",
            block.getHash().toHexString(), missing.toHexString());

        // Save block to DagStore for later retry when dependency arrives
        // Blocks with missing dependencies are stored with height=0 (pending)
        // They can be queried via dagStore.getPendingBlocks() for retry
        try {
          Block persistableBlock = ensurePersistableBlock(block);
          dagStore.saveBlock(persistableBlock);

          log.info("Saved block {} to DagStore awaiting dependency: {}",
              block.getHash().toHexString().substring(0, 16),
              missing.toHexString().substring(0, 16));
        } catch (Exception e) {
          log.error("Failed to save pending block {}: {}",
              block.getHash().toHexString().substring(0, 16),
              e.getMessage());
        }

        return DagImportResult.missingDependency(
            missing,
            "Link target not found: " + missing.toHexString()
        );
      } else {
        log.warn(
            "Bootstrap mode: accepting block {} despite {} missing references (chain height={})",
            block.getHash().toHexString().substring(0, 16),
            resolved.getMissingReferences().size(),
            chainStats.getMainBlockCount());
        resolved = ResolvedLinks.builder()
            .referencedBlocks(new ArrayList<>(resolved.getReferencedBlocks()))
            .referencedTransactions(new ArrayList<>(resolved.getReferencedTransactions()))
            .missingReferences(List.of())
            .build();
      }
    }

    // Validate all referenced Blocks
    for (Block refBlock : resolved.getReferencedBlocks()) {
      // Validate epoch order: blocks can ONLY reference blocks from PREVIOUS epochs
      // Same epoch references are NOT allowed (prevents circular dependencies within epoch)
      // Future epoch references are NOT allowed (violates causality)
      if (refBlock.getEpoch() >= block.getEpoch()) {
        log.debug(
            "Block {} (epoch {}) references block {} (epoch {}) - invalid: must reference EARLIER epochs only",
            block.getHash().toHexString(), block.getEpoch(),
            refBlock.getHash().toHexString(), refBlock.getEpoch());
        return DagImportResult.invalidLink(
            String.format(
                "Referenced block epoch (%d) >= current block epoch (%d) - must reference earlier epochs only",
                refBlock.getEpoch(), block.getEpoch()),
            refBlock.getHash()
        );
      }

      // NEW: Check reference depth for network partition detection
      // This is a SOFT check - we warn but still accept the block
      // This allows handling network partition/merge scenarios
      long referenceDepth = block.getEpoch() - refBlock.getEpoch();

      if (referenceDepth > SYNC_MAX_REFERENCE_DEPTH) {
        // Possible network partition scenario
        log.warn("Block {} (epoch {}) references very old block {} (epoch {}) - depth: {} epochs",
            block.getHash().toHexString().substring(0, 16),
            block.getEpoch(),
            refBlock.getHash().toHexString().substring(0, 16),
            refBlock.getEpoch(),
            referenceDepth);
        log.warn(
            "This may indicate a network partition merge scenario (partition duration: ~{} hours)",
            referenceDepth * 64 / 3600.0);
        log.warn("Block will be accepted, but node operators should verify chain consistency");
        // Continue validation - do NOT reject
      }
    }

    // Validate all referenced Transactions
    for (Transaction tx : resolved.getReferencedTransactions()) {
      // Validate transaction structure
      if (!tx.isValid()) {
        log.debug("Transaction {} has invalid structure", tx.getHash().toHexString());
        return DagImportResult.invalidLink("Invalid transaction structure", tx.getHash());
      }

      // Validate transaction signature
      if (!tx.verifySignature()) {
        log.debug("Transaction {} has invalid signature", tx.getHash().toHexString());
        return DagImportResult.invalidLink("Invalid transaction signature", tx.getHash());
      }

      // Validate transaction amount
      if (tx.getAmount().add(tx.getFee()).subtract(MIN_GAS).isNegative()) {
        log.debug("Transaction {} has insufficient amount", tx.getHash().toHexString());
        return DagImportResult.invalidLink("Transaction amount + fee < MIN_GAS", tx.getHash());
      }
    }

    log.debug("Block {} link validation passed: {} block links, {} transaction links",
        block.getHash().toHexString(),
        resolved.getReferencedBlocks().size(),
        resolved.getReferencedTransactions().size());

    return null;  // Validation passed
  }

  /**
   * Update chain statistics for new main block
   *
   * <p>IMPORTANT: When a block replaces another via epoch competition, its height may be
   * less than the current mainBlockCount. In this case, we should NOT decrease mainBlockCount. Only
   * increase it when the new block truly extends the chain.
   */
  private synchronized void updateChainStatsForNewMainBlock(BlockInfo blockInfo) {
    // Only update mainBlockCount if this block extends the chain
    // (for epoch competition replacements, height < mainBlockCount, so we keep the higher value)
    long newMainBlockCount = Math.max(chainStats.getMainBlockCount(), blockInfo.getHeight());

    chainStats = chainStats
        .withMainBlockCount(newMainBlockCount)
        .withDifficulty(blockInfo.getDifficulty());

    // Save updated stats
    dagStore.saveChainStats(chainStats);

    log.debug("Updated chain stats: mainBlockCount={}, difficulty={}",
        chainStats.getMainBlockCount(), chainStats.getDifficulty().toDecimalString());
  }

  /**
   * Clean up old orphan blocks beyond retention window (NEW CONSENSUS)
   * <p>
   * Periodically removes orphan blocks older than ORPHAN_RETENTION_WINDOW (16384 epochs = 12 days).
   * According to XDAG rules, blocks can only reference blocks within 12 days, so older orphan
   * blocks cannot become main blocks anymore and can be safely deleted.
   * <p>
   * Runs every ORPHAN_CLEANUP_INTERVAL (100) epochs to maintain manageable orphan pool size.
   *
   * @param currentEpoch current epoch number
   */
  private synchronized void cleanupOldOrphans(long currentEpoch) {
    long lastCleanupEpoch = chainStats.getLastOrphanCleanupEpoch();

    // Check if cleanup interval reached
    if (currentEpoch - lastCleanupEpoch < ORPHAN_CLEANUP_INTERVAL) {
      return;  // Not time yet
    }

    log.info("Orphan cleanup triggered at epoch {} (last cleanup: epoch {})",
        currentEpoch, lastCleanupEpoch);

    long cutoffEpoch = currentEpoch - ORPHAN_RETENTION_WINDOW;
    if (cutoffEpoch < 0) {
      cutoffEpoch = 0;  // Don't go negative
    }

    int removedCount = 0;
    long scanStartEpoch = Math.max(0, lastCleanupEpoch - ORPHAN_RETENTION_WINDOW);

    // Scan epochs from last cleanup to cutoff epoch
    for (long epoch = scanStartEpoch; epoch < cutoffEpoch; epoch++) {
      List<Block> candidates = getCandidateBlocksInEpoch(epoch);

      for (Block block : candidates) {
        // Remove orphan blocks (height = 0)
        if (block.getInfo() != null && block.getInfo().getHeight() == 0) {
          try {
            dagStore.deleteBlock(block.getHash());
            removedCount++;

            if (removedCount % 1000 == 0) {
              log.debug("Orphan cleanup progress: removed {} blocks so far", removedCount);
            }
          } catch (Exception e) {
            log.warn("Failed to delete orphan block {}: {}",
                block.getHash().toHexString().substring(0, 16), e.getMessage());
          }
        }
      }
    }

    log.info("Orphan cleanup completed: removed {} blocks from epochs {} to {} (~{} days old)",
        removedCount, scanStartEpoch, cutoffEpoch,
        (currentEpoch - cutoffEpoch) * 64 / 86400);

    // Update last cleanup epoch
    chainStats = chainStats.toBuilder()
        .lastOrphanCleanupEpoch(currentEpoch)
        .build();
    dagStore.saveChainStats(chainStats);
  }

  /**
   * Check and adjust difficulty target if needed (NEW CONSENSUS)
   * <p>
   * Adjusts baseDifficultyTarget every DIFFICULTY_ADJUSTMENT_INTERVAL (1000) epochs based on
   * average blocks per epoch to maintain TARGET_BLOCKS_PER_EPOCH (150) blocks/epoch.
   * <p>
   * Algorithm: - If avgBlocksPerEpoch > TARGET * 1.5 → increase difficulty (lower target) - If
   * avgBlocksPerEpoch < TARGET * 0.5 → decrease difficulty (raise target) - Adjustment limited to
   * MIN_ADJUSTMENT_FACTOR (0.5x) to MAX_ADJUSTMENT_FACTOR (2x)
   *
   * @param currentHeight current main block height
   * @param currentEpoch  current epoch number
   */
  private synchronized void checkAndAdjustDifficulty(long currentHeight, long currentEpoch) {
    long lastAdjustmentEpoch = chainStats.getLastDifficultyAdjustmentEpoch();

    // Check if adjustment interval reached
    if (currentEpoch - lastAdjustmentEpoch < DIFFICULTY_ADJUSTMENT_INTERVAL) {
      return;  // Not time yet
    }

    log.info("Difficulty adjustment triggered at epoch {} (last adjustment: epoch {})",
        currentEpoch, lastAdjustmentEpoch);

    // Calculate average blocks per epoch in the adjustment period
    long totalBlocks = 0;
    long epochCount = 0;

    for (long epoch = lastAdjustmentEpoch; epoch < currentEpoch; epoch++) {
      List<Block> blocks = getCandidateBlocksInEpoch(epoch);
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
      return;
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
  }

  /**
   * Notify listeners of new block
   */
  private void notifyListeners(Block block) {
    synchronized (listeners) {
      if (listeners.isEmpty()) {
        return;
      }

      for (Listener listener : listeners) {
        try {
          byte[] blockBytes = block.toBytes();
          listener.onMessage(new BlockMessage(Bytes.wrap(blockBytes), MessageType.NEW_LINK));
          log.debug("Notified listener {} of new block: {}",
              listener.getClass().getSimpleName(),
              block.getHash().toHexString().substring(0, 16) + "...");
        } catch (Exception e) {
          log.error("Error notifying listener {}: {}",
              listener.getClass().getSimpleName(), e.getMessage(), e);
        }
      }
    }
  }

  // ==================== Block Creation Operations ====================

  @Override
  public Block createCandidateBlock() {
    log.info("Creating candidate block for mining");

    // BUGFIX: Use epoch number directly; block timestamp is derived at epoch end for display
    long epoch = TimeUtils.getCurrentEpochNumber();

    // Use baseDifficultyTarget from chain stats (NEW CONSENSUS)
    UInt256 difficultyTarget = chainStats.getBaseDifficultyTarget();
    if (difficultyTarget == null) {
      // Fallback for uninitialized chains
      difficultyTarget = INITIAL_BASE_DIFFICULTY_TARGET;
      log.warn("Base difficulty target not set, using initial value: {}",
          difficultyTarget.toHexString().substring(0, 16) + "...");
    }

    Bytes coinbase = miningCoinbase;
    List<Link> links = collectCandidateLinks();

    Block candidateBlock = Block.createCandidate(epoch, difficultyTarget, coinbase, links);

    log.info("Created mining candidate block: epoch={}, target={}, links={}, hash={}",
        epoch,
        difficultyTarget.toHexString().substring(0, 16) + "...",
        links.size(),
        candidateBlock.getHash().toHexString().substring(0, 16) + "...");

    return candidateBlock;
  }

  /**
   * Collect links for candidate block creation (REFACTORED)
   *
   * <p>NEW STRATEGY: Reference "top 16 candidates from previous height's epoch" + up to 1024
   * transactions from pool
   *
   * <p>Algorithm:
   * <ol>
   *   <li>Get previous main block (height N-1)</li>
   *   <li>Check if node is up-to-date (strict mining reference depth limit)</li>
   *   <li>Get all candidates in that block's epoch</li>
   *   <li>Sort by work (descending), take top 16 (MAX_BLOCK_LINKS)</li>
   *   <li>Add references to these 16 blocks</li>
   *   <li>Add up to 1024 transaction references from transaction pool</li>
   * </ol>
   *
   * <p>RATIONALE:
   * <ul>
   *   <li>Height N-1 is already confirmed (epoch competition finished)</li>
   *   <li>All candidates are valid and can be referenced</li>
   *   <li>Top 16 includes the winner (highest work) and top candidates</li>
   *   <li>Gives epoch losers a chance to be referenced</li>
   *   <li>Strict reference limit prevents outdated nodes from mining</li>
   *   <li>MAX_LINKS_PER_BLOCK (1,485,000) allows massive transaction throughput</li>
   *   <li>Initial limit of 1024 txs is conservative for stability</li>
   * </ul>
   *
   * <p>See: docs/DESIGN-BLOCK-LINK-ORPHAN-STORE-REFACTOR.md
   *
   * @return list of links (16 block links + up to 1024 tx links), empty list if node is too far
   *     behind
   */
  private List<Link> collectCandidateLinks() {
    List<Link> links = new ArrayList<>();

    // Step 1: Get previous main block (height N-1)
    // IMPORTANT: mainBlockCount is 0-indexed relative to next block height
    // When Genesis (height=1) exists, mainBlockCount=0
    // So we need to get block at height (mainBlockCount), NOT (mainBlockCount-1)
    long currentMainHeight = chainStats.getMainBlockCount();

    // Try to get the last main block
    // If mainBlockCount==0, this will try to get Genesis at height 1
    // If mainBlockCount>0, this gets the actual last main block
    long lastBlockHeight = Math.max(1, currentMainHeight);

    log.debug("Collecting candidate links: currentMainHeight={}, lastBlockHeight={}",
        currentMainHeight, lastBlockHeight);

    Block prevMainBlock = dagStore.getMainBlockByHeight(lastBlockHeight, false);

    if (prevMainBlock == null) {
      // No blocks exist yet (not even Genesis) - this should only happen during initialization
      log.error(
          "ERROR: Cannot find block at height {}! currentMainHeight={}, Genesis might not be imported!",
          lastBlockHeight, currentMainHeight);
      return links;
    }

    log.debug("Found previous main block at height {}, epoch={}, hash={}",
        lastBlockHeight, prevMainBlock.getEpoch(),
        prevMainBlock.getHash().toHexString().substring(0, 16));

    // Step 2: STRICT mining reference depth check
    // Prevent outdated nodes from creating blocks with stale references
    long currentEpoch = TimeUtils.getCurrentEpochNumber();
    long prevEpoch = prevMainBlock.getEpoch();
    long referenceDepth = currentEpoch - prevEpoch;

    // DEVNET: Skip reference depth check to allow development/testing with arbitrary epoch gaps
    // In development, nodes may be stopped for extended periods, and strict epoch checks
    // would prevent mining even after successful sync
    boolean isDevnet = dagKernel.getConfig().getNodeSpec().getNetwork()
        .toString().toLowerCase().contains("devnet");

    boolean allowBootstrap = currentMainHeight <= 1;

    if (!isDevnet) {
      // Production networks: enforce strict reference depth limit
      if (referenceDepth > MINING_MAX_REFERENCE_DEPTH && !allowBootstrap) {
        log.error(
            "MINING BLOCKED: Previous main block (epoch {}) is {} epochs behind current epoch {}",
            prevEpoch, referenceDepth, currentEpoch);
        log.error("Maximum allowed reference depth for mining: {} epochs (~{} minutes)",
            MINING_MAX_REFERENCE_DEPTH, MINING_MAX_REFERENCE_DEPTH * 64 / 60);
        log.error("Node must sync to latest epoch before mining can resume");
        log.error("Current lag: {} epochs (~{} minutes)",
            referenceDepth, referenceDepth * 64 / 60);
        return links;  // Return empty links to prevent mining
      } else if (referenceDepth > MINING_MAX_REFERENCE_DEPTH) {
        log.warn(
            "Reference depth {} exceeds limit {} but allowing bootstrap because currentMainHeight={}",
            referenceDepth, MINING_MAX_REFERENCE_DEPTH, currentMainHeight);
      }
    } else {
      // DEVNET: Log reference depth but don't block mining
      if (referenceDepth > MINING_MAX_REFERENCE_DEPTH) {
        log.info(
            "DEVNET: Reference depth {} epochs (~{} minutes) exceeds normal limit {}, but allowing for development",
            referenceDepth, referenceDepth * 64 / 60, MINING_MAX_REFERENCE_DEPTH);
      }
    }

    // Step 3: Get all candidates in prev block's epoch
    log.debug("Collecting links from height {} (epoch {}), reference depth: {} epochs",
        currentMainHeight, prevEpoch, referenceDepth);

    List<Block> candidates = getCandidateBlocksInEpoch(prevEpoch);

    if (candidates.isEmpty()) {
      // Fallback: if no candidates found (shouldn't happen), at least reference prev main block
      log.warn("No candidates found in epoch {}, only referencing prev main block", prevEpoch);
      links.add(Link.toBlock(prevMainBlock.getHash()));
      return links;
    }

    // Step 3: Sort by work (descending), take top 16 (MAX_BLOCK_LINKS)
    // Work = MAX_UINT256 / hash → smaller hash = more work
    List<Block> top16 = candidates.stream()
        .sorted((b1, b2) -> {
          UInt256 work1 = calculateBlockWork(b1.getHash());
          UInt256 work2 = calculateBlockWork(b2.getHash());
          return work2.compareTo(work1);  // Descending: largest work first
        })
        .limit(Block.MAX_BLOCK_LINKS)
        .toList();

    // Step 4: Add block references
    for (Block block : top16) {
      links.add(Link.toBlock(block.getHash()));
    }

    log.info("Collected {} block links from height {} epoch {} (top {} of {} candidates)",
        links.size(), currentMainHeight, prevEpoch,
        Math.min(Block.MAX_BLOCK_LINKS, candidates.size()), candidates.size());

    // Step 5: Add transaction links from transaction pool
    // MAX_LINKS_PER_BLOCK (1,485,000) - MAX_BLOCK_LINKS (16) = 1,484,984 available for transactions
    // Initial conservative limit: 1024 transactions per block for stability
    final int MAX_TX_LINKS_PER_BLOCK = 1024;

    TransactionPool txPool = dagKernel.getTransactionPool();
    if (txPool != null && txPool.size() > 0) {
      // Select transactions from pool (ordered by fee, highest first)
      List<Transaction> selectedTxs = txPool.selectTransactions(MAX_TX_LINKS_PER_BLOCK);

      for (Transaction tx : selectedTxs) {
        links.add(Link.toTransaction(tx.getHash()));
      }

      log.info("Added {} transaction links from pool ({} total pending, limit {})",
          selectedTxs.size(), txPool.size(), MAX_TX_LINKS_PER_BLOCK);
    } else {
      log.debug("Transaction pool is empty or not available, no transaction links added");
    }

    return links;
  }

  /**
   * Set mining coinbase address
   *
   * @param coinbase mining reward address (20 bytes)
   */
  public void setMiningCoinbase(Bytes coinbase) {
    Bytes normalized;
    if (coinbase == null) {
      log.warn("Null coinbase provided, using zero address (20 bytes)");
      normalized = Bytes.wrap(new byte[20]);
    } else if (coinbase.size() > 20) {
      log.warn("Coinbase address too long ({} bytes), truncating to 20 bytes: {} -> {}",
          coinbase.size(),
          coinbase.toHexString(),
          coinbase.slice(0, 20).toHexString());
      normalized = coinbase.slice(0, 20);
    } else if (coinbase.size() < 20) {
      byte[] padded = new byte[20];
      System.arraycopy(coinbase.toArray(), 0, padded, 0, coinbase.size());
      log.warn("Coinbase address too short ({} bytes), padding to 20 bytes: {} -> {}",
          coinbase.size(),
          coinbase.toHexString(),
          Bytes.wrap(padded).toHexString());
      normalized = Bytes.wrap(padded);
    } else {
      normalized = coinbase;
    }

    if (normalized.equals(this.miningCoinbase)) {
      return;
    }

    this.miningCoinbase = normalized;
    log.info("Mining coinbase address set: {}", normalized.toHexString().substring(0, 16) + "...");
  }

  @Override
  public Block createGenesisBlock(long epoch) {
    log.info("Creating genesis block at epoch {}", epoch);

    // IMPORTANT: Block.createWithNonce() expects epoch number, not timestamp
    // Block.getTimestamp() derives display time via TimeUtils.epochNumberToMainTime(...)
    // So we pass the epoch number directly
    log.info("  - Genesis epoch: {} (timestamp will be main time: {})", epoch,
        TimeUtils.epochNumberToMainTime(epoch));

    // Use zero address (20 bytes) for genesis coinbase
    Bytes coinbase = Bytes.wrap(new byte[20]);
    log.info("  - Using zero coinbase (genesis block)");

    // Get difficulty from genesis config (not hardcoded)
    UInt256 difficulty = dagKernel.getGenesisConfig().getDifficultyUInt256();
    log.info("  - Genesis difficulty: {}", difficulty.toHexString());

    // Create genesis block with epoch number (NOT timestamp)
    // Block.getTimestamp() uses TimeUtils helper to derive display timestamp
    Block genesisBlock = Block.createWithNonce(
        epoch,  // Pass epoch number, Block will convert to timestamp
        difficulty,  // Use configured difficulty from genesis.json
        Bytes32.ZERO,
        coinbase,
        List.of()
    );

    log.info("✓ Genesis block created: hash={}, epoch={}",
        genesisBlock.getHash().toHexString(),
        genesisBlock.getEpoch());

    return genesisBlock;
  }

  // ==================== Main Chain Queries (Height-Based) ====================

  @Override
  public Block getMainBlockByHeight(long height) {
    return dagStore.getMainBlockByHeight(height, false);
  }

  @Override
  public long getMainChainLength() {
    return chainStats.getMainBlockCount();
  }

  @Override
  public List<Block> listMainBlocks(int count) {
    return dagStore.listMainBlocks(count);
  }

  /**
   * Find parent block with maximum cumulative difficulty
   */
  private Block findMaxDifficultyParent(Block block) {
    Block maxDiffParent = null;
    UInt256 maxDiff = UInt256.ZERO;

    for (Link link : block.getLinks()) {
      if (link.isBlock()) {
        Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
        if (parent != null && parent.getInfo() != null) {
          UInt256 parentDiff = parent.getInfo().getDifficulty();
          if (parentDiff.compareTo(maxDiff) > 0) {
            maxDiff = parentDiff;
            maxDiffParent = parent;
          }
        }
      }
    }

    return maxDiffParent;
  }

  // ==================== Epoch Queries (Time-Based) ====================

  @Override
  public long getCurrentEpoch() {
    return TimeUtils.getCurrentEpochNumber();
  }

  @Override
  public long[] getEpochTimeRange(long epoch) {
    // IMPORTANT: Time range must be [startTime, endTime) with EXCLUSIVE end
    // Blocks with timestamp = endTime belong to NEXT epoch!
    // Example: epoch 23693854 → [TimeUtils.epochNumberToEpoch(23693854), TimeUtils.epochNumberToEpoch(23693855))
    long start = TimeUtils.epochNumberToEpoch(epoch);
    long end = TimeUtils.epochNumberToEpoch(epoch + 1);
    return new long[]{start, end};
  }

  @Override
  public List<Block> getCandidateBlocksInEpoch(long epoch) {
    return dagStore.getCandidateBlocksInEpoch(epoch);
  }

  @Override
  public Block getWinnerBlockInEpoch(long epoch) {
    List<Block> candidates = getCandidateBlocksInEpoch(epoch);

    // FALLBACK: If time-range query returns empty, scan recent main blocks manually
    // This works around potential DagStore indexing/caching issues
    //
    // LIMITATION: Fallback only scans main blocks (height > 0), not orphan blocks (height = 0)
    // Rationale:
    //   1. This is an exception path - epoch index should always work
    //   2. getWinnerBlockInEpoch() only needs the winner, which should be a main block
    //   3. Scanning all orphan blocks would be expensive and complex
    //   4. If epoch index is broken, fix the index instead of relying on fallback
    //
    // Impact: If epoch index fails AND winner hasn't been promoted to main block yet,
    // fallback will return null instead of the pending winner (rare edge case)
    if (candidates.isEmpty() && chainStats.getMainBlockCount() > 0) {
      log.debug("Time-range query returned 0 blocks for epoch {}, using fallback scan (main blocks only)", epoch);
      candidates = new ArrayList<>();

      // Scan last 100 main blocks for blocks in this epoch
      long scanStart = Math.max(1, chainStats.getMainBlockCount() - 100);
      log.debug("  Fallback scan range: height {} to {} (main blocks only)",
          scanStart, chainStats.getMainBlockCount());

      for (long height = chainStats.getMainBlockCount(); height >= scanStart; height--) {
        Block block = dagStore.getMainBlockByHeight(height, false);
        if (block != null) {
          long blockEpoch = block.getEpoch();
          log.debug("    Height {}: block={}, epoch={}, match={}",
              height,
              block.getHash().toHexString().substring(0, 16),
              blockEpoch,
              blockEpoch == epoch);

          if (blockEpoch == epoch) {
            candidates.add(block);
            log.debug("  ✓ Found main block at height {} in epoch {} via fallback scan", height, epoch);
          }
        }
      }

      if (!candidates.isEmpty()) {
        log.info("Fallback scan found {} main block(s) in epoch {} (time-range query failed)",
            candidates.size(), epoch);
      } else {
        log.warn("Fallback scan found NO blocks in epoch {} (scanned main blocks heights {} to {})",
            epoch, scanStart, chainStats.getMainBlockCount());
        log.warn("If epoch {} should have blocks, the epoch index may be corrupted", epoch);
      }
    }

    if (candidates.isEmpty()) {
      return null;
    }

    // Find block with smallest hash among ALL candidates in this epoch
    // IMPORTANT: Don't filter by height here! During import, blocks may have height=0 (pending)
    // but still need to participate in epoch competition. The height filter would cause
    // a race condition where the second block doesn't see the first block as a competitor.
    Block winner = null;
    Bytes32 smallestHash = null;

    for (Block candidate : candidates) {
      if (candidate.getInfo() != null) {
        // Consider ALL blocks in epoch, including those with height=0 (pending assignment)
        if (smallestHash == null || candidate.getHash().compareTo(smallestHash) < 0) {
          smallestHash = candidate.getHash();
          winner = candidate;
        }
      }
    }

    return winner;
  }

  @Override
  public EpochStats getEpochStats(long epoch) {
    List<Block> candidates = getCandidateBlocksInEpoch(epoch);
    Block winner = getWinnerBlockInEpoch(epoch);

    // Calculate average block time
    double avgBlockTime = 0.0;
    if (!candidates.isEmpty()) {
      long[] timeRange = getEpochTimeRange(epoch);
      long epochDuration = timeRange[1] - timeRange[0];
      avgBlockTime = (double) epochDuration / candidates.size();
    }

    // Calculate total difficulty added in this epoch
    UInt256 totalDifficulty = UInt256.ZERO;
    for (Block candidate : candidates) {
      if (candidate.getInfo() != null) {
        UInt256 blockWork = calculateBlockWork(candidate.getHash());
        totalDifficulty = totalDifficulty.add(blockWork);
      }
    }

    return EpochStats.builder()
        .epoch(epoch)
        .startTime(epoch * 64)
        .endTime((epoch + 1) * 64)
        .totalBlocks(candidates.size())
        .winningBlockHash(winner != null ? winner.getHash() : null)
        .averageBlockTime(avgBlockTime)
        .totalDifficulty(totalDifficulty)
        .hasMainBlock(winner != null)
        .build();
  }

  // ==================== General Block Queries ====================

  @Override
  public Block getBlockByHash(Bytes32 hash, boolean isRaw) {
    return dagStore.getBlockByHash(hash, isRaw);
  }

  // ==================== Cumulative Difficulty ====================

  @Override
  public UInt256 calculateCumulativeDifficulty(Block block) {
    // IMPORTANT: This implements XDAG's epoch-based difficulty calculation
    // matching C code logic in block.c:724-735
    //
    // Rule: Blocks in the SAME epoch do NOT accumulate difficulty
    // Only cross-epoch references accumulate difficulty
    // This ensures fair competition within each epoch

    long blockEpoch = block.getEpoch();
    UInt256 maxParentDifficulty = UInt256.ZERO;

    for (Link link : block.getLinks()) {
      if (link.isBlock()) {
        Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
        if (parent != null && parent.getInfo() != null) {
          long parentEpoch = parent.getEpoch();

          if (parentEpoch < blockEpoch) {
            // Case 1: Parent is in PREVIOUS epoch
            // → Accumulate difficulty (C code: diff = xdag_diff_add(diff0, blockRef->difficulty))
            UInt256 parentDifficulty = parent.getInfo().getDifficulty();
            if (parentDifficulty.compareTo(maxParentDifficulty) > 0) {
              maxParentDifficulty = parentDifficulty;
            }
          } else {
            // Case 2: Parent is in SAME epoch
            // → Do NOT accumulate (C code: diff = blockRef->difficulty, skip same-epoch blocks)
            // Skip this parent and continue searching for cross-epoch references
            log.debug("Skipping same-epoch parent {} (epoch {})",
                parent.getHash().toHexString().substring(0, 16), parentEpoch);
          }
        }
      }
    }

    // Calculate this block's work
    UInt256 blockWork = calculateBlockWork(block.getHash());

    // Cumulative difficulty = parent max difficulty (from previous epochs) + block work
    UInt256 cumulativeDifficulty = maxParentDifficulty.add(blockWork);

    log.debug(
        "Calculated cumulative difficulty for block {} (epoch {}): parent={}, work={}, cumulative={}",
        block.getHash().toHexString().substring(0, 16),
        blockEpoch,
        maxParentDifficulty.toDecimalString(),
        blockWork.toDecimalString(),
        cumulativeDifficulty.toDecimalString());

    return cumulativeDifficulty;
  }

  @Override
  public UInt256 calculateBlockWork(Bytes32 hash) {
    // XDAG philosophy: work = MAX_UINT256 / hash
    // Smaller hash = more work = higher difficulty

    BigInteger hashValue = new BigInteger(1, hash.toArray());

    if (hashValue.equals(BigInteger.ZERO)) {
      // Theoretically impossible, but handle gracefully
      log.warn("Block hash is zero, returning MAX_VALUE work");
      return UInt256.MAX_VALUE;
    }

    BigInteger maxUint256 = UInt256.MAX_VALUE.toBigInteger();
    BigInteger work = maxUint256.divide(hashValue);

    return UInt256.valueOf(work);
  }

  // ==================== DAG Structure Validation ====================

  @Override
  public DAGValidationResult validateDAGRules(Block block) {
    // Genesis block doesn't need DAG validation (it's the first block)
    if (isGenesisBlock(block)) {
      return DAGValidationResult.valid();
    }

    // Rule 1: Check for cycles
    if (hasCycle(block)) {
      return DAGValidationResult.invalid(
          DAGValidationResult.DAGErrorCode.CYCLE_DETECTED,
          "Block creates a cycle in DAG"
      );
    }

    // Rule 2: Check time window (12 days / 16384 epochs)
    // DEVNET: Skip time window validation for testing with old genesis blocks
    boolean isDevnet = dagKernel.getConfig().getNodeSpec().getNetwork().toString().toLowerCase()
        .contains("devnet");
    if (!isDevnet) {
      long currentEpoch = getCurrentEpoch();
      for (Link link : block.getLinks()) {
        if (link.isBlock()) {
          Block refBlock = dagStore.getBlockByHash(link.getTargetHash(), false);
          if (refBlock != null) {
            long refEpoch = refBlock.getEpoch();
            if (currentEpoch - refEpoch > 16384) {
              return DAGValidationResult.invalid(
                  DAGValidationResult.DAGErrorCode.TIME_WINDOW_VIOLATION,
                  "Referenced block is too old (>" + (currentEpoch - refEpoch) + " epochs)"
              );
            }
          }
        }
      }
    }

    // Rule 3: Check link count (1-16 block links)
    // DEVNET: Allow 0 links for first block when chain is empty
    long blockLinkCount = block.getLinks().stream()
        .filter(link -> !link.isTransaction())
        .count();

    boolean isFirstBlock = (blockLinkCount == 0 && chainStats.getMainBlockCount() == 0);
    if (!isFirstBlock && (blockLinkCount < 1 || blockLinkCount > 16)) {
      return DAGValidationResult.invalid(
          DAGValidationResult.DAGErrorCode.INVALID_LINK_COUNT,
          "Block must have 1-16 block links (found " + blockLinkCount + ")"
      );
    }

    // Rule 4: Check timestamp order (already checked in validateLinks)

    // Rule 5: Check traversal depth
    int depth = calculateDepthFromGenesis(block);
    if (depth > 1000) {
      return DAGValidationResult.invalid(
          DAGValidationResult.DAGErrorCode.TRAVERSAL_DEPTH_EXCEEDED,
          "Block depth from genesis exceeds 1000 layers (depth=" + depth + ")"
      );
    }

    return DAGValidationResult.valid();
  }

  /**
   * Check if adding this block creates a cycle in the DAG
   */
  private boolean hasCycle(Block block) {
    Set<Bytes32> visited = new HashSet<>();
    Set<Bytes32> recursionStack = new HashSet<>();

    return hasCycleDFS(block.getHash(), visited, recursionStack);
  }

  /**
   * DFS-based cycle detection
   */
  private boolean hasCycleDFS(Bytes32 currentHash, Set<Bytes32> visited,
      Set<Bytes32> recursionStack) {
    visited.add(currentHash);
    recursionStack.add(currentHash);

    Block current = dagStore.getBlockByHash(currentHash, false);
    if (current != null) {
      for (Link link : current.getLinks()) {
        if (link.isBlock()) {
          Bytes32 childHash = link.getTargetHash();

          if (!visited.contains(childHash)) {
            if (hasCycleDFS(childHash, visited, recursionStack)) {
              return true;
            }
          } else if (recursionStack.contains(childHash)) {
            // Found a back edge (cycle)
            return true;
          }
        }
      }
    }

    recursionStack.remove(currentHash);
    return false;
  }

  /**
   * Calculate depth from genesis
   */
  private int calculateDepthFromGenesis(Block block) {
    int maxDepth = 0;

    for (Link link : block.getLinks()) {
      if (link.isBlock()) {
        Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
        if (parent != null) {
          int parentDepth = calculateDepthFromGenesis(parent);
          maxDepth = Math.max(maxDepth, parentDepth + 1);
        }
      }
    }

    return maxDepth;
  }

  @Override
  public boolean isBlockInMainChain(Bytes32 hash) {
    Block block = dagStore.getBlockByHash(hash, false);

    if (block == null || block.getInfo() == null) {
      return false;
    }

    // Check if it's a main block (height > 0)
    if (block.getInfo().getHeight() > 0) {
      return true;
    }

    // Check if it's referenced by a main block
    List<Block> references = getBlockReferences(hash);
    for (Block ref : references) {
      if (isBlockInMainChain(ref.getHash())) {
        return true;
      }
    }

    return false;
  }

  @Override
  public List<Block> getBlockReferences(Bytes32 hash) {
    // DagStore returns List<Bytes32> (hashes), need to convert to List<Block>
    List<Bytes32> hashes = dagStore.getBlockReferences(hash);
    return hashes.stream()
        .map(h -> dagStore.getBlockByHash(h, false))
        .filter(block -> block != null)
        .collect(Collectors.toList());
  }

  // ==================== Chain Management ====================

  @Override
  public synchronized void checkNewMain() {
    log.debug("Running checkNewMain() - assigning heights to epoch winners based on epoch order");

    long currentEpoch = getCurrentEpoch();
    long scanStartEpoch = Math.max(1, currentEpoch - ORPHAN_RETENTION_WINDOW);

    // Step 1: Collect all epoch winners and their epochs
    // Map: epoch -> winner block
    Map<Long, Block> epochWinners = new java.util.TreeMap<>();

    for (long epoch = scanStartEpoch; epoch <= currentEpoch; epoch++) {
      Block winner = getWinnerBlockInEpoch(epoch);
      if (winner != null) {
        epochWinners.put(epoch, winner);
      }
    }

    if (epochWinners.isEmpty()) {
      log.warn("No epoch winners found in range {} to {}", scanStartEpoch, currentEpoch);
      return;
    }

    // Step 2: Sort epoch winners by epoch number (ascending - earliest first)
    // Heights follow epoch order: epoch 100 → height 1, epoch 101 → height 2, etc.
    // This ensures continuous heights even with discontinuous epochs
    List<Map.Entry<Long, Block>> sortedWinners = new ArrayList<>(epochWinners.entrySet());
    sortedWinners.sort(Map.Entry.comparingByKey());  // Sort by epoch (key)

    // Step 3: Assign continuous heights: 1, 2, 3, 4, ...
    long height = 1;
    Block topBlock = null;
    UInt256 maxDifficulty = UInt256.ZERO;

    for (Map.Entry<Long, Block> entry : sortedWinners) {
      long epoch = entry.getKey();
      Block winner = entry.getValue();

      // Check if height needs update
      if (winner.getInfo() == null || winner.getInfo().getHeight() != height) {
        log.debug("Assigning height {} to epoch {} winner {}",
            height, epoch,
            winner.getHash().toHexString().substring(0, 16));

        // Update BlockInfo with new height
        BlockInfo updatedInfo = (winner.getInfo() != null ? winner.getInfo().toBuilder()
            : BlockInfo.builder())
            .hash(winner.getHash())
            .epoch(winner.getEpoch())
            .height(height)
            .difficulty(winner.getInfo() != null ? winner.getInfo().getDifficulty() : UInt256.ZERO)
            .build();

        dagStore.saveBlockInfo(updatedInfo);

        // Save updated block
        Block updatedBlock = winner.toBuilder().info(updatedInfo).build();
        dagStore.saveBlock(updatedBlock);

        log.info("Assigned height {} to block {} (epoch {})",
            height, winner.getHash().toHexString().substring(0, 16), epoch);
      }

      // Track top block (last in epoch-sorted order)
      topBlock = winner;
      if (winner.getInfo() != null
          && winner.getInfo().getDifficulty().compareTo(maxDifficulty) > 0) {
        maxDifficulty = winner.getInfo().getDifficulty();
      }

      height++;
    }

    // Step 4: Update chain stats
    long finalMainBlockCount = height - 1;  // height is now one past the last assigned
    if (topBlock != null) {
      chainStats = chainStats
          .withMainBlockCount(finalMainBlockCount)
          .withDifficulty(maxDifficulty);

      dagStore.saveChainStats(chainStats);

      log.info("Height assignment completed: {} epoch winners assigned heights 1 to {}",
          sortedWinners.size(), finalMainBlockCount);
      log.debug("Epoch range: {} to {}, mainBlockCount={}",
          sortedWinners.get(0).getKey(),
          sortedWinners.get(sortedWinners.size() - 1).getKey(),
          finalMainBlockCount);
    }

    // Step 5: Check for chain reorganization (orphan blocks with higher cumulative difficulty)
    checkChainReorganization();

    log.debug("checkNewMain() completed: mainBlockCount={}, difficulty={}",
        chainStats.getMainBlockCount(), chainStats.getDifficulty().toDecimalString());
  }

  /**
   * Check for chain reorganization
   * <p>
   * This method performs a comprehensive check for potential chain reorganization scenarios:
   * <ol>
   *   <li>Scan orphan blocks for higher cumulative difficulty forks</li>
   *   <li>Verify main chain consistency (no gaps in height sequence)</li>
   *   <li>Detect and resolve fork points if better chain exists</li>
   * </ol>
   * <p>
   * Note: Most reorganization is handled incrementally in tryToConnect().
   * This method handles edge cases and performs validation.
   */
  private synchronized void checkChainReorganization() {
    if (chainStats.getMainBlockCount() == 0) {
      return;  // Empty chain, nothing to reorganize
    }

    log.debug("Checking for chain reorganization (current main chain length: {})",
        chainStats.getMainBlockCount());

    // Step 1: Scan recent epochs for orphan blocks with high cumulative difficulty
    long currentEpoch = getCurrentEpoch();
    long scanStartEpoch = Math.max(1, currentEpoch - 100);  // Scan last 100 epochs

    List<Block> potentialForkHeads = new ArrayList<>();
    UInt256 currentDifficulty = chainStats.getDifficulty();

    for (long epoch = scanStartEpoch; epoch <= currentEpoch; epoch++) {
      List<Block> candidates = getCandidateBlocksInEpoch(epoch);

      for (Block block : candidates) {
        // Find orphan blocks (height=0) with high cumulative difficulty
        if (block.getInfo() != null && block.getInfo().getHeight() == 0) {
          UInt256 blockDifficulty = block.getInfo().getDifficulty();

          // If orphan block has higher cumulative difficulty than current main chain
          if (blockDifficulty.compareTo(currentDifficulty) > 0) {
            potentialForkHeads.add(block);
            log.warn("Found orphan block {} with higher difficulty ({}) than main chain ({})",
                block.getHash().toHexString().substring(0, 16),
                blockDifficulty.toDecimalString(),
                currentDifficulty.toDecimalString());
          }
        }
      }
    }

    // Step 2: If better forks found, trigger reorganization
    if (!potentialForkHeads.isEmpty()) {
      log.warn("Found {} orphan blocks with higher cumulative difficulty, investigating...",
          potentialForkHeads.size());

      // Sort by cumulative difficulty (descending)
      potentialForkHeads.sort((b1, b2) ->
          b2.getInfo().getDifficulty().compareTo(b1.getInfo().getDifficulty()));

      Block bestForkHead = potentialForkHeads.getFirst();

      log.info("Best fork head: {} (difficulty: {})",
          bestForkHead.getHash().toHexString(),
          bestForkHead.getInfo().getDifficulty().toDecimalString());

      // Find fork point and perform reorganization
      performChainReorganization(bestForkHead);
    }

    // Step 3: Verify main chain consistency
    verifyMainChainConsistency();

    log.debug("Chain reorganization check completed");
  }

  /**
   * Perform chain reorganization to switch to a better fork
   * <p>
   * Algorithm:
   * <ol>
   *   <li>Find the fork point (common ancestor)</li>
   *   <li>Demote blocks on current main chain after fork point</li>
   *   <li>Promote blocks on new fork to main chain</li>
   *   <li>Update chain statistics</li>
   * </ol>
   *
   * @param newForkHead the head block of the new (better) fork
   */
  private synchronized void performChainReorganization(Block newForkHead) {
    log.warn("CHAIN REORGANIZATION: Switching to fork with head {}",
        newForkHead.getHash().toHexString());

    // Step 1: Find fork point
    Block forkPoint = findForkPoint(newForkHead);

    if (forkPoint == null) {
      log.error("Cannot find fork point for reorganization, aborting");
      return;
    }

    long forkHeight = forkPoint.getInfo().getHeight();
    log.info("Fork point found at height {} (block: {})",
        forkHeight, forkPoint.getHash().toHexString().substring(0, 16));

    // Step 2: Demote blocks on current main chain after fork point
    List<Block> demotedBlocks = demoteBlocksAfterHeight(forkHeight);
    log.info("Demoted {} blocks from old main chain", demotedBlocks.size());

    // Step 3: Build new main chain path from fork point to new head
    List<Block> newMainChainBlocks = buildChainPath(newForkHead, forkPoint);

    if (newMainChainBlocks.isEmpty()) {
      log.error("Failed to build new main chain path, aborting reorganization");
      // Restore demoted blocks by calling checkNewMain() to reassign heights
      // Heights will be assigned based on epoch order and cumulative difficulty
      log.info("Restoring {} demoted blocks by reassigning heights", demotedBlocks.size());
      checkNewMain();
      return;
    }

    log.info("New main chain has {} blocks from fork point to new head",
        newMainChainBlocks.size());

    // Step 4: Promote blocks on new fork (from fork point + 1 to new head)
    long currentHeight = forkHeight;
    for (int i = newMainChainBlocks.size() - 1; i >= 0; i--) {
      Block block = newMainChainBlocks.get(i);

      // Skip fork point itself (already on main chain)
      if (block.getHash().equals(forkPoint.getHash())) {
        continue;
      }

      currentHeight++;
      promoteBlockToHeight(block, currentHeight);
    }

    // Step 5: Update chain statistics
    Block newTip = newMainChainBlocks.getFirst();  // First element is the new head
    chainStats = chainStats
        .withMainBlockCount(currentHeight)
        .withDifficulty(newTip.getInfo().getDifficulty());

    dagStore.saveChainStats(chainStats);

    log.warn("CHAIN REORGANIZATION COMPLETE: New main chain length = {}, new tip = {}",
        currentHeight, newTip.getHash().toHexString().substring(0, 16));
  }

  /**
   * Find the fork point (common ancestor) between new fork and current main chain
   *
   * @param forkHead head block of the new fork
   * @return the fork point block, or null if not found
   */
  private Block findForkPoint(Block forkHead) {
    Set<Bytes32> visited = new HashSet<>();
    Block current = forkHead;

    // Traverse back from fork head until we find a main block
    while (current != null) {
      Bytes32 currentHash = current.getHash();

      // Prevent infinite loop
      if (visited.contains(currentHash)) {
        log.warn("Cycle detected while finding fork point");
        return null;
      }
      visited.add(currentHash);

      // Check if this block is on main chain (height > 0)
      if (current.getInfo() != null && current.getInfo().getHeight() > 0) {
        log.debug("Fork point found: {} at height {}",
            currentHash.toHexString().substring(0, 16),
            current.getInfo().getHeight());
        return current;
      }

      // Move to parent with highest cumulative difficulty
      current = findMaxDifficultyParent(current);
    }

    log.error("Could not find fork point (reached genesis)");
    return null;
  }

  /**
   * Build chain path from fork head back to fork point
   *
   * @param forkHead  the head of the fork
   * @param forkPoint the common ancestor
   * @return list of blocks from forkHead to forkPoint (inclusive, ordered head-first)
   */
  private List<Block> buildChainPath(Block forkHead, Block forkPoint) {
    List<Block> path = new ArrayList<>();
    Set<Bytes32> visited = new HashSet<>();
    Block current = forkHead;

    while (current != null) {
      // Prevent infinite loop
      if (visited.contains(current.getHash())) {
        log.error("Cycle detected while building chain path");
        return new ArrayList<>();  // Return empty list on error
      }
      visited.add(current.getHash());

      path.add(current);

      // Stop when we reach fork point
      if (current.getHash().equals(forkPoint.getHash())) {
        break;
      }

      // Move to parent with highest cumulative difficulty
      current = findMaxDifficultyParent(current);
    }

    return path;
  }

  /**
   * Demote all blocks on main chain after the specified height
   * <p>
   * SECURITY: Genesis block (height 1) is NEVER demoted. Reorganization can only happen from height
   * 2 onwards.
   *
   * @param afterHeight demote blocks with height > afterHeight
   * @return list of demoted blocks
   */
  private List<Block> demoteBlocksAfterHeight(long afterHeight) {
    List<Block> demoted = new ArrayList<>();
    long currentHeight = chainStats.getMainBlockCount();

    // SECURITY: Prevent demoting genesis block (height 1)
    // Genesis block is the foundation of the chain and must never be rolled back
    long minHeight = Math.max(2, afterHeight + 1);  // Never go below height 2

    if (afterHeight < 1) {
      log.warn(
          "SECURITY: Attempted to demote from afterHeight={}, protecting genesis block (height 1)",
          afterHeight);
      log.warn("Reorganization will only affect blocks from height {} onwards", minHeight);
    }

    // Demote from highest to minHeight (protecting genesis)
    for (long height = currentHeight; height >= minHeight; height--) {
      Block block = dagStore.getMainBlockByHeight(height, false);
      if (block != null) {
        demoteBlockToOrphan(block);
        demoted.add(block);
        log.debug("Demoted block {} from height {}",
            block.getHash().toHexString().substring(0, 16), height);
      }
    }

    log.info("Demoted {} blocks (protected genesis at height 1)", demoted.size());
    return demoted;
  }

  /**
   * Promote a block to main chain at specific height
   *
   * @param block  the block to promote
   * @param height the height to assign
   */
  private void promoteBlockToHeight(Block block, long height) {
    BlockInfo updatedInfo = block.getInfo().toBuilder()
        .height(height)
        .build();

    dagStore.saveBlockInfo(updatedInfo);

    Block updatedBlock = block.toBuilder().info(updatedInfo).build();
    dagStore.saveBlock(updatedBlock);

    log.debug("Promoted block {} to height {}",
        block.getHash().toHexString().substring(0, 16), height);
  }

  /**
   * Verify main chain consistency
   * <p>
   * Checks that:
   * <ol>
   *   <li>Height sequence is continuous (no gaps)</li>
   *   <li>Each block's cumulative difficulty >= parent's difficulty</li>
   *   <li>Top block matches chain stats</li>
   * </ol>
   */
  private void verifyMainChainConsistency() {
    long mainBlockCount = chainStats.getMainBlockCount();

    if (mainBlockCount == 0) {
      return;  // Empty chain is trivially consistent
    }

    // Check height sequence
    for (long height = 1; height <= mainBlockCount; height++) {
      Block block = dagStore.getMainBlockByHeight(height, false);
      if (block == null) {
        log.error("INCONSISTENCY: Missing main block at height {}", height);
        return;
      }

      if (block.getInfo() == null || block.getInfo().getHeight() != height) {
        log.error("INCONSISTENCY: Block at height {} has wrong height info: {}",
            height, block.getInfo() != null ? block.getInfo().getHeight() : "null");
        return;
      }
    }

    // Verify top block
    Block topBlock = dagStore.getMainBlockByHeight(mainBlockCount, false);
    if (topBlock == null) {
      log.error("INCONSISTENCY: Top block at height {} not found", mainBlockCount);
      return;
    }

    log.debug("Main chain consistency verified: {} blocks, no gaps",
        mainBlockCount);
  }

  // ==================== Statistics and State ====================

  @Override
  public ChainStats getChainStats() {
    return this.chainStats;
  }

  // ==================== Economic Model ====================

  // ==================== Lifecycle Management ====================

  /**
   * Retry orphan blocks that may now have satisfied dependencies
   *
   * <p>After successfully importing a block, some orphan blocks may now have all
   * their dependencies satisfied. This method retrieves orphan blocks from the queue and attempts
   * to import them again.
   */
  private void retryOrphanBlocks() {
    // Prevent recursive retry (avoid infinite loop)
    if (isRetryingOrphans.get()) {
      return;
    }

    try {
      isRetryingOrphans.set(true);

      // Keep retrying until no more orphans can be imported (cascading retry for chain dependencies)
      int totalSuccessCount = 0;
      int pass = 1;
      boolean madeProgress;

      do {
        madeProgress = false;

        // Get up to 100 pending blocks (height=0) to retry
        // fromEpoch=0 means start from earliest epoch
        List<Bytes32> pendingHashes = dagStore.getPendingBlocks(100, 0);

        if (pendingHashes == null || pendingHashes.isEmpty()) {
          break;  // No pending blocks to retry
        }

        log.debug("Pending block retry pass {}: {} blocks to process", pass, pendingHashes.size());

        int successCount = 0;
        int failCount = 0;

        for (Bytes32 pendingHash : pendingHashes) {
          try {
            // Retrieve pending block from DagStore (full data required for re-import)
            Block pendingBlock = dagStore.getBlockByHash(pendingHash, true);
            if (pendingBlock == null) {
              log.warn("Pending block {} not found in DagStore",
                  pendingHash.toHexString().substring(0, 16));
              continue;
            }

            // Attempt to import
            log.debug("Re-importing pending block {} (current height={})",
                pendingHash.toHexString().substring(0, 16),
                pendingBlock.getInfo() != null ? pendingBlock.getInfo().getHeight() : "null");
            DagImportResult result = tryToConnect(pendingBlock);
            log.debug("Re-import result for block {}: status={}, isMain={}, height={}",
                pendingHash.toHexString().substring(0, 16),
                result.getStatus(),
                result.isMainBlock(),
                result.getHeight());

            if (result.isMainBlock() || result.isOrphan()) {
              successCount++;
              totalSuccessCount++;
              madeProgress = true;
              // Block now has height>0 (main) or stayed at height=0 (orphan)
              // No need to manually remove from pending list
              log.debug("Successfully imported pending block {} (status: {})",
                  pendingHash.toHexString().substring(0, 16), result.getStatus());
            } else if (result.getStatus() == DagImportResult.ImportStatus.MISSING_DEPENDENCY) {
              failCount++;
              // Still missing dependencies, block remains at height=0
            } else if (result.getStatus() == DagImportResult.ImportStatus.DUPLICATE) {
              // Already imported, skip
            } else {
              failCount++;
              log.warn("Pending block {} import failed: {}",
                  pendingHash.toHexString().substring(0, 16), result.getStatus());
            }

          } catch (Exception e) {
            failCount++;
            log.error("Error retrying pending block {}: {}",
                pendingHash.toHexString().substring(0, 16), e.getMessage());
          }
        }

        if (successCount > 0) {
          log.info("Orphan retry pass {} completed: {} succeeded, {} still pending",
              pass, successCount, failCount);
        }

        pass++;
      } while (madeProgress && pass <= 10);  // Max 10 passes to prevent infinite loop

      if (totalSuccessCount > 0) {
        log.info("Orphan block retry completed: {} total blocks imported across {} passes",
            totalSuccessCount, pass - 1);
      }

    } finally {
      isRetryingOrphans.set(false);
    }
  }

  /**
   * Attach placeholder BlockInfo for blocks that have not been fully imported yet.
   *
   * <p>DagStore requires BlockInfo metadata to persist data. When blocks arrive out of order
   * we still need to store their raw data so they can be retried once dependencies show up. This
   * helper builds a zero-height placeholder that is overwritten during the successful import.
   */
  private Block ensurePersistableBlock(Block block) {
    if (block.getInfo() != null) {
      return block;
    }

    UInt256 placeholderDifficulty =
        block.getHeader() != null && block.getHeader().getDifficulty() != null
            ? block.getHeader().getDifficulty()
            : UInt256.ZERO;

    BlockInfo placeholderInfo = BlockInfo.builder()
        .hash(block.getHash())
        .epoch(block.getEpoch())
        .height(0)
        .difficulty(placeholderDifficulty)
        .build();

    return block.toBuilder().info(placeholderInfo).build();
  }

  /**
   * Determine the natural height for a block based on its parents
   * <p>
   * This method finds the best parent block (highest cumulative difficulty) and returns its height
   * + 1 as the natural height for this block.
   * <p>
   * If no parent blocks exist or are found, returns 1 (genesis height).
   *
   * @param block the block to determine natural height for
   * @return the natural height (parent height + 1, or 1 for genesis)
   */
  private long determineNaturalHeight(Block block) {
    // Genesis blocks always have height 1
    if (isGenesisBlock(block)) {
      return 1;
    }

    long maxParentHeight = 0;

    // Find the highest parent block height
    for (Link link : block.getLinks()) {
      if (link.isBlock()) {
        Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
        if (parent != null && parent.getInfo() != null) {
          long parentHeight = parent.getInfo().getHeight();
          if (parentHeight > maxParentHeight) {
            maxParentHeight = parentHeight;
          }
        }
      }
    }

    // Natural height is parent height + 1
    // If no parents found (orphan case), return 0 which will be handled by caller
    long naturalHeight = maxParentHeight + 1;

    log.debug("Block {} natural height: {} (max parent height: {})",
        block.getHash().toHexString().substring(0, 16),
        naturalHeight,
        maxParentHeight);

    return naturalHeight;
  }

  /**
   * Demote a block at specific height and all its descendants
   * <p>
   * This is called during height-based chain reorganization when a better block is found for an
   * existing height. All blocks from the specified height onwards are demoted to orphan status.
   * <p>
   * BUGFIX (2025-11-20): After demotion, scan orphan blocks and promote suitable replacements to
   * fill gaps in the main chain. This prevents the chain from having discontinuous heights.
   *
   * @param fromHeight the height to start demotion from (inclusive)
   */
  private synchronized void demoteBlocksFromHeight(long fromHeight) {
    long currentHeight = chainStats.getMainBlockCount();

    log.info("Demoting blocks from height {} to {} (chain reorganization)",
        fromHeight, currentHeight);

    // Step 1: Collect demoted blocks for epoch range calculation
    List<Block> demotedBlocks = new ArrayList<>();
    int demotedCount = 0;

    // Demote from highest to fromHeight (reverse order to maintain consistency)
    for (long height = currentHeight; height >= fromHeight; height--) {
      Block block = dagStore.getMainBlockByHeight(height, false);
      if (block != null) {
        demotedBlocks.add(block);
        demoteBlockToOrphan(block);
        demotedCount++;
        log.debug("Demoted block {} from height {}",
            block.getHash().toHexString().substring(0, 16), height);
      }
    }

    log.info("Demoted {} blocks during chain reorganization", demotedCount);

    // Step 2: BUGFIX - Find replacement blocks to fill gaps
    if (!demotedBlocks.isEmpty()) {
      log.info("Scanning for replacement blocks to fill gaps at heights {} to {}",
          fromHeight, currentHeight);

      // Calculate epoch range from demoted blocks
      long minEpoch = demotedBlocks.stream()
          .mapToLong(Block::getEpoch)
          .min()
          .orElse(0);

      long currentEpoch = TimeUtils.getCurrentEpochNumber();
      long maxEpoch = Math.min(currentEpoch, minEpoch + 1000);  // Limit scan to reasonable range

      // Step 3: Collect orphan block candidates from relevant epochs
      List<Block> replacementCandidates = new ArrayList<>();

      for (long epoch = minEpoch; epoch <= maxEpoch; epoch++) {
        try {
          List<Block> candidates = dagStore.getCandidateBlocksInEpoch(epoch);

          // Filter to orphans with valid cumulative difficulty
          for (Block candidate : candidates) {
            if (candidate.getInfo() != null &&
                candidate.getInfo().getHeight() == 0 &&  // Orphan block
                candidate.getInfo().getDifficulty().compareTo(UInt256.ZERO) > 0) {
              replacementCandidates.add(candidate);
            }
          }
        } catch (Exception e) {
          log.warn("Failed to scan epoch {} for replacement blocks: {}",
              epoch, e.getMessage());
        }
      }

      log.info("Found {} orphan block candidates for gap filling",
          replacementCandidates.size());

      // Step 4: Sort candidates by cumulative difficulty (descending - best first)
      replacementCandidates.sort((b1, b2) ->
          b2.getInfo().getDifficulty().compareTo(b1.getInfo().getDifficulty()));

      // Step 5: Promote blocks to fill gaps from fromHeight to currentHeight
      int gapsToFill = (int) (currentHeight - fromHeight + 1);
      int filledCount = 0;

      for (int i = 0; i < Math.min(gapsToFill, replacementCandidates.size()); i++) {
        Block replacement = replacementCandidates.get(i);
        long targetHeight = fromHeight + i;

        try {
          promoteBlockToHeight(replacement, targetHeight);
          filledCount++;

          log.info("Filled gap at height {} with block {} (cumDiff: {})",
              targetHeight,
              replacement.getHash().toHexString().substring(0, 16),
              replacement.getInfo().getDifficulty().toDecimalString());
        } catch (Exception e) {
          log.error("Failed to promote block {} to height {}: {}",
              replacement.getHash().toHexString().substring(0, 16),
              targetHeight, e.getMessage());
        }
      }

      log.info("Gap filling completed: filled {}/{} gaps", filledCount, gapsToFill);

      // Step 6: Update chain stats with correct mainBlockCount
      long newMainBlockCount = fromHeight + filledCount - 1;
      if (filledCount > 0) {
        Block newTip = replacementCandidates.get(filledCount - 1);
        chainStats = chainStats
            .withMainBlockCount(newMainBlockCount)
            .withDifficulty(newTip.getInfo().getDifficulty());
        dagStore.saveChainStats(chainStats);

        log.info("Updated chain stats: mainBlockCount={}, tip={}",
            newMainBlockCount, newTip.getHash().toHexString().substring(0, 16));
      } else {
        log.warn("No replacement blocks found - chain has gaps from height {} to {}",
            fromHeight, currentHeight);
      }
    }
  }

  /**
   * Demote a block from main chain to orphan status
   *
   * <p>This is called during epoch competition when a new winner emerges.
   * The previous epoch winner must be demoted to orphan (height=0) to maintain consensus rule: only
   * one main block per epoch.
   *
   * <p>When a block is demoted, we must also adjust the chain stats to ensure
   * the next main block gets the correct height. The mainBlockCount is decremented so that the new
   * winner can take the demoted block's height.
   *
   * @param block the block to demote
   */
  private synchronized void demoteBlockToOrphan(Block block) {
    if (block == null || block.getInfo() == null) {
      log.warn("Attempted to demote null block or block without info");
      return;
    }

    long previousHeight = block.getInfo().getHeight();
    if (previousHeight == 0) {
      log.debug("Block {} is already an orphan, skipping demotion",
          block.getHash().toHexString());
      return;
    }

    log.debug("Demoting block {} from height {} to orphan",
        block.getHash().toHexString().substring(0, 16), previousHeight);

    // BUGFIX: Delete old height mapping BEFORE updating BlockInfo
    // This prevents multiple blocks from mapping to the same height
    // When a block is demoted from height X to height 0, we must explicitly
    // delete the height X -> hash mapping from the database
    try {
      dagStore.deleteHeightMapping(previousHeight);
      log.debug("Deleted height mapping for height {} before demotion",
          previousHeight);
    } catch (Exception e) {
      log.error("Failed to delete height mapping for height {}: {}",
          previousHeight, e.getMessage());
      // Continue with demotion even if delete fails (safer to continue than abort)
    }

    // Rollback transaction executions (Phase 1 - Task 1.4)
    // When a block is demoted, unmark all its transactions as executed
    // so they can be re-executed if the block becomes main again
    rollbackBlockTransactions(block);

    // Update BlockInfo to mark as orphan (height = 0)
    BlockInfo updatedInfo = block.getInfo().toBuilder()
        .height(0)
        .build();

    dagStore.saveBlockInfo(updatedInfo);

    // IMPORTANT: Also save the Block with updated info to update cache and indices
    // This ensures that getBlockByHash() and time-range queries return the updated block
    Block updatedBlock = block.toBuilder().info(updatedInfo).build();
    dagStore.saveBlock(updatedBlock);

    // Demoted blocks are now stored in DagStore with height=0 (pending)
    // They can be queried via dagStore.getPendingBlocks() and may be promoted during reorganization
    log.debug("Demoted block {} to pending (previous height: {})",
        block.getHash().toHexString().substring(0, 16),
        previousHeight);

    // NOTE: We do NOT modify mainBlockCount here!
    // The mainBlockCount should only be updated by updateChainStatsForNewMainBlock()
    // using Math.max(currentCount, newBlockHeight) to ensure correctness during replacements.
    // Decrementing here would break the chain when demoting non-tail blocks.

    log.info("Demoted block {} from height {} to orphan (epoch competition loser)",
        block.getHash().toHexString(), previousHeight);
  }

  /**
   * Rollback transaction executions for a demoted block (Phase 1 - Task 1.4)
   *
   * <p>When a block is demoted from main chain to orphan status (e.g., during chain
   * reorganization or epoch competition), all transactions executed by that block must be unmarked
   * AND their state changes must be reverted.
   *
   * <p>This is critical for:
   * <ul>
   *   <li>Chain reorganization: When blocks are demoted, their state changes must be reverted</li>
   *   <li>Epoch competition: When a block loses to a new winner with smaller hash</li>
   *   <li>Transaction replay: Allows transactions to be re-executed in a different block</li>
   * </ul>
   *
   * <p>Rollback operations for each transaction:
   * <ol>
   *   <li>Sender: Restore balance (refund amount + fee), decrement nonce</li>
   *   <li>Receiver: Deduct balance (remove received amount)</li>
   *   <li>Unmark transaction as executed</li>
   * </ol>
   *
   * @param block the block being demoted
   */
  private void rollbackBlockTransactions(Block block) {
    try {
      // Get all transaction hashes in this block
      List<Bytes32> txHashes = transactionStore.getTransactionHashesByBlock(block.getHash());

      if (txHashes.isEmpty()) {
        log.debug("No transactions to rollback for block {}",
            block.getHash().toHexString().substring(0, 16));
        return;
      }

      // Get account manager for state rollback
      DagAccountManager accountManager = dagKernel.getDagAccountManager();
      if (accountManager == null) {
        log.error("Cannot rollback transactions: DagAccountManager not available");
        return;
      }

      // Rollback each transaction
      int rolledBackCount = 0;
      for (Bytes32 txHash : txHashes) {
        try {
          // Load transaction
          Transaction tx = transactionStore.getTransaction(txHash);
          if (tx == null) {
            log.warn("Transaction {} not found in store, skipping rollback",
                txHash.toHexString().substring(0, 16));
            continue;
          }

          // Rollback account state changes
          rollbackTransactionState(tx, accountManager);

          // Unmark transaction as executed
          transactionStore.unmarkTransactionExecuted(txHash);

          rolledBackCount++;

          if (log.isDebugEnabled()) {
            log.debug("Rolled back transaction {} from block {}",
                txHash.toHexString().substring(0, 16),
                block.getHash().toHexString().substring(0, 16));
          }

        } catch (Exception e) {
          log.error("Failed to rollback transaction {}: {}",
              txHash.toHexString().substring(0, 16), e.getMessage());
        }
      }

      log.info("Rolled back {} transactions (state + execution status) for demoted block {}",
          rolledBackCount, block.getHash().toHexString().substring(0, 16));

    } catch (Exception e) {
      log.error("Failed to rollback transactions for block {}: {}",
          block.getHash().toHexString().substring(0, 16), e.getMessage());
    }
  }

  /**
   * Rollback account state changes for a single transaction
   *
   * <p>Reverses the state changes applied during transaction execution:
   * <ul>
   *   <li>Sender: Restore balance (refund amount + fee), decrement nonce</li>
   *   <li>Receiver: Deduct balance (remove received amount)</li>
   * </ul>
   *
   * @param tx             transaction to rollback
   * @param accountManager account manager for state operations
   */
  private void rollbackTransactionState(Transaction tx, DagAccountManager accountManager) {
    // Convert XAmount to UInt256 (nano units)
    UInt256 txAmount = UInt256.valueOf(tx.getAmount().toDecimal(0, XUnit.NANO_XDAG).longValue());
    UInt256 txFee = UInt256.valueOf(tx.getFee().toDecimal(0, XUnit.NANO_XDAG).longValue());

    // Rollback sender account: refund amount + fee, decrement nonce
    try {
      accountManager.addBalance(tx.getFrom(), txAmount.add(txFee));
      accountManager.decrementNonce(tx.getFrom());

      if (log.isDebugEnabled()) {
        log.debug("Rolled back sender {}: refunded {}, decremented nonce",
            tx.getFrom().toHexString().substring(0, 8),
            txAmount.add(txFee).toDecimalString());
      }
    } catch (Exception e) {
      log.error("Failed to rollback sender account {}: {}",
          tx.getFrom().toHexString().substring(0, 8), e.getMessage());
    }

    // Rollback receiver account: deduct amount
    try {
      UInt256 receiverBalance = accountManager.getBalance(tx.getTo());
      if (receiverBalance.compareTo(txAmount) >= 0) {
        accountManager.subtractBalance(tx.getTo(), txAmount);

        if (log.isDebugEnabled()) {
          log.debug("Rolled back receiver {}: deducted {}",
              tx.getTo().toHexString().substring(0, 8),
              txAmount.toDecimalString());
        }
      } else {
        // This shouldn't happen in normal operation
        log.warn("Cannot fully rollback receiver {}: insufficient balance ({} < {})",
            tx.getTo().toHexString().substring(0, 8),
            receiverBalance.toDecimalString(),
            txAmount.toDecimalString());
      }
    } catch (Exception e) {
      log.error("Failed to rollback receiver account {}: {}",
          tx.getTo().toHexString().substring(0, 8), e.getMessage());
    }
  }

  // ==================== DAG Chain Event Listeners ====================

  @Override
  public void addListener(DagchainListener listener) {
    if (listener == null) {
      throw new IllegalArgumentException("DagchainListener cannot be null");
    }

    synchronized (dagchainListeners) {
      if (!dagchainListeners.contains(listener)) {
        dagchainListeners.add(listener);
        log.debug("Added DAG chain listener: {}", listener.getClass().getSimpleName());
      } else {
        log.warn("Attempted to add duplicate listener: {}", listener.getClass().getSimpleName());
      }
    }
  }

  @Override
  public void removeListener(DagchainListener listener) {
    if (listener == null) {
      return;
    }

    synchronized (dagchainListeners) {
      boolean removed = dagchainListeners.remove(listener);
      if (removed) {
        log.debug("Removed DAG chain listener: {}", listener.getClass().getSimpleName());
      }
    }
  }

  /**
   * Notify all DAG chain listeners of block connection
   *
   * <p>This method is called after a block is successfully imported
   * and added to the main chain. It notifies all registered listeners so they can react to the
   * chain state change.
   *
   * <p>Listener exceptions are caught and logged to prevent one failing
   * listener from affecting others.
   *
   * @param block The block that was connected to the main chain
   */
  private void notifyDagchainListeners(Block block) {
    synchronized (dagchainListeners) {
      if (dagchainListeners.isEmpty()) {
        return;
      }

      log.debug("Notifying {} DAG chain listeners of block connection: {}",
          dagchainListeners.size(),
          block.getHash().toHexString().substring(0, 16));

      for (DagchainListener listener : dagchainListeners) {
        try {
          listener.onBlockConnected(block);
        } catch (Exception e) {
          log.error("Error notifying DAG chain listener {}: {}",
              listener.getClass().getSimpleName(), e.getMessage(), e);
        }
      }
    }
  }

  // ==================== Node Sync State Management ====================

  /**
   * Check if the node is behind (needs sync before mining)
   * <p>
   * A node is considered "behind" when its local main chain epoch lags behind the current time
   * epoch by more than SYNC_LAG_THRESHOLD.
   * <p>
   * Design rationale (per user requirement): - Nodes that are up-to-date can mine immediately -
   * Nodes that are behind MUST sync first before mining - This prevents outdated nodes from
   * creating blocks with stale references
   *
   * @return true if node needs to sync before mining, false if node is up-to-date
   */
  public boolean isNodeBehind() {
    long currentEpoch = getCurrentEpoch();

    // Get local chain's latest epoch
    long localMainHeight = chainStats.getMainBlockCount();
    if (localMainHeight == 0) {
      // Empty chain is always "behind"
      log.debug("Node has no blocks, considered behind");
      return true;
    }

    Block latestMainBlock = dagStore.getMainBlockByHeight(localMainHeight, false);
    if (latestMainBlock == null) {
      log.warn("Cannot find latest main block at height {}, assuming node is behind",
          localMainHeight);
      return true;
    }

    long localLatestEpoch = latestMainBlock.getEpoch();
    long epochGap = currentEpoch - localLatestEpoch;

    boolean isBehind = epochGap > SYNC_LAG_THRESHOLD;

    if (isBehind) {
      log.info(
          "Node is BEHIND: local epoch {} is {} epochs behind current epoch {} (threshold: {})",
          localLatestEpoch, epochGap, currentEpoch, SYNC_LAG_THRESHOLD);
    } else {
      log.debug(
          "Node is UP-TO-DATE: local epoch {} is only {} epochs behind current epoch {} (threshold: {})",
          localLatestEpoch, epochGap, currentEpoch, SYNC_LAG_THRESHOLD);
    }

    return isBehind;
  }
}
