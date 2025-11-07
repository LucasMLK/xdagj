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

package io.xdag.consensus.sync.protocol;

import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.db.DagStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Main Chain Query Utility
 *
 * <p>This class provides high-level query operations for the main chain,
 * abstracting away the underlying storage details. It serves as a convenient
 * wrapper around DagStore's main chain query capabilities.
 *
 * <p><strong>Key Features</strong>:
 * <ul>
 *   <li>Efficient batch queries for main chain blocks</li>
 *   <li>Main chain continuity verification</li>
 *   <li>Height-to-hash mapping queries</li>
 *   <li>Integration with finality boundary calculations</li>
 * </ul>
 *
 * <p><strong>Design Principles (from HYBRID_SYNC_PROTOCOL.md)</strong>:
 * <ul>
 *   <li>Delegates to DagStore for actual storage operations</li>
 *   <li>Provides convenient high-level APIs for sync protocols</li>
 *   <li>Integrates with FinalityBoundary for finalized/active region queries</li>
 * </ul>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * MainChainQuery query = new MainChainQuery(dagStore);
 *
 * // Get main blocks in a range
 * List<Block> blocks = query.getMainBlocksByHeightRange(1000, 2000, false);
 *
 * // Verify main chain continuity
 * boolean isContinuous = query.verifyMainChain(1000, 2000);
 *
 * // Get block by height
 * Block block = query.getMainBlockByHeight(1500);
 * }</pre>
 *
 * @see DagStore
 * @see FinalityBoundary
 * @see <a href="docs/refactor-design/HYBRID_SYNC_PROTOCOL.md">Hybrid Sync Protocol</a>
 * @since v5.1
 */
@Slf4j
public class MainChainQuery {

    private final DagStore dagStore;

    /**
     * Constructor
     *
     * @param dagStore DagStore instance for storage operations
     */
    public MainChainQuery(DagStore dagStore) {
        this.dagStore = Objects.requireNonNull(dagStore, "DagStore cannot be null");
    }

    /**
     * Get main blocks by height range
     *
     * <p>Efficiently retrieves a batch of main chain blocks in the specified
     * height range. Uses DagStore's optimized batch query with L1/L2 cache.
     *
     * <p><strong>Performance</strong>:
     * <ul>
     *   <li>Recommended batch size: 1000 blocks</li>
     *   <li>Expected latency: ~100-500ms for 1000 blocks</li>
     *   <li>Uses height index + RocksDB multiGetAsList()</li>
     * </ul>
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @param isRaw true to load full block data, false for BlockInfo only
     * @return List of main blocks in ascending height order (may contain nulls for missing blocks)
     */
    public List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight, boolean isRaw) {
        if (fromHeight < 0 || toHeight < fromHeight) {
            log.warn("Invalid height range: [{}, {}]", fromHeight, toHeight);
            return Collections.emptyList();
        }

        try {
            return dagStore.getMainBlocksByHeightRange(fromHeight, toHeight, isRaw);
        } catch (Exception e) {
            log.error("Failed to get main blocks by height range [{}, {}]", fromHeight, toHeight, e);
            return Collections.emptyList();
        }
    }

    /**
     * Get main block by height
     *
     * <p>Retrieves a single main block at the specified height.
     *
     * @param height Block height
     * @param isRaw true to load full block data, false for BlockInfo only
     * @return Main block at the height, or null if not found
     */
    public Block getMainBlockByHeight(long height, boolean isRaw) {
        if (height < 0) {
            log.warn("Invalid height: {}", height);
            return null;
        }

        try {
            // Note: DagStore uses position-based indexing
            // Height and position are equivalent for main blocks in v5.1
            return dagStore.getMainBlockAtPosition(height, isRaw);
        } catch (Exception e) {
            log.error("Failed to get main block at height {}", height, e);
            return null;
        }
    }

    /**
     * Verify main chain continuity
     *
     * <p>Checks that:
     * <ul>
     *   <li>All heights in the range have main blocks</li>
     *   <li>Each block references the previous main block</li>
     *   <li>No missing blocks in the chain</li>
     * </ul>
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @return true if chain is continuous, false otherwise
     */
    public boolean verifyMainChain(long fromHeight, long toHeight) {
        if (fromHeight < 0 || toHeight < fromHeight) {
            return false;
        }

        try {
            return dagStore.verifyMainChainContinuity(fromHeight, toHeight);
        } catch (Exception e) {
            log.error("Failed to verify main chain continuity [{}, {}]", fromHeight, toHeight, e);
            return false;
        }
    }

    /**
     * Get current main chain height
     *
     * <p>Returns the height of the current main chain tip.
     *
     * @return Current main chain height, or 0 if no main blocks exist
     */
    public long getCurrentMainHeight() {
        try {
            // Use main chain length as the current height
            return dagStore.getMainChainLength();
        } catch (Exception e) {
            log.error("Failed to get current main height", e);
            return 0;
        }
    }

    /**
     * Get finalized blocks in a height range
     *
     * <p>Returns only the finalized blocks (beyond FINALITY_EPOCHS) in the
     * specified range. Uses FinalityBoundary to determine finalized height.
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @param isRaw true to load full block data, false for BlockInfo only
     * @return List of finalized main blocks
     */
    public List<Block> getFinalizedBlocksInRange(long fromHeight, long toHeight, boolean isRaw) {
        long currentHeight = getCurrentMainHeight();
        long finalizedHeight = FinalityBoundary.getFinalizedHeight(currentHeight);

        // Adjust range to only include finalized blocks
        long adjustedFrom = Math.max(fromHeight, 0);
        long adjustedTo = Math.min(toHeight, finalizedHeight);

        if (adjustedFrom > adjustedTo) {
            // No finalized blocks in this range
            return Collections.emptyList();
        }

        return getMainBlocksByHeightRange(adjustedFrom, adjustedTo, isRaw);
    }

    /**
     * Get active DAG blocks in a height range
     *
     * <p>Returns only the active DAG blocks (within FINALITY_EPOCHS) in the
     * specified range. Uses FinalityBoundary to determine active region.
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @param isRaw true to load full block data, false for BlockInfo only
     * @return List of active DAG main blocks
     */
    public List<Block> getActiveDAGBlocksInRange(long fromHeight, long toHeight, boolean isRaw) {
        long currentHeight = getCurrentMainHeight();
        long finalizedHeight = FinalityBoundary.getFinalizedHeight(currentHeight);

        // Adjust range to only include active DAG blocks
        long adjustedFrom = Math.max(fromHeight, finalizedHeight + 1);
        long adjustedTo = Math.min(toHeight, currentHeight);

        if (adjustedFrom > adjustedTo) {
            // No active DAG blocks in this range
            return Collections.emptyList();
        }

        return getMainBlocksByHeightRange(adjustedFrom, adjustedTo, isRaw);
    }

    /**
     * Count main blocks in a height range
     *
     * <p>Efficiently counts how many main blocks exist in the specified range
     * without loading the actual block data.
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @return Number of main blocks in the range
     */
    public long countMainBlocksInRange(long fromHeight, long toHeight) {
        if (fromHeight < 0 || toHeight < fromHeight) {
            return 0;
        }

        try {
            // Load blocks without raw data (faster)
            List<Block> blocks = dagStore.getMainBlocksByHeightRange(fromHeight, toHeight, false);
            return blocks.stream().filter(Objects::nonNull).count();
        } catch (Exception e) {
            log.error("Failed to count main blocks in range [{}, {}]", fromHeight, toHeight, e);
            return 0;
        }
    }

    /**
     * Get block hashes in a height range
     *
     * <p>Returns only the hashes of main blocks in the specified range,
     * without loading full block data. Useful for sync protocols.
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @return List of block hashes in ascending height order
     */
    public List<Bytes32> getMainBlockHashesInRange(long fromHeight, long toHeight) {
        if (fromHeight < 0 || toHeight < fromHeight) {
            return Collections.emptyList();
        }

        try {
            // Load blocks without raw data (faster)
            List<Block> blocks = dagStore.getMainBlocksByHeightRange(fromHeight, toHeight, false);
            return blocks.stream()
                    .filter(Objects::nonNull)
                    .map(Block::getHash)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get main block hashes in range [{}, {}]", fromHeight, toHeight, e);
            return Collections.emptyList();
        }
    }

    /**
     * Check if a height has a main block
     *
     * @param height Block height
     * @return true if a main block exists at this height
     */
    public boolean hasMainBlockAtHeight(long height) {
        if (height < 0) {
            return false;
        }

        try {
            // Note: DagStore uses position-based indexing
            Block block = dagStore.getMainBlockAtPosition(height, false);
            return block != null && block.getInfo() != null && block.getInfo().isMainBlock();
        } catch (Exception e) {
            log.error("Failed to check if main block exists at height {}", height, e);
            return false;
        }
    }

    /**
     * Get statistics for a height range
     *
     * <p>Returns statistics about the main chain in the specified range.
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @return MainChainStats object with statistics
     */
    public MainChainStats getStats(long fromHeight, long toHeight) {
        if (fromHeight < 0 || toHeight < fromHeight) {
            return new MainChainStats(0, 0, false, 0, 0);
        }

        try {
            List<Block> blocks = dagStore.getMainBlocksByHeightRange(fromHeight, toHeight, false);

            long totalBlocks = toHeight - fromHeight + 1;
            long existingBlocks = blocks.stream().filter(Objects::nonNull).count();
            long missingBlocks = totalBlocks - existingBlocks;
            boolean isContinuous = dagStore.verifyMainChainContinuity(fromHeight, toHeight);

            long currentHeight = getCurrentMainHeight();
            long finalizedCount = blocks.stream()
                    .filter(Objects::nonNull)
                    .map(Block::getInfo)
                    .filter(Objects::nonNull)
                    .filter(info -> FinalityBoundary.isFinalized(info.getHeight(), currentHeight))
                    .count();

            return new MainChainStats(existingBlocks, missingBlocks, isContinuous,
                    finalizedCount, existingBlocks - finalizedCount);
        } catch (Exception e) {
            log.error("Failed to get stats for range [{}, {}]", fromHeight, toHeight, e);
            return new MainChainStats(0, 0, false, 0, 0);
        }
    }

    /**
     * Main Chain Statistics
     *
     * <p>Contains statistical information about a main chain height range.
     */
    public static class MainChainStats {
        private final long existingBlocks;
        private final long missingBlocks;
        private final boolean continuous;
        private final long finalizedBlocks;
        private final long activeBlocks;

        public MainChainStats(long existingBlocks, long missingBlocks, boolean continuous,
                            long finalizedBlocks, long activeBlocks) {
            this.existingBlocks = existingBlocks;
            this.missingBlocks = missingBlocks;
            this.continuous = continuous;
            this.finalizedBlocks = finalizedBlocks;
            this.activeBlocks = activeBlocks;
        }

        public long getExistingBlocks() {
            return existingBlocks;
        }

        public long getMissingBlocks() {
            return missingBlocks;
        }

        public boolean isContinuous() {
            return continuous;
        }

        public long getFinalizedBlocks() {
            return finalizedBlocks;
        }

        public long getActiveBlocks() {
            return activeBlocks;
        }

        @Override
        public String toString() {
            return String.format(
                    "MainChainStats{existing=%d, missing=%d, continuous=%b, finalized=%d, active=%d}",
                    existingBlocks, missingBlocks, continuous, finalizedBlocks, activeBlocks
            );
        }
    }
}
