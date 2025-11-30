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

package io.xdag.store;

import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.core.ChainStats;
import io.xdag.core.XdagLifecycle;
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/**
 * DagStore for XDAG - Block-focused DAG Storage
 *
 * <p>This store manages Block entities and DAG structure.
 * For Transaction storage, use TransactionStore.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><strong>Block-centric</strong>: Focus on Block storage and DAG structure</li>
 *   <li><strong>Epoch-aware</strong>: Support epoch-based queries</li>
 *   <li><strong>Height-based</strong>: Clear distinction between height and epoch</li>
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
 * @since XDAGJ
 */
public interface DagStore extends XdagLifecycle {

  // ==================== Storage Prefixes ====================

  /**
   * Block storage: hash → Block raw data
   */
  byte BLOCK_DATA = (byte) 0xa0;

  /**
   * BlockInfo metadata: hash → BlockInfo
   */
  byte BLOCK_INFO = (byte) 0xa1;

  /**
   * ChainStats: single entry
   */
  byte CHAIN_STATS = (byte) 0xa2;

  /**
   * Epoch index: epoch → List&lt;blockHash&gt;
   */
  byte EPOCH_INDEX = (byte) 0xb1;

  /**
   * Height index: height → blockHash (main blocks only)
   */
  byte HEIGHT_INDEX = (byte) 0xb2;

  /**
   * Block references: blockHash → List&lt;referencingHashes&gt;
   */
  byte BLOCK_REFS_INDEX = (byte) 0xb3;

  /**
   * Orphan reason: blockHash → OrphanReason (BUG-ORPHAN-001 fix)
   */
  byte ORPHAN_REASON = (byte) 0xb4;

  /**
   * Pending block data for missing dependencies
   */
  byte MISSING_DEP_BLOCK = (byte) 0xc0;

  /**
   * Missing parent index: parentHash + childHash → empty (reverse lookup)
   */
  byte MISSING_PARENT_INDEX = (byte) 0xc1;

  /**
   * Missing parents list: blockHash → List<parentHash> (forward lookup)
   * Note: Uses different prefix than MISSING_PARENT_INDEX to avoid key collision
   */
  byte MISSING_PARENTS_LIST = (byte) 0xc2;

  // ==================== Block Operations ====================

  /**
   * Save a block to the database
   *
   * <p>This method stores:
   * <ul>
   *   <li>Block raw data (BLOCK_DATA)</li>
   *   <li>BlockInfo metadata (BLOCK_INFO)</li>
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
   * @param hash  Block hash
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

  /**
   * Delete height-to-hash mapping for a specific height
   * <p>
   * This is used during chain reorganization to remove orphaned height mappings. When a block is
   * demoted from main chain, its height mapping must be explicitly deleted to prevent multiple
   * blocks from mapping to the same height.
   *
   * @param height Main chain height to delete mapping for
   */
  void deleteHeightMapping(long height);

  // ==================== Main Chain Queries (Height-Based) ====================

  /**
   * Get main block at specific height in main chain
   *
   * <p><strong>Height</strong>: Sequential number in main chain (1, 2, 3, ...)
   * <p><strong>NOT 1:1 with Epoch</strong>: Height 100 might be from Epoch 1003
   *
   * @param height Main chain height (1-based, 1 = first main block)
   * @param isRaw  true to load full raw data, false for BlockInfo only
   * @return Block at height, or null if height is invalid
   */
  Block getMainBlockByHeight(long height, boolean isRaw);

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
   * @param toHeight   End height (inclusive)
   * @param isRaw      true to load full raw data, false for BlockInfo only
   * @return List of blocks in ascending height order (may contain nulls for missing blocks)
   */
  List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight, boolean isRaw);

  /**
   * Check if main chain is continuous range
   *
   * <p>Used by sync protocol to verify downloaded main chain integrity.
   *
   * @param fromHeight Start height (inclusive)
   * @param toHeight   End height (inclusive)
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
   * @return List of candidate blocks (maybe empty)
   */
  List<Block> getCandidateBlocksInEpoch(long epoch);

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

  // ==================== Transactional Methods (Atomic Block Processing) ====================

  /**
   * Save block within a transaction context (atomic operation)
   *
   * <p>This method buffers all block-related writes in a transaction:
   * <ul>
   *   <li>Block raw data (BLOCK_DATA)</li>
   *   <li>BlockInfo metadata (BLOCK_INFO)</li>
   *   <li>Epoch index (EPOCH_INDEX)</li>
   *   <li>Height index (HEIGHT_INDEX, if height > 0)</li>
   * </ul>
   *
   * <p>Operations are NOT written to disk until transaction is committed.
   *
   * @param txId  transaction ID from RocksDBTransactionManager
   * @param info  block metadata
   * @param block block data
   * @throws io.xdag.store.rocksdb.transaction.TransactionException if operation fails
   */
  void saveBlockInTransaction(String txId, BlockInfo info, Block block)
      throws io.xdag.store.rocksdb.transaction.TransactionException;

  // ==================== Batch Operations ====================

  // ==================== Pending Blocks (Orphan Management) ====================

  /**
   * Get total count of pending blocks (height=0)
   *
   * <p>This is more efficient than getPendingBlocks().size() for large datasets.
   *
   * @return number of pending blocks
   */
  long getPendingBlockCount();

  // ==================== Orphan Reason Management (BUG-ORPHAN-001 fix) ====================

  /**
   * Save orphan reason for a block
   *
   * <p>This method records WHY a block is an orphan (height=0):
   * <ul>
   *   <li><strong>MISSING_DEPENDENCY</strong>: Block is waiting for parent blocks to arrive</li>
   *   <li><strong>LOST_COMPETITION</strong>: Block lost epoch competition and will never become main</li>
   * </ul>
   *
   * <p>This distinction allows OrphanManager to only retry MISSING_DEPENDENCY orphans,
   * avoiding wasteful retry of LOST_COMPETITION orphans.
   *
   * @param blockHash block hash
   * @param reason orphan reason
   */
  void saveOrphanReason(Bytes32 blockHash, io.xdag.core.OrphanReason reason);

  /**
   * Get orphan reason for a block
   *
   * @param blockHash block hash
   * @return orphan reason, or null if not recorded (legacy orphans before this feature)
   */
  io.xdag.core.OrphanReason getOrphanReason(Bytes32 blockHash);

  /**
   * Delete orphan reason for a block
   *
   * <p>Called when a block is promoted to main chain (height > 0) and no longer an orphan.
   *
   * @param blockHash block hash
   */
  void deleteOrphanReason(Bytes32 blockHash);

  /**
   * Count orphan entries by reason (for monitoring).
   *
   * @param reason orphan reason
   * @return number of blocks recorded with this reason
   */
  long getOrphanCountByReason(io.xdag.core.OrphanReason reason);

  // ==================== Missing Dependency Blocks ====================

  /**
   * Persist a block that failed due to missing dependencies.
   *
   * <p>Stores the raw block data (without adding it to epoch/height indexes) together with
   * the missing parent hashes so the block can be retried when parents arrive.
   *
   * @param block block data to persist
   * @param missingParents parent hashes that are currently missing (may be empty)
   */
  void saveMissingDependencyBlock(Block block, List<Bytes32> missingParents);

  /**
   * Get hashes of blocks waiting for missing dependencies.
   *
   * @param maxCount maximum number of hashes to return
   * @return list of block hashes
   */
  List<Bytes32> getMissingDependencyBlockHashes(int maxCount);

  /**
   * Count blocks stored in the missing-dependency column family.
   *
   * <p>Each entry represents a block that could not be imported because one
   * or more parents were missing at the time of validation.
   *
   * @return number of missing-dependency blocks currently persisted
   */
  long getMissingDependencyBlockCount();

  /**
   * Get recorded missing parent hashes for a pending block.
   *
   * @param blockHash orphan hash
   * @return list of missing parent hashes (never null)
   */
  List<Bytes32> getMissingParents(Bytes32 blockHash);

  /**
   * Delete a block from the missing-dependency store.
   *
   * @param blockHash block hash to remove
   */
  void deleteMissingDependencyBlock(Bytes32 blockHash);

  /**
   * Get block hashes waiting for a specific parent.
   *
   * @param parentHash parent block hash
   * @return list of orphan hashes waiting for this parent
   */
  List<Bytes32> getBlocksWaitingForParent(Bytes32 parentHash);

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

  // ==================== Durability & WAL ====================

  /**
   * Synchronize Write-Ahead Log to disk
   *
   * <p>This method forces RocksDB to flush the WAL (Write-Ahead Log) to disk,
   * ensuring data durability. It should be called:
   * <ul>
   *   <li>At epoch boundaries to ensure epoch data persistence</li>
   *   <li>Before graceful shutdown to prevent data loss</li>
   *   <li>After critical batch operations (e.g., initial sync)</li>
   * </ul>
   *
   * <p><strong>Performance Impact</strong>:
   * <ul>
   *   <li>fsync() syscall: ~5-10ms on SSD</li>
   *   <li>Should not be called for every block write</li>
   *   <li>Recommended frequency: per-epoch (every 64 seconds)</li>
   * </ul>
   *
   * <p><strong>BUG-STORAGE-002 Fix</strong>:
   * This method fixes the data loss issue where blocks written with async writes
   * (setSync(false)) are lost when node is force-killed before RocksDB flushes WAL.
   *
   * @see <a href="https://github.com/facebook/rocksdb/wiki/Write-Ahead-Log">RocksDB WAL</a>
   */
  void syncWal();

  /**
   * Flush in-memory writes to SST files to guarantee read-your-write visibility.
   *
   * <p>Used by consensus code before running integrity verification so that
   * range scans (new iterators) observe the latest demotions/promotions without
   * forcing a full WAL fsync.
   */
  void flushMemTable();
}
