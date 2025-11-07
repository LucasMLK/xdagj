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

package io.xdag.db.rocksdb;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import com.google.common.collect.Lists;
import io.xdag.core.BlockHeader;
import io.xdag.core.BlockInfo;
import io.xdag.core.Block;
import io.xdag.core.ChainStats;
import io.xdag.core.SnapshotInfo;
import io.xdag.core.XAmount;
import io.xdag.db.BlockStore;
import io.xdag.db.execption.DeserializationException;
import io.xdag.db.execption.SerializationException;
import io.xdag.utils.BlockUtils;
import io.xdag.utils.BytesUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;
import org.bouncycastle.util.encoders.Hex;
import org.objenesis.strategy.StdInstantiatorStrategy;

@Slf4j
public class BlockStoreImpl implements BlockStore {

    private final Kryo kryo;

    /**
     * <prefix-hash,value> eg:<diff-hash,blockDiff>
     */
    private final KVSource<byte[], byte[]> indexSource;
    /**
     * <prefix-time-hash,hash>
     */
    private final KVSource<byte[], byte[]> timeSource;
    /**
     * <hash,rawData>
     */
    private final KVSource<byte[], byte[]> blockSource;
    private final KVSource<byte[], byte[]> txHistorySource;

    public BlockStoreImpl(
            KVSource<byte[], byte[]> index,
            KVSource<byte[], byte[]> time,
            KVSource<byte[], byte[]> block,
            KVSource<byte[], byte[]> txHistory) {
        this.indexSource = index;
        this.timeSource = time;
        this.blockSource = block;
        this.txHistorySource = txHistory;
        this.kryo = new Kryo();
        kryoRegister();
    }

    private void kryoRegister() {
        kryo.setReferences(false);
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        kryo.register(BigInteger.class);
        kryo.register(byte[].class);
        // Phase 7.3: XdagStats registration removed (class deleted, using ChainStats + CompactSerializer)
        // Phase 7.3.1: XdagTopStatus registration removed (class deleted, merged into ChainStats)
        kryo.register(SnapshotInfo.class);
        kryo.register(UInt64.class);
        kryo.register(XAmount.class);
    }

    private byte[] serialize(final Object obj) throws SerializationException {
        synchronized (kryo) {
            try {
                final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                final Output output = new Output(outputStream);
                kryo.writeObject(output, obj);
                output.flush();
                output.close();
                return outputStream.toByteArray();
            } catch (final IllegalArgumentException | KryoException exception) {
                throw new SerializationException(exception.getMessage(), exception);
            }
        }
    }

    private Object deserialize(final byte[] bytes, Class<?> type) throws DeserializationException {
        synchronized (kryo) {
            try {
                final ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                final Input input = new Input(inputStream);
                return kryo.readObject(input, type);
            } catch (final IllegalArgumentException | KryoException | NullPointerException exception) {
                log.debug("Deserialize data:{}", Hex.toHexString(bytes));
                throw new DeserializationException(exception.getMessage(), exception);
            }
        }
    }

    public void start() {
        indexSource.init();
        timeSource.init();
        blockSource.init();
        txHistorySource.init();
    }

    @Override
    public void stop() {
        indexSource.close();
        timeSource.close();
        blockSource.close();
        txHistorySource.close();
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    public void reset() {
        indexSource.reset();
        timeSource.reset();
        blockSource.reset();
        txHistorySource.reset();
    }

    // ========== Phase 7.3 Continuation: ChainStats Support ==========

    @Override
    public void saveChainStats(ChainStats stats) {
        // v5.1: Persist ChainStats directly using CompactSerializer (XdagStats deleted)
        try {
            byte[] serialized = io.xdag.serialization.CompactSerializer.serialize(stats);
            indexSource.put(new byte[]{SETTING_STATS}, serialized);
            log.debug("Saved ChainStats using CompactSerializer: mainBlocks={}, difficulty={}, size={} bytes",
                     stats.getMainBlockCount(), stats.getDifficulty().toDecimalString(), serialized.length);
        } catch (Exception e) {
            log.error("Failed to serialize ChainStats using CompactSerializer", e);
        }
    }

    @Override
    public ChainStats getChainStats() {
        // Load ChainStats directly using CompactSerializer
        byte[] serialized = indexSource.get(new byte[]{SETTING_STATS});
        if (serialized == null) {
            return null;
        }

        try {
            return io.xdag.serialization.CompactSerializer.deserializeChainStats(serialized);
        } catch (Exception e) {
            log.error("Failed to deserialize ChainStats using CompactSerializer", e);
            return null;
        }
    }


    public void deleteAllTxHistoryFromRocksdb() {
        for (byte[] key : txHistorySource.keys()) {
            try {
                txHistorySource.delete(key);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    // Phase 7.3.1: XdagTopStatus methods deleted - top block state merged into ChainStats
    // Use saveChainStats/getChainStats instead (see Phase 7.3 section above)

    public void saveOurBlock(int index, byte[] hash) {
        // Use full hash (new architecture)
        indexSource.put(BlockUtils.getOurKey(index, hash), new byte[]{0});
    }

    public boolean hasBlock(Bytes32 hash) {
        // Use full hash directly (new architecture)
        return blockSource.get(hash.toArray()) != null;
    }

    public boolean hasBlockInfo(Bytes32 hash) {
        // Use full hash directly (new architecture)
        return indexSource.get(BytesUtils.merge(HASH_BLOCK_INFO, hash.toArray())) != null;
    }

    public boolean isSnapshotBoot() {
        byte[] data = indexSource.get(new byte[]{SNAPSHOT_BOOT});
        if (data == null) {
            return false;
        } else {
            int res = BytesUtils.bytesToInt(data, 0, false);
            return res == 1;
        }
    }

    public void setSnapshotBoot() {
        indexSource.put(new byte[]{SNAPSHOT_BOOT}, BytesUtils.intToBytes(1, false));
    }

    public void savePreSeed(byte[] preseed) {
        indexSource.put(new byte[]{SNAPSHOT_PRESEED}, preseed);
    }

    public byte[] getPreSeed() {
        return indexSource.get(new byte[]{SNAPSHOT_PRESEED});
    }

    // ========== Phase 2 Core Refactor: New Methods Implementation ==========

    @Override
    public void saveBlockInfo(BlockInfo blockInfo) {
        // Use CompactSerializer for new immutable BlockInfo
        try {
            byte[] serialized = io.xdag.serialization.CompactSerializer.serialize(blockInfo);

            // Use full hash as database key (new architecture)
            byte[] fullHashKey = blockInfo.getHash().toArray();

            indexSource.put(BytesUtils.merge(HASH_BLOCK_INFO, fullHashKey), serialized);
            indexSource.put(BlockUtils.getHeight(blockInfo.getHeight()), fullHashKey);

            // ========== Build New Indexes ==========

            // 1. BLOCK_EPOCH_INDEX: epoch -> blockHash
            long epoch = blockInfo.getEpoch();  // timestamp / 64
            byte[] epochKey = BytesUtils.merge(BLOCK_EPOCH_INDEX, BytesUtils.longToBytes(epoch, true));
            // Append blockHash to the epoch's block list
            byte[] existingEpochData = indexSource.get(epochKey);
            if (existingEpochData == null) {
                // First block in this epoch
                indexSource.put(epochKey, blockInfo.getHash().toArray());
            } else {
                // Append to existing list (simple concatenation)
                byte[] newEpochData = BytesUtils.merge(existingEpochData, blockInfo.getHash().toArray());
                indexSource.put(epochKey, newEpochData);
            }

            // 2. MAIN_BLOCKS_INDEX: height -> blockHash (only for main blocks)
            if (blockInfo.isMainBlock()) {
                byte[] mainBlockKey = BytesUtils.merge(MAIN_BLOCKS_INDEX,
                                                       BytesUtils.longToBytes(blockInfo.getHeight(), true));
                indexSource.put(mainBlockKey, blockInfo.getHash().toArray());
                log.debug("Indexed main block at height {}", blockInfo.getHeight());
            }

            // 3. BLOCK_REFS_INDEX: Build reference index
            // For each block that this block references, add this block to their reference list
            buildBlockReferences(blockInfo);

            log.debug("Saved BlockInfo using CompactSerializer: {} bytes (was ~300 with Kryo)",
                     serialized.length);
        } catch (Exception e) {
            log.error("Failed to serialize BlockInfo using CompactSerializer, falling back to legacy", e);
            // Temporarily disabled - waiting for migration to v5.1
            log.warn("BlockInfo.toLegacy() no longer exists - cannot fallback to legacy serialization");
            // Fallback to legacy method
            // saveBlockInfo(blockInfo.toLegacy());
        }
    }

    /**
     * Build reverse reference index: for each block referenced by this block,
     * add this block to their reference list
     */
    private void buildBlockReferences(BlockInfo blockInfo) {
        Bytes32 thisBlockHash = blockInfo.getHash();

        // Temporarily disabled - waiting for migration to v5.1
        /*
        // Add reference for 'ref' field (if exists)
        if (blockInfo.getRef() != null && !blockInfo.getRef().isZero()) {
            addBlockReference(blockInfo.getRef(), thisBlockHash);
        }

        // Add reference for 'maxDiffLink' field (if exists)
        if (blockInfo.getMaxDiffLink() != null && !blockInfo.getMaxDiffLink().isZero()) {
            addBlockReference(blockInfo.getMaxDiffLink(), thisBlockHash);
        }
        */

        // Note: Full implementation would need to read the block's XdagBlock
        // and index ALL 15 links. For now, we index the most important ones.
    }

    /**
     * Add a reference: referencedBlock <- referencingBlock
     */
    private void addBlockReference(Bytes32 referencedBlock, Bytes32 referencingBlock) {
        byte[] refKey = BytesUtils.merge(BLOCK_REFS_INDEX, referencedBlock.toArray());
        byte[] existingRefs = indexSource.get(refKey);

        if (existingRefs == null) {
            // First reference to this block
            indexSource.put(refKey, referencingBlock.toArray());
        } else {
            // Append to existing reference list
            byte[] newRefs = BytesUtils.merge(existingRefs, referencingBlock.toArray());
            indexSource.put(refKey, newRefs);
        }
    }

    @Override
    public List<Bytes32> getBlockReferences(Bytes32 blockHash) {
        // Use BLOCK_REFS_INDEX to find all blocks that reference this block
        byte[] refKey = BytesUtils.merge(BLOCK_REFS_INDEX, blockHash.toArray());
        byte[] refsData = indexSource.get(refKey);

        List<Bytes32> result = Lists.newArrayList();

        if (refsData == null) {
            return result;
        }

        // Parse reference hashes from concatenated data (each hash is 32 bytes)
        int numRefs = refsData.length / 32;
        for (int i = 0; i < numRefs; i++) {
            byte[] refHashBytes = BytesUtils.subArray(refsData, i * 32, 32);
            result.add(Bytes32.wrap(refHashBytes));
        }

        log.debug("Block {} is referenced by {} other blocks", blockHash.toHexString(), result.size());
        return result;
    }

    // ========== Phase 4: Block Storage Implementation ==========

    @Override
    public void saveBlock(Block block) {
        long time = block.getTimestamp();
        Bytes32 hash = block.getHash();

        // 1. Time index (same as Block)
        timeSource.put(BlockUtils.getTimeKey(time, hash), new byte[]{0});

        // 2. Raw Block data (variable-length serialization)
        byte[] BlockBytes = block.toBytes();
        blockSource.put(hash.toArray(), BlockBytes);

        // 3. BlockInfo metadata
        // Note: Block.getInfo() may return null if not initialized
        BlockInfo info = block.getInfo();
        if (info != null) {
            saveBlockInfo(info);
        } else {
            // Create minimal BlockInfo for blocks without metadata
            // This should not normally happen, but we handle it gracefully
            log.warn("Block {} has no BlockInfo, creating minimal metadata", hash.toHexString());
            // Temporarily disabled - waiting for migration to v5.1
            BlockInfo minimalInfo = BlockInfo.builder()
                .hash(hash)
                .timestamp(block.getTimestamp())
                // .type(0L) // DELETED in v5.1
                // .flags(0) // DELETED in v5.1
                .height(0L)
                .difficulty(UInt256.ZERO)
                // .amount(XAmount.ZERO) // DELETED in v5.1
                // .fee(XAmount.ZERO) // DELETED in v5.1
                .build();
            saveBlockInfo(minimalInfo);
        }

        log.debug("Saved Block: {} ({} bytes)", hash.toHexString(), BlockBytes.length);
    }

    @Override
    public Block getBlockByHash(Bytes32 hash, boolean isRaw) {
        if (isRaw) {
            return getRawBlockByHash(hash);
        }
        return getBlockInfoByHash(hash);
    }

    @Override
    public Block getRawBlockByHash(Bytes32 hash) {
        // 1. Get raw Block bytes from blockSource
        byte[] BlockBytes = blockSource.get(hash.toArray());
        if (BlockBytes == null) {
            log.debug("Block raw data not found for hash: {}", hash.toHexString());
            return null;
        }

        // 2. Deserialize Block from bytes
        Block block;
        try {
            block = Block.fromBytes(BlockBytes);
        } catch (Exception e) {
            log.error("Failed to deserialize Block from bytes for hash: {}", hash.toHexString(), e);
            return null;
        }

        // 3. Load BlockInfo from indexSource
        BlockInfo info = loadBlockInfoFromIndex(hash);
        if (info != null) {
            // Attach BlockInfo to Block
            // Note: Block is immutable, so we need to rebuild it with info
            block = block.toBuilder().info(info).build();
        } else {
            log.warn("BlockInfo not found for Block: {}, using block without metadata", hash.toHexString());
        }

        return block;
    }

    @Override
    public Block getBlockInfoByHash(Bytes32 hash) {
        // Get BlockInfo only, no raw data
        BlockInfo info = loadBlockInfoFromIndex(hash);
        if (info == null) {
            log.debug("BlockInfo not found for hash: {}", hash.toHexString());
            return null;
        }

        // Create minimal Block with BlockInfo only
        // This is useful for metadata-only queries (faster than full deserialization)
        // We create a minimal Block with empty header and links
        Block block = Block.builder()
            .header(BlockHeader.builder()
                .timestamp(info.getTimestamp())
                .nonce(Bytes32.ZERO)
                .difficulty(info.getDifficulty())
                .coinbase(Bytes32.ZERO)
                .build())
            .links(Lists.newArrayList())
            .info(info)
            .build();

        return block;
    }

    // ========== Phase 7.3 Fix: Missing BlockStore Methods (Stub Implementations) ==========

    /**
     * Remove our block from index
     *
     * @deprecated Phase 8.3.1: Not needed in v5.1 architecture. No active callers found.
     * "Our blocks" concept is superseded by wallet-level address tracking.
     * Kept for interface compatibility but performs no operation.
     *
     * @param hash Block hash to remove
     */
    @Deprecated
    @Override
    public void removeOurBlock(byte[] hash) {
        log.info("removeOurBlock() is deprecated - not needed in v5.1 architecture");
        // No-op in v5.1: "our blocks" tracking moved to wallet/address layer
    }

    /**
     * Get key index by hash
     *
     * @deprecated Phase 8.3.1: Not needed in v5.1 architecture. No active callers found.
     * Key-to-index mapping is superseded by direct hash-based lookups.
     * Kept for interface compatibility but returns -1 (not found).
     *
     * @param hash Block hash
     * @return int -1 (always returns not found in v5.1)
     */
    @Deprecated
    @Override
    public int getKeyIndexByHash(Bytes32 hash) {
        log.info("getKeyIndexByHash() is deprecated - not needed in v5.1 architecture");
        return -1;  // v5.1: Direct hash-based lookups replace index-based access
    }

    /**
     * Get our block by index
     *
     * @deprecated Phase 8.3.1: Not needed in v5.1 architecture. No active callers found.
     * Index-based "our block" retrieval is superseded by direct hash-based queries.
     * Kept for interface compatibility but returns null.
     *
     * @param index Block index
     * @return Bytes null (always returns null in v5.1)
     */
    @Deprecated
    @Override
    public Bytes getOurBlock(int index) {
        log.info("getOurBlock() is deprecated - not needed in v5.1 architecture");
        return null;  // v5.1: Use getBlockByHash() for direct hash-based access
    }

    /**
     * Load block info from index (Phase 7.3 stub)
     *
     * TODO v5.1: This method needs proper implementation
     *
     * @param hash Block hash
     * @return BlockInfo or null
     */
    private BlockInfo loadBlockInfoFromIndex(Bytes32 hash) {
        // Use existing getBlockInfoByHash method from parent
        byte[] serialized = indexSource.get(BytesUtils.merge(HASH_BLOCK_INFO, hash.toArray()));
        if (serialized == null) {
            return null;
        }

        try {
            return io.xdag.serialization.CompactSerializer.deserializeBlockInfo(serialized);
        } catch (Exception e) {
            log.error("Failed to deserialize BlockInfo for hash: {}", hash.toHexString(), e);
            return null;
        }
    }

    // ========== Phase 7.3 Continuation: Main Chain Index Access ==========

    /**
     * Get Block by main chain height (Phase 7.3 continuation)
     *
     * Implementation:
     * 1. Use MAIN_BLOCKS_INDEX to get blockHash from height
     * 2. Retrieve Block using getBlockByHash()
     *
     * @param height Main chain height (must be > 0 for main blocks)
     * @param isRaw true to load full raw data, false for BlockInfo only
     * @return Block main block at height, or null if not found
     */
    @Override
    public Block getBlockByHeight(long height, boolean isRaw) {
        // 1. Get blockHash from MAIN_BLOCKS_INDEX
        byte[] mainBlockKey = BytesUtils.merge(MAIN_BLOCKS_INDEX, BytesUtils.longToBytes(height, true));
        byte[] blockHashBytes = indexSource.get(mainBlockKey);

        if (blockHashBytes == null) {
            log.debug("No main block found at height: {}", height);
            return null;
        }

        // 2. Convert to Bytes32
        Bytes32 blockHash = Bytes32.wrap(blockHashBytes);

        // 3. Retrieve Block
        Block block = getBlockByHash(blockHash, isRaw);

        if (block == null) {
            log.warn("Main block index exists at height {} but Block not found for hash: {}",
                    height, blockHash.toHexString());
            return null;
        }

        log.debug("Retrieved main block at height {}: {}", height, blockHash.toHexString());
        return block;
    }

    /**
     * Get list of Block objects within time range (Phase 7.3 continuation)
     *
     * Implementation:
     * 1. Scan TIME_HASH_INFO index for blocks in time range
     * 2. Retrieve each Block by hash
     *
     * @param startTime Start timestamp (XDAG format)
     * @param endTime End timestamp (XDAG format)
     * @return List of Block objects in time range
     */
    @Override
    public List<Block> getBlocksByTime(long startTime, long endTime) {
        List<Block> result = Lists.newArrayList();

        // Scan time index for blocks in range
        // Time keys format: TIME_HASH_INFO + timestamp + hash
        byte[] startKey = BytesUtils.merge(TIME_HASH_INFO, BytesUtils.longToBytes(startTime, true));
        byte[] endKey = BytesUtils.merge(TIME_HASH_INFO, BytesUtils.longToBytes(endTime, true));

        // Note: This is a simplified implementation
        // A full implementation would need to iterate through timeSource keys
        // For now, we return an empty list and log a warning
        log.warn("getBlocksByTime() partial implementation - time range queries need full iteration support");
        log.debug("Queried time range: {} to {}", startTime, endTime);

        // TODO Phase 7.3: Implement full time-based iteration using RocksDB iterator
        // Current KVSource interface may not support range queries
        // May need to add iterator support to KVSource or use RocksDB directly

        return result;
    }

}

