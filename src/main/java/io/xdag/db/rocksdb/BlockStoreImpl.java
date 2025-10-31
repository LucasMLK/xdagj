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
import io.xdag.core.*;
import io.xdag.db.BlockStore;
import io.xdag.db.execption.DeserializationException;
import io.xdag.db.execption.SerializationException;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.BlockUtils;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.bouncycastle.util.encoders.Hex;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static io.xdag.utils.BytesUtils.equalBytes;

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
        kryo.register(LegacyBlockInfo.class);
        kryo.register(XdagStats.class);
        kryo.register(XdagTopStatus.class);
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

    public void saveXdagStatus(XdagStats status) {
        byte[] value = null;
        try {
            value = serialize(status);
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }
        indexSource.put(new byte[]{SETTING_STATS}, value);
    }

    @Override
    public void saveTxHistoryToRocksdb(TxHistory txHistory, int id) {
        byte[] remark = new byte[]{};
        if (txHistory.getRemark() != null) {
            remark = txHistory.getRemark().getBytes(StandardCharsets.UTF_8);
        }
        byte[] isWalletAddress = new byte[]{(byte) (txHistory.getAddress().getIsAddress() ? 1 : 0)};
        byte[] key = BytesUtils.merge(TX_HISTORY, BytesUtils.merge(txHistory.getAddress().getAddress().toArray(),
                BasicUtils.address2Hash(txHistory.getHash()).toArray(), BytesUtils.intToBytes(id, true)));
        // key: 0xa0 + address hash + txHash + id
        byte[] value;
        value = BytesUtils.merge(txHistory.getAddress().getType().asByte(), BytesUtils.merge(isWalletAddress,
                txHistory.getAddress().getAddress().toArray(),
                BasicUtils.address2Hash(txHistory.getHash()).toArray(),
                txHistory.getAddress().getAmount().toXAmount().toBytes().reverse().toArray(),
                BytesUtils.longToBytes(txHistory.getTimestamp(), true),
                BytesUtils.longToBytes(remark.length, true),
                remark));
        // value: type  +  isWalletAddress +address hash +txHash+ amount + timestamp + remark_length + remark
        txHistorySource.put(key, value);
        log.info("MySQL write exception, transaction history stored in Rocksdb. {}", txHistory);
    }

    public List<TxHistory> getAllTxHistoryFromRocksdb() {
        List<TxHistory> res = Lists.newArrayList();
        Set<byte[]> Keys = txHistorySource.keys();
        for (byte[] key : Keys) {
            byte[] txHistoryBytes = txHistorySource.get(key);
            byte type = BytesUtils.subArray(txHistoryBytes, 0, 1)[0];
            boolean isAddress = BytesUtils.subArray(txHistoryBytes, 1, 1)[0] == 1;
            XdagField.FieldType fieldType = XdagField.FieldType.fromByte(type);
            Bytes32 addressHash = Bytes32.wrap(BytesUtils.subArray(txHistoryBytes, 2, 32));
            Bytes32 txHash = Bytes32.wrap(BytesUtils.subArray(txHistoryBytes, 34, 32));
            String hash = BasicUtils.hash2Address(txHash);
            XAmount amount =
                    XAmount.ofXAmount(Bytes.wrap(BytesUtils.subArray(txHistoryBytes, 66, 8)).reverse().toLong());
            long timestamp = BytesUtils.bytesToLong(BytesUtils.subArray(txHistoryBytes, 74, 8), 0, true);
            Address address = new Address(addressHash, fieldType, amount, isAddress);
            long remarkLength = BytesUtils.bytesToLong(BytesUtils.subArray(txHistoryBytes, 82, 8), 0, true);
            String remark = null;
            if (remarkLength != 0) {
                remark = new String(BytesUtils.subArray(txHistoryBytes, 90, (int) remarkLength),
                        StandardCharsets.UTF_8).trim();
            }
            res.add(new TxHistory(address, hash, timestamp, remark));
        }
        return res;
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


    // 状态也是存在区块里面的
    public XdagStats getXdagStatus() {
        XdagStats status = null;
        byte[] value = indexSource.get(new byte[]{SETTING_STATS});
        if (value == null) {
            return null;
        }
        try {
            status = (XdagStats) deserialize(value, XdagStats.class);
        } catch (DeserializationException e) {
            log.error(e.getMessage(), e);
        }
        return status;
    }

    public void saveXdagTopStatus(XdagTopStatus status) {
        byte[] value = null;
        try {
            value = serialize(status);
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }
        indexSource.put(new byte[]{SETTING_TOP_STATUS}, value);
    }

    // pretop状态
    public XdagTopStatus getXdagTopStatus() {
        XdagTopStatus status = null;
        byte[] value = indexSource.get(new byte[]{SETTING_TOP_STATUS});
        if (value == null) {
            return null;
        }
        try {
            status = (XdagTopStatus) deserialize(value, XdagTopStatus.class);
        } catch (DeserializationException e) {
            log.error(e.getMessage(), e);
        }
        return status;
    }

    // 存储block的过程
    public void saveBlock(Block block) {
        long time = block.getTimestamp();

        // Use full hash for all database keys (new architecture)
        timeSource.put(BlockUtils.getTimeKey(time, block.getHash()), new byte[]{0});
        blockSource.put(block.getHash().toArray(), block.getXdagBlock().getData().toArray());
        saveBlockSums(block);

        // Use new V2 method instead of legacy saveBlockInfo
        saveBlockInfoV2(block.getInfo());
    }

    public void saveOurBlock(int index, byte[] hash) {
        // Use full hash (new architecture)
        indexSource.put(BlockUtils.getOurKey(index, hash), new byte[]{0});
    }

    public Bytes getOurBlock(int index) {
        AtomicReference<Bytes> blockHash = new AtomicReference<>(Bytes.of(0));
        fetchOurBlocks(pair -> {
            int keyIndex = pair.getKey();
            if (keyIndex == index) {
                if (pair.getValue() != null && pair.getValue().getHash() != null) {
                    blockHash.set(pair.getValue().getHash());
                    return Boolean.TRUE;
                } else {
                    return Boolean.FALSE;
                }
            }
            return Boolean.FALSE;
        });
        return blockHash.get();
    }

    public int getKeyIndexByHash(Bytes32 hash) {
        AtomicInteger keyIndex = new AtomicInteger(-1);
        fetchOurBlocks(pair -> {
            Block block = pair.getValue();
            if (hash.equals(block.getHash())) {
                int index = pair.getKey();
                keyIndex.set(index);
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
        return keyIndex.get();
    }

    public void removeOurBlock(byte[] hash) {
        // Use full hash (new architecture)
        fetchOurBlocks(pair -> {
            Block block = pair.getValue();
            if (equalBytes(hash, block.getHash().toArray())) {
                int index = pair.getKey();
                indexSource.delete(BlockUtils.getOurKey(index, hash));
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
    }

    public void fetchOurBlocks(Function<Pair<Integer, Block>, Boolean> function) {
        indexSource.fetchPrefix(new byte[]{OURS_BLOCK_INFO}, pair -> {
            int index = BlockUtils.getOurIndex(pair.getKey());
            assert BlockUtils.getOurHash(pair.getKey()) != null;
            Block block = getBlockInfoByHash(Bytes32.wrap(Objects.requireNonNull(BlockUtils.getOurHash(pair.getKey()))));
            if (function.apply(Pair.of(index, block))) {
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
    }

    // ========== Phase 2 Core Refactor: DEPRECATED SUMS Methods ==========
    // These methods are part of the old sync protocol
    // They will be replaced by the new Hybrid Sync protocol in Phase 3

    /**
     * @deprecated Old sync protocol, use new Hybrid Sync instead
     */
    @Override
    @Deprecated
    public void saveBlockSums(Block block) {
        long size = 512;
        long sum = block.getXdagBlock().getSum();
        long time = block.getTimestamp();
        List<String> filename = FileUtils.getFileName(time);
        for (int i = 0; i < filename.size(); i++) {
            updateSum(filename.get(i), sum, size, (time >> (40 - 8 * i)) & 0xff);
        }
    }

    /**
     * @deprecated Old sync protocol, use new Hybrid Sync instead
     */
    @Override
    @Deprecated
    public MutableBytes getSums(String key) {
        byte[] value = indexSource.get(BytesUtils.merge(SUMS_BLOCK_INFO, key.getBytes(StandardCharsets.UTF_8)));
        if (value == null) {
            return null;
        } else {
            MutableBytes sums = null;
            try {
                sums = MutableBytes.wrap((byte[]) deserialize(value, byte[].class));
            } catch (DeserializationException e) {
                log.error(e.getMessage(), e);
            }
            return sums;
        }
    }

    /**
     * @deprecated Old sync protocol, use new Hybrid Sync instead
     */
    @Override
    @Deprecated
    public void putSums(String key, Bytes sums) {
        byte[] value = null;
        try {
            value = serialize(sums.toArray());
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }
        indexSource.put(BytesUtils.merge(SUMS_BLOCK_INFO, key.getBytes(StandardCharsets.UTF_8)), value);
    }

    /**
     * @deprecated Old sync protocol, use new Hybrid Sync instead
     */
    @Override
    @Deprecated
    public void updateSum(String key, long sum, long size, long index) {
        MutableBytes sums = getSums(key);
        if (sums == null) {
//            sums = new byte[4096];
            sums = MutableBytes.create(4096);
//            System.arraycopy(BytesUtils.longToBytes(sum, true), 0, sums, (int) (16 * index), 8);
            sums.set((int) (16 * index), Bytes.wrap(BytesUtils.longToBytes(sum, true)));
//            System.arraycopy(BytesUtils.longToBytes(size, true), 0, sums, (int) (index * 16 + 8), 8);
            sums.set((int) (index * 16 + 8), Bytes.wrap(BytesUtils.longToBytes(size, true)));
            putSums(key, sums);
        } else {
            // size + sum
//            byte[] data = ArrayUtils.subarray(sums, 16 * (int)index, 16 * (int)index + 16);
            MutableBytes data = sums.slice(16 * (int) index, 16).mutableCopy();
//            sum += BytesUtils.bytesToLong(data, 0, true);
            sum += data.getLong(0, ByteOrder.LITTLE_ENDIAN);
//            size += BytesUtils.bytesToLong(data, 8, true);
            size += data.getLong(8, ByteOrder.LITTLE_ENDIAN);
//            System.arraycopy(BytesUtils.longToBytes(sum, true), 0, data, 0, 8);
            data.set(0, Bytes.wrap(BytesUtils.longToBytes(sum, true)));
//            System.arraycopy(BytesUtils.longToBytes(size, true), 0, data, 8, 8);
            data.set(8, Bytes.wrap(BytesUtils.longToBytes(size, true)));
//            System.arraycopy(data, 0, sums, 16 * (int)index, 16);
            sums.set(16 * (int) index, data.slice(0, 16));
            putSums(key, sums);
        }
    }

    /**
     * @deprecated Old sync protocol, use new Hybrid Sync instead
     */
    @Override
    @Deprecated
    public int loadSum(long starttime, long endtime, MutableBytes sums) {
        int level;
        String key;
        endtime -= starttime;

        if (endtime == 0 || (endtime & (endtime - 1)) != 0) {
            return -1;
        }
//        if (endtime == 0 || (endtime & (endtime - 1)) != 0 || (endtime & 0xFFFEEEEEEEEFFFFFL) != 0) return -1;

        for (level = -6; endtime != 0; level++, endtime >>= 4) {
        }

        List<String> files = FileUtils.getFileName((starttime) & 0xffffff000000L);

        if (level < 2) {
            key = files.get(3);
        } else if (level < 4) {
            key = files.get(2);
        } else if (level < 6) {
            key = files.get(1);
        } else {
            key = files.getFirst();
        }

        Bytes buf = getSums(key);
        if (buf == null) {
//            Arrays.fill(sums, (byte)0);
            sums.fill((byte) 0);
            return 1;
        }
        long size = 0;
        long sum = 0;
        if ((level & 1) != 0) {
//            Arrays.fill(sums, (byte)0);
            sums.fill((byte) 0);
            for (int i = 1; i <= 256; i++) {
//                long totalsum = BytesUtils.bytesToLong(buf, i * 16, true);
                long totalsum = buf.getLong((i-1) * 16, ByteOrder.LITTLE_ENDIAN);
                sum += totalsum;
//                long totalsize = BytesUtils.bytesToLong(buf, i * 16 + 8, true);
                long totalsize = buf.getLong((i-1) * 16 + 8, ByteOrder.LITTLE_ENDIAN);
                size += totalsize;
                if (i % 16 == 0) {
//                    System.arraycopy(BytesUtils.longToBytes(sum, true), 0, sums, i - 16, 8);
                    sums.set(i - 16, Bytes.wrap(BytesUtils.longToBytes(sum, true)));
//                    System.arraycopy(BytesUtils.longToBytes(size, true), 0, sums, i - 8, 8);
                    sums.set(i - 8, Bytes.wrap(BytesUtils.longToBytes(size, true)));
                    sum = 0;
                    size = 0;
                }
            }
        } else {
            long index = (starttime >> (level + 4) * 4) & 0xf0;
//            System.arraycopy(buf, (int) (index * 16), sums, 0, 16 * 16);
            sums.set(0, buf.slice((int) index * 16, 16 * 16));
        }
        return 1;
    }

    /**
     * @deprecated Use saveBlockInfoV2 instead. This is kept only for legacy data migration.
     */
    @Deprecated
    public void saveBlockInfo(LegacyBlockInfo blockInfo) {
        byte[] value = null;
        try {
            value = serialize(blockInfo);
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }

        // Convert legacy hash format to full hash for storage key
        // This method is deprecated and should not be used in new code
        Bytes hashData = Bytes.wrap(blockInfo.getHashlow()).slice(8, 24);
        MutableBytes32 fullHash = MutableBytes32.create();
        fullHash.set(0, hashData);

        indexSource.put(BytesUtils.merge(HASH_BLOCK_INFO, fullHash.toArray()), value);
        indexSource.put(BlockUtils.getHeight(blockInfo.getHeight()), fullHash.toArray());
    }

    public boolean hasBlock(Bytes32 hash) {
        // Use full hash directly (new architecture)
        return blockSource.get(hash.toArray()) != null;
    }

    public boolean hasBlockInfo(Bytes32 hash) {
        // Use full hash directly (new architecture)
        return indexSource.get(BytesUtils.merge(HASH_BLOCK_INFO, hash.toArray())) != null;
    }

    public List<Block> getBlocksUsedTime(long startTime, long endTime) {
        List<Block> res = Lists.newArrayList();
        long time = startTime;
        while (time < endTime) {
            List<Block> blocks = getBlocksByTime(time);
            time += 0x10000;
            if (CollectionUtils.isEmpty(blocks)) {
                continue;
            }
            res.addAll(blocks);
        }
        return res;
    }

    public List<Block> getBlocksByTime(long startTime) {
        List<Block> blocks = Lists.newArrayList();
        byte[] keyPrefix = BlockUtils.getTimeKey(startTime, null);
        List<byte[]> keys = timeSource.prefixKeyLookup(keyPrefix);
        for (byte[] bytes : keys) {
            // 1 + 8 : prefix + time
            byte[] hash = BytesUtils.subArray(bytes, 1 + 8, 32);
            Block block = getBlockByHash(Bytes32.wrap(hash), true);
            if (block != null) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    // ADD: 通过高度获取区块
    public Block getBlockByHeight(long height) {
        byte[] hash = indexSource.get(BlockUtils.getHeight(height));
        if (hash == null) {
            return null;
        }
        return getBlockByHash(Bytes32.wrap(hash), false);
    }

    public Block getBlockByHash(Bytes32 hash, boolean isRaw) {
        if (isRaw) {
            return getRawBlockByHash(hash);
        }
        return getBlockInfoByHash(hash);
    }

    public Block getRawBlockByHash(Bytes32 hash) {
        Block block = getBlockInfoByHash(hash);
        if (block == null) {
            return null;
        }

        // Save the BlockInfo from database before parsing
        BlockInfo savedInfo = block.getInfo();

        // Use full hash directly for blockSource lookup (new architecture)
        byte[] blockData = blockSource.get(hash.toArray());

        if (blockData == null) {
            return null;
        }

        block.setXdagBlock(new XdagBlock(blockData));
        block.setParsed(false);
        block.parse();

        // Restore the BlockInfo from database after parsing
        // This ensures that database-saved flags (like BI_MAIN_CHAIN) are preserved
        // even though parse() overwrites them with values from raw block data
        block.setInfo(savedInfo);

        return block;
    }

    public Block getBlockInfoByHash(Bytes32 hash) {
        // Use full hash directly for database lookup (new architecture)
        byte[] value = indexSource.get(BytesUtils.merge(HASH_BLOCK_INFO, hash.toArray()));

        if (value == null) {
            return null;
        }

        // Try CompactSerializer first (new format)
        // If it fails, fallback to Kryo (old format)
        try {
            BlockInfo blockInfo = io.xdag.serialization.CompactSerializer.deserializeBlockInfo(value);
            return new Block(blockInfo);
        } catch (Exception e) {
            // Fallback to Kryo deserialization (legacy format)
            try {
                LegacyBlockInfo blockInfo = (LegacyBlockInfo) deserialize(value, LegacyBlockInfo.class);
                return new Block(blockInfo);
            } catch (DeserializationException ex) {
                log.error("hash: {}", hash.toHexString());
                log.error("can't deserialize data with both CompactSerializer and Kryo: {}",
                         Hex.toHexString(value));
                log.error(ex.getMessage(), ex);
                return null;
            }
        }
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
    public void saveBlockInfoV2(BlockInfo blockInfo) {
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
            // Fallback to legacy method
            saveBlockInfo(blockInfo.toLegacy());
        }
    }

    /**
     * Build reverse reference index: for each block referenced by this block,
     * add this block to their reference list
     */
    private void buildBlockReferences(BlockInfo blockInfo) {
        Bytes32 thisBlockHash = blockInfo.getHash();

        // Add reference for 'ref' field (if exists)
        if (blockInfo.getRef() != null && !blockInfo.getRef().isZero()) {
            addBlockReference(blockInfo.getRef(), thisBlockHash);
        }

        // Add reference for 'maxDiffLink' field (if exists)
        if (blockInfo.getMaxDiffLink() != null && !blockInfo.getMaxDiffLink().isZero()) {
            addBlockReference(blockInfo.getMaxDiffLink(), thisBlockHash);
        }

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
    public List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight) {
        // Use MAIN_BLOCKS_INDEX for optimized query
        List<Block> result = Lists.newArrayList();

        for (long h = fromHeight; h <= toHeight; h++) {
            byte[] mainBlockKey = BytesUtils.merge(MAIN_BLOCKS_INDEX,
                                                   BytesUtils.longToBytes(h, true));
            byte[] hash = indexSource.get(mainBlockKey);

            if (hash != null) {
                Block block = getBlockByHash(Bytes32.wrap(hash), false);
                if (block != null) {
                    result.add(block);
                }
            }
        }

        log.debug("Retrieved {} main blocks from height {} to {}",
                 result.size(), fromHeight, toHeight);
        return result;
    }

    @Override
    public List<Block> getBlocksByEpoch(long epoch) {
        // Use BLOCK_EPOCH_INDEX for fast epoch-based query
        byte[] epochKey = BytesUtils.merge(BLOCK_EPOCH_INDEX, BytesUtils.longToBytes(epoch, true));
        byte[] epochData = indexSource.get(epochKey);

        List<Block> result = Lists.newArrayList();

        if (epochData == null) {
            log.debug("No blocks found in epoch {}", epoch);
            return result;
        }

        // Parse block hashes from concatenated data (each hash is 32 bytes)
        int numBlocks = epochData.length / 32;
        for (int i = 0; i < numBlocks; i++) {
            byte[] hashBytes = BytesUtils.subArray(epochData, i * 32, 32);
            Bytes32 hash = Bytes32.wrap(hashBytes);
            Block block = getBlockByHash(hash, false);
            if (block != null) {
                result.add(block);
            }
        }

        log.debug("Retrieved {} blocks from epoch {}", result.size(), epoch);
        return result;
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

    @Override
    public List<Block> getBlocksByHashes(List<Bytes32> hashes) {
        // Simple implementation: query each hash individually
        // TODO Phase 2 core: Optimize with batch query
        List<Block> result = Lists.newArrayList();
        for (Bytes32 hash : hashes) {
            Block block = getBlockByHash(hash, false);
            result.add(block); // null entries for missing blocks
        }
        return result;
    }

    // ========== Phase 4: BlockV5 Storage Implementation ==========

    @Override
    public void saveBlockV5(BlockV5 block) {
        long time = block.getTimestamp();
        Bytes32 hash = block.getHash();

        // 1. Time index (same as Block)
        timeSource.put(BlockUtils.getTimeKey(time, hash), new byte[]{0});

        // 2. Raw BlockV5 data (variable-length serialization)
        byte[] blockV5Bytes = block.toBytes();
        blockSource.put(hash.toArray(), blockV5Bytes);

        // 3. BlockInfo metadata
        // Note: BlockV5.getInfo() may return null if not initialized
        BlockInfo info = block.getInfo();
        if (info != null) {
            saveBlockInfoV2(info);
        } else {
            // Create minimal BlockInfo for blocks without metadata
            // This should not normally happen, but we handle it gracefully
            log.warn("BlockV5 {} has no BlockInfo, creating minimal metadata", hash.toHexString());
            BlockInfo minimalInfo = BlockInfo.builder()
                .hash(hash)
                .timestamp(block.getTimestamp())
                .type(0L)
                .flags(0)
                .height(0L)
                .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
                .amount(XAmount.ZERO)
                .fee(XAmount.ZERO)
                .build();
            saveBlockInfoV2(minimalInfo);
        }

        log.debug("Saved BlockV5: {} ({} bytes)", hash.toHexString(), blockV5Bytes.length);
    }

    @Override
    public BlockV5 getBlockV5ByHash(Bytes32 hash, boolean isRaw) {
        if (isRaw) {
            return getRawBlockV5ByHash(hash);
        }
        return getBlockV5InfoByHash(hash);
    }

    @Override
    public BlockV5 getRawBlockV5ByHash(Bytes32 hash) {
        // 1. Get raw BlockV5 bytes from blockSource
        byte[] blockV5Bytes = blockSource.get(hash.toArray());
        if (blockV5Bytes == null) {
            log.debug("BlockV5 raw data not found for hash: {}", hash.toHexString());
            return null;
        }

        // 2. Deserialize BlockV5 from bytes
        BlockV5 block;
        try {
            block = BlockV5.fromBytes(blockV5Bytes);
        } catch (Exception e) {
            log.error("Failed to deserialize BlockV5 from bytes for hash: {}", hash.toHexString(), e);
            return null;
        }

        // 3. Load BlockInfo from indexSource
        BlockInfo info = loadBlockInfoFromIndex(hash);
        if (info != null) {
            // Attach BlockInfo to BlockV5
            // Note: BlockV5 is immutable, so we need to rebuild it with info
            block = block.toBuilder().info(info).build();
        } else {
            log.warn("BlockInfo not found for BlockV5: {}, using block without metadata", hash.toHexString());
        }

        return block;
    }

    @Override
    public BlockV5 getBlockV5InfoByHash(Bytes32 hash) {
        // Get BlockInfo only, no raw data
        BlockInfo info = loadBlockInfoFromIndex(hash);
        if (info == null) {
            log.debug("BlockInfo not found for hash: {}", hash.toHexString());
            return null;
        }

        // Create minimal BlockV5 with BlockInfo only
        // This is useful for metadata-only queries (faster than full deserialization)
        // We create a minimal BlockV5 with empty header and links
        BlockV5 block = BlockV5.builder()
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

    /**
     * Helper method to load BlockInfo from indexSource
     * Tries CompactSerializer first, fallback to Kryo for legacy data
     */
    private BlockInfo loadBlockInfoFromIndex(Bytes32 hash) {
        byte[] value = indexSource.get(BytesUtils.merge(HASH_BLOCK_INFO, hash.toArray()));
        if (value == null) {
            return null;
        }

        // Try CompactSerializer first (new format)
        try {
            return io.xdag.serialization.CompactSerializer.deserializeBlockInfo(value);
        } catch (Exception e) {
            // Fallback to Kryo deserialization (legacy format)
            try {
                LegacyBlockInfo legacyInfo = (LegacyBlockInfo) deserialize(value, LegacyBlockInfo.class);
                return BlockInfo.fromLegacy(legacyInfo);
            } catch (DeserializationException ex) {
                log.error("Failed to deserialize BlockInfo for hash: {}", hash.toHexString(), ex);
                return null;
            }
        }
    }

}

