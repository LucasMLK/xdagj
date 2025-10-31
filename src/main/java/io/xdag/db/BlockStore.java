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

import io.xdag.core.XdagLifecycle;
import io.xdag.core.*;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;

import java.util.List;
import java.util.function.Function;

public interface BlockStore extends XdagLifecycle {

    byte SETTING_STATS = (byte) 0x10;
    byte TIME_HASH_INFO = (byte) 0x20;
    byte HASH_BLOCK_INFO = (byte) 0x30;

    /**
     * @deprecated Old sync protocol, will be removed in Phase 3
     */
    @Deprecated
    byte SUMS_BLOCK_INFO = (byte) 0x40;

    byte OURS_BLOCK_INFO = (byte) 0x50;
    byte SETTING_TOP_STATUS = (byte) 0x60;
    byte SNAPSHOT_BOOT = (byte) 0x70;
    byte BLOCK_HEIGHT = (byte) 0x80;
    byte SNAPSHOT_PRESEED = (byte) 0x90;
    byte TX_HISTORY = (byte) 0xa0;

    // ========== Phase 2 Core Refactor: New Index Prefixes ==========
    byte BLOCK_EPOCH_INDEX = (byte) 0xb0;      // epoch -> List<blockHash>
    byte MAIN_BLOCKS_INDEX = (byte) 0xc0;      // height -> blockHash (main blocks only)
    byte BLOCK_REFS_INDEX = (byte) 0xd0;       // blockHash -> List<referencingHashes>

    /**
     * @deprecated Old sync protocol file, will be removed in Phase 3
     */
    @Deprecated
    String SUM_FILE_NAME = "sums.dat";

    void reset();

    XdagStats getXdagStatus();

    void saveXdagTopStatus(XdagTopStatus status);

    XdagTopStatus getXdagTopStatus();

    void saveBlock(Block block);

    void saveBlockInfo(LegacyBlockInfo blockInfo);

    void saveOurBlock(int index, byte[] hash);

    void saveTxHistoryToRocksdb(TxHistory txHistory,int id);

    List<TxHistory> getAllTxHistoryFromRocksdb();

    void deleteAllTxHistoryFromRocksdb();

    boolean hasBlock(Bytes32 hash);

    boolean hasBlockInfo(Bytes32 hash);

    List<Block> getBlocksUsedTime(long startTime, long endTime);

    List<Block> getBlocksByTime(long startTime);

    Block getBlockByHeight(long height);

    Block getBlockByHash(Bytes32 hash, boolean isRaw);

    Block getBlockInfoByHash(Bytes32 hash);

    Block getRawBlockByHash(Bytes32 hash);

    Bytes getOurBlock(int index);

    int getKeyIndexByHash(Bytes32 hash);

    void removeOurBlock(byte[] hash);

    void fetchOurBlocks(Function<Pair<Integer, Block>, Boolean> function);

    // Snapshot Boot
    boolean isSnapshotBoot();

    void setSnapshotBoot();

    // RandomX seed
    void savePreSeed(byte[] preseed);

    byte[] getPreSeed();

    // ========== Phase 2 Core Refactor: DEPRECATED - To be removed ==========
    // These SUMS methods are part of the old sync protocol
    // They will be replaced by the new Hybrid Sync protocol in Phase 3

    /**
     * @deprecated Old sync protocol, use new Hybrid Sync instead
     */
    @Deprecated
    void saveBlockSums(Block block);

    /**
     * @deprecated Old sync protocol, use new Hybrid Sync instead
     */
    @Deprecated
    MutableBytes getSums(String key);

    /**
     * @deprecated Old sync protocol, use new Hybrid Sync instead
     */
    @Deprecated
    void putSums(String key, Bytes sums);

    /**
     * @deprecated Old sync protocol, use new Hybrid Sync instead
     */
    @Deprecated
    void updateSum(String key, long sum, long size, long index);

    /**
     * @deprecated Old sync protocol, use new Hybrid Sync instead
     */
    @Deprecated
    int loadSum(long starttime, long endtime, MutableBytes sums);

    void saveXdagStatus(XdagStats status);

    // ========== Phase 2 Core Refactor: New Methods ==========
    // These methods use the new BlockInfo (immutable, type-safe)

    /**
     * Save block info using new immutable BlockInfo
     * @param blockInfo The new BlockInfo (not LegacyBlockInfo)
     */
    void saveBlockInfoV2(BlockInfo blockInfo);

    /**
     * Get main blocks by height range (optimized for chain sync)
     * Only returns blocks with BI_MAIN flag set
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @return List of main blocks in the height range
     */
    List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight);

    /**
     * Get all blocks in a specific epoch (optimized for DAG sync)
     *
     * @param epoch The epoch number (timestamp / 64)
     * @return List of all blocks in this epoch
     */
    List<Block> getBlocksByEpoch(long epoch);

    /**
     * Get block references (for Solidification)
     * Returns all blocks that reference the given block
     *
     * @param blockHash The block hash to find references for
     * @return List of hashes of blocks that reference this block
     */
    List<Bytes32> getBlockReferences(Bytes32 blockHash);

    /**
     * Batch get blocks by hashes (performance optimization)
     *
     * @param hashes List of block hashes to retrieve
     * @return List of blocks (null entries for missing blocks)
     */
    List<Block> getBlocksByHashes(List<Bytes32> hashes);

    // ========== Phase 4: BlockV5 Storage Support ==========

    /**
     * Save BlockV5 to storage (Phase 4.1)
     *
     * Stores:
     * - Raw BlockV5 bytes (variable-length serialization)
     * - BlockInfo metadata (CompactSerializer)
     * - Time index for range queries
     * - Epoch/height indexes
     *
     * @param block BlockV5 to save
     */
    void saveBlockV5(BlockV5 block);

    /**
     * Get BlockV5 by hash (Phase 4.1)
     *
     * @param hash Block hash
     * @param isRaw true to load full raw data, false for BlockInfo only
     * @return BlockV5 or null if not found
     */
    BlockV5 getBlockV5ByHash(Bytes32 hash, boolean isRaw);

    /**
     * Get raw BlockV5 with full deserialized data (Phase 4.1)
     *
     * @param hash Block hash
     * @return BlockV5 with complete data or null if not found
     */
    BlockV5 getRawBlockV5ByHash(Bytes32 hash);

    /**
     * Get BlockV5 with BlockInfo metadata only (Phase 4.1)
     * Does not load or parse raw block data (faster)
     *
     * @param hash Block hash
     * @return BlockV5 with BlockInfo or null if not found
     */
    BlockV5 getBlockV5InfoByHash(Bytes32 hash);

}
