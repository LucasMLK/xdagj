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
import io.xdag.core.Block;
import io.xdag.core.ChainStats;
import io.xdag.core.XdagLifecycle;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public interface BlockStore extends XdagLifecycle {

    byte SETTING_STATS = (byte) 0x10;
    byte TIME_HASH_INFO = (byte) 0x20;
    byte HASH_BLOCK_INFO = (byte) 0x30;

    byte OURS_BLOCK_INFO = (byte) 0x50;
    byte SETTING_TOP_STATUS = (byte) 0x60;
    byte SNAPSHOT_BOOT = (byte) 0x70;
    byte BLOCK_HEIGHT = (byte) 0x80;
    byte SNAPSHOT_PRESEED = (byte) 0x90;

  // ========== Phase 2 Core Refactor: New Index Prefixes ==========
    byte BLOCK_EPOCH_INDEX = (byte) 0xb0;      // epoch -> List<blockHash>
    byte MAIN_BLOCKS_INDEX = (byte) 0xc0;      // height -> blockHash (main blocks only)
    byte BLOCK_REFS_INDEX = (byte) 0xd0;       // blockHash -> List<referencingHashes>

    void reset();

    // Phase 7.3.1: XdagTopStatus deleted - top block state merged into ChainStats
    // Use saveChainStats/getChainStats instead (see Phase 7.3 section below)

    boolean hasBlock(Bytes32 hash);

    boolean hasBlockInfo(Bytes32 hash);

    Bytes getOurBlock(int index);

    int getKeyIndexByHash(Bytes32 hash);

    void removeOurBlock(byte[] hash);

    // Snapshot Boot
    boolean isSnapshotBoot();

    void setSnapshotBoot();

    // RandomX seed
    void savePreSeed(byte[] preseed);

    byte[] getPreSeed();

    // ========== Phase 7.3 Continuation: ChainStats Support ==========

    /**
     * Save chain statistics (v5.1 immutable design)
     *
     * @param stats ChainStats to save
     */
    void saveChainStats(ChainStats stats);

    /**
     * Get chain statistics (v5.1 immutable design)
     *
     * @return ChainStats or null if not found
     */
    ChainStats getChainStats();

    // ========== Phase 2 Core Refactor: New Methods ==========
    // These methods use the new BlockInfo (immutable, type-safe)

    /**
     * Save block info using new immutable BlockInfo
     * @param blockInfo The new BlockInfo (not LegacyBlockInfo)
     */
    void saveBlockInfoV2(BlockInfo blockInfo);
    /**
     * Get block references (for Solidification)
     * Returns all blocks that reference the given block
     *
     * @param blockHash The block hash to find references for
     * @return List of hashes of blocks that reference this block
     */
    List<Bytes32> getBlockReferences(Bytes32 blockHash);

    // ========== Phase 4: Block Storage Support ==========

    /**
     * Save Block to storage (Phase 4.1)
     * <p>
     * Stores:
     * - Raw Block bytes (variable-length serialization)
     * - BlockInfo metadata (CompactSerializer)
     * - Time index for range queries
     * - Epoch/height indexes
     *
     * @param block Block to save
     */
    void saveBlock(Block block);

    /**
     * Get Block by hash (Phase 4.1)
     *
     * @param hash Block hash
     * @param isRaw true to load full raw data, false for BlockInfo only
     * @return Block or null if not found
     */
    Block getBlockByHash(Bytes32 hash, boolean isRaw);

    /**
     * Get raw Block with full deserialized data (Phase 4.1)
     *
     * @param hash Block hash
     * @return Block with complete data or null if not found
     */
    Block getRawBlockByHash(Bytes32 hash);

    /**
     * Get Block with BlockInfo metadata only (Phase 4.1)
     * Does not load or parse raw block data (faster)
     *
     * @param hash Block hash
     * @return Block with BlockInfo or null if not found
     */
    Block getBlockInfoByHash(Bytes32 hash);

    // ========== Phase 7.3: Main Chain Index Access ==========

    /**
     * Get Block by main chain height (Phase 7.3)
     *
     * Uses MAIN_BLOCKS_INDEX (0xc0) to map height → blockHash,
     * then retrieves the Block object.
     *
     * @param height Main chain height (must be > 0)
     * @param isRaw true to load full raw data, false for BlockInfo only
     * @return Block main block at height, or null if not found
     */
    Block getBlockByHeight(long height, boolean isRaw);

    /**
     * Get list of Block objects within time range (Phase 7.3)
     *
     * Uses TIME_HASH_INFO index to find blocks in time range.
     *
     * @param startTime Start timestamp (XDAG format)
     * @param endTime End timestamp (XDAG format)
     * @return List of Block objects in time range
     */
    List<Block> getBlocksByTime(long startTime, long endTime);

}
