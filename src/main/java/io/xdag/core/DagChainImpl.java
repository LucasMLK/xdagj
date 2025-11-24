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
import io.xdag.core.listener.NewBlockListener;
import io.xdag.store.DagStore;
import io.xdag.store.TransactionStore;
import io.xdag.store.cache.DagEntityResolver;
import io.xdag.store.cache.ResolvedLinks;
import io.xdag.utils.TimeUtils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  // Refactored components (P0 phase)
  private final BlockValidator blockValidator;
  private final BlockImporter blockImporter;

  // Refactored components (P1 phase)
  private final BlockBuilder blockBuilder;
  private final ChainReorganizer chainReorganizer;
  private final DifficultyAdjuster difficultyAdjuster;
  private final OrphanManager orphanManager;

  private volatile ChainStats chainStats;
  private final List<Listener> listeners = new ArrayList<>();
  private final List<DagchainListener> dagchainListeners = new ArrayList<>();
  private final List<NewBlockListener> newBlockListeners = new ArrayList<>();

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

    // Initialize refactored components (P0 phase)
    this.blockValidator = new BlockValidator(
        dagStore,
        entityResolver,
        dagKernel.getConfig());

    this.blockImporter = new BlockImporter(
        dagKernel,
        blockValidator);

    // Initialize refactored components (P1 phase)
    this.blockBuilder = new BlockBuilder(dagKernel);
    this.chainReorganizer = new ChainReorganizer(dagKernel);
    this.difficultyAdjuster = new DifficultyAdjuster(dagKernel);
    this.orphanManager = new OrphanManager(dagKernel);

    log.info("DagChainImpl initialized with DagKernel");
    log.info("  - DagStore: {}", dagStore.getClass().getSimpleName());
    log.info("  - DagEntityResolver: {}", entityResolver.getClass().getSimpleName());
    log.info("  - TransactionStore: {}", transactionStore.getClass().getSimpleName());
    log.info("  - BlockValidator: initialized (P0 refactoring)");
    log.info("  - BlockImporter: initialized (P0 refactoring)");
    log.info("  - BlockBuilder: initialized (P1 refactoring)");
    log.info("  - ChainReorganizer: initialized (P1 refactoring)");
    log.info("  - DifficultyAdjuster: initialized (P1 refactoring)");
    log.info("  - OrphanManager: initialized (P1 refactoring)");
  }

  // ==================== Block Import Operations ====================

  @Override
  public synchronized DagImportResult tryToConnect(Block block) {
    try {
      log.debug("Attempting to connect block: {}", block.getHash().toHexString());

      // Delegate to BlockImporter (P0 refactoring)
      BlockImporter.ImportResult importResult = blockImporter.importBlock(block, chainStats);

      if (!importResult.isSuccess()) {
        // Import failed - return error
        return DagImportResult.error(
            new Exception(importResult.getErrorMessage()),
            importResult.getErrorMessage());
      }

      // Import successful - create block with BlockInfo for notifications
      BlockInfo blockInfo = BlockInfo.builder()
          .hash(block.getHash())
          .epoch(importResult.getEpoch())
          .height(importResult.getHeight())
          .difficulty(importResult.getCumulativeDifficulty())
          .build();

      Block blockWithInfo = block.toBuilder().info(blockInfo).build();

      // Notify listeners
      notifyListeners(blockWithInfo);
      notifyNewBlockListeners(blockWithInfo);

      // Notify DAG chain listeners (only for main blocks)
      if (importResult.isBestChain()) {
        notifyDagchainListeners(blockWithInfo);

        // Update chain stats for main blocks
        updateChainStatsForNewMainBlock(blockInfo);

        // Delegate to DifficultyAdjuster (P1 refactoring)
        this.chainStats = difficultyAdjuster.checkAndAdjustDifficulty(
            this.chainStats,
            importResult.getHeight(),
            block.getEpoch(),
            this::getCandidateBlocksInEpoch);

        // Delegate to OrphanManager (P1 refactoring)
        this.chainStats = orphanManager.cleanupOldOrphans(
            this.chainStats,
            block.getEpoch(),
            this::getCandidateBlocksInEpoch);
      }

      // Delegate to OrphanManager (P1 refactoring)
      orphanManager.retryOrphanBlocks(this::tryToConnect);

      log.info("Successfully imported block {}: height={}, difficulty={}",
          block.getHash().toHexString(),
          importResult.getHeight(),
          importResult.getCumulativeDifficulty().toDecimalString());

      // Return detailed result
      if (importResult.isBestChain()) {
        return DagImportResult.mainBlock(
            importResult.getEpoch(),
            importResult.getHeight(),
            importResult.getCumulativeDifficulty(),
            importResult.isEpochWinner());
      } else {
        // Orphan blocks (height=0) are automatically stored in DagStore
        log.debug("Block {} is orphan (epoch {}, cumDiff {})",
            block.getHash().toHexString().substring(0, 16),
            importResult.getEpoch(),
            importResult.getCumulativeDifficulty().toDecimalString());

        return DagImportResult.orphan(
            importResult.getEpoch(),
            importResult.getCumulativeDifficulty(),
            importResult.isEpochWinner());
      }

    } catch (Exception e) {
      log.error("Error importing block {}: {}", block.getHash().toHexString(), e.getMessage(), e);
      return DagImportResult.error(e, "Exception during import: " + e.getMessage());
    }
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

  /**
   * Notify listeners that react to any new block addition.
   */
  private void notifyNewBlockListeners(Block block) {
    synchronized (newBlockListeners) {
      if (newBlockListeners.isEmpty()) {
        return;
      }

      for (NewBlockListener listener : newBlockListeners) {
        try {
          listener.onNewBlock(block);
        } catch (Exception e) {
          log.error("Error notifying NewBlockListener {}: {}",
              listener.getClass().getSimpleName(), e.getMessage(), e);
        }
      }
    }
  }

  // ==================== Block Creation Operations ====================

  @Override
  public Block createCandidateBlock() {
    // Delegate to BlockBuilder (P1 refactoring)
    return blockBuilder.createCandidateBlock(this.chainStats);
  }


  /**
   * Set mining coinbase address
   *
   * @param coinbase mining reward address (20 bytes)
   */
  public void setMiningCoinbase(Bytes coinbase) {
    // Delegate to BlockBuilder (P1 refactoring)
    blockBuilder.setMiningCoinbase(coinbase);
  }

  @Override
  public Block createGenesisBlock(long epoch) {
    // Delegate to BlockBuilder (P1 refactoring)
    return blockBuilder.createGenesisBlock(epoch);
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
  public List<Bytes32> getBlockHashesByEpoch(long epoch) {
    List<Block> candidates = getCandidateBlocksInEpoch(epoch);
    if (candidates == null || candidates.isEmpty()) {
      return Collections.emptyList();
    }
    return candidates.stream()
        .map(Block::getHash)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
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
    // Delegate to BlockValidator (P0 refactoring)
    return blockValidator.validateDAGRules(block, chainStats);
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
    // Delegate to ChainReorganizer (P1 refactoring)
    this.chainStats = chainReorganizer.checkNewMain(
        this.chainStats,
        this::getCurrentEpoch,
        this::getWinnerBlockInEpoch);
  }












  // ==================== Statistics and State ====================

  @Override
  public ChainStats getChainStats() {
    return this.chainStats;
  }

  // ==================== Economic Model ====================

  // ==================== Lifecycle Management ====================


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

  @Override
  public void registerNewBlockListener(NewBlockListener listener) {
    if (listener == null) {
      throw new IllegalArgumentException("NewBlockListener cannot be null");
    }

    synchronized (newBlockListeners) {
      if (newBlockListeners.contains(listener)) {
        log.debug("Attempted to register duplicate NewBlockListener: {}",
            listener.getClass().getSimpleName());
        return;
      }
      newBlockListeners.add(listener);
      log.debug("Registered NewBlockListener: {}", listener.getClass().getSimpleName());
    }
  }
}
