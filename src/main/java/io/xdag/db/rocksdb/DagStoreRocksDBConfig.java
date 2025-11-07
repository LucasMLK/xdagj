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

import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;

/**
 * DagStoreRocksDBConfig - Optimized RocksDB Configuration for XDAG DAG Storage
 *
 * <p>This class provides RocksDB configuration optimized for XDAG's specific workload:
 * <ul>
 *   <li><strong>Read-heavy</strong>: 90%+ reads vs writes</li>
 *   <li><strong>Hot data</strong>: Recent blocks accessed frequently</li>
 *   <li><strong>Sequential writes</strong>: Block imports are sequential</li>
 *   <li><strong>Range scans</strong>: Epoch and time range queries</li>
 * </ul>
 *
 * <h2>Configuration Principles</h2>
 * <ul>
 *   <li><strong>Large Block Cache</strong>: 2-4 GB for hot data</li>
 *   <li><strong>Bloom Filters</strong>: Fast existence checks</li>
 *   <li><strong>LZ4 Compression</strong>: Fast compression/decompression</li>
 *   <li><strong>Optimized Compaction</strong>: Balance read and write performance</li>
 * </ul>
 *
 * <h2>Memory Budget</h2>
 * <pre>
 * Block Cache (L2):        2-4 GB   (shared across column families)
 * Write Buffers:           192 MB   (64 MB × 3)
 * Index + Filters:         ~500 MB  (cached in block cache)
 * OS Page Cache:           2-4 GB   (managed by OS)
 * ------------------------------------------------
 * Total Memory:            ~5-9 GB
 * </pre>
 *
 * @since v5.1 Phase 8
 */
@Slf4j
public class DagStoreRocksDBConfig {

    // ==================== Memory Configuration ====================

    /** Block cache size (L2 cache) - 2 GB default */
    private static final long BLOCK_CACHE_SIZE = 2L * 1024 * 1024 * 1024;  // 2 GB

    /** Write buffer size (MemTable) - 64 MB per CF */
    private static final long WRITE_BUFFER_SIZE = 64 * 1024 * 1024;  // 64 MB

    /** Max write buffers - 3 allows for 2 immutable + 1 active */
    private static final int MAX_WRITE_BUFFER_NUMBER = 3;

    /** Block size - 16 KB (good for SSD) */
    private static final long BLOCK_SIZE = 16 * 1024;  // 16 KB

    // ==================== Compaction Configuration ====================

    /** L0 files before compaction trigger */
    private static final int LEVEL0_FILE_NUM_COMPACTION_TRIGGER = 4;

    /** L0 files before slowdown */
    private static final int LEVEL0_SLOWDOWN_WRITES_TRIGGER = 20;

    /** L0 files before stop writes */
    private static final int LEVEL0_STOP_WRITES_TRIGGER = 36;

    /** L1 target size - 256 MB */
    private static final long MAX_BYTES_FOR_LEVEL_BASE = 256 * 1024 * 1024;  // 256 MB

    /** Level size multiplier - each level is 10x previous */
    private static final double MAX_BYTES_FOR_LEVEL_MULTIPLIER = 10.0;

    // ==================== Background Jobs ====================

    /** Background threads for compaction and flush */
    private static final int MAX_BACKGROUND_JOBS = 4;

    // ==================== DBOptions ====================

    /**
     * Create optimized DBOptions for DagStore
     *
     * @return Configured DBOptions
     */
    public static DBOptions createDBOptions() {
        DBOptions options = new DBOptions();

        // Parallelism
        options.setIncreaseParallelism(4);
        options.setMaxBackgroundJobs(MAX_BACKGROUND_JOBS);

        // WAL configuration
        options.setWalSizeLimitMB(256);  // 256 MB WAL limit
        options.setWalTtlSeconds(3600);  // Keep WAL for 1 hour

        // File management
        options.setMaxOpenFiles(500);  // Limit open file descriptors

        // Statistics (enable for monitoring)
        options.setStatsDumpPeriodSec(600);  // Dump stats every 10 minutes

        // Create if missing
        options.setCreateIfMissing(true);
        options.setCreateMissingColumnFamilies(true);

        log.info("DBOptions created: parallelism=4, maxBackgroundJobs={}, maxOpenFiles=500",
                MAX_BACKGROUND_JOBS);

        return options;
    }

    // ==================== ColumnFamilyOptions ====================

    /**
     * Create optimized ColumnFamilyOptions for DagStore
     *
     * @return Configured ColumnFamilyOptions
     */
    public static ColumnFamilyOptions createColumnFamilyOptions() {
        ColumnFamilyOptions cfOptions = new ColumnFamilyOptions();

        // Block-based table configuration
        BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();

        // L2 Block Cache (shared across CFs)
        Cache blockCache = new LRUCache(BLOCK_CACHE_SIZE);
        tableConfig.setBlockCache(blockCache);
        tableConfig.setBlockSize(BLOCK_SIZE);

        // Bloom Filter (10 bits per key = ~1% false positive rate)
        BloomFilter bloomFilter = new BloomFilter(10, false);
        tableConfig.setFilterPolicy(bloomFilter);

        // Cache index and filter blocks in block cache
        tableConfig.setCacheIndexAndFilterBlocks(true);
        tableConfig.setPinL0FilterAndIndexBlocksInCache(true);

        // Optimize for point lookups
        tableConfig.setIndexType(IndexType.kBinarySearch);

        cfOptions.setTableFormatConfig(tableConfig);

        // Write buffer configuration
        cfOptions.setWriteBufferSize(WRITE_BUFFER_SIZE);
        cfOptions.setMaxWriteBufferNumber(MAX_WRITE_BUFFER_NUMBER);
        cfOptions.setMinWriteBufferNumberToMerge(1);

        // Compression (LZ4 for speed)
        cfOptions.setCompressionType(CompressionType.LZ4_COMPRESSION);

        // Compaction configuration
        cfOptions.setLevel0FileNumCompactionTrigger(LEVEL0_FILE_NUM_COMPACTION_TRIGGER);
        cfOptions.setLevel0SlowdownWritesTrigger(LEVEL0_SLOWDOWN_WRITES_TRIGGER);
        cfOptions.setLevel0StopWritesTrigger(LEVEL0_STOP_WRITES_TRIGGER);

        // LSM tree levels
        cfOptions.setNumLevels(7);
        cfOptions.setMaxBytesForLevelBase(MAX_BYTES_FOR_LEVEL_BASE);
        cfOptions.setMaxBytesForLevelMultiplier(MAX_BYTES_FOR_LEVEL_MULTIPLIER);

        // Compaction style
        cfOptions.setCompactionStyle(CompactionStyle.LEVEL);

        log.info("ColumnFamilyOptions created: blockCache={}MB, writeBuffer={}MB, compression=LZ4",
                BLOCK_CACHE_SIZE / (1024 * 1024), WRITE_BUFFER_SIZE / (1024 * 1024));

        return cfOptions;
    }

    // ==================== WriteOptions ====================

    /**
     * Create WriteOptions for normal writes
     *
     * <p>Optimized for throughput:
     * <ul>
     *   <li>Async writes (sync=false)</li>
     *   <li>WAL enabled (for crash recovery)</li>
     * </ul>
     *
     * @return Configured WriteOptions
     */
    public static WriteOptions createWriteOptions() {
        WriteOptions writeOptions = new WriteOptions();

        // Async writes for performance
        writeOptions.setSync(false);

        // Enable WAL for durability
        writeOptions.setDisableWAL(false);

        return writeOptions;
    }

    /**
     * Create WriteOptions for critical writes (e.g., ChainStats)
     *
     * <p>Optimized for durability:
     * <ul>
     *   <li>Sync writes (sync=true)</li>
     *   <li>WAL enabled</li>
     * </ul>
     *
     * @return Configured WriteOptions for sync writes
     */
    public static WriteOptions createSyncWriteOptions() {
        WriteOptions writeOptions = new WriteOptions();

        // Sync writes for critical data
        writeOptions.setSync(true);

        // Enable WAL
        writeOptions.setDisableWAL(false);

        return writeOptions;
    }

    // ==================== ReadOptions ====================

    /**
     * Create ReadOptions for reads
     *
     * <p>Optimized for speed:
     * <ul>
     *   <li>Fill cache enabled</li>
     *   <li>Checksum verification disabled (for speed)</li>
     * </ul>
     *
     * @return Configured ReadOptions
     */
    public static ReadOptions createReadOptions() {
        ReadOptions readOptions = new ReadOptions();

        // Fill cache on reads
        readOptions.setFillCache(true);

        // Skip checksum for speed (data integrity handled by upper layers)
        readOptions.setVerifyChecksums(false);

        return readOptions;
    }

    /**
     * Create ReadOptions for range scans (e.g., epoch queries)
     *
     * <p>Optimized for sequential reads:
     * <ul>
     *   <li>Fill cache disabled (don't pollute cache with scan data)</li>
     *   <li>ReadAhead enabled</li>
     * </ul>
     *
     * @return Configured ReadOptions for scans
     */
    public static ReadOptions createScanReadOptions() {
        ReadOptions readOptions = new ReadOptions();

        // Don't fill cache for scans
        readOptions.setFillCache(false);

        // Enable read-ahead for sequential access
        readOptions.setReadaheadSize(256 * 1024);  // 256 KB read-ahead

        // Skip checksum
        readOptions.setVerifyChecksums(false);

        return readOptions;
    }

    // ==================== Configuration Summary ====================

    /**
     * Log configuration summary
     */
    public static void logConfiguration() {
        log.info("=== DagStore RocksDB Configuration ===");
        log.info("Block Cache (L2):         {} MB", BLOCK_CACHE_SIZE / (1024 * 1024));
        log.info("Write Buffer per CF:      {} MB", WRITE_BUFFER_SIZE / (1024 * 1024));
        log.info("Max Write Buffers:        {}", MAX_WRITE_BUFFER_NUMBER);
        log.info("Total Write Buffers:      {} MB", (WRITE_BUFFER_SIZE * MAX_WRITE_BUFFER_NUMBER) / (1024 * 1024));
        log.info("Block Size:               {} KB", BLOCK_SIZE / 1024);
        log.info("Compression:              LZ4");
        log.info("Bloom Filter:             10 bits/key (~1% FP)");
        log.info("L0 Compaction Trigger:    {} files", LEVEL0_FILE_NUM_COMPACTION_TRIGGER);
        log.info("L1 Target Size:           {} MB", MAX_BYTES_FOR_LEVEL_BASE / (1024 * 1024));
        log.info("Level Multiplier:         {}x", MAX_BYTES_FOR_LEVEL_MULTIPLIER);
        log.info("Background Jobs:          {}", MAX_BACKGROUND_JOBS);
        log.info("======================================");
    }

    // ==================== Cleanup ====================

    /**
     * Close and cleanup RocksDB resources
     *
     * @param dbOptions DBOptions to close
     * @param cfOptions ColumnFamilyOptions to close
     * @param writeOptions WriteOptions to close
     * @param readOptions ReadOptions to close
     */
    public static void cleanup(DBOptions dbOptions,
                               ColumnFamilyOptions cfOptions,
                               WriteOptions writeOptions,
                               ReadOptions readOptions) {
        if (readOptions != null) {
            readOptions.close();
        }
        if (writeOptions != null) {
            writeOptions.close();
        }
        if (cfOptions != null) {
            cfOptions.close();
        }
        if (dbOptions != null) {
            dbOptions.close();
        }
    }
}
