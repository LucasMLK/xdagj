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
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Orphan Manager - 孤块管理器
 *
 * <p>负责孤块的管理和维护：
 * - 重试孤块导入（依赖满足后）
 * - 清理过期孤块（超过保留窗口）
 * - 跟踪孤块统计
 *
 * <p>从DagChainImpl提取，作为P1重构的一部分
 */
@Slf4j
public class OrphanManager {

  /**
   * Orphan block retention window (in epochs)
   * <p>
   * XDAG rule: blocks can only reference blocks within 12 days (16384 epochs) After this window,
   * orphan blocks cannot become main blocks anymore
   */
  private static final long ORPHAN_RETENTION_WINDOW = 16384;

  /**
   * Orphan cleanup interval (in epochs)
   * <p>
   * Run cleanup every 100 epochs (~1.78 hours)
   */
  private static final long ORPHAN_CLEANUP_INTERVAL = 100;

  private final DagKernel dagKernel;
  private final DagStore dagStore;
  private final ThreadLocal<Boolean> isRetryingOrphans = ThreadLocal.withInitial(() -> false);

  /**
   * 创建OrphanManager
   *
   * @param dagKernel DAG内核，提供所有必要的组件访问
   */
  public OrphanManager(DagKernel dagKernel) {
    this.dagKernel = dagKernel;
    this.dagStore = dagKernel.getDagStore();
  }

  /**
   * Retry orphan blocks that may now have satisfied dependencies
   *
   * <p>After successfully importing a block, some orphan blocks may now have all
   * their dependencies satisfied. This method retrieves orphan blocks from the queue and attempts
   * to import them again.
   *
   * @param tryToConnect function to attempt block import
   */
  public void retryOrphanBlocks(
      java.util.function.Function<Block, DagImportResult> tryToConnect) {

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
                  formatHash(pendingHash));
              continue;
            }

            // Attempt to import
            log.debug("Re-importing pending block {} (current height={})",
                formatHash(pendingHash),
                pendingBlock.getInfo() != null ? pendingBlock.getInfo().getHeight() : "null");
            DagImportResult result = tryToConnect.apply(pendingBlock);
            log.debug("Re-import result for block {}: status={}, isMain={}, height={}",
                formatHash(pendingHash),
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
                  formatHash(pendingHash), result.getStatus());
            } else if (result.getStatus() == DagImportResult.ImportStatus.MISSING_DEPENDENCY) {
              failCount++;
              // Still missing dependencies, block remains at height=0
            } else if (result.getStatus() == DagImportResult.ImportStatus.DUPLICATE) {
              // Already imported, skip
            } else {
              failCount++;
              log.warn("Pending block {} import failed: {}",
                  formatHash(pendingHash), result.getStatus());
            }

          } catch (Exception e) {
            failCount++;
            log.error("Error retrying pending block {}: {}",
                formatHash(pendingHash), e.getMessage());
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
   * Clean up old orphan blocks beyond retention window
   * <p>
   * Periodically removes orphan blocks older than ORPHAN_RETENTION_WINDOW (16384 epochs = 12 days).
   * According to XDAG rules, blocks can only reference blocks within 12 days, so older orphan
   * blocks cannot become main blocks anymore and can be safely deleted.
   * <p>
   * Runs every ORPHAN_CLEANUP_INTERVAL (100) epochs to maintain manageable orphan pool size.
   *
   * @param chainStats            current chain statistics
   * @param currentEpoch          current epoch number
   * @param getCandidateBlocksInEpoch function to get candidate blocks in an epoch
   * @return updated chain statistics
   */
  public ChainStats cleanupOldOrphans(
      ChainStats chainStats,
      long currentEpoch,
      java.util.function.LongFunction<List<Block>> getCandidateBlocksInEpoch) {

    long lastCleanupEpoch = chainStats.getLastOrphanCleanupEpoch();

    // Check if cleanup interval reached
    if (currentEpoch - lastCleanupEpoch < ORPHAN_CLEANUP_INTERVAL) {
      return chainStats;  // Not time yet
    }

    log.info("Orphan cleanup triggered at epoch {} (last cleanup: epoch {})",
        currentEpoch, lastCleanupEpoch);

    long cutoffEpoch = currentEpoch - ORPHAN_RETENTION_WINDOW;
    if (cutoffEpoch < 0) {
      cutoffEpoch = 0;  // Don't go negative
    }

    int removedCount = 0;
    long scanStartEpoch = Math.max(0, lastCleanupEpoch - ORPHAN_RETENTION_WINDOW);

    // BUGFIX: Limit scan range to prevent scanning too many epochs
    // In test scenarios, lastCleanupEpoch may be very old (tens of thousands of epochs ago)
    // We don't need to scan ALL epochs since last cleanup - just cleanup the retention window
    long maxScanRange = ORPHAN_RETENTION_WINDOW + ORPHAN_CLEANUP_INTERVAL;
    if ((cutoffEpoch - scanStartEpoch) > maxScanRange) {
      long originalStart = scanStartEpoch;
      scanStartEpoch = Math.max(0, cutoffEpoch - maxScanRange);
      log.warn("Large epoch gap detected in orphan cleanup: {} epochs between {} and {}",
          cutoffEpoch - originalStart, originalStart, cutoffEpoch);
      log.warn("Limiting cleanup scan to recent {} epochs (from epoch {} to {})",
          maxScanRange, scanStartEpoch, cutoffEpoch);
    }

    // Scan epochs from last cleanup to cutoff epoch
    for (long epoch = scanStartEpoch; epoch < cutoffEpoch; epoch++) {
      List<Block> candidates = getCandidateBlocksInEpoch.apply(epoch);

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
                formatHash(block.getHash()), e.getMessage());
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

    return chainStats;
  }

  /**
   * Format block hash for logging
   */
  private String formatHash(Bytes32 hash) {
    return hash.toHexString().substring(0, 16);
  }
}
