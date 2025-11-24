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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Chain Reorganizer - 链重组管理器
 *
 * <p>负责处理主链重组和区块升降级：
 * - 检测并执行链重组
 * - 查找分叉点
 * - 升级/降级区块
 * - 回滚和重放交易
 * - 验证主链一致性
 *
 * <p>从DagChainImpl提取，作为P1重构的一部分
 */
@Slf4j
public class ChainReorganizer {

  private static final long ORPHAN_RETENTION_WINDOW = 16384; // 12 days in epochs

  private final DagKernel dagKernel;
  private final DagStore dagStore;
  private final TransactionStore transactionStore;

  /**
   * 创建ChainReorganizer
   *
   * @param dagKernel DAG内核，提供所有必要的组件访问
   */
  public ChainReorganizer(DagKernel dagKernel) {
    this.dagKernel = dagKernel;
    this.dagStore = dagKernel.getDagStore();
    this.transactionStore = dagKernel.getTransactionStore();
  }

  /**
   * Check and update main chain heights
   *
   * <p>Scans all epoch winners and assigns continuous heights based on epoch order.
   * This ensures that the main chain has no gaps in height assignments.
   *
   * @param chainStats current chain statistics
   * @param getCurrentEpoch function to get current epoch
   * @param getWinnerBlockInEpoch function to get winner in an epoch
   * @return updated chain statistics
   */
  public ChainStats checkNewMain(
      ChainStats chainStats,
      java.util.function.LongSupplier getCurrentEpoch,
      java.util.function.LongFunction<Block> getWinnerBlockInEpoch) {

    log.debug("Running checkNewMain() - assigning heights to epoch winners based on epoch order");

    long currentEpoch = getCurrentEpoch.getAsLong();
    long scanStartEpoch = Math.max(1, currentEpoch - ORPHAN_RETENTION_WINDOW);

    // Step 1: Collect all epoch winners and their epochs
    Map<Long, Block> epochWinners = new java.util.TreeMap<>();

    for (long epoch = scanStartEpoch; epoch <= currentEpoch; epoch++) {
      Block winner = getWinnerBlockInEpoch.apply(epoch);
      if (winner != null) {
        epochWinners.put(epoch, winner);
      }
    }

    if (epochWinners.isEmpty()) {
      log.warn("No epoch winners found in range {} to {}", scanStartEpoch, currentEpoch);
      return chainStats;
    }

    // Step 2: Sort epoch winners by epoch number (ascending)
    List<Map.Entry<Long, Block>> sortedWinners = new ArrayList<>(epochWinners.entrySet());
    sortedWinners.sort(Map.Entry.comparingByKey());

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
            height, epoch, formatHash(winner.getHash()));

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
            height, formatHash(winner.getHash()), epoch);
      }

      // Track top block
      topBlock = winner;
      if (winner.getInfo() != null
          && winner.getInfo().getDifficulty().compareTo(maxDifficulty) > 0) {
        maxDifficulty = winner.getInfo().getDifficulty();
      }

      height++;
    }

    // Step 4: Update chain stats
    long finalMainBlockCount = height - 1;
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

    // Step 5: Check for chain reorganization
    chainStats = checkChainReorganization(
        chainStats,
        getCurrentEpoch,
        (epoch) -> dagStore.getCandidateBlocksInEpoch(epoch),
        this::findMaxDifficultyParent);

    log.debug("checkNewMain() completed: mainBlockCount={}, difficulty={}",
        chainStats.getMainBlockCount(), chainStats.getDifficulty().toDecimalString());

    return chainStats;
  }

  /**
   * Check for chain reorganization
   *
   * <p>Scans for orphan blocks with higher cumulative difficulty and triggers reorganization if found.
   *
   * @param chainStats current chain statistics
   * @param getCurrentEpoch function to get current epoch
   * @param getCandidateBlocksInEpoch function to get candidates in an epoch
   * @param findMaxDifficultyParent function to find parent with max difficulty
   * @return updated chain statistics
   */
  private ChainStats checkChainReorganization(
      ChainStats chainStats,
      java.util.function.LongSupplier getCurrentEpoch,
      java.util.function.LongFunction<List<Block>> getCandidateBlocksInEpoch,
      java.util.function.Function<Block, Block> findMaxDifficultyParent) {

    if (chainStats.getMainBlockCount() == 0) {
      return chainStats;
    }

    log.debug("Checking for chain reorganization (current main chain length: {})",
        chainStats.getMainBlockCount());

    // Step 1: Scan recent epochs for orphan blocks with high cumulative difficulty
    long currentEpoch = getCurrentEpoch.getAsLong();
    long scanStartEpoch = Math.max(1, currentEpoch - 100);

    List<Block> potentialForkHeads = new ArrayList<>();
    UInt256 currentDifficulty = chainStats.getDifficulty();

    for (long epoch = scanStartEpoch; epoch <= currentEpoch; epoch++) {
      List<Block> candidates = getCandidateBlocksInEpoch.apply(epoch);

      for (Block block : candidates) {
        if (block.getInfo() != null && block.getInfo().getHeight() == 0) {
          UInt256 blockDifficulty = block.getInfo().getDifficulty();

          if (blockDifficulty.compareTo(currentDifficulty) > 0) {
            potentialForkHeads.add(block);
            log.warn("Found orphan block {} with higher difficulty ({}) than main chain ({})",
                formatHash(block.getHash()),
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

      potentialForkHeads.sort((b1, b2) ->
          b2.getInfo().getDifficulty().compareTo(b1.getInfo().getDifficulty()));

      Block bestForkHead = potentialForkHeads.get(0);

      log.info("Best fork head: {} (difficulty: {})",
          bestForkHead.getHash().toHexString(),
          bestForkHead.getInfo().getDifficulty().toDecimalString());

      chainStats = performChainReorganization(chainStats, bestForkHead, findMaxDifficultyParent);
    }

    // Step 3: Verify main chain consistency
    verifyMainChainConsistency(chainStats);

    log.debug("Chain reorganization check completed");
    return chainStats;
  }

  /**
   * Perform chain reorganization to switch to a better fork
   *
   * @param chainStats current chain statistics
   * @param newForkHead the head block of the new (better) fork
   * @param findMaxDifficultyParent function to find parent with max difficulty
   * @return updated chain statistics
   */
  private ChainStats performChainReorganization(
      ChainStats chainStats,
      Block newForkHead,
      java.util.function.Function<Block, Block> findMaxDifficultyParent) {

    log.warn("CHAIN REORGANIZATION: Switching to fork with head {}",
        newForkHead.getHash().toHexString());

    // Step 1: Find fork point
    Block forkPoint = findForkPoint(newForkHead, findMaxDifficultyParent);

    if (forkPoint == null) {
      log.error("Cannot find fork point for reorganization, aborting");
      return chainStats;
    }

    long forkHeight = forkPoint.getInfo().getHeight();
    log.info("Fork point found at height {} (block: {})",
        forkHeight, formatHash(forkPoint.getHash()));

    // Step 2: Demote blocks on current main chain after fork point
    List<Block> demotedBlocks = demoteBlocksAfterHeight(chainStats, forkHeight);
    log.info("Demoted {} blocks from old main chain", demotedBlocks.size());

    // Step 3: Build new main chain path
    List<Block> newMainChainBlocks = buildChainPath(newForkHead, forkPoint, findMaxDifficultyParent);

    if (newMainChainBlocks.isEmpty()) {
      log.error("Failed to build new main chain path, aborting reorganization");
      log.info("Restoring {} demoted blocks by reassigning heights", demotedBlocks.size());
      // Restoration will be handled by next checkNewMain() call
      return chainStats;
    }

    log.info("New main chain has {} blocks from fork point to new head",
        newMainChainBlocks.size());

    // Step 4: Promote blocks on new fork
    long currentHeight = forkHeight;
    for (int i = newMainChainBlocks.size() - 1; i >= 0; i--) {
      Block block = newMainChainBlocks.get(i);

      if (block.getHash().equals(forkPoint.getHash())) {
        continue;
      }

      currentHeight++;
      promoteBlockToHeight(block, currentHeight);
    }

    // Step 5: Update chain statistics
    Block newTip = newMainChainBlocks.get(0);
    chainStats = chainStats
        .withMainBlockCount(currentHeight)
        .withDifficulty(newTip.getInfo().getDifficulty());

    dagStore.saveChainStats(chainStats);

    log.warn("CHAIN REORGANIZATION COMPLETE: New main chain length = {}, new tip = {}",
        currentHeight, formatHash(newTip.getHash()));

    return chainStats;
  }

  /**
   * Find the fork point (common ancestor) between new fork and current main chain
   */
  private Block findForkPoint(
      Block forkHead,
      java.util.function.Function<Block, Block> findMaxDifficultyParent) {

    Set<Bytes32> visited = new HashSet<>();
    Block current = forkHead;

    while (current != null) {
      Bytes32 currentHash = current.getHash();

      if (visited.contains(currentHash)) {
        log.warn("Cycle detected while finding fork point");
        return null;
      }
      visited.add(currentHash);

      if (current.getInfo() != null && current.getInfo().getHeight() > 0) {
        log.debug("Fork point found: {} at height {}",
            formatHash(currentHash), current.getInfo().getHeight());
        return current;
      }

      current = findMaxDifficultyParent.apply(current);
    }

    log.error("Could not find fork point (reached genesis)");
    return null;
  }

  /**
   * Build chain path from fork head back to fork point
   */
  private List<Block> buildChainPath(
      Block forkHead,
      Block forkPoint,
      java.util.function.Function<Block, Block> findMaxDifficultyParent) {

    List<Block> path = new ArrayList<>();
    Set<Bytes32> visited = new HashSet<>();
    Block current = forkHead;

    while (current != null) {
      if (visited.contains(current.getHash())) {
        log.error("Cycle detected while building chain path");
        return new ArrayList<>();
      }
      visited.add(current.getHash());

      path.add(current);

      if (current.getHash().equals(forkPoint.getHash())) {
        break;
      }

      current = findMaxDifficultyParent.apply(current);
    }

    return path;
  }

  /**
   * Demote all blocks on main chain after the specified height
   */
  private List<Block> demoteBlocksAfterHeight(ChainStats chainStats, long afterHeight) {
    List<Block> demoted = new ArrayList<>();
    long currentHeight = chainStats.getMainBlockCount();

    // SECURITY: Protect genesis block (height 1)
    long minHeight = Math.max(2, afterHeight + 1);

    if (afterHeight < 1) {
      log.warn("SECURITY: Attempted to demote from afterHeight={}, protecting genesis block (height 1)",
          afterHeight);
      log.warn("Reorganization will only affect blocks from height {} onwards", minHeight);
    }

    for (long height = currentHeight; height >= minHeight; height--) {
      Block block = dagStore.getMainBlockByHeight(height, false);
      if (block != null) {
        demoteBlockToOrphan(block);
        demoted.add(block);
        log.debug("Demoted block {} from height {}", formatHash(block.getHash()), height);
      }
    }

    log.info("Demoted {} blocks (protected genesis at height 1)", demoted.size());
    return demoted;
  }

  /**
   * Promote a block to main chain at specific height
   */
  private void promoteBlockToHeight(Block block, long height) {
    BlockInfo updatedInfo = block.getInfo().toBuilder()
        .height(height)
        .build();

    dagStore.saveBlockInfo(updatedInfo);

    Block updatedBlock = block.toBuilder().info(updatedInfo).build();
    dagStore.saveBlock(updatedBlock);

    log.debug("Promoted block {} to height {}", formatHash(block.getHash()), height);
  }

  /**
   * Verify main chain consistency
   */
  private void verifyMainChainConsistency(ChainStats chainStats) {
    long mainBlockCount = chainStats.getMainBlockCount();

    if (mainBlockCount == 0) {
      return;
    }

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

    Block topBlock = dagStore.getMainBlockByHeight(mainBlockCount, false);
    if (topBlock == null) {
      log.error("INCONSISTENCY: Top block at height {} not found", mainBlockCount);
      return;
    }

    log.debug("Main chain consistency verified: {} blocks, no gaps", mainBlockCount);
  }

  /**
   * Demote blocks from a specific height and fill gaps with replacement blocks
   */
  public ChainStats demoteBlocksFromHeight(ChainStats chainStats, long fromHeight) {
    long currentHeight = chainStats.getMainBlockCount();

    log.info("Demoting blocks from height {} to {} (chain reorganization)",
        fromHeight, currentHeight);

    // Step 1: Collect and demote blocks
    List<Block> demotedBlocks = new ArrayList<>();
    int demotedCount = 0;

    for (long height = currentHeight; height >= fromHeight; height--) {
      Block block = dagStore.getMainBlockByHeight(height, false);
      if (block != null) {
        demotedBlocks.add(block);
        demoteBlockToOrphan(block);
        demotedCount++;
        log.debug("Demoted block {} from height {}", formatHash(block.getHash()), height);
      }
    }

    log.info("Demoted {} blocks during chain reorganization", demotedCount);

    // Step 2: Find replacement blocks to fill gaps
    if (!demotedBlocks.isEmpty()) {
      log.info("Scanning for replacement blocks to fill gaps at heights {} to {}",
          fromHeight, currentHeight);

      long minEpoch = demotedBlocks.stream()
          .mapToLong(Block::getEpoch)
          .min()
          .orElse(0);

      long currentEpoch = TimeUtils.getCurrentEpochNumber();
      long maxEpoch = Math.min(currentEpoch, minEpoch + 1000);

      // Collect orphan block candidates
      List<Block> replacementCandidates = new ArrayList<>();

      for (long epoch = minEpoch; epoch <= maxEpoch; epoch++) {
        try {
          List<Block> candidates = dagStore.getCandidateBlocksInEpoch(epoch);

          for (Block candidate : candidates) {
            if (candidate.getInfo() != null &&
                candidate.getInfo().getHeight() == 0 &&
                candidate.getInfo().getDifficulty().compareTo(UInt256.ZERO) > 0) {
              replacementCandidates.add(candidate);
            }
          }
        } catch (Exception e) {
          log.warn("Failed to scan epoch {} for replacement blocks: {}", epoch, e.getMessage());
        }
      }

      log.info("Found {} orphan block candidates for gap filling", replacementCandidates.size());

      // Sort by cumulative difficulty (descending)
      replacementCandidates.sort((b1, b2) ->
          b2.getInfo().getDifficulty().compareTo(b1.getInfo().getDifficulty()));

      // Promote blocks to fill gaps
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
              formatHash(replacement.getHash()),
              replacement.getInfo().getDifficulty().toDecimalString());
        } catch (Exception e) {
          log.error("Failed to promote block {} to height {}: {}",
              formatHash(replacement.getHash()), targetHeight, e.getMessage());
        }
      }

      log.info("Gap filling completed: filled {}/{} gaps", filledCount, gapsToFill);

      // Update chain stats
      long newMainBlockCount = fromHeight + filledCount - 1;
      if (filledCount > 0) {
        Block newTip = replacementCandidates.get(filledCount - 1);
        chainStats = chainStats
            .withMainBlockCount(newMainBlockCount)
            .withDifficulty(newTip.getInfo().getDifficulty());
        dagStore.saveChainStats(chainStats);

        log.info("Updated chain stats: mainBlockCount={}, tip={}",
            newMainBlockCount, formatHash(newTip.getHash()));
      } else {
        log.warn("No replacement blocks found - chain has gaps from height {} to {}",
            fromHeight, currentHeight);
      }
    }

    return chainStats;
  }

  /**
   * Demote a block from main chain to orphan status
   */
  public void demoteBlockToOrphan(Block block) {
    if (block == null || block.getInfo() == null) {
      log.warn("Attempted to demote null block or block without info");
      return;
    }

    long previousHeight = block.getInfo().getHeight();
    if (previousHeight == 0) {
      log.debug("Block {} is already an orphan, skipping demotion", block.getHash().toHexString());
      return;
    }

    log.debug("Demoting block {} from height {} to orphan",
        formatHash(block.getHash()), previousHeight);

    // Delete old height mapping
    try {
      dagStore.deleteHeightMapping(previousHeight);
      log.debug("Deleted height mapping for height {} before demotion", previousHeight);
    } catch (Exception e) {
      log.error("Failed to delete height mapping for height {}: {}", previousHeight, e.getMessage());
    }

    // Rollback transaction executions
    rollbackBlockTransactions(block);

    // Update BlockInfo to mark as orphan
    BlockInfo updatedInfo = block.getInfo().toBuilder()
        .height(0)
        .build();

    dagStore.saveBlockInfo(updatedInfo);

    Block updatedBlock = block.toBuilder().info(updatedInfo).build();
    dagStore.saveBlock(updatedBlock);

    log.debug("Demoted block {} to pending (previous height: {})",
        formatHash(block.getHash()), previousHeight);

    log.info("Demoted block {} from height {} to orphan (epoch competition loser)",
        block.getHash().toHexString(), previousHeight);
  }

  /**
   * Rollback transaction executions for a demoted block
   */
  private void rollbackBlockTransactions(Block block) {
    try {
      List<Bytes32> txHashes = transactionStore.getTransactionHashesByBlock(block.getHash());

      if (txHashes.isEmpty()) {
        log.debug("No transactions to rollback for block {}", formatHash(block.getHash()));
        return;
      }

      DagAccountManager accountManager = dagKernel.getDagAccountManager();
      if (accountManager == null) {
        log.error("Cannot rollback transactions: DagAccountManager not available");
        return;
      }

      int rolledBackCount = 0;
      for (Bytes32 txHash : txHashes) {
        try {
          Transaction tx = transactionStore.getTransaction(txHash);
          if (tx == null) {
            log.warn("Transaction {} not found in store, skipping rollback",
                formatHash(txHash));
            continue;
          }

          rollbackTransactionState(tx, accountManager);
          transactionStore.unmarkTransactionExecuted(txHash);
          rolledBackCount++;

          if (log.isDebugEnabled()) {
            log.debug("Rolled back transaction {} from block {}",
                formatHash(txHash), formatHash(block.getHash()));
          }

        } catch (Exception e) {
          log.error("Failed to rollback transaction {}: {}", formatHash(txHash), e.getMessage());
        }
      }

      log.info("Rolled back {} transactions (state + execution status) for demoted block {}",
          rolledBackCount, formatHash(block.getHash()));

      transactionStore.removeTransactionsByBlock(block.getHash());

    } catch (Exception e) {
      log.error("Failed to rollback transactions for block {}: {}",
          formatHash(block.getHash()), e.getMessage());
    }
  }

  /**
   * Rollback account state changes for a single transaction
   */
  private void rollbackTransactionState(Transaction tx, DagAccountManager accountManager) {
    UInt256 txAmount = UInt256.valueOf(tx.getAmount().toDecimal(0, XUnit.NANO_XDAG).longValue());
    UInt256 txFee = UInt256.valueOf(tx.getFee().toDecimal(0, XUnit.NANO_XDAG).longValue());

    // Rollback sender account
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

    // Rollback receiver account
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

  /**
   * Format block hash for logging
   */
  private String formatHash(Bytes32 hash) {
    return hash.toHexString().substring(0, 16);
  }
}
