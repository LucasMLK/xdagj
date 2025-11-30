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
 *
 * <p>从DagChainImpl提取，作为P1重构的一部分
 *
 * <p>Note: Orphan cleanup功能已移除
 * 原因：每个epoch最多16个区块，存储量可控（约134MB for 16384 epochs）
 * 删除孤块有破坏DAG引用的风险（见BUG-LINK-NOT-FOUND）
 * 设计决策：优先保证正确性而非存储效率
 */
@Slf4j
public class OrphanManager {

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

        // BUG-ORPHAN-001 fix: Only retry MISSING_DEPENDENCY orphans
        // LOST_COMPETITION orphans will never become main blocks, no need to retry
        List<Bytes32> pendingHashes = dagStore.getPendingBlocksByReason(
            OrphanReason.MISSING_DEPENDENCY, 100, 0);

        if (pendingHashes == null || pendingHashes.isEmpty()) {
          break;  // No pending blocks to retry
        }

        log.debug("Pending block retry pass {}: {} MISSING_DEPENDENCY blocks to process",
            pass, pendingHashes.size());

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
          // BUG-LOGGING-002 fix: Changed to DEBUG level to reduce log noise
          log.debug("Orphan retry pass {} completed: {} succeeded, {} still pending",
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
   * Format block hash for logging
   */
  private String formatHash(Bytes32 hash) {
    return hash.toHexString().substring(0, 16);
  }
}
