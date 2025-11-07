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

/**
 * Blockchain interface for XdagJ v5.1
 *
 * <p>This interface defines all core blockchain operations including:
 * <ul>
 *   <li>Block creation and connection</li>
 *   <li>Block query and retrieval</li>
 *   <li>Chain statistics and state management</li>
 *   <li>Economic calculations (rewards, supply)</li>
 * </ul>
 *
 * <p>Design principles:
 * <ul>
 *   <li>Uses Block (immutable) instead of legacy Block</li>
 *   <li>Uses ChainStats (immutable) for statistics</li>
 *   <li>Link-based references for efficient DAG structure</li>
 * </ul>
 *
 * @since v5.1
 */
public interface Blockchain {

    /**
     * Get pre-seed for snapshot initialization
     *
     * <p>The pre-seed is used during blockchain initialization from snapshot
     * to ensure consistent state restoration.
     *
     * @return pre-seed bytes, or null if not available
     */
    byte[] getPreSeed();


    /**
     * Try to connect a new Block to the blockchain
     *
     * <p>This method validates and imports a Block into the blockchain.
     * Block uses Link-based references instead of Address objects.
     *
     * <p>Possible results:
     * <ul>
     *   <li>IMPORTED_BEST - Block becomes new best block</li>
     *   <li>IMPORTED_NOT_BEST - Block imported but not best</li>
     *   <li>EXIST - Block already exists</li>
     *   <li>NO_PARENT - Parent block not found</li>
     *   <li>INVALID_BLOCK - Validation failed</li>
     * </ul>
     *
     * @param block Block to connect
     * @return ImportResult indicating the result of the import operation
     */
    ImportResult tryToConnect(Block block);

    /**
     * Create a candidate block for mining
     *
     * <p>This method creates a new candidate block ready for Proof-of-Work mining.
     * The block includes:
     * <ul>
     *   <li>Current timestamp</li>
     *   <li>Current network difficulty</li>
     *   <li>Nonce = 0 (to be found by mining)</li>
     *   <li>Coinbase address (miner's address)</li>
     *   <li>Links to previous main block and orphan blocks (max 16 block links)</li>
     * </ul>
     *
     * <p>Block structure uses Link-based references for efficient DAG representation.
     * After mining finds a valid nonce, use {@link Block#withNonce(Bytes32)} to create
     * the final block for import.
     *
     * @return candidate block for mining (nonce = 0, needs POW)
     * @see Block#createCandidate(long, org.apache.tuweni.units.bigints.UInt256, Bytes32, java.util.List)
     * @see Block#withNonce(Bytes32)
     */
    Block createMainBlock();

    /**
     * Create the genesis block for blockchain initialization
     *
     * <p>The genesis block is the first block in the blockchain, created during
     * fresh node startup. It has special characteristics:
     * <ul>
     *   <li>Empty links list (no previous blocks)</li>
     *   <li>Minimal difficulty (difficulty = 1)</li>
     *   <li>Zero nonce (no mining required)</li>
     *   <li>Coinbase set to provided key</li>
     *   <li>Specified timestamp (usually config genesis time)</li>
     * </ul>
     *
     * <p>This method is called when starting a new blockchain from scratch,
     * identified by absence of existing blocks.
     *
     * @param key ECKeyPair for coinbase address
     * @param timestamp genesis block timestamp (XDAG timestamp format)
     * @return genesis block
     * @see Block#createWithNonce(long, org.apache.tuweni.units.bigints.UInt256, Bytes32, Bytes32, java.util.List)
     */
    Block createGenesisBlock(ECKeyPair key, long timestamp);

    /**
     * Create a reward block for pool distribution
     *
     * <p>Creates a Block containing multiple Transaction references for distributing
     * mining rewards to pool participants (foundation, pool operator, miners).
     *
     * <p>Process:
     * <ol>
     *   <li>Creates Transaction objects for each recipient</li>
     *   <li>Signs each Transaction with the source key</li>
     *   <li>Saves Transactions to TransactionStore</li>
     *   <li>Creates Block with Link.toTransaction() references</li>
     * </ol>
     *
     * <p>The returned block must be imported via {@link #tryToConnect(Block)}
     * by the caller to finalize the reward distribution.
     *
     * @param sourceBlockHash hash of source block (where funds come from)
     * @param recipients list of recipient addresses
     * @param amounts list of amounts for each recipient (must match recipients size)
     * @param sourceKey ECKeyPair for signing transactions (source of funds)
     * @param nonce account nonce for first transaction
     * @param totalFee total transaction fee (distributed across transactions)
     * @return Block containing reward transactions
     * @see Transaction#createTransfer(Bytes32, Bytes32, XAmount, long, XAmount)
     * @see Link#toTransaction(Bytes32)
     */
    Block createRewardBlock(
            Bytes32 sourceBlockHash,
            List<Bytes32> recipients,
            List<XAmount> amounts,
            ECKeyPair sourceKey,
            long nonce,
            XAmount totalFee);

    /**
     * Get Block by its hash
     *
     * @param hash block hash (32 bytes)
     * @param isRaw whether to include raw block data (reserved for future use)
     * @return Block instance, or null if not found
     */
    Block getBlockByHash(Bytes32 hash, boolean isRaw);

    /**
     * Get Block by its height (main block number)
     *
     * <p>Only main blocks have heights. Orphan blocks will not be found by this method.
     *
     * @param height main block number (0-based)
     * @return Block instance, or null if not found
     */
    Block getBlockByHeight(long height);

    /**
     * Check and update the main chain
     *
     * <p>Scans the DAG to identify the best chain based on cumulative difficulty.
     * Updates main chain pointers if a better chain is found.
     * This is typically called periodically by a background thread.
     */
    void checkNewMain();

    /**
     * Get the latest main block number
     *
     * @return current height of the main chain (number of main blocks - 1)
     */
    long getLatestMainBlockNumber();

    /**
     * Get list of recent main blocks
     *
     * <p>Returns the most recent main blocks in descending order (newest first).
     *
     * @param count maximum number of main blocks to retrieve
     * @return list of Block instances (may be empty if no main blocks exist)
     */
    List<Block> listMainBlocks(int count);

    /**
     * Get list of blocks mined by this node
     *
     * <p>Returns blocks where the coinbase address belongs to this node's wallet.
     *
     * @param count maximum number of mined blocks to retrieve
     * @return list of Block instances (may be empty if none mined)
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

    /**
     * Get current blockchain statistics
     *
     * <p>Returns immutable snapshot of blockchain state including:
     * <ul>
     *   <li>Block counts (total, main, orphan, extra)</li>
     *   <li>Network state (hosts, max difficulty)</li>
     *   <li>Top block information (hash, difficulty)</li>
     * </ul>
     *
     * @return ChainStats containing current blockchain statistics
     */
    ChainStats getChainStats();

    /**
     * Increment waiting sync count
     *
     * <p>Increments the counter of blocks waiting for parent blocks during sync.
     * Used by SyncManager when adding blocks to the waiting queue.
     *
     * <p>This affects the value returned by {@link ChainStats#getWaitingSyncCount()}.
     */
    void incrementWaitingSyncCount();

    /**
     * Decrement waiting sync count
     *
     * <p>Decrements the counter of blocks waiting for parent blocks during sync.
     * Used by SyncManager when removing blocks from the waiting queue.
     *
     * <p>This affects the value returned by {@link ChainStats#getWaitingSyncCount()}.
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
     */
    void updateStatsFromRemote(ChainStats remoteStats);

    /**
     * Calculate block mining reward
     *
     * <p>Calculates the reward amount for a given main block based on
     * the reward schedule defined in the protocol specification.
     *
     * @param nmain main block number (height)
     * @return reward amount in XAmount
     */
    XAmount getReward(long nmain);

    /**
     * Calculate total XDAG supply
     *
     * <p>Calculates the cumulative XDAG supply at a given main block,
     * based on all rewards issued up to that block.
     *
     * @param nmain main block number (height)
     * @return total supply in XAmount
     */
    XAmount getSupply(long nmain);

    /**
     * Start main chain check thread
     *
     * <p>Starts a background thread that periodically calls {@link #checkNewMain()}
     * to identify and update the best chain.
     *
     * @param period check period in milliseconds
     */
    void startCheckMain(long period);

    /**
     * Stop main chain check thread
     *
     * <p>Stops the background thread started by {@link #startCheckMain(long)}.
     */
    void stopCheckMain();

    /**
     * Register blockchain event listener
     *
     * <p>Registers a listener to receive notifications about blockchain events
     * such as new blocks, chain reorganizations, etc.
     *
     * @param listener event listener to register
     */
    void registerListener(Listener listener);

}
