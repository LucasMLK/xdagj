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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * LRU Cache wrapper for FinalizedBlockStore
 *
 * Provides fast in-memory access to frequently queried blocks:
 * - Cache BlockInfo (lightweight metadata) for fast lookups
 * - Cache full Blocks for repeated access
 * - LRU eviction policy to limit memory usage
 * - Thread-safe with Guava Cache
 *
 * Performance:
 * - Cache hit: ~0.001 ms (1 microsecond)
 * - Cache miss: ~0.1 ms (database lookup)
 * - Memory: ~10KB per cached block (configurable)
 *
 * Use case:
 * - Recent blocks accessed repeatedly during sync
 * - Hot blocks referenced by many new blocks
 * - Reduce database I/O pressure
 */
@Slf4j
public class CachedBlockStore implements FinalizedBlockStore {

    private final FinalizedBlockStore delegate;
    private final Cache<Bytes32, BlockInfo> blockInfoCache;
    private final Cache<Bytes32, Block> blockCache;
    private final Cache<Long, Bytes32> heightCache; // height → hash

    // Statistics
    private long blockInfoCacheHits = 0;
    private long blockInfoCacheMisses = 0;
    private long blockCacheHits = 0;
    private long blockCacheMisses = 0;
    private long heightCacheHits = 0;
    private long heightCacheMisses = 0;

    /**
     * Create cached store with custom cache sizes
     *
     * @param delegate Underlying store
     * @param blockInfoCacheSize Max number of BlockInfo entries
     * @param blockCacheSize Max number of Block entries
     * @param heightCacheSize Max number of height index entries
     */
    public CachedBlockStore(FinalizedBlockStore delegate,
                             long blockInfoCacheSize,
                             long blockCacheSize,
                             long heightCacheSize) {
        this.delegate = delegate;

        // BlockInfo cache: lightweight metadata
        this.blockInfoCache = CacheBuilder.newBuilder()
                .maximumSize(blockInfoCacheSize)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .recordStats()
                .build();

        // Block cache: full block data
        this.blockCache = CacheBuilder.newBuilder()
                .maximumSize(blockCacheSize)
                .expireAfterAccess(5, TimeUnit.MINUTES)
                .recordStats()
                .build();

        // Height index cache
        this.heightCache = CacheBuilder.newBuilder()
                .maximumSize(heightCacheSize)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .recordStats()
                .build();

        log.info("Created CachedBlockStore: blockInfo={}, blocks={}, height={}",
                blockInfoCacheSize, blockCacheSize, heightCacheSize);
    }

    /**
     * Create with default cache sizes:
     * - BlockInfo: 100,000 entries (~10 MB)
     * - Blocks: 10,000 entries (~50 MB)
     * - Height index: 50,000 entries (~1 MB)
     */
    public CachedBlockStore(FinalizedBlockStore delegate) {
        this(delegate, 100_000, 10_000, 50_000);
    }

    // ========== Cached Methods ==========

    @Override
    public Optional<BlockInfo> getBlockInfoByHash(Bytes32 hash) {
        // Check cache first
        BlockInfo cached = blockInfoCache.getIfPresent(hash);
        if (cached != null) {
            blockInfoCacheHits++;
            return Optional.of(cached);
        }

        // Cache miss, query database
        blockInfoCacheMisses++;
        Optional<BlockInfo> result = delegate.getBlockInfoByHash(hash);

        // Cache result
        result.ifPresent(info -> blockInfoCache.put(hash, info));

        return result;
    }

    @Override
    public Optional<Block> getBlockByHash(Bytes32 hash) {
        // Check cache first
        Block cached = blockCache.getIfPresent(hash);
        if (cached != null) {
            blockCacheHits++;
            return Optional.of(cached);
        }

        // Cache miss, query database
        blockCacheMisses++;
        Optional<Block> result = delegate.getBlockByHash(hash);

        // Cache result
        result.ifPresent(block -> {
            blockCache.put(hash, block);
            // Also cache BlockInfo
            blockInfoCache.put(hash, BlockInfo.fromLegacy(block.getInfo()));
        });

        return result;
    }

    @Override
    public Optional<BlockInfo> getMainBlockInfoByHeight(long height) {
        // Check height cache first
        Bytes32 cachedHash = heightCache.getIfPresent(height);
        if (cachedHash != null) {
            heightCacheHits++;
            return getBlockInfoByHash(cachedHash);
        }

        // Cache miss, query database
        heightCacheMisses++;
        Optional<BlockInfo> result = delegate.getMainBlockInfoByHeight(height);

        // Cache result
        result.ifPresent(info -> {
            heightCache.put(height, info.getHashLow());
            blockInfoCache.put(info.getHashLow(), info);
        });

        return result;
    }

    @Override
    public Optional<Block> getMainBlockByHeight(long height) {
        // Check height cache first
        Bytes32 cachedHash = heightCache.getIfPresent(height);
        if (cachedHash != null) {
            heightCacheHits++;
            return getBlockByHash(cachedHash);
        }

        // Cache miss, query database
        heightCacheMisses++;
        Optional<Block> result = delegate.getMainBlockByHeight(height);

        // Cache result
        result.ifPresent(block -> {
            Bytes32 hash = block.getHashLow();
            heightCache.put(height, hash);
            blockCache.put(hash, block);
            blockInfoCache.put(hash, BlockInfo.fromLegacy(block.getInfo()));
        });

        return result;
    }

    @Override
    public void saveBlock(Block block) {
        delegate.saveBlock(block);

        // Update cache
        Bytes32 hash = block.getHashLow();
        blockCache.put(hash, block);

        BlockInfo info = BlockInfo.fromLegacy(block.getInfo());
        blockInfoCache.put(hash, info);

        if (info.isMainBlock()) {
            heightCache.put(info.getHeight(), hash);
        }
    }

    @Override
    public void saveBlockInfo(BlockInfo blockInfo) {
        delegate.saveBlockInfo(blockInfo);

        // Update cache
        blockInfoCache.put(blockInfo.getHashLow(), blockInfo);

        if (blockInfo.isMainBlock()) {
            heightCache.put(blockInfo.getHeight(), blockInfo.getHashLow());
        }
    }

    @Override
    public long saveBatch(List<Block> blocks) {
        long saved = delegate.saveBatch(blocks);

        // Update cache for all saved blocks
        for (Block block : blocks) {
            Bytes32 hash = block.getHashLow();
            blockCache.put(hash, block);

            BlockInfo info = BlockInfo.fromLegacy(block.getInfo());
            blockInfoCache.put(hash, info);

            if (info.isMainBlock()) {
                heightCache.put(info.getHeight(), hash);
            }
        }

        return saved;
    }

    // ========== Statistics ==========

    /**
     * Get BlockInfo cache hit rate
     */
    public double getBlockInfoCacheHitRate() {
        long total = blockInfoCacheHits + blockInfoCacheMisses;
        return total > 0 ? (double) blockInfoCacheHits / total * 100 : 0;
    }

    /**
     * Get Block cache hit rate
     */
    public double getBlockCacheHitRate() {
        long total = blockCacheHits + blockCacheMisses;
        return total > 0 ? (double) blockCacheHits / total * 100 : 0;
    }

    /**
     * Get height index cache hit rate
     */
    public double getHeightCacheHitRate() {
        long total = heightCacheHits + heightCacheMisses;
        return total > 0 ? (double) heightCacheHits / total * 100 : 0;
    }

    /**
     * Get cache sizes
     */
    public CacheStats getCacheStats() {
        return new CacheStats(
                blockInfoCache.size(),
                blockCache.size(),
                heightCache.size(),
                getBlockInfoCacheHitRate(),
                getBlockCacheHitRate(),
                getHeightCacheHitRate()
        );
    }

    /**
     * Reset statistics
     */
    public void resetStatistics() {
        blockInfoCacheHits = 0;
        blockInfoCacheMisses = 0;
        blockCacheHits = 0;
        blockCacheMisses = 0;
        heightCacheHits = 0;
        heightCacheMisses = 0;
    }

    /**
     * Clear all caches
     */
    public void clearCaches() {
        blockInfoCache.invalidateAll();
        blockCache.invalidateAll();
        heightCache.invalidateAll();
        log.info("All caches cleared");
    }

    /**
     * Print cache statistics
     */
    public void printStatistics() {
        System.out.printf("Cache Statistics:%n");
        System.out.printf("  BlockInfo Cache:%n");
        System.out.printf("    Size:      %,d entries%n", blockInfoCache.size());
        System.out.printf("    Hit rate:  %.1f%%%n", getBlockInfoCacheHitRate());
        System.out.printf("  Block Cache:%n");
        System.out.printf("    Size:      %,d entries%n", blockCache.size());
        System.out.printf("    Hit rate:  %.1f%%%n", getBlockCacheHitRate());
        System.out.printf("  Height Cache:%n");
        System.out.printf("    Size:      %,d entries%n", heightCache.size());
        System.out.printf("    Hit rate:  %.1f%%%n", getHeightCacheHitRate());
    }

    // ========== Delegated Methods (unchanged) ==========

    @Override
    public boolean hasBlock(Bytes32 hash) {
        // Check cache first
        if (blockInfoCache.getIfPresent(hash) != null) {
            return true;
        }
        return delegate.hasBlock(hash);
    }

    @Override
    public List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight) {
        // Don't cache range queries (too many entries)
        return delegate.getMainBlocksByHeightRange(fromHeight, toHeight);
    }

    @Override
    public List<BlockInfo> getMainBlockInfosByHeightRange(long fromHeight, long toHeight) {
        return delegate.getMainBlockInfosByHeightRange(fromHeight, toHeight);
    }

    @Override
    public long getMaxFinalizedHeight() {
        return delegate.getMaxFinalizedHeight();
    }

    @Override
    public boolean verifyMainChainContinuity(long fromHeight, long toHeight) {
        return delegate.verifyMainChainContinuity(fromHeight, toHeight);
    }

    @Override
    public List<Bytes32> getBlockHashesByEpoch(long epoch) {
        return delegate.getBlockHashesByEpoch(epoch);
    }

    @Override
    public List<Block> getBlocksByEpoch(long epoch) {
        return delegate.getBlocksByEpoch(epoch);
    }

    @Override
    public List<BlockInfo> getBlockInfosByEpoch(long epoch) {
        return delegate.getBlockInfosByEpoch(epoch);
    }

    @Override
    public List<Block> getBlocksByEpochRange(long fromEpoch, long toEpoch) {
        return delegate.getBlocksByEpochRange(fromEpoch, toEpoch);
    }

    @Override
    public long countBlocksInEpoch(long epoch) {
        return delegate.countBlocksInEpoch(epoch);
    }

    @Override
    public long getTotalBlockCount() {
        return delegate.getTotalBlockCount();
    }

    @Override
    public long getTotalMainBlockCount() {
        return delegate.getTotalMainBlockCount();
    }

    @Override
    public long getStorageSize() {
        return delegate.getStorageSize();
    }

    @Override
    public FinalizedStats getStatsForRange(long fromHeight, long toHeight) {
        return delegate.getStatsForRange(fromHeight, toHeight);
    }

    @Override
    public long rebuildIndexes() {
        return delegate.rebuildIndexes();
    }

    @Override
    public boolean verifyIntegrity() {
        return delegate.verifyIntegrity();
    }

    @Override
    public void compact() {
        delegate.compact();
    }

    @Override
    public void close() {
        log.info("Closing CachedBlockStore");
        printStatistics();
        clearCaches();
        delegate.close();
    }

    // ========== Nested Classes ==========

    /**
     * Cache statistics record
     */
    public record CacheStats(
            long blockInfoCacheSize,
            long blockCacheSize,
            long heightCacheSize,
            double blockInfoHitRate,
            double blockHitRate,
            double heightHitRate
    ) {}
}
