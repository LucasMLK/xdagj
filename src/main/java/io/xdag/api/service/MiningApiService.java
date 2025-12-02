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

package io.xdag.api.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.xdag.Wallet;
import io.xdag.api.service.dto.BlockSubmitResult;
import io.xdag.api.service.dto.RandomXInfo;
import io.xdag.consensus.epoch.EpochConsensusManager;
import io.xdag.consensus.miner.BlockGenerator;
import io.xdag.consensus.pow.PowAlgorithm;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.core.DagImportResult;
import io.xdag.p2p.SyncManager;
import io.xdag.utils.TimeUtils;
import java.time.Duration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * MiningApiService - Node mining API service for pool server integration
 *
 * <p>This service allows external pool servers to connect to the XDAG node
 * and coordinate mining activities via HTTP API.
 *
 * <h2>Architecture (with Epoch Consensus)</h2>
 * <pre>
 * Pool Server (xdagj-pool)
 *      │
 *      │ HTTP + JSON
 *      ▼
 * MiningApiService ← YOU ARE HERE
 *      ├─> BlockGenerator (generates candidate blocks)
 *      ├─> EpochConsensusManager (collects solutions, selects best at epoch end)
 *      │   ├─> SolutionCollector (validates and collects submissions)
 *      │   ├─> BestSolutionSelector (picks highest difficulty)
 *      │   └─> BackupMiner (forces block if no solutions)
 *      ├─> DagChain (imports winning block at epoch boundary)
 *      └─> CandidateBlockCache (validates submissions)
 * </pre>
 *
 * <h2>Consensus Behavior</h2>
 * <ul>
 *   <li><b>With EpochConsensusManager</b>: Solutions collected during 64s epoch, best wins at T=64s</li>
 *   <li><b>Without EpochConsensusManager</b>: Legacy immediate import (first valid solution wins)</li>
 * </ul>
 *
 * <h2>Key Responsibilities</h2>
 * <ul>
 *   <li>Generate candidate blocks for pools to mine</li>
 *   <li>Cache candidate blocks to validate submissions</li>
 *   <li>Submit solutions to EpochConsensusManager (or direct import for legacy)</li>
 *   <li>Provide network difficulty and RandomX status</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe and supports multiple concurrent pool connections.
 *
 * <h2>Usage Example</h2>
 * <pre>
 * MiningApiService apiService = new MiningApiService(dagChain, wallet, powAlgorithm);
 * apiService.setEpochConsensusManager(epochConsensusManager); // Enable epoch consensus
 *
 * // Pool fetches candidate block
 * Block candidate = apiService.getCandidateBlock("pool1");
 *
 * // Pool mines and submits result
 * Block minedBlock = candidate.withNonce(foundNonce);
 * BlockSubmitResult result = apiService.submitMinedBlock(minedBlock, "pool1");
 * // Result: "Solution collected for epoch N, will be processed at epoch end"
 * </pre>
 *
 * @since XDAGJ 1.0
 */
@Slf4j
public class MiningApiService {

  // ========== Dependencies ==========


  @Getter
  private final DagChain dagChain;

  @Getter
  private final BlockGenerator blockGenerator;
  private final PowAlgorithm powAlgorithm;

  /**
   * Epoch consensus manager for solution collection (optional, for BUG-CONSENSUS fix)
   * If null, falls back to legacy immediate import behavior
   */
  private volatile EpochConsensusManager epochConsensusManager;

  /**
   * Sync manager for checking synchronization status (BUG-SYNC-001 fix)
   * Mining is blocked until node is synchronized with the network
   */
  private volatile SyncManager syncManager;

  // ========== State ==========

  /**
   * Cache of candidate blocks provided to pools
   */
  private final CandidateBlockCache blockCache;

  // ========== Constructor ==========

  /**
   * Create a new MiningApiService
   *
   * @param dagChain     DagChain for block operations
   * @param wallet       Wallet for coinbase addresses
   * @param powAlgorithm PoW algorithm instance (can be null if not using RandomX)
   */
  public MiningApiService(DagChain dagChain, Wallet wallet, PowAlgorithm powAlgorithm) {
    if (dagChain == null) {
      throw new IllegalArgumentException("DagChain cannot be null");
    }
    if (wallet == null) {
      throw new IllegalArgumentException("Wallet cannot be null");
    }

    this.dagChain = dagChain;
    this.powAlgorithm = powAlgorithm;
    this.blockGenerator = new BlockGenerator(dagChain, wallet, powAlgorithm);
    this.blockCache = new CandidateBlockCache();

    log.info("MiningApiService initialized");
  }

  /**
   * Set the epoch consensus manager (for BUG-CONSENSUS fix integration)
   *
   * <p>This method is called by DagKernel during initialization to enable
   * the new epoch-based consensus mechanism. If not set, the service falls
   * back to legacy immediate import behavior.
   *
   * @param consensusManager The EpochConsensusManager instance
   */
  public void setEpochConsensusManager(EpochConsensusManager consensusManager) {
    this.epochConsensusManager = consensusManager;
    if (consensusManager != null) {
      log.debug("Epoch consensus manager enabled");
    } else {
      log.info("Epoch consensus manager disabled - using legacy immediate import");
    }
  }

  /**
   * Set the sync manager (for BUG-SYNC-001 fix)
   *
   * <p>This method is called by DagKernel during initialization to enable
   * synchronization checks before mining.
   *
   * @param syncManager The SyncManager instance
   */
  public void setSyncManager(SyncManager syncManager) {
    this.syncManager = syncManager;
    if (syncManager != null) {
      log.debug("SyncManager enabled - mining blocked until synchronized");
    }
  }

  // ========== API Methods ==========

  /**
   * Get current candidate block for mining
   *
   * <p>Pool servers call this method periodically (typically every 64 seconds at epoch start)
   * to fetch a fresh candidate block to mine.
   *
   * @param poolId Unique identifier for the pool (for logging and authentication)
   * @return Candidate block ready for mining, or null if unable to generate
   */
  public Block getCandidateBlock(String poolId) {
    try {
      log.info("Pool '{}' requesting candidate block", poolId);

      // BUG-SYNC-001 fix: Block mining until synchronized
      if (syncManager != null && !syncManager.isSynchronized()) {
        log.debug("Pool '{}' cannot get candidate: node not synchronized " +
            "(localEpoch={}, remoteEpoch={}; localHeight={}, remoteHeight={})",
            poolId, syncManager.getLocalTipEpoch(), syncManager.getRemoteTipEpoch(),
            dagChain.getMainChainLength(), syncManager.getRemoteTipHeight());
        return null;
      }

      // Generate candidate block via BlockGenerator
      Block candidate = blockGenerator.generateCandidate();

      if (candidate == null) {
        log.error("Failed to generate candidate block for pool '{}'", poolId);
        return null;
      }

      // Cache the candidate block (keyed by hash without nonce)
      Bytes32 cacheKey = calculateHashWithoutNonce(candidate);
      blockCache.put(cacheKey, candidate);

      log.info("Provided candidate block to pool '{}': hash={}, epoch={}, cache_size={}",
          poolId,
          candidate.getHash().toHexString().substring(0, 18) + "...",
          candidate.getEpoch(),
          blockCache.size());

      return candidate;

    } catch (Exception e) {
      log.error("Error generating candidate block for pool '{}'", poolId, e);
      return null;
    }
  }

  /**
   * Submit a mined block to the node
   *
   * <p>Pool servers call this method when they have found a valid block (best share
   * with nonce filled in).
   *
   * @param block  The mined block with nonce filled in
   * @param poolId Unique identifier for the pool submitting the block
   * @return Result indicating success or failure reason
   */
  public BlockSubmitResult submitMinedBlock(Block block, String poolId) {
    try {
      log.info("Pool '{}' submitting mined block: hash={}",
          poolId,
          block.getHash().toHexString().substring(0, 18) + "...");

      // BUG-SYNC-001 fix: Reject mining submissions until synchronized
      if (syncManager != null && !syncManager.isSynchronized()) {
        log.debug("Pool '{}' submission rejected: node not synchronized " +
            "(localEpoch={}, remoteEpoch={}; localHeight={}, remoteHeight={})",
            poolId, syncManager.getLocalTipEpoch(), syncManager.getRemoteTipEpoch(),
            dagChain.getMainChainLength(), syncManager.getRemoteTipHeight());
        return BlockSubmitResult.rejected("Node not synchronized", "NOT_SYNCHRONIZED");
      }

      // Step 1: Validate that block is based on a known candidate
      Bytes32 hashWithoutNonce = calculateHashWithoutNonce(block);
      if (!blockCache.contains(hashWithoutNonce)) {
        log.warn("Pool '{}' submitted unknown block (not based on our candidate)", poolId);
        return BlockSubmitResult.rejected("Unknown candidate block", "UNKNOWN_CANDIDATE");
      }

      // Step 2: Check if epoch consensus is enabled
      if (epochConsensusManager != null && epochConsensusManager.isRunning()) {
        // NEW: Submit to EpochConsensusManager for collection (BUG-CONSENSUS-002 fix)
        io.xdag.consensus.epoch.SubmitResult result = epochConsensusManager.submitSolution(block, poolId);

        if (result.isAccepted()) {
          log.debug("Solution from pool '{}' collected for epoch processing", poolId);
          return BlockSubmitResult.accepted(block.getHash());
        } else {
          log.debug("Solution from pool '{}' rejected: {}", poolId, result.getErrorMessage());
          return BlockSubmitResult.rejected(result.getErrorMessage(), "REJECTED");
        }

      } else {
        // LEGACY: Immediate import (old behavior)
        log.debug("Using legacy immediate import for pool '{}'", poolId);
        DagImportResult importResult = dagChain.tryToConnect(block);

        // Step 3: Process result
        if (importResult.isSuccess()) {
          log.info("Block from pool '{}' accepted: hash={}",
              poolId, block.getHash().toHexString().substring(0, 18) + "...");

          // Remove from cache (block is now on-chain)
          blockCache.remove(hashWithoutNonce);

          return BlockSubmitResult.accepted(block.getHash());

        } else {
          log.debug("Block from pool '{}' rejected: {}",
              poolId, importResult.getErrorMessage());

          return BlockSubmitResult.rejected(
              importResult.getErrorMessage() != null ? importResult.getErrorMessage()
                  : "Import failed",
              "IMPORT_FAILED"
          );
        }
      }

    } catch (Exception e) {
      log.error("Error processing submitted block from pool '{}'", poolId, e);
      return BlockSubmitResult.rejected("Internal error: " + e.getMessage(), "INTERNAL_ERROR");
    }
  }

  /**
   * Get current network difficulty target
   *
   * @return Current difficulty target as UInt256, or null if unable to retrieve
   */
  public UInt256 getCurrentDifficultyTarget() {
    try {
      // Get current difficulty from chain stats
      UInt256 difficulty = dagChain.getChainStats().getBaseDifficultyTarget();

      log.debug("Current difficulty target: {}", difficulty.toHexString().substring(0, 18) + "...");

      return difficulty;

    } catch (Exception e) {
      // BUGFIX (BUG-028): Return null instead of UInt256.MAX_VALUE on error
      // Previously: Returned MAX_VALUE (lowest difficulty = easiest mining) which is wrong
      // Now: Return null to signal error - callers must handle null case
      // MAX_VALUE would allow any hash to pass difficulty check, causing invalid mining
      log.error("Error getting current difficulty, returning null", e);
      return null;
    }
  }

  /**
   * Get RandomX mining information
   *
   * @return RandomX status information
   */
  public RandomXInfo getRandomXInfo() {
    try {
      long currentEpoch = TimeUtils.getCurrentEpochNumber();

      // Check if RandomX is enabled
      if (powAlgorithm == null) {
        log.debug("RandomX not enabled, returning disabled status");
        return RandomXInfo.disabled(currentEpoch);
      }

      // Get RandomX fork epoch from constants
      long forkEpoch = io.xdag.config.RandomXConstants.RANDOMX_FORK_HEIGHT
          / io.xdag.config.RandomXConstants.SEEDHASH_EPOCH_BLOCKS;

      boolean isActive = powAlgorithm.isActive(currentEpoch);
      boolean vmReady = powAlgorithm.isReady();

      log.debug("RandomX info: currentEpoch={}, forkEpoch={}, active={}, vmReady={}",
          currentEpoch, forkEpoch, isActive, vmReady);

      if (isActive) {
        return RandomXInfo.postFork(currentEpoch, forkEpoch, vmReady);
      } else {
        return RandomXInfo.preFork(currentEpoch, forkEpoch);
      }

    } catch (Exception e) {
      log.error("Error getting RandomX info", e);
      // Return safe default
      long currentEpoch = TimeUtils.getCurrentEpochNumber();
      return RandomXInfo.disabled(currentEpoch);
    }
  }

  // ========== Helper Methods ==========

  /**
   * Calculate hash of block without considering the nonce
   *
   * <p>This is used as a cache key to identify candidate blocks.
   * When a pool submits a mined block with a different nonce, we can still identify which candidate
   * it was based on.
   *
   * @param block Block to calculate hash for
   * @return Hash without nonce consideration
   */
  private Bytes32 calculateHashWithoutNonce(Block block) {
    // Create a copy of the block with zeroed nonce
    Block blockWithoutNonce = block.withNonce(Bytes32.ZERO);
    return blockWithoutNonce.getHash();
  }

  // ========== Statistics and Monitoring ==========

  /**
   * Get cache statistics
   *
   * @return Human-readable cache stats
   */
  public String getCacheStatistics() {
    return blockCache.getStatisticsSummary();
  }

  /**
   * Clear the candidate block cache
   *
   * <p>This is useful for testing or when resetting the node.
   */
  public void clearCache() {
    blockCache.clear();
  }

  // ========== Inner Class: CandidateBlockCache ==========

  /**
   * CandidateBlockCache - Caffeine-based cache for candidate blocks
   *
   * <p>This cache stores candidate blocks that have been provided to pool servers.
   * When pools submit mined blocks, the node validates that they are based on known candidate
   * blocks.
   *
   * <h2>Why Cache Candidate Blocks?</h2>
   * <p>Pool servers may submit mined blocks at any time. The node needs to verify that:
   * <ul>
   *   <li>The block was based on a candidate block we provided</li>
   *   <li>The pool didn't create arbitrary blocks</li>
   *   <li>The block structure matches what we sent</li>
   * </ul>
   *
   * <h2>Cache Configuration</h2>
   * <pre>
   * Max Size:  100 entries (~1.7 hours at 64s/epoch)
   * TTL:       192 seconds (3 epochs)
   * Eviction:  LRU (via Caffeine TinyLFU)
   * Stats:     Enabled (hit rate, eviction count, etc.)
   * </pre>
   *
   * <h2>Why 3 Epochs TTL?</h2>
   * <ul>
   *   <li>1 epoch = 64s (normal mining time)</li>
   *   <li>2-3 epochs = 128-192s (allows delayed submissions)</li>
   *   <li>Beyond 3 epochs, candidate is definitely stale</li>
   * </ul>
   *
   * @since XDAGJ 1.0
   */
  private static class CandidateBlockCache {

    /**
     * Caffeine cache: hashWithoutNonce → candidate block
     */
    private final Cache<Bytes32, Block> cache;

    /**
     * Initialize cache with Caffeine
     */
    CandidateBlockCache() {
      this.cache = Caffeine.newBuilder()
          .maximumSize(100)  // Max 100 candidate blocks (~1.7 hours)
          .expireAfterWrite(Duration.ofSeconds(192))  // 3 epochs TTL
          .recordStats()  // Enable statistics
          .build();

      log.info("CandidateBlockCache initialized: max=100, ttl=192s, eviction=LRU");
    }

    /**
     * Add a candidate block to the cache
     *
     * @param hashWithoutNonce Hash of block before nonce is filled in
     * @param candidateBlock   The candidate block
     */
    void put(Bytes32 hashWithoutNonce, Block candidateBlock) {
      if (hashWithoutNonce == null || candidateBlock == null) {
        log.warn("Attempted to cache null candidate block");
        return;
      }

      cache.put(hashWithoutNonce, candidateBlock);

      log.debug("Cached candidate block: hash={}, cache_size={}",
          hashWithoutNonce.toHexString().substring(0, 16) + "...",
          cache.estimatedSize());
    }

    /**
     * Check if a candidate block exists in the cache
     *
     * @param hashWithoutNonce Hash of block before nonce
     * @return true if candidate is in cache, false otherwise
     */
    boolean contains(Bytes32 hashWithoutNonce) {
      return hashWithoutNonce != null && cache.getIfPresent(hashWithoutNonce) != null;
    }

    /**
     * Get a candidate block from the cache
     *
     * @param hashWithoutNonce Hash of block before nonce
     * @return Candidate block, or null if not found
     */
    Block get(Bytes32 hashWithoutNonce) {
      return hashWithoutNonce != null ? cache.getIfPresent(hashWithoutNonce) : null;
    }

    /**
     * Remove a candidate block from the cache
     *
     * @param hashWithoutNonce Hash of block before nonce
     * @return The removed block, or null if not found
     */
    Block remove(Bytes32 hashWithoutNonce) {
      if (hashWithoutNonce == null) {
        return null;
      }
      Block block = cache.getIfPresent(hashWithoutNonce);
      cache.invalidate(hashWithoutNonce);
      return block;
    }

    /**
     * Clear all cached candidate blocks
     */
    void clear() {
      cache.invalidateAll();
      log.info("Cleared candidate block cache");
    }

    /**
     * Get current cache size
     *
     * @return Number of cached candidate blocks
     */
    int size() {
      return (int) cache.estimatedSize();
    }

    /**
     * Get cache statistics
     *
     * @return Caffeine CacheStats
     */
    CacheStats getStats() {
      return cache.stats();
    }

    /**
     * Get detailed statistics summary
     *
     * @return Human-readable statistics
     */
    String getStatisticsSummary() {
      CacheStats stats = cache.stats();
      return String.format(
          "CandidateBlockCache[size:%d, hits:%d, misses:%d, hitRate:%.2f%%, evictions:%d]",
          cache.estimatedSize(),
          stats.hitCount(),
          stats.missCount(),
          stats.hitRate() * 100,
          stats.evictionCount()
      );
    }
  }
}
