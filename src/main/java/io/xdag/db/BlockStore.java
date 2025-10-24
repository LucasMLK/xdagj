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
    byte SUMS_BLOCK_INFO = (byte) 0x40;
    byte OURS_BLOCK_INFO = (byte) 0x50;
    byte SETTING_TOP_STATUS = (byte) 0x60;
    byte SNAPSHOT_BOOT = (byte) 0x70;
    byte BLOCK_HEIGHT = (byte) 0x80;
    byte SNAPSHOT_PRESEED = (byte) 0x90;
    byte TX_HISTORY = (byte) 0xa0;
    String SUM_FILE_NAME = "sums.dat";

    void reset();

    XdagStats getXdagStatus();

    void saveXdagTopStatus(XdagTopStatus status);

    XdagTopStatus getXdagTopStatus();

    void saveBlock(Block block);

    void saveBlockInfo(LegacyBlockInfo blockInfo);

    void saveOurBlock(int index, byte[] hashlow);

    void saveTxHistoryToRocksdb(TxHistory txHistory,int id);

    List<TxHistory> getAllTxHistoryFromRocksdb();

    void deleteAllTxHistoryFromRocksdb();

    boolean hasBlock(Bytes32 hashlow);

    boolean hasBlockInfo(Bytes32 hashlow);

    List<Block> getBlocksUsedTime(long startTime, long endTime);

    List<Block> getBlocksByTime(long startTime);

    Block getBlockByHeight(long height);

    Block getBlockByHash(Bytes32 hashlow, boolean isRaw);

    Block getBlockInfoByHash(Bytes32 hashlow);

    Block getRawBlockByHash(Bytes32 hashlow);

    Bytes getOurBlock(int index);

    int getKeyIndexByHash(Bytes32 hashlow);

    void removeOurBlock(byte[] hashlow);

    void fetchOurBlocks(Function<Pair<Integer, Block>, Boolean> function);

    // Snapshot Boot
    boolean isSnapshotBoot();

    void setSnapshotBoot();

    // RandomX seed
    void savePreSeed(byte[] preseed);

    byte[] getPreSeed();

    // sums.dat and sum.dat
    void saveBlockSums(Block block);

    MutableBytes getSums(String key);

    void putSums(String key, Bytes sums);

    void updateSum(String key, long sum, long size, long index);

    int loadSum(long starttime, long endtime, MutableBytes sums);

    void saveXdagStatus(XdagStats status);

    // ========== Phase 2 Core Refactor: New Methods ==========
    // These methods use the new BlockInfo (immutable, type-safe)

    /**
     * Save block info using new immutable BlockInfo
     * @param blockInfo The new BlockInfo (not LegacyBlockInfo)
     * @deprecated Use this instead of saveBlockInfo(LegacyBlockInfo)
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

}
