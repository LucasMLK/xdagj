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

import java.util.List;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * DagChain interface for XDAG epoch-based DAG consensus
 *
 * <p>This interface defines blockchain operations for XDAG's Directed Acyclic Graph (DAG)
 * architecture with epoch-based consensus mechanism.
 *
 * <h2>XDAG Core Concepts</h2>
 *
 * <h3>1. Epoch-Based Consensus</h3>
 * <ul>
 *   <li><strong>Epoch</strong>: 64-second time window (epoch = timestamp / 64)</li>
 *   <li><strong>Competition</strong>: All blocks in same epoch compete</li>
 *   <li><strong>Winner Selection</strong>: Block with the smallest hash wins</li>
 *   <li><strong>Main Block</strong>: Winning block becomes part of main chain</li>
 * </ul>
 *
 * <h3>2. Height vs Epoch</h3>
 * <ul>
 *   <li><strong>Height</strong>: Sequential number in main chain (1, 2, 3, ...)</li>
 *   <li><strong>Epoch</strong>: Time window (may have no blocks - empty epoch)</li>
 *   <li><strong>NOT 1:1 Mapping</strong>: Height 100 might correspond to Epoch 1003</li>
 * </ul>
 *
 * <pre>
 * Example:
 * Epoch 1000 → Block A wins → Height 1
 * Epoch 1001 → Block B wins → Height 2
 * Epoch 1002 → (no blocks) → (no main block)
 * Epoch 1003 → Block C wins → Height 3
 *
 * getMainBlockByHeight(3) → Block C (from Epoch 1003, not 1003)
 * getWinnerBlockInEpoch(1002) → null (empty epoch)
 * </pre>
 *
 * <h3>3. Cumulative Difficulty</h3>
 * <ul>
 *   <li><strong>Block Work</strong>: work = MAX_UINT256 / hash (XDAG philosophy: smaller hash = more work)</li>
 *   <li><strong>Cumulative Difficulty</strong>: Sum of all block work from genesis to current block</li>
 *   <li><strong>Chain Selection</strong>: Chain with maximum cumulative difficulty becomes main chain</li>
 * </ul>
 *
 * <h3>4. DAG Structure</h3>
 * <ul>
 *   <li><strong>Multiple Parents</strong>: Block can reference 1-16 parent blocks</li>
 *   <li><strong>No Cycles</strong>: Strict cycle detection enforced</li>
 *   <li><strong>Time Window</strong>: Can only reference blocks within 12 days (16384 epochs)</li>
 *   <li><strong>Transaction Validity</strong>: Transaction is valid if referenced by a main block</li>
 * </ul>
 *
 * <h3>5. Block States</h3>
 * <ul>
 *   <li><strong>Main Block</strong>: BlockInfo. Height &gt; 0 (on main chain)</li>
 *   <li><strong>Orphan Block</strong>: BlockInfo.height = 0 (not on main chain)</li>
 *   <li><strong>Candidate Block</strong>: Competing in current epoch (not yet determined)</li>
 * </ul>
 *
 * @see Block
 * @see Transaction
 * @see Link
 * @see BlockInfo
 * @see ChainStats
 * @since XDAGJ
 */
public interface DagChain {

  // ==================== Block Import Operations ====================

  /**
   * Validate and import a block into the DAG
   *
   * <p>This method performs comprehensive validation and import of a block:
   * <ol>
   *   <li><strong>Basic Validation</strong>: Timestamp, structure, PoW difficulty</li>
   *   <li><strong>Link Validation</strong>: Verify all Transaction and Block references to exist and are valid</li>
   *   <li><strong>DAG Rules Validation</strong>:
   *     <ul>
   *       <li>No cycles in DAG</li>
   *       <li>Time window constraints (12 days / 16384 epochs)</li>
   *       <li>Link count limits (1-16 block links)</li>
   *       <li>Timestamp ordering (all referenced blocks must be earlier)</li>
   *     </ul>
   *   </li>
   *   <li><strong>Cumulative Difficulty Calculation</strong>:
   *     <ul>
   *       <li>Find parent with maximum cumulative difficulty</li>
   *       <li>Calculate this block's work: MAX_UINT256 / hash</li>
   *       <li>Sum: parent difficulty + block work</li>
   *     </ul>
   *   </li>
   *   <li><strong>Main Chain Determination</strong>:
   *     <ul>
   *       <li>Compare with current main chain cumulative difficulty</li>
   *       <li>If higher: promote to main block, trigger reorganization if needed</li>
   *       <li>If lower: save as orphan block</li>
   *     </ul>
   *   </li>
   *   <li><strong>Epoch Competition</strong>:
   *     <ul>
   *       <li>Check if this block has the smallest hash in its epoch</li>
   *       <li>If yes: mark as main block candidate</li>
   *       <li>If no: mark as failed candidate</li>
   *     </ul>
   *   </li>
   *   <li><strong>Storage and Notification</strong>:
   *     <ul>
   *       <li>Save block data and BlockInfo metadata</li>
   *       <li>Update chain statistics</li>
   *       <li>Notify listeners of new block</li>
   *     </ul>
   *   </li>
   * </ol>
   *
   * <p><strong>Return Values</strong>:
   * <ul>
   *   <li>{@link DagImportResult.ImportStatus#SUCCESS} with {@link DagImportResult.BlockState#MAIN_BLOCK}:
   *     Block extends main chain (new highest difficulty)</li>
   *   <li>{@link DagImportResult.ImportStatus#SUCCESS} with {@link DagImportResult.BlockState#ORPHAN}:
   *     Block imported as orphan or side chain</li>
   *   <li>{@link DagImportResult.ImportStatus#DUPLICATE}:
   *     Block already exists in DAG</li>
   *   <li>{@link DagImportResult.ImportStatus#MISSING_DEPENDENCY}:
   *     Referenced parent block or transaction not found</li>
   *   <li>{@link DagImportResult.ImportStatus#INVALID}:
   *     Validation failed (basic, link, or DAG rules)</li>
   *   <li>{@link DagImportResult.ImportStatus#ERROR}:
   *     Exception during import</li>
   * </ul>
   *
   * <p><strong>Detailed Information</strong>:
   * The result includes:
   * <ul>
   *   <li>Epoch competition status (whether block won its epoch)</li>
   *   <li>Block height in main chain (if main block)</li>
   *   <li>Cumulative difficulty calculated for the block)</li>
   *   <li>Detailed error information (if import failed)</li>
   * </ul>
   *
   * @param block block to import (must be fully constructed with valid PoW)
   * @return import result indicating success/failure and chain height
   * @see DagImportResult
   * @see #validateDAGRules(Block)
   * @see #calculateCumulativeDifficulty(Block)
   */
  DagImportResult tryToConnect(Block block);

  // ==================== Block Creation Operations ====================

  /**
   * Create a candidate block for mining
   *
   * <p>This method creates a new candidate block ready for Proof-of-Work mining.
   * The block includes:
   * <ul>
   *   <li>Current timestamp (aligned to current epoch)</li>
   *   <li>Current network difficulty target</li>
   *   <li>Nonce = 0 (to be found by mining process)</li>
   *   <li>Coinbase address (this node's mining address)</li>
   *   <li>Links to blocks:
   *     <ul>
   *       <li>Previous main block (parent with the highest cumulative difficulty)</li>
   *       <li>Recent orphan blocks (for network health and connectivity)</li>
   *     </ul>
   *   </li>
   * </ul>
   *
   * <p><strong>Important</strong>: The returned block is a <em>candidate</em> that will compete
   * with other blocks in the same epoch. Only after mining finds a valid nonce and the epoch
   * ends will it be known if this block becomes a main block.
   *
   * <p><strong>Workflow</strong>:
   * <ol>
   *   <li>Call this method to get candidate block</li>
   *   <li>Mine to find valid nonce: {@code while (!block.isValidPoW()) { nonce++; }}</li>
   *   <li>Create final block: {@code Block minedBlock = block.withNonce(foundNonce);}</li>
   *   <li>Import block: {@code tryToConnect(minedBlock);}</li>
   *   <li>Broadcast to network</li>
   * </ol>
   *
   * @return candidate block for mining (nonce = 0, needs PoW)
   * @see Block#withNonce(Bytes32)
   * @see Block#isValidPoW()
   * @see #tryToConnect(Block)
   */
  Block createCandidateBlock();

  /**
   * Create the genesis block with deterministic coinbase (Bitcoin/Ethereum approach)
   *
   * <p>This is the way to create genesis blocks in XDAG.
   * All nodes use a deterministic zero coinbase address to ensure deterministic
   * genesis block creation.
   *
   * <p><strong>Why Deterministic Genesis?</strong>
   * <ul>
   *   <li>All nodes on the same network must create IDENTICAL genesis blocks</li>
   *   <li>Genesis block hash defines the network identity (like Bitcoin/Ethereum)</li>
   *   <li>Nodes with different genesis blocks cannot sync with each other</li>
   *   <li>Zero coinbase address (20-byte array of zeros) ensures determinism</li>
   * </ul>
   *
   * <p><strong>Example genesis.json</strong>:
   * <pre>
   * {
   *   "networkId": "mainnet",
   *   "epoch": 23694000,
   *   "initialDifficulty": "0x1",
   *   ...
   * }
   * </pre>
   *
   * <p>All nodes using this genesis.json will create the SAME genesis block.
   *
   * <p>The genesis block has special characteristics:
   * <ul>
   *   <li>Empty links list (no previous blocks to reference)</li>
   *   <li>Minimal difficulty (difficulty = 1)</li>
   *   <li>Zero nonce (no mining required for genesis)</li>
   *   <li>Coinbase is zero address (20-byte array of zeros)</li>
   *   <li>Specified timestamp (from genesis.json)</li>
   *   <li>Height = 1 (first block in main chain)</li>
   *   <li>Cumulative difficulty = initial work</li>
   * </ul>
   *
   * <p>This method should only be called once per blockchain instance, when no blocks exist.
   *
   * @param epoch genesis block epoch number (XDAG epoch)
   * @return genesis block ready for import
   * @see #tryToConnect(Block)
   * @since XDAGJ
   */
  Block createGenesisBlock(long epoch);

  // ==================== Main Chain Queries (Height-Based) ====================

  /**
   * Get main block by its height in the main chain
   *
   * <p><strong>What is "height"?</strong>
   * Height is the sequential number of a main block in the main chain. Main chain = sequence of all
   * epoch winning blocks in chronological order.
   *
   * <p><strong>Height vs Epoch</strong>:
   * <ul>
   *   <li><strong>Height</strong>: Sequential (1, 2, 3, ...) - always continuous</li>
   *   <li><strong>Epoch</strong>: Time window (64 seconds) - may have gaps (empty epochs)</li>
   *   <li><strong>Not 1:1</strong>: Height 100 might correspond to Epoch 1003 (not Epoch 100)</li>
   * </ul>
   *
   * <p><strong>Example</strong>:
   * <pre>
   * Height 1: Block A (from Epoch 1000)
   * Height 2: Block B (from Epoch 1001)
   * Height 3: Block C (from Epoch 1003) ← skipped Epoch 1002 (no blocks)
   * Height 4: Block D (from Epoch 1004)
   * </pre>
   *
   * <p>Only main blocks have heights (height &gt; 0). Orphan blocks have height = 0.
   *
   * @param height main chain height (1-based, height=1 is first main block after genesis)
   * @return main block at this height, or null if height is invalid
   * @see #getMainChainLength()
   * @see #getWinnerBlockInEpoch(long)
   */
  Block getMainBlockByHeight(long height);

  /**
   * Get the length of the main chain
   *
   * <p>Returns the total number of main blocks in the main chain.
   * Equivalent to the height of the latest main block.
   *
   * <p><strong>Example</strong>:
   * <pre>
   * If main chain has 500 blocks:
   *   getMainChainLength() → 500
   *   getMainBlockByHeight(500) → latest main block
   *   getMainBlockByHeight(501) → null (doesn't exist yet)
   * </pre>
   *
   * @return main chain length (number of main blocks)
   * @see #getMainBlockByHeight(long)
   */
  long getMainChainLength();

  /**
   * List recent main blocks
   *
   * <p>Returns the most recent main blocks in descending order (newest first).
   *
   * <p><strong>Example</strong>:
   * <pre>
   * If main chain has 500 blocks:
   *   listMainBlocks(10) → [Block_500, Block_499, ..., Block_491] (10 blocks)
   *   listMainBlocks(600) → all 500 blocks (limited by actual count)
   * </pre>
   *
   * @param count maximum number of main blocks to retrieve
   * @return list of main blocks in descending order (may be empty if no main blocks exist)
   * @see #getMainBlockByHeight(long)
   * @see #getMainChainLength()
   */
  List<Block> listMainBlocks(int count);

  // ==================== Epoch Queries (Time-Based) ====================

  /**
   * Get current epoch number
   *
   * <p>Epoch calculation: {@code epoch = currentTimestamp / 64}
   *
   * <p>Each epoch lasts exactly 64 seconds. All blocks created within the same
   * epoch compete to become the main block.
   *
   * @return current epoch number
   * @see #getEpochTimeRange(long)
   */
  long getCurrentEpoch();

  /**
   * Get time range for a specific epoch
   *
   * <p>Returns [startTime, endTime) time interval for the epoch.
   * <ul>
   *   <li>startTime = epoch * 64 (inclusive)</li>
   *   <li>endTime = (epoch + 1) * 64 (exclusive)</li>
   * </ul>
   *
   * <p><strong>Example</strong>:
   * <pre>
   * getEpochTimeRange(1000) → [64000, 64064)
   * getEpochTimeRange(1001) → [64064, 64128)
   * </pre>
   *
   * @param epoch epoch number
   * @return two-element array [startTime, endTime) in XDAG timestamp format
   * @see #getCurrentEpoch()
   */
  long[] getEpochTimeRange(long epoch);

  /**
   * Get all candidate blocks in a specific epoch
   *
   * <p>Returns all blocks created within the specified epoch time window,
   * including:
   * <ul>
   *   <li><strong>Winner block</strong>: Block with smallest hash (if exists and not orphan)</li>
   *   <li><strong>Failed candidates</strong>: Blocks that lost the competition</li>
   *   <li><strong>Orphan blocks</strong>: Blocks not referenced by any other block</li>
   * </ul>
   *
   * <p><strong>Example</strong>:
   * <pre>
   * Epoch 1000 has 4 candidate blocks:
   *   - Block_A (hash=0x0001..., winner)
   *   - Block_B (hash=0x0002..., failed)
   *   - Block_C (hash=0x0003..., orphan)
   *   - Block_D (hash=0x0004..., orphan)
   *
   * getCandidateBlocksInEpoch(1000) → [Block_A, Block_B, Block_C, Block_D]
   * getWinnerBlockInEpoch(1000)     → Block_A
   * </pre>
   *
   * @param epoch epoch number (timestamp / 64)
   * @return list of all candidate blocks in this epoch (may be empty if epoch has no blocks)
   * @see #getWinnerBlockInEpoch(long)
   * @see #getEpochStats(long)
   */
  List<Block> getCandidateBlocksInEpoch(long epoch);

  /**
   * Get hashes for all blocks in a specific epoch.
   *
   * <p>Used by FastDAG sync to quickly advertise which blocks exist in a time range.
   *
   * @param epoch epoch number (timestamp / 64)
   * @return ordered list of block hashes (may be empty but never {@code null})
   */
  List<Bytes32> getBlockHashesByEpoch(long epoch);

  /**
   * Get the winning block for a specific epoch
   *
   * <p>Returns the block with the smallest hash in the given epoch
   * (if it exists and is not an orphan).
   *
   * <p><strong>Winner Selection Criteria</strong>:
   * <ol>
   *   <li>Block created within epoch time window</li>
   *   <li>Block has smallest hash among all candidates in epoch</li>
   *   <li>Block is not an orphan (referenced by other blocks or is a main block)</li>
   * </ol>
   *
   * <p><strong>Returns null if</strong>:
   * <ul>
   *   <li>Epoch has no blocks (empty epoch)</li>
   *   <li>All blocks in epoch are orphans</li>
   *   <li>Epoch is in the future (not yet reached)</li>
   * </ul>
   *
   * <p><strong>Example</strong>:
   * <pre>
   * Epoch 1000: Block_A (hash=0x0001), Block_B (hash=0x0002)
   *   → getWinnerBlockInEpoch(1000) = Block_A (smallest hash)
   *
   * Epoch 1001: (no blocks)
   *   → getWinnerBlockInEpoch(1001) = null
   *
   * Epoch 1002: Block_C, Block_D (both orphans)
   *   → getWinnerBlockInEpoch(1002) = null (no valid winner)
   * </pre>
   *
   * @param epoch epoch number (timestamp / 64)
   * @return winning block for this epoch, or null if no valid winner
   * @see #getCandidateBlocksInEpoch(long)
   */
  Block getWinnerBlockInEpoch(long epoch);

  /**
   * Get statistics for a specific epoch
   *
   * <p>Returns detailed statistics for the given epoch:
   * <ul>
   *   <li>Total candidate blocks</li>
   *   <li>Winning block hash</li>
   *   <li>Average block time within epoch</li>
   *   <li>Total difficulty added in this epoch</li>
   *   <li>Whether epoch has a main block</li>
   * </ul>
   *
   * @param epoch epoch number
   * @return epoch statistics
   * @see EpochStats
   * @see #getCandidateBlocksInEpoch(long)
   * @see #getWinnerBlockInEpoch(long)
   */
  EpochStats getEpochStats(long epoch);

  // ==================== General Block Queries ====================

  /**
   * Get block by its hash
   *
   * @param hash  block hash (32 bytes)
   * @param isRaw whether to include raw block data (true) or just BlockInfo metadata (false)
   * @return block instance, or null if not found
   * @see #getMainBlockByHeight(long)
   * @see #getWinnerBlockInEpoch(long)
   */
  Block getBlockByHash(Bytes32 hash, boolean isRaw);

  // ==================== Cumulative Difficulty ====================

  /**
   * Calculate cumulative difficulty for a block
   *
   * <p>XDAG uses cumulative difficulty to select the best chain.
   * The chain with maximum cumulative difficulty becomes the main chain.
   *
   * <p><strong>Calculation Algorithm</strong>:
   * <ol>
   *   <li>Find parent block with maximum cumulative difficulty among all block links</li>
   *   <li>Calculate this block's work: {@code blockWork = MAX_UINT256 / hash}</li>
   *   <li>Cumulative difficulty = parent's cumulative difficulty + block work</li>
   * </ol>
   *
   * <p><strong>XDAG Philosophy</strong>:
   * <pre>
   * Difficulty is inverse of hash value.
   * Smaller hash → more work → higher difficulty
   *
   * Block work = MAX_UINT256 / hash
   *   - Smallest possible hash → maximum work (MAX_UINT256)
   *   - Largest possible hash → minimum work (≈1)
   * </pre>
   *
   * @param block block to calculate cumulative difficulty for
   * @return cumulative difficulty (sum of work from genesis to this block)
   * @throws IllegalArgumentException if block has no parent (except genesis)
   * @see #calculateBlockWork(Bytes32)
   * @see #tryToConnect(Block)
   */
  UInt256 calculateCumulativeDifficulty(Block block);

  /**
   * Calculate work for a single block
   *
   * <p>Block work calculation follows XDAG original design:
   * {@code blockWork = MAX_UINT256 / hash}
   *
   * <p>This means:
   * <ul>
   *   <li>Smallest possible hash (0x0000...0001) → maximum work (MAX_UINT256)</li>
   *   <li>Largest possible hash (0xFFFF...FFFF) → minimum work (1)</li>
   *   <li>Block work represents the expected number of hash attempts needed</li>
   * </ul>
   *
   * <p><strong>Example</strong>:
   * <pre>
   * hash = 0x0000000000000000000000000000000000000000000000000000000000000100
   * blockWork = MAX_UINT256 / 0x100 = 2^248
   *
   * hash = 0x0100000000000000000000000000000000000000000000000000000000000000
   * blockWork = MAX_UINT256 / 0x01000...000 = 2^8 = 256
   * </pre>
   *
   * @param hash block hash (32 bytes)
   * @return block work value
   * @throws IllegalArgumentException if hash is zero
   * @see #calculateCumulativeDifficulty(Block)
   */
  UInt256 calculateBlockWork(Bytes32 hash);

  // ==================== DAG Structure Validation ====================

  /**
   * Validate block against DAG structure rules
   *
   * <p>Validates block according to XDAG DAG rules:
   * <ol>
   *   <li><strong>No cycles</strong>: Block cannot create a cycle in DAG
   *     <ul>
   *       <li>Detect cycles via DFS traversal</li>
   *       <li>Cycle detection must be efficient (O(V+E))</li>
   *     </ul>
   *   </li>
   *   <li><strong>Time window</strong>: Can only reference blocks within 12 days (16384 epochs)
   *     <ul>
   *       <li>Max time difference: (currentEpoch - refBlockEpoch) &lt;= 16384</li>
   *       <li>Prevents referencing very old blocks</li>
   *     </ul>
   *   </li>
   *   <li><strong>Link limits</strong>: Must have 1-16 block links
   *     <ul>
   *       <li>Minimum 1 link (except genesis block)</li>
   *       <li>Maximum 16 links (to limit DAG complexity)</li>
   *     </ul>
   *   </li>
   *   <li><strong>Timestamp order</strong>: All referenced blocks must have earlier timestamps
   *     <ul>
   *       <li>Ensures time flows forward in DAG</li>
   *       <li>refBlock.timestamp &lt; thisBlock.timestamp</li>
   *     </ul>
   *   </li>
   *   <li><strong>Traversal depth</strong>: Path depth from genesis must not exceed 1000 layers
   *     <ul>
   *       <li>Prevents extremely deep DAG paths</li>
   *       <li>Limits worst-case traversal time</li>
   *     </ul>
   *   </li>
   * </ol>
   *
   * @param block block to validate
   * @return validation result with detailed error info if invalid
   * @see DAGValidationResult
   * @see #tryToConnect(Block)
   */
  DAGValidationResult validateDAGRules(Block block);

  /**
   * Check if a block is in the main chain
   *
   * <p>In XDAG DAG structure, a block is on the main chain if:
   * <ul>
   *   <li>It is a main block (BlockInfo.height &gt; 0), OR</li>
   *   <li>It is directly or indirectly referenced by a main block</li>
   * </ul>
   *
   * <p><strong>Example</strong>:
   * <pre>
   * Main chain: [Genesis] → [Block_A] → [Block_B] → [Block_C]
   *                             ↑
   *                         [Block_X] (orphan referenced by Block_A)
   *
   * isBlockInMainChain(Block_A) → true (main block, height=1)
   * isBlockInMainChain(Block_X) → true (referenced by main block)
   * isBlockInMainChain(Block_Y) → false (orphan, not referenced)
   * </pre>
   *
   * @param hash block hash to check
   * @return true if block is on main chain (directly or indirectly)
   * @see #getMainBlockByHeight(long)
   * @see #getBlockReferences(Bytes32)
   */
  boolean isBlockInMainChain(Bytes32 hash);

  /**
   * Get all blocks that reference a specific block
   *
   * <p>This is a reverse lookup for DAG traversal. Returns all blocks
   * that have a Link pointing to the specified block.
   *
   * <p><strong>Use Cases</strong>:
   * <ul>
   *   <li>Check if orphan block is referenced (can be removed from orphan pool)</li>
   *   <li>Trace transaction validity (is tx referenced by a main block?)</li>
   *   <li>Analyze DAG connectivity and structure</li>
   * </ul>
   *
   * <p><strong>Example</strong>:
   * <pre>
   * Block_A is referenced by:
   *   - Block_B (via Link)
   *   - Block_C (via Link)
   *   - Block_D (via Link)
   *
   * getBlockReferences(Block_A.hash) → [Block_B, Block_C, Block_D]
   * </pre>
   *
   * @param hash block hash to find references for
   * @return list of blocks that reference this block (may be empty)
   * @see #isBlockInMainChain(Bytes32)
   */
  List<Block> getBlockReferences(Bytes32 hash);

  // ==================== Chain Management ====================

  /**
   * Check and update the main chain
   *
   * <p>In XDAG, this method performs periodic main chain maintenance:
   * <ol>
   *   <li><strong>Scan Recent Epochs</strong>: Check recent epochs for winners (smallest hash)</li>
   *   <li><strong>Calculate Cumulative Difficulty</strong>: Compare competing chains</li>
   *   <li><strong>Chain Reorganization</strong>: Switch to chain with maximum cumulative difficulty if needed</li>
   *   <li><strong>Update Statistics</strong>: Update ChainStats with latest information</li>
   * </ol>
   *
   * <p>This method is typically called periodically by a background thread (e.g., every 1024ms).
   *
   * @see #tryToConnect(Block)
   * @see #calculateCumulativeDifficulty(Block)
   */
  void checkNewMain();

  // ==================== Statistics and State ====================

  /**
   * Get current blockchain statistics
   *
   * <p>Returns immutable snapshot of blockchain state including:
   * <ul>
   *   <li>Block counts (total, main, orphan, extra)</li>
   *   <li>Network state (hosts, max difficulty)</li>
   *   <li>Top block information (hash, difficulty)</li>
   *   <li>Wallet balance</li>
   * </ul>
   *
   * @return ChainStats containing current blockchain statistics
   * @see ChainStats
   */
  ChainStats getChainStats();

  // ==================== Lifecycle Management ====================

  // ==================== DAG Chain Event Listeners ====================

  /**
   * Add DAG chain event listener
   *
   * <p>Registers a listener to receive notifications when blocks are
   * connected to or disconnected from the main chain.
   *
   * <p><strong>Thread Safety:</strong> This method is thread-safe.
   * Listeners may be called from different threads.
   *
   * <p><strong>Use Cases:</strong>
   * <ul>
   *   <li>RandomX seed updates on epoch boundaries</li>
   *   <li>Transaction indexing</li>
   *   <li>Statistics collection</li>
   * </ul>
   *
   * @param listener DAG chain listener to register
   * @throws IllegalArgumentException if listener is null
   * @see DagchainListener
   * @see #removeListener(DagchainListener)
   * @since XDAGJ v0.8.1
   */
  void addListener(DagchainListener listener);

  /**
   * Remove DAG chain event listener
   *
   * <p>Unregisters a previously registered DAG chain listener.
   *
   * <p><strong>Thread Safety:</strong> This method is thread-safe.
   *
   * @param listener DAG chain listener to unregister
   * @see DagchainListener
   * @see #addListener(DagchainListener)
   * @since XDAGJ v0.8.1
   */
  void removeListener(DagchainListener listener);

  /**
   * Register a listener for new block events (specifically for P2P broadcasting).
   *
   * @param listener the listener to register
   */
  void registerNewBlockListener(io.xdag.core.listener.NewBlockListener listener);
}
