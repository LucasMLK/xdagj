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

import static io.xdag.db.DagStore.*;

import io.xdag.config.Config;
import io.xdag.core.Block;
import io.xdag.core.BlockHeader;
import io.xdag.core.BlockInfo;
import io.xdag.core.ChainStats;
import io.xdag.db.DagStore;
import io.xdag.db.store.DagCache;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.rocksdb.*;

/**
 * DagStoreImpl - RocksDB implementation of DagStore
 *
 * <p>This class provides persistent storage for XDAG DAG entities with
 * three-tier caching architecture:
 * <ul>
 *   <li><strong>L1 Cache</strong>: DagCache (Caffeine) - 13.8 MB</li>
 *   <li><strong>L2 Cache</strong>: RocksDB Block Cache - 2-4 GB</li>
 *   <li><strong>L3 Storage</strong>: SSD - 50-85 GB (10 years)</li>
 * </ul>
 *
 * <h2>Storage Layout</h2>
 * <pre>
 * 0xa0: Block Data        - hash → Block raw data
 * 0xa1: BlockInfo         - hash → BlockInfo metadata
 * 0xa2: ChainStats        - single entry
 * 0xb0: Time Index        - timestamp + hash → empty (range scan)
 * 0xb1: Epoch Index       - epoch + hash → empty (range scan)
 * 0xb2: Height Index      - height → hash
 * 0xb3: Block Refs        - hash → List&lt;refHash&gt;
 * </pre>
 *
 * @since v5.1 Phase 8
 */
@Slf4j
public class DagStoreImpl implements DagStore {

    private static final byte[] EMPTY_VALUE = new byte[0];
    private static final String DB_NAME = "dagstore";

    private final Config config;
    private final File dbDir;

    private RocksDB db;
    private DBOptions dbOptions;
    private ColumnFamilyOptions cfOptions;
    private WriteOptions writeOptions;
    private WriteOptions syncWriteOptions;
    private ReadOptions readOptions;
    private ReadOptions scanReadOptions;

    private final DagCache cache;

    private volatile boolean closed = false;

    // ==================== Constructor & Lifecycle ====================

    public DagStoreImpl(Config config) {
        this.config = config;
        this.dbDir = new File(config.getNodeSpec().getStoreDir(), DB_NAME);
        this.cache = new DagCache();

        log.info("Initializing DagStore at: {}", dbDir.getAbsolutePath());
    }

    @Override
    public void start() {
        if (!dbDir.exists()) {
            if (!dbDir.mkdirs()) {
                throw new RuntimeException("Failed to create DagStore directory: " + dbDir);
            }
        }

        try {
            // Load RocksDB library
            RocksDB.loadLibrary();

            // Create options
            dbOptions = DagStoreRocksDBConfig.createDBOptions();
            cfOptions = DagStoreRocksDBConfig.createColumnFamilyOptions();
            writeOptions = DagStoreRocksDBConfig.createWriteOptions();
            syncWriteOptions = DagStoreRocksDBConfig.createSyncWriteOptions();
            readOptions = DagStoreRocksDBConfig.createReadOptions();
            scanReadOptions = DagStoreRocksDBConfig.createScanReadOptions();

            // Log configuration
            DagStoreRocksDBConfig.logConfiguration();

            // Open database with column families
            List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
            cfDescriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions));

            List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
            db = RocksDB.open(dbOptions, dbDir.getAbsolutePath(), cfDescriptors, cfHandles);

            log.info("DagStore started successfully");

        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to start DagStore", e);
        }
    }

    @Override
    public void stop() {
        if (closed) {
            return;
        }

        log.info("Stopping DagStore...");

        try {
            // Flush and close database
            if (db != null) {
                db.syncWal();
                db.close();
            }

            // Close options
            DagStoreRocksDBConfig.cleanup(dbOptions, cfOptions, writeOptions, readOptions);
            if (syncWriteOptions != null) {
                syncWriteOptions.close();
            }
            if (scanReadOptions != null) {
                scanReadOptions.close();
            }

            closed = true;
            log.info("DagStore stopped");

        } catch (RocksDBException e) {
            log.error("Error stopping DagStore", e);
        }
    }

    @Override
    public boolean isRunning() {
        return !closed && db != null;
    }

    // ==================== Block Operations ====================

    @Override
    public void saveBlock(Block block) {
        if (block == null || block.getInfo() == null) {
            throw new IllegalArgumentException("Block or BlockInfo is null");
        }

        WriteBatch batch = new WriteBatch();
        try {
            Bytes32 hash = block.getHash();
            BlockInfo info = block.getInfo();

            // 1. Save block data
            byte[] blockKey = buildBlockKey(hash);
            byte[] blockData = serializeBlock(block);
            batch.put(blockKey, blockData);

            // 2. Save BlockInfo
            byte[] infoKey = buildBlockInfoKey(hash);
            byte[] infoData = serializeBlockInfo(info);
            batch.put(infoKey, infoData);

            // 3. Index by time
            byte[] timeKey = buildTimeIndexKey(block.getTimestamp(), hash);
            batch.put(timeKey, EMPTY_VALUE);

            // 4. Index by epoch
            byte[] epochKey = buildEpochIndexKey(block.getEpoch(), hash);
            batch.put(epochKey, EMPTY_VALUE);

            // 5. Index by height (if main block)
            if (info.getHeight() > 0) {
                byte[] heightKey = buildHeightIndexKey(info.getHeight());
                batch.put(heightKey, hash.toArray());
            }

            // 6. Write batch atomically
            db.write(writeOptions, batch);

            // 7. Update L1 cache
            cache.putBlock(hash, block);
            cache.putBlockInfo(hash, info);
            if (info.getHeight() > 0) {
                cache.putHashByHeight(info.getHeight(), hash);
            }

            log.debug("Saved block: {} (height={})", hash.toHexString(), info.getHeight());

        } catch (RocksDBException e) {
            log.error("Failed to save block: {}", block.getHash().toHexString(), e);
            throw new RuntimeException("Failed to save block", e);
        } finally {
            batch.close();
        }
    }

    @Override
    public Block getBlockByHash(Bytes32 hash, boolean isRaw) {
        if (hash == null) {
            return null;
        }

        // L1 Cache check
        Block cached = cache.getBlock(hash);
        if (cached != null) {
            return cached;
        }

        // L2 + Disk read
        try {
            if (isRaw) {
                // Load full block data
                byte[] blockKey = buildBlockKey(hash);
                byte[] blockData = db.get(readOptions, blockKey);
                if (blockData == null) {
                    return null;
                }

                Block block = deserializeBlock(blockData);

                // Also load BlockInfo
                BlockInfo info = getBlockInfo(hash);
                if (info != null) {
                    block = block.toBuilder().info(info).build();
                }

                // Update cache
                cache.putBlock(hash, block);
                return block;

            } else {
                // Load BlockInfo only
                BlockInfo info = getBlockInfo(hash);
                if (info == null) {
                    return null;
                }

                // Create lightweight Block with info only
                // Note: Block doesn't have hash in builder, only in built instance
                Block block = Block.builder()
                        .header(BlockHeader.builder()
                                .timestamp(info.getTimestamp())
                                .difficulty(info.getDifficulty())
                                .nonce(Bytes32.ZERO)
                                .coinbase(Bytes32.ZERO)
                                .build())
                        .links(new ArrayList<>())
                        .info(info)
                        .build();

                return block;
            }

        } catch (RocksDBException e) {
            log.error("Failed to get block: {}", hash.toHexString(), e);
            return null;
        }
    }

    @Override
    public boolean hasBlock(Bytes32 hash) {
        if (hash == null) {
            return false;
        }

        // L1 Cache check
        if (cache.getBlock(hash) != null || cache.getBlockInfo(hash) != null) {
            return true;
        }

        // L2 + Bloom filter check
        try {
            byte[] infoKey = buildBlockInfoKey(hash);
            byte[] data = db.get(readOptions, infoKey);
            return data != null;
        } catch (RocksDBException e) {
            log.error("Failed to check block existence: {}", hash.toHexString(), e);
            return false;
        }
    }

    @Override
    public void deleteBlock(Bytes32 hash) {
        if (hash == null) {
            return;
        }

        WriteBatch batch = new WriteBatch();
        try {
            // Delete block data
            byte[] blockKey = buildBlockKey(hash);
            batch.delete(blockKey);

            // Delete BlockInfo
            byte[] infoKey = buildBlockInfoKey(hash);
            batch.delete(infoKey);

            // Note: Indexes are not deleted for performance
            // They will be cleaned up during compaction

            db.write(writeOptions, batch);

            // Invalidate cache
            cache.invalidateBlock(hash);
            cache.invalidateBlockInfo(hash);

            log.debug("Deleted block: {}", hash.toHexString());

        } catch (RocksDBException e) {
            log.error("Failed to delete block: {}", hash.toHexString(), e);
            throw new RuntimeException("Failed to delete block", e);
        } finally {
            batch.close();
        }
    }

    // ==================== Main Chain Queries ====================

    @Override
    public Block getMainBlockAtPosition(long position, boolean isRaw) {
        if (position <= 0) {
            return null;
        }

        // L1 Cache check for height-to-hash mapping
        Bytes32 hash = cache.getHashByHeight(position);
        if (hash != null) {
            return getBlockByHash(hash, isRaw);
        }

        // L2 + Disk read
        try {
            byte[] heightKey = buildHeightIndexKey(position);
            byte[] hashData = db.get(readOptions, heightKey);
            if (hashData == null || hashData.length != 32) {
                return null;
            }

            hash = Bytes32.wrap(hashData);

            // Update cache
            cache.putHashByHeight(position, hash);

            return getBlockByHash(hash, isRaw);

        } catch (RocksDBException e) {
            log.error("Failed to get main block at position {}", position, e);
            return null;
        }
    }

    @Override
    public long getMainChainLength() {
        ChainStats stats = getChainStats();
        return stats != null ? stats.getMainBlockCount() : 0;
    }

    @Override
    public List<Block> listMainBlocks(int count) {
        List<Block> blocks = new ArrayList<>();
        long length = getMainChainLength();
        long start = Math.max(1, length - count + 1);

        for (long pos = length; pos >= start; pos--) {
            Block block = getMainBlockAtPosition(pos, false);
            if (block != null) {
                blocks.add(block);
            }
        }

        return blocks;
    }

    @Override
    public List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight, boolean isRaw) {
        List<Block> blocks = new ArrayList<>();

        // Validation
        if (fromHeight < 1 || toHeight < fromHeight) {
            log.warn("Invalid height range: [{}, {}]", fromHeight, toHeight);
            return blocks;
        }

        // Limit batch size to prevent memory issues
        long batchSize = toHeight - fromHeight + 1;
        if (batchSize > 10000) {
            log.warn("Batch size too large: {}, limiting to 10000", batchSize);
            toHeight = fromHeight + 9999;
        }

        log.debug("Fetching main blocks: height [{}, {}], raw={}", fromHeight, toHeight, isRaw);

        try {
            // Phase 1: Batch load height-to-hash mappings
            List<Bytes32> hashes = new ArrayList<>();
            List<byte[]> keys = new ArrayList<>();

            for (long h = fromHeight; h <= toHeight; h++) {
                // Check L1 cache first
                Bytes32 cachedHash = cache.getHashByHeight(h);
                if (cachedHash != null) {
                    hashes.add(cachedHash);
                } else {
                    keys.add(buildHeightIndexKey(h));
                    hashes.add(null);  // Placeholder
                }
            }

            // Batch read missing height indices from RocksDB
            if (!keys.isEmpty()) {
                try {
                    List<byte[]> values = db.multiGetAsList(readOptions, keys);
                    int keyIndex = 0;

                    for (int i = 0; i < hashes.size(); i++) {
                        if (hashes.get(i) == null) {
                            byte[] hashData = values.get(keyIndex++);
                            if (hashData != null && hashData.length == 32) {
                                Bytes32 hash = Bytes32.wrap(hashData);
                                hashes.set(i, hash);

                                // Update L1 cache
                                cache.putHashByHeight(fromHeight + i, hash);
                            }
                        }
                    }
                } catch (RocksDBException e) {
                    log.error("Failed to batch read height indices", e);
                    return blocks;
                }
            }

            // Phase 2: Batch load blocks
            if (isRaw) {
                // Load full block data
                for (Bytes32 hash : hashes) {
                    if (hash != null) {
                        Block block = getBlockByHash(hash, true);
                        blocks.add(block);
                    } else {
                        blocks.add(null);  // Missing block
                    }
                }
            } else {
                // Load BlockInfo only (faster)
                for (Bytes32 hash : hashes) {
                    if (hash != null) {
                        Block block = getBlockByHash(hash, false);
                        blocks.add(block);
                    } else {
                        blocks.add(null);  // Missing block
                    }
                }
            }

            log.debug("Fetched {} main blocks (isRaw={})", blocks.size(), isRaw);
            return blocks;

        } catch (Exception e) {
            log.error("Failed to get main blocks by height range", e);
            return blocks;
        }
    }

    @Override
    public boolean verifyMainChainContinuity(long fromHeight, long toHeight) {
        if (fromHeight < 1 || toHeight < fromHeight) {
            return false;
        }

        try {
            // Load blocks with BlockInfo only (faster)
            List<Block> blocks = getMainBlocksByHeightRange(fromHeight, toHeight, false);

            // Check all blocks exist
            for (Block block : blocks) {
                if (block == null) {
                    log.debug("Main chain gap detected in range [{}, {}]", fromHeight, toHeight);
                    return false;
                }
            }

            // Verify each block's maxDiffLink points to previous block
            Block prevBlock = null;
            for (Block block : blocks) {
                if (prevBlock != null) {
                    final Block previousBlock = prevBlock;  // Create final copy for lambda
                    // Check if current block references previous block
                    boolean references = block.getLinks().stream()
                            .anyMatch(link -> link.isBlock() && link.getTargetHash().equals(previousBlock.getHash()));

                    if (!references) {
                        log.debug("Main chain discontinuity: block {} doesn't reference {}",
                                 block.getHash().toHexString(), previousBlock.getHash().toHexString());
                        return false;
                    }
                }
                prevBlock = block;
            }

            log.debug("Main chain verified: height [{}, {}] is continuous", fromHeight, toHeight);
            return true;

        } catch (Exception e) {
            log.error("Failed to verify main chain continuity", e);
            return false;
        }
    }

    // ==================== Epoch Queries ====================

    @Override
    public List<Block> getCandidateBlocksInEpoch(long epoch) {
        List<Block> candidates = new ArrayList<>();

        try {
            // Build epoch prefix for range scan
            byte[] startKey = buildEpochIndexKey(epoch, Bytes32.ZERO);
            byte[] endKey = buildEpochIndexKey(epoch + 1, Bytes32.ZERO);

            // Range scan
            try (RocksIterator iterator = db.newIterator(scanReadOptions)) {
                iterator.seek(startKey);

                while (iterator.isValid()) {
                    byte[] key = iterator.key();

                    // Check if still in epoch range
                    if (ByteBuffer.wrap(key).compareTo(ByteBuffer.wrap(endKey)) >= 0) {
                        break;
                    }

                    // Extract block hash from key
                    if (key.length == 41) {  // 1 + 8 + 32
                        byte[] hashBytes = new byte[32];
                        System.arraycopy(key, 9, hashBytes, 0, 32);
                        Bytes32 hash = Bytes32.wrap(hashBytes);

                        Block block = getBlockByHash(hash, false);
                        if (block != null) {
                            candidates.add(block);
                        }
                    }

                    iterator.next();
                }
            }

        } catch (Exception e) {
            log.error("Failed to get candidate blocks in epoch {}", epoch, e);
        }

        return candidates;
    }

    @Override
    public Block getWinnerBlockInEpoch(long epoch) {
        // Check cache first
        Bytes32 winnerHash = cache.getEpochWinner(epoch);
        if (winnerHash != null) {
            return getBlockByHash(winnerHash, false);
        }

        // Get all candidates
        List<Block> candidates = getCandidateBlocksInEpoch(epoch);
        if (candidates.isEmpty()) {
            return null;
        }

        // Find winner: smallest hash among main blocks
        Block winner = null;
        Bytes32 smallestHash = null;

        for (Block candidate : candidates) {
            if (candidate.getInfo() != null && candidate.getInfo().getHeight() > 0) {
                if (smallestHash == null || candidate.getHash().compareTo(smallestHash) < 0) {
                    smallestHash = candidate.getHash();
                    winner = candidate;
                }
            }
        }

        // Cache winner
        if (winner != null) {
            cache.putEpochWinner(epoch, winner.getHash());
        }

        return winner;
    }

    @Override
    public long getPositionOfWinnerBlock(long epoch) {
        Block winner = getWinnerBlockInEpoch(epoch);
        if (winner == null || winner.getInfo() == null) {
            return -1;
        }
        return winner.getInfo().getHeight();
    }

    @Override
    public List<Block> getBlocksByTimeRange(long startTime, long endTime) {
        List<Block> blocks = new ArrayList<>();

        try {
            byte[] startKey = buildTimeIndexKey(startTime, Bytes32.ZERO);
            byte[] endKey = buildTimeIndexKey(endTime, Bytes32.ZERO);

            try (RocksIterator iterator = db.newIterator(scanReadOptions)) {
                iterator.seek(startKey);

                while (iterator.isValid()) {
                    byte[] key = iterator.key();

                    if (ByteBuffer.wrap(key).compareTo(ByteBuffer.wrap(endKey)) >= 0) {
                        break;
                    }

                    // Extract hash
                    if (key.length == 41) {
                        byte[] hashBytes = new byte[32];
                        System.arraycopy(key, 9, hashBytes, 0, 32);
                        Bytes32 hash = Bytes32.wrap(hashBytes);

                        Block block = getBlockByHash(hash, false);
                        if (block != null) {
                            blocks.add(block);
                        }
                    }

                    iterator.next();
                }
            }

        } catch (Exception e) {
            log.error("Failed to get blocks by time range [{}, {})", startTime, endTime, e);
        }

        return blocks;
    }

    // ==================== BlockInfo Operations ====================

    @Override
    public void saveBlockInfo(BlockInfo blockInfo) {
        if (blockInfo == null) {
            throw new IllegalArgumentException("BlockInfo is null");
        }

        try {
            byte[] key = buildBlockInfoKey(blockInfo.getHash());
            byte[] data = serializeBlockInfo(blockInfo);
            db.put(writeOptions, key, data);

            cache.putBlockInfo(blockInfo.getHash(), blockInfo);

            log.debug("Saved BlockInfo: {}", blockInfo.getHash().toHexString());

        } catch (RocksDBException e) {
            log.error("Failed to save BlockInfo", e);
            throw new RuntimeException("Failed to save BlockInfo", e);
        }
    }

    @Override
    public BlockInfo getBlockInfo(Bytes32 hash) {
        if (hash == null) {
            return null;
        }

        // L1 Cache check
        BlockInfo cached = cache.getBlockInfo(hash);
        if (cached != null) {
            return cached;
        }

        // L2 + Disk read
        try {
            byte[] key = buildBlockInfoKey(hash);
            byte[] data = db.get(readOptions, key);
            if (data == null) {
                return null;
            }

            BlockInfo info = deserializeBlockInfo(data);
            cache.putBlockInfo(hash, info);
            return info;

        } catch (RocksDBException e) {
            log.error("Failed to get BlockInfo: {}", hash.toHexString(), e);
            return null;
        }
    }

    @Override
    public boolean hasBlockInfo(Bytes32 hash) {
        if (hash == null) {
            return false;
        }

        if (cache.getBlockInfo(hash) != null) {
            return true;
        }

        try {
            byte[] key = buildBlockInfoKey(hash);
            byte[] data = db.get(readOptions, key);
            return data != null;
        } catch (RocksDBException e) {
            log.error("Failed to check BlockInfo existence: {}", hash.toHexString(), e);
            return false;
        }
    }

    // ==================== ChainStats Operations ====================

    @Override
    public void saveChainStats(ChainStats stats) {
        if (stats == null) {
            throw new IllegalArgumentException("ChainStats is null");
        }

        try {
            byte[] key = new byte[]{CHAIN_STATS};
            byte[] data = serializeChainStats(stats);
            db.put(syncWriteOptions, key, data);  // Use sync write for critical data

            log.debug("Saved ChainStats");

        } catch (RocksDBException e) {
            log.error("Failed to save ChainStats", e);
            throw new RuntimeException("Failed to save ChainStats", e);
        }
    }

    @Override
    public ChainStats getChainStats() {
        try {
            byte[] key = new byte[]{CHAIN_STATS};
            byte[] data = db.get(readOptions, key);
            if (data == null) {
                return null;
            }

            return deserializeChainStats(data);

        } catch (RocksDBException e) {
            log.error("Failed to get ChainStats", e);
            return null;
        }
    }

    // ==================== Block References ====================

    @Override
    public List<Bytes32> getBlockReferences(Bytes32 blockHash) {
        List<Bytes32> references = new ArrayList<>();

        try {
            byte[] key = buildBlockRefsKey(blockHash);
            byte[] data = db.get(readOptions, key);
            if (data == null || data.length == 0) {
                return references;
            }

            // Deserialize list of hashes
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.remaining() >= 32) {
                byte[] hashBytes = new byte[32];
                buffer.get(hashBytes);
                references.add(Bytes32.wrap(hashBytes));
            }

        } catch (RocksDBException e) {
            log.error("Failed to get block references for {}", blockHash.toHexString(), e);
        }

        return references;
    }

    @Override
    public void indexBlockReference(Bytes32 referencingBlock, Bytes32 referencedBlock) {
        if (referencingBlock == null || referencedBlock == null) {
            return;
        }

        try {
            byte[] key = buildBlockRefsKey(referencedBlock);

            // Read existing references
            List<Bytes32> refs = getBlockReferences(referencedBlock);

            // Add new reference if not exists
            if (!refs.contains(referencingBlock)) {
                refs.add(referencingBlock);

                // Serialize list
                ByteBuffer buffer = ByteBuffer.allocate(refs.size() * 32);
                for (Bytes32 ref : refs) {
                    buffer.put(ref.toArray());
                }

                db.put(writeOptions, key, buffer.array());
            }

        } catch (RocksDBException e) {
            log.error("Failed to index block reference", e);
        }
    }

    // ==================== Batch Operations ====================

    @Override
    public void saveBlocks(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }

        WriteBatch batch = new WriteBatch();
        try {
            for (Block block : blocks) {
                if (block == null || block.getInfo() == null) {
                    continue;
                }

                Bytes32 hash = block.getHash();
                BlockInfo info = block.getInfo();

                // Add to batch
                batch.put(buildBlockKey(hash), serializeBlock(block));
                batch.put(buildBlockInfoKey(hash), serializeBlockInfo(info));
                batch.put(buildTimeIndexKey(block.getTimestamp(), hash), EMPTY_VALUE);
                batch.put(buildEpochIndexKey(block.getEpoch(), hash), EMPTY_VALUE);

                if (info.getHeight() > 0) {
                    batch.put(buildHeightIndexKey(info.getHeight()), hash.toArray());
                }
            }

            db.write(writeOptions, batch);

            log.debug("Batch saved {} blocks", blocks.size());

        } catch (RocksDBException e) {
            log.error("Failed to batch save blocks", e);
            throw new RuntimeException("Failed to batch save blocks", e);
        } finally {
            batch.close();
        }
    }

    @Override
    public List<Block> getBlocksByHashes(List<Bytes32> hashes) {
        List<Block> blocks = new ArrayList<>();
        if (hashes == null || hashes.isEmpty()) {
            return blocks;
        }

        for (Bytes32 hash : hashes) {
            Block block = getBlockByHash(hash, false);
            blocks.add(block);  // Add null if not found
        }

        return blocks;
    }

    // ==================== Statistics ====================

    @Override
    public long getBlockCount() {
        // TODO: Implement using RocksDB statistics or counter
        return 0;
    }

    @Override
    public long getDatabaseSize() {
        try {
            return getDirectorySizeRecursive(dbDir);
        } catch (Exception e) {
            log.error("Failed to get database size", e);
            return 0;
        }
    }

    /**
     * Calculate directory size recursively
     */
    private long getDirectorySizeRecursive(File dir) {
        long size = 0;
        if (dir.isFile()) {
            return dir.length();
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else {
                    size += getDirectorySizeRecursive(file);
                }
            }
        }
        return size;
    }

    @Override
    public void reset() {
        log.warn("Resetting DagStore...");

        try {
            // Close database
            if (db != null) {
                db.close();
            }

            // Delete database files
            deleteDirectoryRecursive(dbDir);

            // Recreate database
            start();

            // Clear cache
            cache.invalidateAll();

            log.info("DagStore reset completed");

        } catch (Exception e) {
            log.error("Failed to reset DagStore", e);
            throw new RuntimeException("Failed to reset DagStore", e);
        }
    }

    /**
     * Delete directory recursively
     */
    private boolean deleteDirectoryRecursive(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectoryRecursive(file);
                }
            }
        }
        return dir.delete();
    }

    // ==================== Key Building Methods ====================

    private byte[] buildBlockKey(Bytes32 hash) {
        byte[] key = new byte[33];
        key[0] = BLOCK_DATA;
        System.arraycopy(hash.toArray(), 0, key, 1, 32);
        return key;
    }

    private byte[] buildBlockInfoKey(Bytes32 hash) {
        byte[] key = new byte[33];
        key[0] = BLOCK_INFO;
        System.arraycopy(hash.toArray(), 0, key, 1, 32);
        return key;
    }

    private byte[] buildTimeIndexKey(long timestamp, Bytes32 hash) {
        byte[] key = new byte[41];  // 1 + 8 + 32
        key[0] = TIME_INDEX;
        ByteBuffer.wrap(key, 1, 8).putLong(timestamp);
        System.arraycopy(hash.toArray(), 0, key, 9, 32);
        return key;
    }

    private byte[] buildEpochIndexKey(long epoch, Bytes32 hash) {
        byte[] key = new byte[41];  // 1 + 8 + 32
        key[0] = EPOCH_INDEX;
        ByteBuffer.wrap(key, 1, 8).putLong(epoch);
        System.arraycopy(hash.toArray(), 0, key, 9, 32);
        return key;
    }

    private byte[] buildHeightIndexKey(long height) {
        byte[] key = new byte[9];  // 1 + 8
        key[0] = HEIGHT_INDEX;
        ByteBuffer.wrap(key, 1, 8).putLong(height);
        return key;
    }

    private byte[] buildBlockRefsKey(Bytes32 hash) {
        byte[] key = new byte[33];
        key[0] = BLOCK_REFS_INDEX;
        System.arraycopy(hash.toArray(), 0, key, 1, 32);
        return key;
    }

    // ==================== Serialization Methods ====================

    /**
     * Serialize Block to bytes
     *
     * <p>Uses Block's built-in toBytes() method for compact serialization.
     *
     * @param block Block to serialize
     * @return Serialized bytes
     */
    private byte[] serializeBlock(Block block) {
        if (block == null) {
            return new byte[0];
        }

        try {
            return block.toBytes();
        } catch (Exception e) {
            log.error("Failed to serialize block: {}", block.getHash().toHexString(), e);
            return new byte[0];
        }
    }

    /**
     * Deserialize Block from bytes
     *
     * <p>Uses Block's built-in fromBytes() method for deserialization.
     *
     * @param data Serialized bytes
     * @return Deserialized Block
     */
    private Block deserializeBlock(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            return Block.fromBytes(data);
        } catch (Exception e) {
            log.error("Failed to deserialize block", e);
            return null;
        }
    }

    /**
     * Serialize BlockInfo to bytes (compact format)
     *
     * <p>Fixed-size 84-byte format:
     * - hash: 32 bytes
     * - timestamp: 8 bytes
     * - height: 8 bytes
     * - difficulty: 32 bytes
     * - flags: 4 bytes (v5.1 compatibility - always 0)
     *
     * @param info BlockInfo to serialize
     * @return Serialized bytes (84 bytes)
     */
    private byte[] serializeBlockInfo(BlockInfo info) {
        ByteBuffer buffer = ByteBuffer.allocate(84);
        buffer.put(info.getHash().toArray());          // 32 bytes
        buffer.putLong(info.getTimestamp());          // 8 bytes
        buffer.putLong(info.getHeight());             // 8 bytes
        buffer.put(info.getDifficulty().toBytes().toArray());  // 32 bytes
        buffer.putInt(0);  // flags placeholder for v5.1 compatibility  // 4 bytes
        return buffer.array();
    }

    /**
     * Deserialize BlockInfo from bytes
     *
     * @param data Serialized bytes (84 bytes)
     * @return Deserialized BlockInfo
     */
    private BlockInfo deserializeBlockInfo(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte[] hashBytes = new byte[32];
        buffer.get(hashBytes);
        Bytes32 hash = Bytes32.wrap(hashBytes);

        long timestamp = buffer.getLong();
        long height = buffer.getLong();

        byte[] diffBytes = new byte[32];
        buffer.get(diffBytes);
        UInt256 difficulty = UInt256.fromBytes(Bytes32.wrap(diffBytes));

        int flags = buffer.getInt();  // Read but ignore flags (v5.1 compatibility)

        return BlockInfo.builder()
                .hash(hash)
                .timestamp(timestamp)
                .height(height)
                .difficulty(difficulty)
                .build();
    }

    /**
     * Serialize ChainStats to bytes
     *
     * <p>Uses CompactSerializer for optimized ChainStats serialization.
     *
     * @param stats ChainStats to serialize
     * @return Serialized bytes
     */
    private byte[] serializeChainStats(ChainStats stats) {
        if (stats == null) {
            return new byte[0];
        }

        try {
            return io.xdag.serialization.CompactSerializer.serialize(stats);
        } catch (Exception e) {
            log.error("Failed to serialize ChainStats", e);
            return new byte[0];
        }
    }

    /**
     * Deserialize ChainStats from bytes
     *
     * <p>Uses CompactSerializer for ChainStats deserialization.
     *
     * @param data Serialized bytes
     * @return Deserialized ChainStats
     */
    private ChainStats deserializeChainStats(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            return io.xdag.serialization.CompactSerializer.deserializeChainStats(data);
        } catch (Exception e) {
            log.error("Failed to deserialize ChainStats", e);
            return null;
        }
    }
}
