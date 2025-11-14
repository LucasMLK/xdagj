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

package io.xdag.rpc.service;

import io.xdag.Wallet;
import io.xdag.consensus.miner.BlockGenerator;
import io.xdag.consensus.pow.PowAlgorithm;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.core.DagImportResult;
import io.xdag.utils.XdagTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * MiningRpcServiceImpl - Implementation of node mining RPC interface
 *
 * <p>This is the core implementation that allows external pool servers to connect
 * to the XDAG node and coordinate mining activities.
 *
 * <h2>Architecture</h2>
 * <pre>
 * Pool Server (xdagj-pool)
 *      │
 *      │ JSON-RPC / HTTP
 *      ▼
 * MiningRpcServiceImpl ← YOU ARE HERE
 *      ├─> BlockGenerator (generates candidate blocks)
 *      ├─> DagChain (imports mined blocks)
 *      └─> CandidateBlockCache (validates submissions)
 * </pre>
 *
 * <h2>Key Responsibilities</h2>
 * <ul>
 *   <li>Generate candidate blocks for pools to mine</li>
 *   <li>Cache candidate blocks to validate submissions</li>
 *   <li>Validate and import mined blocks from pools</li>
 *   <li>Provide network difficulty and RandomX status</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe and supports multiple concurrent pool connections.
 * The {@link CandidateBlockCache} uses concurrent data structures.
 *
 * <h2>Usage Example</h2>
 * <pre>
 * MiningRpcServiceImpl rpcService = new MiningRpcServiceImpl(dagChain, wallet, powAlgorithm);
 *
 * // Pool fetches candidate block
 * Block candidate = rpcService.getCandidateBlock("pool1");
 *
 * // Pool mines and submits result
 * Block minedBlock = candidate.withNonce(foundNonce);
 * BlockSubmitResult result = rpcService.submitMinedBlock(minedBlock, "pool1");
 * </pre>
 *
 * @since XDAGJ v5.1
 * @see NodeMiningRpcService
 * @see BlockGenerator
 * @see CandidateBlockCache
 */
@Slf4j
public class MiningRpcServiceImpl implements NodeMiningRpcService {

    // ========== Dependencies ==========

    private final DagChain dagChain;
    private final BlockGenerator blockGenerator;
    private final PowAlgorithm powAlgorithm;

    // ========== State ==========

    /**
     * Cache of candidate blocks provided to pools
     */
    private final CandidateBlockCache blockCache;

    // ========== Constructor ==========

    /**
     * Create a new MiningRpcServiceImpl
     *
     * @param dagChain DagChain for block operations
     * @param wallet Wallet for coinbase addresses
     * @param powAlgorithm PoW algorithm instance (can be null if not using RandomX)
     */
    public MiningRpcServiceImpl(DagChain dagChain, Wallet wallet, PowAlgorithm powAlgorithm) {
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

        log.info("MiningRpcServiceImpl initialized");
    }

    // ========== RPC Interface Implementation ==========

    @Override
    public Block getCandidateBlock(String poolId) {
        try {
            log.info("Pool '{}' requesting candidate block", poolId);

            // Generate candidate block via BlockGenerator
            Block candidate = blockGenerator.generateCandidate();

            if (candidate == null) {
                log.error("Failed to generate candidate block for pool '{}'", poolId);
                return null;
            }

            // Cache the candidate block (keyed by hash without nonce)
            // Note: We use the block's hash as the cache key since the nonce will change
            Bytes32 cacheKey = calculateHashWithoutNonce(candidate);
            blockCache.put(cacheKey, candidate);

            log.info("Provided candidate block to pool '{}': hash={}, epoch={}, cache_size={}",
                    poolId,
                    candidate.getHash().toHexString().substring(0, 18) + "...",
                    XdagTime.getEpoch(candidate.getTimestamp()),
                    blockCache.size());

            return candidate;

        } catch (Exception e) {
            log.error("Error generating candidate block for pool '{}'", poolId, e);
            return null;
        }
    }

    @Override
    public BlockSubmitResult submitMinedBlock(Block block, String poolId) {
        try {
            log.info("Pool '{}' submitting mined block: hash={}",
                    poolId,
                    block.getHash().toHexString().substring(0, 18) + "...");

            // Step 1: Validate that block is based on a known candidate
            Bytes32 hashWithoutNonce = calculateHashWithoutNonce(block);
            if (!blockCache.contains(hashWithoutNonce)) {
                log.warn("Pool '{}' submitted unknown block (not based on our candidate)", poolId);
                return BlockSubmitResult.rejected("Unknown candidate block", "UNKNOWN_CANDIDATE");
            }

            // Step 2: Import block to blockchain via DagChain
            DagImportResult importResult = dagChain.tryToConnect(block);

            // Step 3: Process result
            if (importResult.isSuccess()) {
                log.info("✓ Block from pool '{}' accepted and imported: hash={}",
                        poolId, block.getHash().toHexString().substring(0, 18) + "...");

                // Remove from cache (block is now on-chain)
                blockCache.remove(hashWithoutNonce);

                return BlockSubmitResult.accepted(block.getHash());

            } else {
                log.warn("✗ Block from pool '{}' rejected: {}",
                        poolId, importResult.getErrorMessage());

                return BlockSubmitResult.rejected(
                        importResult.getErrorMessage() != null ? importResult.getErrorMessage() : "Import failed",
                        "IMPORT_FAILED"
                );
            }

        } catch (Exception e) {
            log.error("Error processing submitted block from pool '{}'", poolId, e);
            return BlockSubmitResult.rejected("Internal error: " + e.getMessage(), "INTERNAL_ERROR");
        }
    }

    @Override
    public UInt256 getCurrentDifficultyTarget() {
        try {
            // Get current difficulty from chain stats
            UInt256 difficulty = dagChain.getChainStats().getBaseDifficultyTarget();

            log.debug("Current difficulty target: {}", difficulty.toHexString().substring(0, 18) + "...");

            return difficulty;

        } catch (Exception e) {
            log.error("Error getting current difficulty", e);
            // Return a default high difficulty on error
            return UInt256.MAX_VALUE;
        }
    }

    @Override
    public RandomXInfo getRandomXInfo() {
        try {
            long currentEpoch = XdagTime.getEpoch(XdagTime.getCurrentTimestamp());

            // Check if RandomX is enabled
            if (powAlgorithm == null) {
                log.debug("RandomX not enabled, returning disabled status");
                return RandomXInfo.disabled(currentEpoch);
            }

            // Get RandomX fork epoch from constants
            // Note: In production, this should come from network configuration
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
            long currentEpoch = XdagTime.getEpoch(XdagTime.getCurrentTimestamp());
            return RandomXInfo.disabled(currentEpoch);
        }
    }

    // ========== Helper Methods ==========

    /**
     * Calculate hash of block without considering the nonce
     *
     * <p>This is used as a cache key to identify candidate blocks.
     * When a pool submits a mined block with a different nonce,
     * we can still identify which candidate it was based on.
     *
     * <p><strong>Implementation Note</strong>:
     * In XDAG, the block structure before nonce is deterministic.
     * We use the candidate block's original hash as the key, since
     * the nonce is the only thing that changes during mining.
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
        return String.format(
                "CandidateBlockCache: %d entries cached",
                blockCache.size()
        );
    }

    /**
     * Clear the candidate block cache
     *
     * <p>This is useful for testing or when resetting the node.
     */
    public void clearCache() {
        blockCache.clear();
        log.info("Candidate block cache cleared");
    }

    /**
     * Get the block generator instance
     *
     * @return BlockGenerator instance
     */
    public BlockGenerator getBlockGenerator() {
        return blockGenerator;
    }

    /**
     * Get the DagChain instance
     *
     * @return DagChain instance
     */
    public DagChain getDagChain() {
        return dagChain;
    }
}
