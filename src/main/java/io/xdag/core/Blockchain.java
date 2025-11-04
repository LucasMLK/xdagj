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
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import java.util.List;
import java.util.Map;

public interface Blockchain {

    // Get pre-seed for snapshot initialization
    byte[] getPreSeed();


    /**
     * Try to connect a new BlockV5 to the blockchain (Phase 4 Layer 3 Task 1.1)
     *
     * This method validates and imports a BlockV5 into the blockchain.
     * BlockV5 uses Link-based references instead of Address objects.
     *
     * @param block BlockV5 to connect
     * @return ImportResult indicating the result of the import operation
     *
     * @since Phase 4 v5.1
     */
    ImportResult tryToConnect(BlockV5 block);

    /**
     * Create a BlockV5 mining main block (v5.1 implementation)
     *
     * Phase 5.5: This is the NEW method for BlockV5 mining block creation.
     * Replaces the deprecated createNewBlock() method for mining use case.
     *
     * Key features:
     * 1. Uses Link.toBlock() instead of Address objects for block references
     * 2. Coinbase stored in BlockHeader (not as a link)
     * 3. Returns BlockV5 with Link-based DAG structure
     * 4. Uses current network difficulty from blockchain stats
     * 5. Creates candidate block (nonce = 0, ready for POW mining)
     *
     * Block structure:
     * - Header: timestamp, difficulty, nonce=0, coinbase
     * - Links: [pretop_block (if exists), orphan_blocks...]
     * - Max block links: 16 (from BlockV5.MAX_BLOCK_LINKS)
     *
     * @return BlockV5 candidate block for mining (nonce = 0, needs POW)
     * @see BlockV5#createCandidate(long, org.apache.tuweni.units.bigints.UInt256, Bytes32, List)
     * @see Link#toBlock(Bytes32)
     * @since Phase 5.5 v5.1
     */
    BlockV5 createMainBlockV5();

    /**
     * Create a genesis BlockV5 (v5.1 implementation)
     *
     * Phase 7.5: Genesis block creation for fresh node startup.
     * Called when xdagStats.getOurLastBlockHash() == null (first-time node initialization).
     *
     * Genesis block characteristics:
     * 1. Empty links list (no previous blocks to reference)
     * 2. Minimal difficulty (difficulty = 1)
     * 3. Zero nonce (no mining required for genesis)
     * 4. Coinbase set to wallet's default key
     * 5. Timestamp = current time or config genesis time
     *
     * @param key ECKeyPair for coinbase address
     * @param timestamp Genesis block timestamp
     * @return BlockV5 genesis block
     * @see BlockV5#createWithNonce(long, org.apache.tuweni.units.bigints.UInt256, Bytes32, Bytes32, List)
     * @since Phase 7.5 v5.1
     */
    BlockV5 createGenesisBlockV5(ECKeyPair key, long timestamp);

    /**
     * Create a reward BlockV5 for pool distribution (v5.1 implementation)
     *
     * Phase 7.6: Pool reward distribution using BlockV5 architecture.
     * Creates a BlockV5 containing Transaction references for reward distribution.
     *
     * This method:
     * 1. Creates Transaction objects for each recipient (foundation, pool)
     * 2. Signs each Transaction with the source key
     * 3. Saves Transactions to TransactionStore
     * 4. Creates BlockV5 with Link.toTransaction() references
     * 5. Returns BlockV5 (caller imports via tryToConnect)
     *
     * @param sourceBlockHash Hash of source block (where funds come from)
     * @param recipients List of recipient addresses
     * @param amounts List of amounts for each recipient (must match recipients size)
     * @param sourceKey ECKeyPair for signing transactions (source of funds)
     * @param nonce Account nonce for first transaction
     * @param totalFee Total transaction fee (distributed across transactions)
     * @return BlockV5 containing reward transactions
     * @see Transaction#createTransfer(Bytes32, Bytes32, XAmount, long, XAmount)
     * @see Link#toTransaction(Bytes32)
     * @since Phase 7.6 v5.1
     */
    BlockV5 createRewardBlockV5(
            Bytes32 sourceBlockHash,
            List<Bytes32> recipients,
            List<XAmount> amounts,
            ECKeyPair sourceKey,
            long nonce,
            XAmount totalFee);

    /**
     * Get BlockV5 by its hash (v5.1 unified interface - Phase 8.3.2)
     *
     * Phase 8.3.2: Blockchain interface migration to BlockV5.
     * This replaces the legacy Block getBlockByHash() method.
     *
     * @param hash Block hash
     * @param isRaw Whether to include raw block data
     * @return BlockV5 or null if not found
     * @since Phase 8.3.2 v5.1
     */
    BlockV5 getBlockByHash(Bytes32 hash, boolean isRaw);

    /**
     * Get BlockV5 by its height (v5.1 unified interface - Phase 8.3.2)
     *
     * Phase 8.3.2: Blockchain interface migration to BlockV5.
     * This replaces the legacy Block getBlockByHeight() method.
     *
     * @param height Block height (main block number)
     * @return BlockV5 or null if not found
     * @since Phase 8.3.2 v5.1
     */
    BlockV5 getBlockByHeight(long height);

    // Check and update main chain
    void checkNewMain();

    // Get the latest main block number
    long getLatestMainBlockNumber();

    /**
     * Get list of main BlockV5s with specified count (v5.1 unified interface - Phase 8.3.2)
     *
     * Phase 8.3.2: Blockchain interface migration to BlockV5.
     * This replaces the legacy List<Block> listMainBlocks() method.
     *
     * @param count Number of main blocks to retrieve
     * @return List of BlockV5 main blocks
     * @since Phase 8.3.2 v5.1
     */
    List<BlockV5> listMainBlocks(int count);

    /**
     * Get list of mined BlockV5s with specified count (v5.1 unified interface - Phase 8.3.2)
     *
     * Phase 8.3.2: Blockchain interface migration to BlockV5.
     * This replaces the legacy List<Block> listMinedBlocks() method.
     *
     * @param count Number of mined blocks to retrieve
     * @return List of BlockV5 mined blocks
     * @since Phase 8.3.2 v5.1
     */
    List<BlockV5> listMinedBlocks(int count);

    // Get memory blocks created by current node
    Map<Bytes, Integer> getMemOurBlocks();

    /**
     * Get blockchain statistics (v5.1 unified interface - Phase 7.3)
     *
     * Phase 7.3: XdagStats was refactored into immutable ChainStats.
     * ChainStats provides blockchain statistics without mutable state.
     *
     * @return ChainStats containing current blockchain statistics
     * @see ChainStats#toLegacy() for backward compatibility
     * @since Phase 7.3 v5.1
     */
    ChainStats getChainStats();

    // Get XDAG top status
    XdagTopStatus getXdagTopStatus();

    // Calculate reward for given main block number
    XAmount getReward(long nmain);

    // Calculate total supply at given main block number
    XAmount getSupply(long nmain);

    /**
     * Get BlockV5 objects within specified time range (v5.1 unified interface - Phase 8.3.2)
     *
     * Phase 8.3.2: Blockchain interface migration to BlockV5.
     * This replaces the legacy List<Block> getBlocksByTime() method.
     *
     * @param starttime Start time in XDAG timestamp format
     * @param endtime End time in XDAG timestamp format
     * @return List of BlockV5 objects in the time range
     * @since Phase 8.3.2 v5.1
     */
    List<BlockV5> getBlocksByTime(long starttime, long endtime);

    // Start main chain check thread with given period
    void startCheckMain(long period);

    // Stop main chain check thread
    void stopCheckMain();

    // Register blockchain event listener
    void registerListener(Listener listener);

    /**
     * Get extended blockchain statistics (hashrate tracking)
     *
     * Phase 7.3: XdagExtStats tracks hash rate history for network monitoring.
     * This provides detailed performance metrics beyond basic ChainStats.
     *
     * @return XdagExtStats containing hash rate history
     * @since Phase 7.3 v5.1
     */
    XdagExtStats getXdagExtStats();
}
