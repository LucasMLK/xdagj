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
     * Try to connect a new block to the blockchain (legacy v1.0 implementation).
     *
     * @deprecated As of v5.1 refactor, this method accepts legacy Block objects with Address-based
     *             references. After complete refactor and system restart with BlockV5-only storage,
     *             all block connection should use {@link #tryToConnect(BlockV5)}.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.4 (Current): Mark as @Deprecated</li>
     *               <li>Post-Restart: After fresh start, all blocks are BlockV5,
     *                   making this Block-based method obsolete</li>
     *               <li>Future: Remove this method entirely after system is stable with BlockV5-only</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             Use {@link #tryToConnect(BlockV5)} for connecting new blocks. After complete refactor,
     *             all block creation will produce BlockV5 objects, so this method will no longer be called.
     *
     *             <p><b>Impact:</b>
     *             This method is the main entry point for adding blocks to the blockchain. It validates
     *             block structure, checks parent blocks, handles fork resolution, and updates the main
     *             chain. Used by network layer, mining, and wallet operations.
     *
     * @param block The Block to connect (legacy Address-based structure)
     * @return ImportResult indicating success or failure
     * @see #tryToConnect(BlockV5)
     */
    @Deprecated(since = "0.8.1", forRemoval = true)
    ImportResult tryToConnect(Block block);

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
     * Create a new block (legacy v1.0 implementation).
     *
     * @deprecated As of v5.1 refactor, this method creates legacy Block objects with Address-based
     *             references. After complete refactor and system restart with BlockV5-only storage,
     *             all block creation should use BlockV5 with Transaction and Link structures.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.2 (Current): Mark as @Deprecated</li>
     *               <li>Phase 5.5 (Planned): Create BlockV5 creation methods</li>
     *               <li>Post-Restart: Remove this method entirely</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             For transaction blocks, use Transaction objects instead of Address-based blocks.
     *             For mining blocks, use createMainBlock() replacement (TBD in Phase 5.5).
     *
     * @param addressPairs Map of address hash to EC key pairs (for signing)
     * @param toAddresses List of recipient address hashes
     * @param mining Whether this is a mining block
     * @param remark Optional remark text
     * @param fee Transaction fee
     * @param txNonce Transaction nonce for replay protection
     * @return Created block
     *
     * @see io.xdag.core.BlockV5
     * @see io.xdag.core.Transaction
     * @see io.xdag.core.Link
     */
    @Deprecated(since = "0.8.1", forRemoval = true)
    Block createNewBlock(
            Map<Bytes32, ECKeyPair> addressPairs,
            List<Bytes32> toAddresses,
            boolean mining,
            String remark,
            XAmount fee,
            UInt64 txNonce);

    // Get block by its hash
    Block getBlockByHash(Bytes32 hash, boolean isRaw);

    // Get block by its height
    Block getBlockByHeight(long height);

    // Check and update main chain
    void checkNewMain();

    // Get the latest main block number
    long getLatestMainBlockNumber();

    // Get list of main blocks with specified count
    List<Block> listMainBlocks(int count);

    // Get list of mined blocks with specified count
    List<Block> listMinedBlocks(int count);

    // Get memory blocks created by current node
    Map<Bytes, Integer> getMemOurBlocks();

    // Get XDAG network statistics
    XdagStats getXdagStats();

    // Get XDAG top status
    XdagTopStatus getXdagTopStatus();

    // Calculate reward for given main block number
    XAmount getReward(long nmain);

    // Calculate total supply at given main block number
    XAmount getSupply(long nmain);

    // Get blocks within specified time range
    List<Block> getBlocksByTime(long starttime, long endtime);

    // Start main chain check thread with given period
    void startCheckMain(long period);

    // Stop main chain check thread
    void stopCheckMain();

    // Register blockchain event listener
    void registerListener(Listener listener);

    // Get transaction history for given address
    List<TxHistory> getBlockTxHistoryByAddress(Bytes32 addressHash, int page, Object... parameters);

    // Get extended XDAG network statistics
    XdagExtStats getXdagExtStats();
}
