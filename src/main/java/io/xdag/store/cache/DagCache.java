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

package io.xdag.store.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.core.Transaction;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * DagCache - L1 in-memory cache for hot data
 *
 * <p>This cache provides fast access to frequently accessed DAG entities:
 * <ul>
 *   <li><strong>Blocks</strong>: Recently accessed Block objects (raw data)</li>
 *   <li><strong>BlockInfo</strong>: Block metadata (lightweight)</li>
 *   <li><strong>Transactions</strong>: Recently accessed Transaction objects</li>
 *   <li><strong>Height-to-Hash</strong>: Main chain height mappings</li>
 *   <li><strong>Epoch Winners</strong>: Cached epoch competition results</li>
 * </ul>
 *
 * <h2>Cache Configuration</h2>
 * <pre>
 * Block Cache:       10,000 entries × 500 bytes  ≈ 5 MB
 * BlockInfo Cache:   50,000 entries × 84 bytes   ≈ 4.2 MB
 * Transaction Cache: 20,000 entries × 200 bytes  ≈ 4 MB
 * Height Cache:      10,000 entries × 40 bytes   ≈ 0.4 MB
 * Epoch Winner:       5,000 entries × 40 bytes   ≈ 0.2 MB
 * ------------------------------------------------
 * Total L1 Cache:                                ≈ 13.8 MB
 * </pre>
 *
 * <h2>Performance Targets</h2>
 * <ul>
 *   <li>Block Cache Hit Rate: 85-95%</li>
 *   <li>BlockInfo Cache Hit Rate: 90-95%</li>
 *   <li>Transaction Cache Hit Rate: 70-80%</li>
 *   <li>Overall Cache Hit Rate: 90%+</li>
 * </ul>
 *
 * <h2>Eviction Policy</h2>
 * <ul>
 *   <li>Algorithm: LRU (Least Recently Used)</li>
 *   <li>Expiration: Time-based (access time)</li>
 *   <li>Size-based: Maximum entry count</li>
 * </ul>
 *
 * @since XDAGJ
 */
@Slf4j
public class DagCache {

  // ==================== Cache Instances ====================

  /**
   * Block cache - most recently accessed Block objects (raw data)
   */
  private final Cache<Bytes32, Block> blockCache;

  /**
   * BlockInfo cache - most recently accessed BlockInfo metadata
   */
  private final Cache<Bytes32, BlockInfo> blockInfoCache;

  /**
   * Transaction cache - most recently accessed Transaction objects
   */
  private final Cache<Bytes32, Transaction> transactionCache;

  /**
   * Height-to-Hash cache - main chain height mapping
   */
  private final Cache<Long, Bytes32> heightToHashCache;

  /**
   * Epoch winner cache - epoch competition results
   */
  private final Cache<Long, Bytes32> epochWinnerCache;

  // ==================== Constructor ====================

  public DagCache() {
    log.info("Initializing DagCache (L1)...");

    // Block cache configuration (BUG-P2P-001 fix: increased capacity to reduce eviction)
    this.blockCache = Caffeine.newBuilder()
        .maximumSize(50_000)  // 50K blocks × 500 bytes = 25 MB
        .expireAfterAccess(Duration.ofHours(2))  // 2 hour idle time
        .recordStats()  // Enable statistics
        .build();

    // BlockInfo cache configuration
    this.blockInfoCache = Caffeine.newBuilder()
        .maximumSize(50_000)  // 50K BlockInfo × 84 bytes = 4.2 MB
        .expireAfterAccess(Duration.ofHours(1))  // 1 hour idle time
        .recordStats()
        .build();

    // Transaction cache configuration
    this.transactionCache = Caffeine.newBuilder()
        .maximumSize(20_000)  // 20K tx × 200 bytes = 4 MB
        .expireAfterAccess(Duration.ofMinutes(15))  // 15 min idle time
        .recordStats()
        .build();

    // Height-to-Hash cache configuration
    this.heightToHashCache = Caffeine.newBuilder()
        .maximumSize(10_000)  // Most recent 10K main blocks
        .expireAfterAccess(Duration.ofHours(2))  // 2 hour idle time
        .recordStats()
        .build();

    // Epoch winner cache configuration
    this.epochWinnerCache = Caffeine.newBuilder()
        .maximumSize(5_000)  // Most recent 5K epochs
        .expireAfterAccess(Duration.ofHours(2))  // 2 hour idle time
        .recordStats()
        .build();

    log.info("DagCache initialized: Block(10K), BlockInfo(50K), Tx(20K), Height(10K), Epoch(5K)");
  }

  // ==================== Block Cache Operations ====================

  /**
   * Get block from cache
   *
   * @param hash Block hash
   * @return Block or null if not in cache
   */
  public Block getBlock(Bytes32 hash) {
    return blockCache.getIfPresent(hash);
  }

  /**
   * Put block into cache
   *
   * @param hash  Block hash
   * @param block Block object
   */
  public void putBlock(Bytes32 hash, Block block) {
    if (hash != null && block != null) {
      blockCache.put(hash, block);
    }
  }

  /**
   * Invalidate block from cache
   *
   * @param hash Block hash
   */
  public void invalidateBlock(Bytes32 hash) {
    if (hash != null) {
      blockCache.invalidate(hash);
    }
  }

  // ==================== BlockInfo Cache Operations ====================

  /**
   * Get BlockInfo from cache
   *
   * @param hash Block hash
   * @return BlockInfo or null if not in cache
   */
  public BlockInfo getBlockInfo(Bytes32 hash) {
    return blockInfoCache.getIfPresent(hash);
  }

  /**
   * Put BlockInfo into cache
   *
   * @param hash      Block hash
   * @param blockInfo BlockInfo object
   */
  public void putBlockInfo(Bytes32 hash, BlockInfo blockInfo) {
    if (hash != null && blockInfo != null) {
      blockInfoCache.put(hash, blockInfo);
    }
  }

  /**
   * Invalidate BlockInfo from cache
   *
   * @param hash Block hash
   */
  public void invalidateBlockInfo(Bytes32 hash) {
    if (hash != null) {
      blockInfoCache.invalidate(hash);
    }
  }

  // ==================== Transaction Cache Operations ====================

  /**
   * Get transaction from cache
   *
   * @param hash Transaction hash
   * @return Transaction or null if not in cache
   */
  public Transaction getTransaction(Bytes32 hash) {
    return transactionCache.getIfPresent(hash);
  }

  /**
   * Put transaction into cache
   *
   * @param hash Transaction hash
   * @param tx   Transaction object
   */
  public void putTransaction(Bytes32 hash, Transaction tx) {
    if (hash != null && tx != null) {
      transactionCache.put(hash, tx);
    }
  }

  /**
   * Invalidate transaction from cache
   *
   * @param hash Transaction hash
   */
  public void invalidateTransaction(Bytes32 hash) {
    if (hash != null) {
      transactionCache.invalidate(hash);
    }
  }

  // ==================== Height-to-Hash Cache Operations ====================

  /**
   * Get block hash by main chain height
   *
   * @param height Main chain height
   * @return Block hash or null if not in cache
   */
  public Bytes32 getHashByHeight(long height) {
    return heightToHashCache.getIfPresent(height);
  }

  /**
   * Put height-to-hash mapping into cache
   *
   * @param height Main chain height
   * @param hash   Block hash
   */
  public void putHashByHeight(long height, Bytes32 hash) {
    if (hash != null) {
      heightToHashCache.put(height, hash);
    }
  }

  /**
   * Invalidate height-to-hash mapping
   *
   * @param height Main chain height
   */
  public void invalidateHeight(long height) {
    heightToHashCache.invalidate(height);
  }

  // ==================== Epoch Winner Cache Operations ====================

  /**
   * Get epoch winner block hash
   *
   * @param epoch Epoch number
   * @return Winner block hash or null if not in cache
   */
  public Bytes32 getEpochWinner(long epoch) {
    return epochWinnerCache.getIfPresent(epoch);
  }

  /**
   * Put epoch winner into cache
   *
   * @param epoch      Epoch number
   * @param winnerHash Winner block hash
   */
  public void putEpochWinner(long epoch, Bytes32 winnerHash) {
    if (winnerHash != null) {
      epochWinnerCache.put(epoch, winnerHash);
    }
  }

  // ==================== Cache Management ====================

  /**
   * Clear all caches
   */
  public void invalidateAll() {
    log.info("Invalidating all caches...");
    blockCache.invalidateAll();
    blockInfoCache.invalidateAll();
    transactionCache.invalidateAll();
    heightToHashCache.invalidateAll();
    epochWinnerCache.invalidateAll();
    log.info("All caches invalidated");
  }

  // ==================== Statistics ====================

  /**
   * Calculate overall cache hit rate
   *
   * @return Hit rate (0.0 to 1.0)
   */
  public double getOverallHitRate() {
    long totalHits = blockCache.stats().hitCount()
        + blockInfoCache.stats().hitCount()
        + transactionCache.stats().hitCount()
        + heightToHashCache.stats().hitCount()
        + epochWinnerCache.stats().hitCount();

    long totalRequests = blockCache.stats().requestCount()
        + blockInfoCache.stats().requestCount()
        + transactionCache.stats().requestCount()
        + heightToHashCache.stats().requestCount()
        + epochWinnerCache.stats().requestCount();

    return totalRequests == 0 ? 0.0 : (double) totalHits / totalRequests;
  }

  /**
   * Get cache size summary
   *
   * @return Human-readable cache size summary
   */
  public String getCacheSizeSummary() {
    return String.format(
        "DagCache[Block:%d, BlockInfo:%d, Tx:%d, Height:%d, Epoch:%d, HitRate:%.2f%%]",
        blockCache.estimatedSize(),
        blockInfoCache.estimatedSize(),
        transactionCache.estimatedSize(),
        heightToHashCache.estimatedSize(),
        epochWinnerCache.estimatedSize(),
        getOverallHitRate() * 100
    );
  }

  /**
   * Log cache statistics
   */
  public void logStats() {
    log.info("=== DagCache Statistics ===");
    log.info("Block Cache: {}", formatCacheStats(blockCache.stats()));
    log.info("BlockInfo Cache: {}", formatCacheStats(blockInfoCache.stats()));
    log.info("Transaction Cache: {}", formatCacheStats(transactionCache.stats()));
    log.info("Height Cache: {}", formatCacheStats(heightToHashCache.stats()));
    log.info("Epoch Winner Cache: {}", formatCacheStats(epochWinnerCache.stats()));
    log.info("Overall Hit Rate: {}", String.format("%.2f%%", getOverallHitRate() * 100));
    log.info("==========================");
  }

  private String formatCacheStats(CacheStats stats) {
    return String.format(
        "hits=%d, misses=%d, hitRate=%.2f%%, size=%d",
        stats.hitCount(),
        stats.missCount(),
        stats.hitRate() * 100,
        stats.requestCount()
    );
  }
}
