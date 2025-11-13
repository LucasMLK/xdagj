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

package io.xdag.db;

import io.xdag.core.BlockInfo;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Storage interface for finalized blocks (blocks older than 12 days / 16384 epochs)
 *
 * This store maintains:
 * - Complete DAG structure (all inputs/outputs preserved)
 * - Main chain index (height → hash)
 * - Epoch index (epoch → List<hash>)
 * - Block metadata cache (for fast queries)
 *
 * Design principles:
 * - Finalized blocks are immutable (never modified)
 * - Full DAG structure preserved for signature verification
 * - Optimized for batch reads (syncing new nodes)
 * - Separated from active DAG store for better performance
 *
 * Reference: FINALIZED_BLOCK_STORAGE.md, HYBRID_SYNC_PROTOCOL.md
 */
public interface FinalizedBlockStore extends AutoCloseable {

    // ========== Basic Operations ==========

    /**
     * Save finalized block metadata only
     * Used for fast lookups without loading full block data
     *
     * @param blockInfo Block metadata
     */
    void saveBlockInfo(BlockInfo blockInfo);

    /**
     * Check if a block exists in finalized storage
     * Uses Bloom filter for fast negative lookups
     *
     * @param hash Block hash
     * @return true if block exists
     */
    boolean hasBlock(Bytes32 hash);

    /**
     * Get finalized block metadata by hash
     * Faster than getBlockByHash() if only metadata is needed
     *
     * @param hash Block hash
     * @return Block metadata, or empty if not found
     */
    Optional<BlockInfo> getBlockInfoByHash(Bytes32 hash);

    // ========== Main Chain Index ==========
    /**
     * Get main block metadata by height
     *
     * @param height Block height
     * @return Main block metadata, or empty if not finalized yet
     */
    Optional<BlockInfo> getMainBlockInfoByHeight(long height);

    /**
     * Get a range of main block metadata by height
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @return List of main block metadata in order
     */
    List<BlockInfo> getMainBlockInfosByHeightRange(long fromHeight, long toHeight);

    /**
     * Get the highest finalized main block height
     *
     * @return Highest finalized height, or -1 if no blocks finalized
     */
    long getMaxFinalizedHeight();

    /**
     * Verify main chain continuity in a range
     *
     * @param fromHeight Start height
     * @param toHeight End height
     * @return true if chain is continuous (each block's maxDiffLink points to previous)
     */
    boolean verifyMainChainContinuity(long fromHeight, long toHeight);

    // ========== Epoch Index ==========

    /**
     * Get all block hashes in an epoch
     *
     * @param epoch Epoch number (timestamp / 64)
     * @return List of block hashes in this epoch, or empty list if epoch not finalized
     */
    List<Bytes32> getBlockHashesByEpoch(long epoch);

    /**
     * Get all block metadata in an epoch
     *
     * @param epoch Epoch number
     * @return List of block metadata in this epoch
     */
    List<BlockInfo> getBlockInfosByEpoch(long epoch);

    /**
     * Count blocks in an epoch
     *
     * @param epoch Epoch number
     * @return Number of blocks in this epoch
     */
    long countBlocksInEpoch(long epoch);

    // ========== Statistics ==========

    /**
     * Get total number of finalized blocks
     *
     * @return Total finalized block count
     */
    long getTotalBlockCount();

    /**
     * Get total number of finalized main blocks
     *
     * @return Total finalized main block count
     */
    long getTotalMainBlockCount();

    /**
     * Get storage size in bytes
     *
     * @return Approximate storage size
     */
    long getStorageSize();

    /**
     * Get statistics for a height range
     *
     * @param fromHeight Start height
     * @param toHeight End height
     * @return Statistics (block count, total difficulty, etc.)
     */
    FinalizedStats getStatsForRange(long fromHeight, long toHeight);

    // ========== Maintenance ==========

    /**
     * Rebuild all indexes
     * Used for recovery or migration
     *
     * @return Number of blocks re-indexed
     */
    long rebuildIndexes();

    /**
     * Verify data integrity
     *
     * @return true if all data is consistent
     */
    boolean verifyIntegrity();

    /**
     * Compact storage (RocksDB compaction)
     */
    void compact();

    /**
     * Close the store and release resources
     */
    void close();

    // ========== Nested Classes ==========

    /**
     * Statistics for a range of finalized blocks
     */
    record FinalizedStats(
            long blockCount,
            long mainBlockCount,
            long firstHeight,
            long lastHeight,
            long firstEpoch,
            long lastEpoch
    ) {
        public static FinalizedStats zero() {
            return new FinalizedStats(0, 0, -1, -1, -1, -1);
        }
    }
}
