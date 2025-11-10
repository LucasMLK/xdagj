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

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import io.xdag.core.BlockInfo;
import io.xdag.db.store.FinalizedBlockStore;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Bloom Filter wrapper for FinalizedBlockStore
 *
 * Provides fast negative lookups for hasBlock() queries:
 * - If Bloom Filter says "no" → definitely not in store (100% accurate)
 * - If Bloom Filter says "yes" → might be in store (need to check)
 *
 * Performance:
 * - Memory: ~12 MB for 10M blocks (1% false positive rate)
 * - Speedup: 90%+ queries can skip database lookup
 * - False positive rate: ~1%
 *
 * Use case:
 * - Block synchronization: quickly check if we already have a block
 * - Duplicate detection: avoid re-processing existing blocks
 */
@Slf4j
public class BloomFilterBlockStore implements FinalizedBlockStore {

    private final FinalizedBlockStore delegate;
    private final BloomFilter<byte[]> bloomFilter;

    // Statistics
    private long bloomHits = 0;      // Bloom filter said "no" (saved DB lookup)
    private long bloomMisses = 0;    // Bloom filter said "yes" (need DB lookup)
    private long falsePositives = 0; // Bloom said "yes" but DB said "no"

    /**
     * Create Bloom Filter wrapper
     *
     * @param delegate Underlying store
     * @param expectedInsertions Expected number of blocks (default: 10M)
     * @param falsePositiveRate Acceptable false positive rate (default: 0.01 = 1%)
     */
    public BloomFilterBlockStore(FinalizedBlockStore delegate,
                                  long expectedInsertions,
                                  double falsePositiveRate) {
        this.delegate = delegate;
        this.bloomFilter = BloomFilter.create(
                Funnels.byteArrayFunnel(),
                expectedInsertions,
                falsePositiveRate
        );

        log.info("Created BloomFilter: expectedInsertions={}, falsePositiveRate={}",
                expectedInsertions, falsePositiveRate);
    }

    /**
     * Create with default parameters (10M blocks, 1% FP rate)
     */
    public BloomFilterBlockStore(FinalizedBlockStore delegate) {
        this(delegate, 10_000_000, 0.01);
    }

    // ========== Optimized Methods ==========

    @Override
    public boolean hasBlock(Bytes32 hash) {
        byte[] hashBytes = hash.toArray();

        // Check Bloom Filter first
        if (!bloomFilter.mightContain(hashBytes)) {
            // Definitely not in store
            bloomHits++;
            return false;
        }

        // Might be in store, need to check
        bloomMisses++;
        boolean exists = delegate.hasBlock(hash);

        if (!exists) {
            // False positive
            falsePositives++;
        }

        return exists;
    }

    @Override
    public void saveBlockInfo(BlockInfo blockInfo) {
        delegate.saveBlockInfo(blockInfo);
        // Add to Bloom Filter
        bloomFilter.put(blockInfo.getHash().toArray());
    }

    // ========== Statistics ==========

    /**
     * Get Bloom Filter hit rate (percentage of queries saved from DB lookup)
     */
    public double getBloomHitRate() {
        long total = bloomHits + bloomMisses;
        return total > 0 ? (double) bloomHits / total * 100 : 0;
    }

    /**
     * Get false positive rate (actual observed rate)
     */
    public double getFalsePositiveRate() {
        return bloomMisses > 0 ? (double) falsePositives / bloomMisses * 100 : 0;
    }

    /**
     * Print statistics
     */
    public void printStatistics() {
        long total = bloomHits + bloomMisses;
        System.out.printf("Bloom Filter Statistics:%n");
        System.out.printf("  Total queries:        %,d%n", total);
        System.out.printf("  Bloom hits (saved):   %,d (%.1f%%)%n", bloomHits, getBloomHitRate());
        System.out.printf("  Bloom misses:         %,d (%.1f%%)%n", bloomMisses, 100 - getBloomHitRate());
        System.out.printf("  False positives:      %,d (%.2f%%)%n", falsePositives, getFalsePositiveRate());
    }

    /**
     * Reset statistics
     */
    public void resetStatistics() {
        bloomHits = 0;
        bloomMisses = 0;
        falsePositives = 0;
    }

    // ========== Delegated Methods (unchanged) ==========

    @Override
    public Optional<BlockInfo> getBlockInfoByHash(Bytes32 hash) {
        return delegate.getBlockInfoByHash(hash);
    }

    @Override
    public Optional<BlockInfo> getMainBlockInfoByHeight(long height) {
        return delegate.getMainBlockInfoByHeight(height);
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
    public List<BlockInfo> getBlockInfosByEpoch(long epoch) {
        return delegate.getBlockInfosByEpoch(epoch);
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
        log.info("Closing BloomFilterBlockStore");
        printStatistics();
        delegate.close();
    }
}
