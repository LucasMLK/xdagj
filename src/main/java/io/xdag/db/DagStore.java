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

import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.core.ChainStats;
import io.xdag.core.XdagLifecycle;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/**
 * DagStore for XDAG v5.1 - Block-focused DAG Storage
 *
 * <p>This store manages Block entities and DAG structure.
 * For Transaction storage, use TransactionStore.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><strong>Block-centric</strong>: Focus on Block storage and DAG structure</li>
 *   <li><strong>Epoch-aware</strong>: Support epoch-based queries</li>
 *   <li><strong>Position-based</strong>: Clear distinction between position and epoch</li>
 *   <li><strong>Performance-optimized</strong>: Multi-tier caching (L1 + L2)</li>
 * </ul>
 *
 * <h2>Storage Layout</h2>
 * <pre>
 * DagStore (0xa0-0xbf)
 * 0xa0: Block storage (hash → Block)
 * 0xa1: BlockInfo metadata (hash → BlockInfo)
 * 0xa2: ChainStats
 *
 * 0xb0: Time index (timestamp → List&lt;blockHash&gt;)
 * 0xb1: Epoch index (epoch → List&lt;blockHash&gt;)
 * 0xb2: Height index (height → blockHash)
 * 0xb3: Block references (blockHash → List&lt;referencingHashes&gt;)
 * </pre>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>Block Import: &lt; 50ms (P99)</li>
 *   <li>Block Read: &lt; 5ms (cache), &lt; 20ms (disk)</li>
 *   <li>Batch Import: 1000-5000 blocks/s</li>
 *   <li>Cache Hit Rate: 90%+</li>
 * </ul>
 *
 * @since v5.1 Phase 8
 * @see TransactionStore
 * @see io.xdag.db.store.DagCache
 * @see io.xdag.db.store.DagEntityResolver
 */
public interface DagStore extends XdagLifecycle {

    // ==================== Storage Prefixes ====================

    /** Block storage: hash → Block raw data */
    byte BLOCK_DATA = (byte) 0xa0;

    /** BlockInfo metadata: hash → BlockInfo */
    byte BLOCK_INFO = (byte) 0xa1;

    /** ChainStats: single entry */
    byte CHAIN_STATS = (byte) 0xa2;

    /** Time index: timestamp → List&lt;blockHash&gt; */
    byte TIME_INDEX = (byte) 0xb0;

    /** Epoch index: epoch → List&lt;blockHash&gt; */
    byte EPOCH_INDEX = (byte) 0xb1;

    /** Height index: height → blockHash (main blocks only) */
    byte HEIGHT_INDEX = (byte) 0xb2;

    /** Block references: blockHash → List&lt;referencingHashes&gt; */
    byte BLOCK_REFS_INDEX = (byte) 0xb3;

    // ==================== Block Operations ====================

    /**
     * Save a block to the database
     *
     * <p>This method stores:
     * <ul>
     *   <li>Block raw data (BLOCK_DATA)</li>
     *   <li>BlockInfo metadata (BLOCK_INFO)</li>
     *   <li>Time index (TIME_INDEX)</li>
     *   <li>Epoch index (EPOCH_INDEX)</li>
     *   <li>Height index (HEIGHT_INDEX, if main block)</li>
     * </ul>
     *
     * @param block Block to save (must have BlockInfo set)
     * @throws IllegalArgumentException if block.getInfo() is null
     */
    void saveBlock(Block block);

    /**
     * Get block by hash
     *
     * @param hash Block hash
     * @param isRaw true to load full raw data, false for BlockInfo only
     * @return Block or null if not found
     */
    Block getBlockByHash(Bytes32 hash, boolean isRaw);

    /**
     * Check if block exists
     *
     * <p>This method uses Bloom filter for fast checking.
     *
     * @param hash Block hash
     * @return true if block exists
     */
    boolean hasBlock(Bytes32 hash);

    /**
     * Delete block from database (for testing or reorganization)
     *
     * @param hash Block hash
     */
    void deleteBlock(Bytes32 hash);

    // ==================== Main Chain Queries (Position-Based) ====================

    /**
     * Get main block at specific position in main chain
     *
     * <p><strong>Position</strong>: Sequential number in main chain (1, 2, 3, ...)
     * <p><strong>NOT 1:1 with Epoch</strong>: Position 100 might be from Epoch 1003
     *
     * @param position Main chain position (1-based, 1 = first main block)
     * @param isRaw true to load full raw data, false for BlockInfo only
     * @return Block at position, or null if position is invalid
     */
    Block getMainBlockAtPosition(long position, boolean isRaw);

    /**
     * Get main chain length (number of main blocks)
     *
     * @return Main chain length
     */
    long getMainChainLength();

    /**
     * List recent main blocks
     *
     * @param count Maximum number of blocks to retrieve
     * @return List of main blocks in descending order (newest first)
     */
    List<Block> listMainBlocks(int count);

    /**
     * Get multiple main blocks by height range (for sync protocol)
     *
     * <p>Efficient batch query for linear main chain sync.
     * Recommended batch size: 1000 blocks.
     *
     * <p><strong>Performance</strong>:
     * <ul>
     *   <li>Uses cached height-to-hash mappings (L1 cache)</li>
     *   <li>Batch loads blocks from RocksDB (L2 cache + disk)</li>
     *   <li>~100-500ms for 1000 blocks</li>
     * </ul>
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @param isRaw true to load full raw data, false for BlockInfo only
     * @return List of blocks in ascending height order (may contain nulls for missing blocks)
     */
    List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight, boolean isRaw);

    /**
     * Check if main chain is continuous in height range
     *
     * <p>Used by sync protocol to verify downloaded main chain integrity.
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @return true if all blocks exist and maxDiffLink forms continuous chain
     */
    boolean verifyMainChainContinuity(long fromHeight, long toHeight);

    // ==================== Epoch Queries (Time-Based) ====================

    /**
     * Get all candidate blocks in a specific epoch
     *
     * <p>Returns all blocks with timestamps in [epoch*64, (epoch+1)*64)
     *
     * @param epoch Epoch number (timestamp / 64)
     * @return List of candidate blocks (may be empty)
     */
    List<Block> getCandidateBlocksInEpoch(long epoch);

    /**
     * Get winning block for a specific epoch
     *
     * <p>Winner = block with smallest hash among all main block candidates in epoch
     *
     * @param epoch Epoch number
     * @return Winning block, or null if epoch has no main blocks
     */
    Block getWinnerBlockInEpoch(long epoch);

    /**
     * Get position of winning block in a specific epoch
     *
     * <p>Provides Epoch → Position mapping
     *
     * @param epoch Epoch number
     * @return Main chain position, or -1 if no winner
     */
    long getPositionOfWinnerBlock(long epoch);

    /**
     * Get blocks within time range
     *
     * @param startTime Start timestamp (XDAG format, inclusive)
     * @param endTime End timestamp (XDAG format, exclusive)
     * @return List of blocks in time range
     */
    List<Block> getBlocksByTimeRange(long startTime, long endTime);

    // ==================== DAG Structure ====================

    /**
     * Get all blocks that reference a specific block
     *
     * <p>This is a reverse lookup for DAG traversal.
     *
     * @param blockHash Block hash to find references for
     * @return List of hashes of blocks that reference this block
     */
    List<Bytes32> getBlockReferences(Bytes32 blockHash);

    /**
     * Index block reference relationship
     *
     * <p>Records that referencingBlock references referencedBlock
     *
     * @param referencingBlock Block that contains the reference
     * @param referencedBlock Block being referenced
     */
    void indexBlockReference(Bytes32 referencingBlock, Bytes32 referencedBlock);

    // ==================== BlockInfo & Metadata ====================

    /**
     * Save BlockInfo metadata
     *
     * @param blockInfo BlockInfo to save
     */
    void saveBlockInfo(BlockInfo blockInfo);

    /**
     * Get BlockInfo metadata only (without Block data)
     *
     * @param hash Block hash
     * @return BlockInfo or null if not found
     */
    BlockInfo getBlockInfo(Bytes32 hash);

    /**
     * Check if BlockInfo exists
     *
     * @param hash Block hash
     * @return true if BlockInfo exists
     */
    boolean hasBlockInfo(Bytes32 hash);

    // ==================== ChainStats ====================

    /**
     * Save chain statistics
     *
     * @param stats ChainStats to save
     */
    void saveChainStats(ChainStats stats);

    /**
     * Get chain statistics
     *
     * @return ChainStats or null if not found
     */
    ChainStats getChainStats();

    // ==================== Batch Operations ====================

    /**
     * Batch save multiple blocks
     *
     * <p>More efficient than calling saveBlock() multiple times.
     * Uses WriteBatch for atomic writes.
     *
     * @param blocks List of blocks to save
     */
    void saveBlocks(List<Block> blocks);

    /**
     * Batch get multiple blocks by hashes
     *
     * @param hashes List of block hashes
     * @return List of Blocks (null entries for missing blocks)
     */
    List<Block> getBlocksByHashes(List<Bytes32> hashes);

    // ==================== Statistics ====================

    /**
     * Get total number of blocks in database
     *
     * @return Block count
     */
    long getBlockCount();

    /**
     * Get database size in bytes
     *
     * @return Database size
     */
    long getDatabaseSize();

    /**
     * Reset the database (for testing or migration)
     */
    void reset();
}
