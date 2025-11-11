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

import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.listener.Listener;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * DagChain interface for XDAG v5.1 epoch-based DAG consensus
 *
 * <p>This interface defines blockchain operations for XDAG's Directed Acyclic Graph (DAG)
 * architecture with epoch-based consensus mechanism.
 *
 * <h2>XDAG v5.1 Core Concepts</h2>
 *
 * <h3>1. Epoch-Based Consensus</h3>
 * <ul>
 *   <li><strong>Epoch</strong>: 64-second time window (epoch = timestamp / 64)</li>
 *   <li><strong>Competition</strong>: All blocks in same epoch compete</li>
 *   <li><strong>Winner Selection</strong>: Block with smallest hash wins</li>
 *   <li><strong>Main Block</strong>: Winning block becomes part of main chain</li>
 * </ul>
 *
 * <h3>2. Position vs Epoch</h3>
 * <ul>
 *   <li><strong>Position</strong>: Sequential number in main chain (1, 2, 3, ...)</li>
 *   <li><strong>Epoch</strong>: Time window (may have no blocks - empty epoch)</li>
 *   <li><strong>NOT 1:1 Mapping</strong>: Position 100 might correspond to Epoch 1003</li>
 * </ul>
 *
 * <pre>
 * Example:
 * Epoch 1000 → Block A wins → Position 1
 * Epoch 1001 → Block B wins → Position 2
 * Epoch 1002 → (no blocks) → (no main block)
 * Epoch 1003 → Block C wins → Position 3
 *
 * getMainBlockAtPosition(3) → Block C (from Epoch 1003, not 1003)
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
 *   <li><strong>Main Block</strong>: BlockInfo.height &gt; 0 (on main chain)</li>
 *   <li><strong>Orphan Block</strong>: BlockInfo.height = 0 (not on main chain)</li>
 *   <li><strong>Candidate Block</strong>: Competing in current epoch (not yet determined)</li>
 * </ul>
 *
 * @since v5.1
 * @see Block
 * @see Transaction
 * @see Link
 * @see BlockInfo
 * @see ChainStats
 */
public interface DagChain {

    // ==================== Block Import Operations ====================

    /**
     * Validate and import a block into the DAG
     *
     * <p>This method performs comprehensive validation and import of a block:
     * <ol>
     *   <li><strong>Basic Validation</strong>: Timestamp, structure, PoW difficulty</li>
     *   <li><strong>Link Validation</strong>: Verify all Transaction and Block references exist and are valid</li>
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
     *       <li>Check if this block has smallest hash in its epoch</li>
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
     *   <li>Block position in main chain (if main block)</li>
     *   <li>Cumulative difficulty calculated for the block</li>
     *   <li>Detailed error information (if import failed)</li>
     * </ul>
     *
     * @param block block to import (must be fully constructed with valid PoW)
     * @return import result indicating success/failure and chain position
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
     *       <li>Previous main block (parent with highest cumulative difficulty)</li>
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
     * Create the genesis block for blockchain initialization
     *
     * <p>The genesis block is the first block in the DAG, created during fresh node startup.
     * It has special characteristics:
     * <ul>
     *   <li>Empty links list (no previous blocks to reference)</li>
     *   <li>Minimal difficulty (difficulty = 1)</li>
     *   <li>Zero nonce (no mining required for genesis)</li>
     *   <li>Coinbase set to provided key</li>
     *   <li>Specified timestamp (usually from config: xdag.era time)</li>
     *   <li>Position = 1 (first block in main chain)</li>
     *   <li>Cumulative difficulty = initial work</li>
     * </ul>
     *
     * <p>This method should only be called once per blockchain instance, when no blocks exist.
     *
     * @param key ECKeyPair for coinbase address (genesis block creator)
     * @param timestamp genesis block timestamp (XDAG timestamp format)
     * @return genesis block ready for import
     * @see #tryToConnect(Block)
     * @deprecated Use {@link #createGenesisBlock(Bytes32, long)} instead for deterministic genesis
     */
    @Deprecated
    Block createGenesisBlock(ECKeyPair key, long timestamp);

    /**
     * Create the genesis block with deterministic coinbase (Bitcoin/Ethereum approach)
     *
     * <p>This is the RECOMMENDED way to create genesis blocks in XDAG v5.1.
     * Unlike the deprecated {@link #createGenesisBlock(ECKeyPair, long)} which uses
     * wallet keys (resulting in different genesis blocks per node), this method
     * uses a predefined coinbase address from genesis.json.
     *
     * <p><strong>Why Deterministic Genesis?</strong>
     * <ul>
     *   <li>All nodes on the same network must create IDENTICAL genesis blocks</li>
     *   <li>Genesis block hash defines the network identity (like Bitcoin/Ethereum)</li>
     *   <li>Nodes with different genesis blocks cannot sync with each other</li>
     *   <li>Coinbase address is network-defined in genesis.json, not wallet-dependent</li>
     * </ul>
     *
     * <p><strong>Example genesis.json</strong>:
     * <pre>
     * {
     *   "networkId": "mainnet",
     *   "genesisCoinbase": "0x0000000000000000000000000000000000000000000000000000000000000000",
     *   "timestamp": 1516406400,
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
     *   <li>Coinbase set to genesisCoinbase from config</li>
     *   <li>Specified timestamp (from genesis.json)</li>
     *   <li>Position = 1 (first block in main chain)</li>
     *   <li>Cumulative difficulty = initial work</li>
     * </ul>
     *
     * <p>This method should only be called once per blockchain instance, when no blocks exist.
     *
     * @param coinbase Coinbase address from genesis.json (32 bytes)
     * @param timestamp genesis block timestamp (XDAG timestamp format)
     * @return genesis block ready for import
     * @see #tryToConnect(Block)
     * @since v5.1 Phase 12.5
     */
    Block createGenesisBlock(Bytes32 coinbase, long timestamp);

    /**
     * Create a reward block for pool distribution
     *
     * <p>Creates a Block containing multiple Transaction references for distributing
     * mining rewards to pool participants (foundation, pool operator, miners).
     *
     * <p><strong>Process</strong>:
     * <ol>
     *   <li>Create Transaction objects for each recipient (foundation, pool, miners)</li>
     *   <li>Sign each Transaction with the source key (reward source block's key)</li>
     *   <li>Save Transactions to TransactionStore</li>
     *   <li>Create Block with Link.toTransaction() references</li>
     *   <li>Caller must import via {@link #tryToConnect(Block)} to finalize</li>
     * </ol>
     *
     * <p><strong>Reward Distribution Example</strong>:
     * <pre>
     * Source block: Block A (earned 10 XDAG mining reward)
     * Recipients:
     *   - Foundation: 1 XDAG (10%)
     *   - Pool operator: 2 XDAG (20%)
     *   - Miner 1: 3 XDAG (30%)
     *   - Miner 2: 4 XDAG (40%)
     * Total fee: 0.4 XDAG (divided among 4 transactions)
     * </pre>
     *
     * @param sourceBlockHash hash of source block (where funds come from)
     * @param recipients list of recipient addresses
     * @param amounts list of amounts for each recipient (must match recipients size)
     * @param sourceKey ECKeyPair for signing transactions (must own source block)
     * @param nonce account nonce for first transaction (subsequent transactions use nonce+1, nonce+2, ...)
     * @param totalFee total transaction fee (distributed evenly across all transactions)
     * @return Block containing reward transactions
     * @throws IllegalArgumentException if recipients.size() != amounts.size()
     * @see Transaction#createTransfer(Bytes32, Bytes32, XAmount, long, XAmount)
     * @see Link#toTransaction(Bytes32)
     * @see #tryToConnect(Block)
     */
    Block createRewardBlock(
            Bytes32 sourceBlockHash,
            List<Bytes32> recipients,
            List<XAmount> amounts,
            ECKeyPair sourceKey,
            long nonce,
            XAmount totalFee);

    // ==================== Main Chain Queries (Position-Based) ====================

    /**
     * Get main block by its position in the main chain
     *
     * <p><strong>What is "position"?</strong>
     * Position is the sequential number of a main block in the main chain.
     * Main chain = sequence of all epoch winning blocks in chronological order.
     *
     * <p><strong>Position vs Epoch</strong>:
     * <ul>
     *   <li><strong>Position</strong>: Sequential (1, 2, 3, ...) - always continuous</li>
     *   <li><strong>Epoch</strong>: Time window (64 seconds) - may have gaps (empty epochs)</li>
     *   <li><strong>Not 1:1</strong>: Position 100 might correspond to Epoch 1003 (not Epoch 100)</li>
     * </ul>
     *
     * <p><strong>Example</strong>:
     * <pre>
     * Position 1: Block A (from Epoch 1000)
     * Position 2: Block B (from Epoch 1001)
     * Position 3: Block C (from Epoch 1003) ← skipped Epoch 1002 (no blocks)
     * Position 4: Block D (from Epoch 1004)
     * </pre>
     *
     * <p>Only main blocks have positions (height &gt; 0). Orphan blocks have height = 0.
     *
     * @param position main chain position (1-based, position=1 is first main block after genesis)
     * @return main block at this position, or null if position is invalid
     * @see #getMainChainLength()
     * @see #getEpochOfMainBlock(long)
     * @see #getWinnerBlockInEpoch(long)
     */
    Block getMainBlockAtPosition(long position);

    /**
     * Get the length of the main chain
     *
     * <p>Returns the total number of main blocks in the main chain.
     * Equivalent to the position of the latest main block.
     *
     * <p><strong>Example</strong>:
     * <pre>
     * If main chain has 500 blocks:
     *   getMainChainLength() → 500
     *   getMainBlockAtPosition(500) → latest main block
     *   getMainBlockAtPosition(501) → null (doesn't exist yet)
     * </pre>
     *
     * @return main chain length (number of main blocks)
     * @see #getMainBlockAtPosition(long)
     */
    long getMainChainLength();

    /**
     * Get the epoch number of a main block at given position
     *
     * <p>This method provides Position → Epoch mapping.
     * Useful for understanding which time window a main block belongs to.
     *
     * <p><strong>Example</strong>:
     * <pre>
     * getEpochOfMainBlock(1) → 1000  (first main block is in Epoch 1000)
     * getEpochOfMainBlock(2) → 1001  (second main block is in Epoch 1001)
     * getEpochOfMainBlock(3) → 1003  (third main block is in Epoch 1003, skipped 1002)
     * </pre>
     *
     * @param position main chain position (1-based)
     * @return epoch number of the main block at this position, or -1 if position is invalid
     * @throws IllegalArgumentException if position &lt;= 0
     * @see #getMainBlockAtPosition(long)
     * @see #getPositionOfWinnerBlock(long)
     */
    long getEpochOfMainBlock(long position);

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
     * @see #getMainBlockAtPosition(long)
     * @see #getMainChainLength()
     */
    List<Block> listMainBlocks(int count);

    /**
     * Get main chain path from a block to genesis
     *
     * <p>Traces the main chain path backwards from the given block to the genesis block.
     * Each step follows the parent with maximum cumulative difficulty.
     *
     * <p><strong>Example</strong>:
     * <pre>
     * Main chain: Genesis → Block_A → Block_B → Block_C
     *
     * getMainChainPath(Block_C.hash) → [Block_C, Block_B, Block_A, Genesis]
     * </pre>
     *
     * @param hash starting block hash (must be a main block)
     * @return list of blocks from hash to genesis (descending order)
     * @throws IllegalArgumentException if block is not on main chain
     * @see #isBlockInMainChain(Bytes32)
     */
    List<Block> getMainChainPath(Bytes32 hash);

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
     * @see #getPositionOfWinnerBlock(long)
     */
    Block getWinnerBlockInEpoch(long epoch);

    /**
     * Get the position of the winning block in a specific epoch
     *
     * <p>This method provides Epoch → Position mapping.
     * Returns the main chain position of the epoch's winning block.
     *
     * <p><strong>Example</strong>:
     * <pre>
     * Epoch 1000 → Block_A wins → position=1
     * Epoch 1001 → Block_B wins → position=2
     * Epoch 1002 → (no blocks)  → position=-1
     * Epoch 1003 → Block_C wins → position=3
     *
     * getPositionOfWinnerBlock(1000) → 1
     * getPositionOfWinnerBlock(1001) → 2
     * getPositionOfWinnerBlock(1002) → -1 (no winner)
     * getPositionOfWinnerBlock(1003) → 3
     * </pre>
     *
     * @param epoch epoch number
     * @return main chain position of winning block, or -1 if epoch has no winning block
     * @see #getWinnerBlockInEpoch(long)
     * @see #getEpochOfMainBlock(long)
     */
    long getPositionOfWinnerBlock(long epoch);

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
     * @param hash block hash (32 bytes)
     * @param isRaw whether to include raw block data (true) or just BlockInfo metadata (false)
     * @return block instance, or null if not found
     * @see #getMainBlockAtPosition(long)
     * @see #getWinnerBlockInEpoch(long)
     */
    Block getBlockByHash(Bytes32 hash, boolean isRaw);

    /**
     * Get blocks within a time range
     *
     * <p>Returns all blocks with timestamps in the specified range.
     * Useful for time-based queries and analysis.
     *
     * @param startTime start timestamp (XDAG format, inclusive)
     * @param endTime end timestamp (XDAG format, exclusive)
     * @return list of blocks in time range, sorted by timestamp
     * @see #getCandidateBlocksInEpoch(long)
     */
    List<Block> getBlocksByTimeRange(long startTime, long endTime);

    /**
     * List blocks mined by this node
     *
     * @param count maximum number of mined blocks to retrieve
     * @return list of blocks mined by this node (may be empty)
     */
    List<Block> listMinedBlocks(int count);

    /**
     * Get memory blocks created by current node
     *
     * <p>Returns blocks that were created locally but not yet finalized
     * to persistent storage. Used for tracking pending block creation.
     *
     * @return map of block hash to creation count
     */
    Map<Bytes, Integer> getMemOurBlocks();

    // ==================== Cumulative Difficulty ====================

    /**
     * Calculate cumulative difficulty for a block
     *
     * <p>XDAG v5.1 uses cumulative difficulty to select the best chain.
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
     * <p>In XDAG v5.1 DAG structure, a block is on the main chain if:
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
     * @see #getMainBlockAtPosition(long)
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
     * <p>In XDAG v5.1, this method performs periodic main chain maintenance:
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
     * @see #startCheckMain(long)
     */
    void checkNewMain();

    /**
     * Start main chain check thread
     *
     * <p>Starts a background thread that periodically calls {@link #checkNewMain()}
     * to identify and update the best chain.
     *
     * @param period check period in milliseconds (recommended: 1024ms)
     * @see #checkNewMain()
     * @see #stopCheckMain()
     */
    void startCheckMain(long period);

    /**
     * Stop main chain check thread
     *
     * <p>Stops the background thread started by {@link #startCheckMain(long)}.
     *
     * @see #startCheckMain(long)
     */
    void stopCheckMain();

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

    /**
     * Increment waiting sync count
     *
     * <p>Increments the counter of blocks waiting for parent blocks during sync.
     * Used by SyncManager when adding blocks to the waiting queue.
     *
     * @see #decrementWaitingSyncCount()
     */
    void incrementWaitingSyncCount();

    /**
     * Decrement waiting sync count
     *
     * <p>Decrements the counter of blocks waiting for parent blocks during sync.
     * Used by SyncManager when removing blocks from the waiting queue.
     *
     * @see #incrementWaitingSyncCount()
     */
    void decrementWaitingSyncCount();

    /**
     * Update blockchain statistics from remote peer
     *
     * <p>Updates global network statistics based on data received from remote peers,
     * including total network hosts, blocks, main blocks, and maximum difficulty.
     * Values are updated to reflect the maximum seen across the network.
     *
     * @param remoteStats statistics received from remote peer
     * @see ChainStats
     */
    void updateStatsFromRemote(ChainStats remoteStats);

    // ==================== Economic Model ====================

    /**
     * Calculate block mining reward
     *
     * <p>Calculates the reward amount for a given main block based on
     * the reward schedule defined in the protocol specification.
     *
     * @param nmain main block number (position in main chain)
     * @return reward amount
     * @see #getSupply(long)
     */
    XAmount getReward(long nmain);

    /**
     * Calculate total XDAG supply
     *
     * <p>Calculates the cumulative XDAG supply at a given main block,
     * based on all rewards issued up to that block.
     *
     * @param nmain main block number (position in main chain)
     * @return total supply
     * @see #getReward(long)
     */
    XAmount getSupply(long nmain);

    // ==================== Lifecycle Management ====================

    /**
     * Register blockchain event listener
     *
     * <p>Registers a listener to receive notifications about blockchain events
     * such as new blocks, chain reorganizations, etc.
     *
     * @param listener event listener to register
     * @see Listener
     */
    void registerListener(Listener listener);

    /**
     * Get pre-seed for snapshot initialization
     *
     * <p>The pre-seed is used during blockchain initialization from snapshot
     * to ensure consistent state restoration.
     *
     * @return pre-seed bytes, or null if not available
     */
    byte[] getPreSeed();
}
