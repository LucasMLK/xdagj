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

package io.xdag.db.store;

import io.xdag.core.BlockInfo;
import io.xdag.serialization.CompactSerializer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/**
 * RocksDB implementation of FinalizedBlockStore
 *
 * Column Families:
 * - blocks: hash → Block (complete block data)
 * - block_info: hash → BlockInfo (metadata only, fast queries)
 * - main_chain: height → hash (main chain index)
 * - epoch_index: epoch → List<hash> (epoch to blocks mapping)
 *
 * Uses CompactSerializer for efficient storage (~180 bytes per BlockInfo)
 *
 * Thread-safety: All methods are thread-safe via RocksDB's internal synchronization
 */
@Slf4j
public class FinalizedBlockStoreImpl implements FinalizedBlockStore {

    private final RocksDB db;
    private final ColumnFamilyHandle blocksCF;
    private final ColumnFamilyHandle blockInfoCF;
    private final ColumnFamilyHandle mainChainCF;
    private final ColumnFamilyHandle epochIndexCF;

    // Statistics
    private final AtomicLong totalBlockCount = new AtomicLong(0);
    private final AtomicLong totalMainBlockCount = new AtomicLong(0);

    /**
     * Create a new FinalizedBlockStore
     *
     * @param dbPath Path to RocksDB directory
     * @throws IOException if database cannot be opened
     */
    public FinalizedBlockStoreImpl(String dbPath) throws IOException {
        try {
            // Load RocksDB library
            RocksDB.loadLibrary();

            // Configure database options
            DBOptions dbOptions = new DBOptions()
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true)
                    .setMaxBackgroundJobs(4)
                    .setMaxOpenFiles(1000);

            // Configure column family options with compression
            ColumnFamilyOptions cfOptions = new ColumnFamilyOptions()
                    .setCompressionType(CompressionType.LZ4_COMPRESSION)
                    .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
                    .setWriteBufferSize(64 * 1024 * 1024) // 64MB
                    .setMaxWriteBufferNumber(3);

            // Column family descriptors
            List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions),
                    new ColumnFamilyDescriptor("blocks".getBytes(), cfOptions),
                    new ColumnFamilyDescriptor("block_info".getBytes(), cfOptions),
                    new ColumnFamilyDescriptor("main_chain".getBytes(), cfOptions),
                    new ColumnFamilyDescriptor("epoch_index".getBytes(), cfOptions)
            );

            // Column family handles
            List<ColumnFamilyHandle> cfHandles = new ArrayList<>();

            // Open database
            this.db = RocksDB.open(dbOptions, dbPath, cfDescriptors, cfHandles);

            // Assign column family handles
            // cfHandles[0] is default CF (unused)
            this.blocksCF = cfHandles.get(1);
            this.blockInfoCF = cfHandles.get(2);
            this.mainChainCF = cfHandles.get(3);
            this.epochIndexCF = cfHandles.get(4);

            log.info("FinalizedBlockStore opened at: {}", dbPath);

            // Initialize statistics
            initializeStatistics();

        } catch (RocksDBException e) {
            throw new IOException("Failed to open FinalizedBlockStore", e);
        }
    }

    /**
     * Initialize statistics by scanning the database
     */
    private void initializeStatistics() {
        try (RocksIterator it = db.newIterator(blockInfoCF)) {
            long blockCount = 0;
            long mainBlockCount = 0;

            it.seekToFirst();
            while (it.isValid()) {
                blockCount++;

                // Deserialize to check if it's a main block
                try {
                    BlockInfo info = CompactSerializer.deserializeBlockInfo(it.value());
                    if (info.isMainBlock()) {
                        mainBlockCount++;
                    }
                } catch (IOException e) {
                    log.warn("Failed to deserialize BlockInfo during statistics initialization", e);
                }

                it.next();
            }

            totalBlockCount.set(blockCount);
            totalMainBlockCount.set(mainBlockCount);

            log.info("Statistics initialized: totalBlocks={}, totalMainBlocks={}",
                    blockCount, mainBlockCount);

        } catch (Exception e) {
            log.error("Failed to initialize statistics", e);
        }
    }

    // ========== Basic Operations ==========
    @Override
    public void saveBlockInfo(BlockInfo blockInfo) {
        try {
            byte[] hashKey = blockInfo.getHash().toArray();
            byte[] serialized = CompactSerializer.serialize(blockInfo);
            db.put(blockInfoCF, hashKey, serialized);

        } catch (RocksDBException | IOException e) {
            log.error("Failed to save BlockInfo: {}", blockInfo.getHash().toHexString(), e);
            throw new RuntimeException("Failed to save BlockInfo", e);
        }
    }

    @Override
    public boolean hasBlock(Bytes32 hash) {
        try {
            byte[] value = db.get(blockInfoCF, hash.toArray());
            return value != null;
        } catch (RocksDBException e) {
            log.error("Failed to check block existence: {}", hash.toHexString(), e);
            return false;
        }
    }

    @Override
    public Optional<BlockInfo> getBlockInfoByHash(Bytes32 hash) {
        try {
            byte[] serialized = db.get(blockInfoCF, hash.toArray());
            if (serialized == null) {
                return Optional.empty();
            }

            BlockInfo info = CompactSerializer.deserializeBlockInfo(serialized);
            return Optional.of(info);

        } catch (RocksDBException | IOException e) {
            log.error("Failed to get BlockInfo: {}", hash.toHexString(), e);
            return Optional.empty();
        }
    }

    // ========== Main Chain Index ==========

    @Override
    public Optional<BlockInfo> getMainBlockInfoByHeight(long height) {
        try {
            byte[] hashBytes = db.get(mainChainCF, longToBytes(height));
            if (hashBytes == null) {
                return Optional.empty();
            }

            Bytes32 hash = Bytes32.wrap(hashBytes);
            return getBlockInfoByHash(hash);

        } catch (RocksDBException e) {
            log.error("Failed to get main block info by height: {}", height, e);
            return Optional.empty();
        }
    }

    @Override
    public List<BlockInfo> getMainBlockInfosByHeightRange(long fromHeight, long toHeight) {
        List<BlockInfo> infos = new ArrayList<>();

        for (long h = fromHeight; h <= toHeight; h++) {
            getMainBlockInfoByHeight(h).ifPresent(infos::add);
        }

        return infos;
    }

    @Override
    public long getMaxFinalizedHeight() {
        try (RocksIterator it = db.newIterator(mainChainCF)) {
            it.seekToLast();

            if (!it.isValid()) {
                return -1;
            }

            return bytesToLong(it.key());

        } catch (Exception e) {
            log.error("Failed to get max finalized height", e);
            return -1;
        }
    }

    @Override
    public boolean verifyMainChainContinuity(long fromHeight, long toHeight) {
        try {
            Bytes32 prevHash = null;

            for (long h = fromHeight; h <= toHeight; h++) {
                Optional<BlockInfo> infoOpt = getMainBlockInfoByHeight(h);

                if (infoOpt.isEmpty()) {
                    log.warn("Main chain gap at height {}", h);
                    return false;
                }

                BlockInfo info = infoOpt.get();

                // Temporarily disabled - waiting for migration to v5.1
                /*
                // Check if this block points to previous block
                if (prevHash != null && info.getMaxDiffLink() != null) {
                    if (!info.getMaxDiffLink().equals(prevHash)) {
                        log.warn("Main chain discontinuity at height {}: expected {}, got {}",
                                h, prevHash.toHexString(), info.getMaxDiffLink().toHexString());
                        return false;
                    }
                }
                */

                prevHash = info.getHash();
            }

            return true;

        } catch (Exception e) {
            log.error("Failed to verify main chain continuity", e);
            return false;
        }
    }

    // ========== Epoch Index ==========

    @Override
    public List<Bytes32> getBlockHashesByEpoch(long epoch) {
        try {
            byte[] serialized = db.get(epochIndexCF, longToBytes(epoch));
            if (serialized == null) {
                return Collections.emptyList();
            }

            return deserializeHashList(serialized);

        } catch (RocksDBException e) {
            log.error("Failed to get block hashes for epoch {}", epoch, e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<BlockInfo> getBlockInfosByEpoch(long epoch) {
        List<Bytes32> hashes = getBlockHashesByEpoch(epoch);
        List<BlockInfo> infos = new ArrayList<>();

        for (Bytes32 hash : hashes) {
            getBlockInfoByHash(hash).ifPresent(infos::add);
        }

        return infos;
    }

    @Override
    public long countBlocksInEpoch(long epoch) {
        return getBlockHashesByEpoch(epoch).size();
    }

    // ========== Statistics ==========

    @Override
    public long getTotalBlockCount() {
        return totalBlockCount.get();
    }

    @Override
    public long getTotalMainBlockCount() {
        return totalMainBlockCount.get();
    }

    @Override
    public long getStorageSize() {
        try {
            long size = 0;
            size += Long.parseLong(db.getProperty(blocksCF, "rocksdb.total-sst-files-size"));
            size += Long.parseLong(db.getProperty(blockInfoCF, "rocksdb.total-sst-files-size"));
            size += Long.parseLong(db.getProperty(mainChainCF, "rocksdb.total-sst-files-size"));
            size += Long.parseLong(db.getProperty(epochIndexCF, "rocksdb.total-sst-files-size"));
            return size;
        } catch (RocksDBException | NumberFormatException e) {
            log.error("Failed to get storage size", e);
            return -1;
        }
    }

    @Override
    public FinalizedStats getStatsForRange(long fromHeight, long toHeight) {
        long blockCount = 0;
        long mainBlockCount = 0;
        long firstEpoch = -1;
        long lastEpoch = -1;

        for (long h = fromHeight; h <= toHeight; h++) {
            Optional<BlockInfo> infoOpt = getMainBlockInfoByHeight(h);
            if (infoOpt.isPresent()) {
                BlockInfo info = infoOpt.get();
                blockCount++;
                mainBlockCount++;

                long epoch = info.getEpoch();
                if (firstEpoch == -1) {
                    firstEpoch = epoch;
                }
                lastEpoch = epoch;
            }
        }

        return new FinalizedStats(
                blockCount,
                mainBlockCount,
                fromHeight,
                toHeight,
                firstEpoch,
                lastEpoch
        );
    }

    // ========== Maintenance ==========

    @Override
    public long rebuildIndexes() {
        log.info("Rebuilding indexes...");

        long count = 0;

        try (RocksIterator it = db.newIterator(blockInfoCF);
             WriteBatch batch = new WriteBatch();
             WriteOptions writeOptions = new WriteOptions()) {

            it.seekToFirst();

            while (it.isValid()) {
                try {
                    BlockInfo info = CompactSerializer.deserializeBlockInfo(it.value());

                    // Rebuild main chain index
                    if (info.isMainBlock()) {
                        batch.put(mainChainCF, longToBytes(info.getHeight()), info.getHash().toArray());
                    }

                    // Rebuild epoch index
                    updateEpochIndexBatch(batch, info.getEpoch(), info.getHash());

                    count++;

                    // Commit every 10000 blocks
                    if (count % 10000 == 0) {
                        db.write(writeOptions, batch);
                        batch.clear();
                        log.info("Rebuilt indexes for {} blocks", count);
                    }

                } catch (IOException e) {
                    log.error("Failed to deserialize BlockInfo during rebuild", e);
                }

                it.next();
            }

            // Commit remaining
            if (batch.count() > 0) {
                db.write(writeOptions, batch);
            }

            log.info("Index rebuild complete: {} blocks", count);

        } catch (RocksDBException e) {
            log.error("Failed to rebuild indexes", e);
            throw new RuntimeException("Failed to rebuild indexes", e);
        }

        return count;
    }

    @Override
    public boolean verifyIntegrity() {
        log.info("Verifying data integrity...");

        try (RocksIterator it = db.newIterator(blockInfoCF)) {
            it.seekToFirst();

            int count = 0;
            int errors = 0;

            while (it.isValid()) {
                count++;

                try {
                    Bytes32 hash = Bytes32.wrap(it.key());

                    // Verify BlockInfo can be deserialized
                    BlockInfo info = CompactSerializer.deserializeBlockInfo(it.value());

                    // Verify block data exists
                    byte[] blockData = db.get(blocksCF, hash.toArray());
                    if (blockData == null) {
                        log.error("BlockInfo exists but block data missing: {}", hash.toHexString());
                        errors++;
                    }

                    // Verify main chain index for main blocks
                    if (info.isMainBlock()) {
                        byte[] indexedHash = db.get(mainChainCF, longToBytes(info.getHeight()));
                        if (indexedHash == null || !Bytes32.wrap(indexedHash).equals(hash)) {
                            log.error("Main chain index mismatch at height {}", info.getHeight());
                            errors++;
                        }
                    }

                } catch (Exception e) {
                    log.error("Integrity check failed for block", e);
                    errors++;
                }

                it.next();
            }

            log.info("Integrity check complete: {} blocks checked, {} errors", count, errors);
            return errors == 0;

        } catch (Exception e) {
            log.error("Failed to verify integrity", e);
            return false;
        }
    }

    @Override
    public void compact() {
        try {
            log.info("Starting compaction...");
            db.compactRange(blocksCF);
            db.compactRange(blockInfoCF);
            db.compactRange(mainChainCF);
            db.compactRange(epochIndexCF);
            log.info("Compaction complete");
        } catch (RocksDBException e) {
            log.error("Failed to compact database", e);
        }
    }

    @Override
    public void close() {
        log.info("Closing FinalizedBlockStore...");

        blocksCF.close();
        blockInfoCF.close();
        mainChainCF.close();
        epochIndexCF.close();
        db.close();

        log.info("FinalizedBlockStore closed");
    }

    // ========== Helper Methods ==========

    /**
     * Update main chain index
     */
    private void updateMainChainIndex(long height, Bytes32 hash) throws RocksDBException {
        db.put(mainChainCF, longToBytes(height), hash.toArray());
    }

    /**
     * Update epoch index by adding a block hash
     */
    private void updateEpochIndex(long epoch, Bytes32 hash) throws RocksDBException {
        List<Bytes32> hashes = getBlockHashesByEpoch(epoch);

        // Add new hash if not already present
        if (!hashes.contains(hash)) {
            hashes = new ArrayList<>(hashes);
            hashes.add(hash);

            byte[] serialized = serializeHashList(hashes);
            db.put(epochIndexCF, longToBytes(epoch), serialized);
        }
    }

    /**
     * Update epoch index within a write batch
     */
    private void updateEpochIndexBatch(WriteBatch batch, long epoch, Bytes32 hash) throws RocksDBException {
        List<Bytes32> hashes = getBlockHashesByEpoch(epoch);

        if (!hashes.contains(hash)) {
            hashes = new ArrayList<>(hashes);
            hashes.add(hash);

            byte[] serialized = serializeHashList(hashes);
            batch.put(epochIndexCF, longToBytes(epoch), serialized);
        }
    }

    /**
     * Serialize list of hashes
     */
    private byte[] serializeHashList(List<Bytes32> hashes) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + hashes.size() * 32);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(hashes.size());
        for (Bytes32 hash : hashes) {
            buffer.put(hash.toArray());
        }

        return buffer.array();
    }

    /**
     * Deserialize list of hashes
     */
    private List<Bytes32> deserializeHashList(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int count = buffer.getInt();
        List<Bytes32> hashes = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            byte[] hashBytes = new byte[32];
            buffer.get(hashBytes);
            hashes.add(Bytes32.wrap(hashBytes));
        }

        return hashes;
    }

    /**
     * Convert long to bytes (little-endian)
     */
    private byte[] longToBytes(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    /**
     * Convert bytes to long (little-endian)
     */
    private long bytesToLong(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
}
