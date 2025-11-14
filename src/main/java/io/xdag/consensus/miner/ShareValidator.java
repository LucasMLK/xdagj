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

package io.xdag.consensus.miner;

import io.xdag.consensus.pow.HashContext;
import io.xdag.consensus.pow.PowAlgorithm;
import io.xdag.core.Block;
import io.xdag.crypto.hash.XdagSha256Digest;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ShareValidator - Pool-specific share validation (DEPRECATED)
 *
 * <p><strong>⚠️ DEPRECATION NOTICE</strong>:
 * This class implements <strong>pool-specific functionality</strong> that is deprecated and will be
 * removed in version <strong>0.9.0</strong>. Share validation logic should be moved to the
 * standalone <strong>xdagj-pool</strong> project.
 *
 * <h2>Why Deprecated?</h2>
 * <p>Share validation is pool server functionality, not blockchain node functionality:
 * <ul>
 *   <li>❌ Only relevant for pool servers managing multiple miners</li>
 *   <li>❌ Blockchain nodes don't need to track "best shares"</li>
 *   <li>❌ Couples pool logic with node implementation</li>
 *   <li>❌ Should be in separate pool server project</li>
 * </ul>
 *
 * <h2>Migration Path</h2>
 * <p><strong>OLD (Deprecated)</strong>:
 * <pre>
 * // Node has internal pool with share validation
 * ShareValidator validator = new ShareValidator(powAlgorithm);
 * boolean isBest = validator.validateShare(nonce, task);
 * </pre>
 *
 * <p><strong>NEW (Recommended)</strong>:
 * <pre>
 * // Separate pool server (xdagj-pool) validates shares
 * // Node only receives final mined blocks via RPC
 *
 * // In xdagj-pool project:
 * PoolShareValidator validator = new PoolShareValidator(difficulty);
 * boolean isValid = validator.validateWorkerShare(nonce, target);
 *
 * // When block found, submit to node:
 * Block minedBlock = buildBlock(bestNonce);
 * nodeRpcClient.submitMinedBlock(minedBlock);
 * </pre>
 *
 * <h2>Temporary Usage (Testing Only)</h2>
 * <p>For development and testing, you can still use ShareValidator, but be aware:
 * <ul>
 *   <li>⚠️ Not recommended for production</li>
 *   <li>⚠️ Will be removed in v0.9.0</li>
 *   <li>⚠️ No new features will be added</li>
 *   <li>⚠️ Bugs may not be fixed</li>
 * </ul>
 *
 * <h2>What This Class Does</h2>
 * <ul>
 *   <li>Validates mining shares (nonces) submitted by miners</li>
 *   <li>Calculates hash using RandomX or SHA256</li>
 *   <li>Tracks the best (lowest) hash found so far</li>
 *   <li>Creates mined block with best nonce</li>
 * </ul>
 *
 * <h2>Timeline</h2>
 * <ul>
 *   <li><strong>v0.8.2</strong>: Marked as @Deprecated (current)</li>
 *   <li><strong>v0.8.3</strong>: xdagj-pool project available with equivalent functionality</li>
 *   <li><strong>v0.9.0</strong>: ShareValidator removed (breaking change)</li>
 * </ul>
 *
 * @since XDAGJ v5.1
 * @deprecated Since v0.8.2, scheduled for removal in v0.9.0.
 *             Use pool-specific share validation in xdagj-pool project instead.
 * @see io.xdag.rpc.service.MiningRpcServiceImpl
 * @see io.xdag.consensus.miner.MiningManager
 */
@Deprecated(since = "0.8.2", forRemoval = true)
@Slf4j
public class ShareValidator {

    private final PowAlgorithm powAlgorithm;

    /**
     * Best share (nonce) found so far
     */
    @Getter
    private final AtomicReference<Bytes32> bestShare = new AtomicReference<>(Bytes32.ZERO);

    /**
     * Best hash found so far (initialized to max value)
     */
    @Getter
    private final AtomicReference<Bytes32> bestHash = new AtomicReference<>(
            Bytes32.fromHexString("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")
    );

    /**
     * Total number of shares validated
     */
    @Getter
    private final AtomicLong totalSharesValidated = new AtomicLong(0);

    /**
     * Number of shares that were better than previous best
     */
    @Getter
    private final AtomicLong improvedSharesCount = new AtomicLong(0);

    /**
     * Create a new ShareValidator
     *
     * @param powAlgorithm PoW algorithm instance (can be null if not using RandomX)
     */
    public ShareValidator(PowAlgorithm powAlgorithm) {
        this.powAlgorithm = powAlgorithm;
    }

    /**
     * Validate a mining share and update best share if this is better
     *
     * <p>This method:
     * <ol>
     *   <li>Calculates hash based on POW algorithm (RandomX or SHA256)</li>
     *   <li>Compares with current best hash</li>
     *   <li>Updates best share/hash if this is better</li>
     *   <li>Returns true if this is the new best</li>
     * </ol>
     *
     * <p>Thread-safe: Can be called concurrently from multiple threads.
     *
     * @param nonce Nonce (share) to validate
     * @param task Mining task context
     * @return true if this share is better than previous best, false otherwise
     * @throws IllegalArgumentException if nonce or task is null
     */
    public boolean validateShare(Bytes32 nonce, MiningTask task) {
        if (nonce == null) {
            throw new IllegalArgumentException("Nonce cannot be null");
        }
        if (task == null) {
            throw new IllegalArgumentException("MiningTask cannot be null");
        }

        // Increment validation counter
        totalSharesValidated.incrementAndGet();

        // Calculate hash for this share
        Bytes32 hash = calculateHash(nonce, task);

        // Check if this is better than current best
        boolean isBetter = false;

        // Use compareAndSet loop for thread-safe update
        while (true) {
            Bytes32 currentBestHash = bestHash.get();

            // Compare hashes (lower is better in POW)
            if (hash.compareTo(currentBestHash) < 0) {
                // This share is better, try to update
                if (bestHash.compareAndSet(currentBestHash, hash)) {
                    // Successfully updated best hash, now update best share
                    bestShare.set(nonce);
                    improvedSharesCount.incrementAndGet();
                    isBetter = true;

                    log.debug("New best share: hash={}, nonce={}",
                            hash.toHexString(),
                            nonce.toHexString());
                    break;
                }
                // CAS failed, another thread updated it, retry
            } else {
                // This share is not better, no need to update
                break;
            }
        }

        return isBetter;
    }

    /**
     * Calculate hash for a given nonce and mining task
     *
     * <p>Selects the appropriate POW algorithm based on the task:
     * <ul>
     *   <li>RandomX: For blocks after RandomX fork</li>
     *   <li>SHA256: For blocks before RandomX fork</li>
     * </ul>
     *
     * @param nonce Nonce to hash
     * @param task Mining task containing pre-hash and algorithm info
     * @return Bytes32 resulting hash
     */
    private Bytes32 calculateHash(Bytes32 nonce, MiningTask task) {
        if (task.isRandomX()) {
            return calculateRandomXHash(nonce, task);
        } else {
            return calculateSHA256Hash(nonce, task);
        }
    }

    /**
     * Calculate RandomX hash
     *
     * <p>Formula: hash = RandomX(blockData, timestamp)
     *
     * @param nonce Nonce to hash
     * @param task Mining task containing RandomX seed and pre-hash
     * @return Bytes32 RandomX hash
     */
    private Bytes32 calculateRandomXHash(Bytes32 nonce, MiningTask task) {
        if (powAlgorithm == null) {
            throw new IllegalStateException("PoW algorithm not available for RandomX task");
        }

        try {
            // Get candidate block and create block data with new nonce
            Block candidate = task.getCandidateBlock();
            Block blockWithNonce = candidate.withNonce(nonce);
            byte[] blockData = blockWithNonce.toBytes();

            // Create hash context with block timestamp
            HashContext context = HashContext.forMining(task.getTimestamp());

            // Calculate hash using PoW algorithm
            byte[] hashBytes = powAlgorithm.calculateBlockHash(blockData, context);

            if (hashBytes == null) {
                log.warn("PoW hash calculation returned null for timestamp {}", task.getTimestamp());
                // Return max value (worst possible hash)
                return Bytes32.fromHexString("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
            }

            return Bytes32.wrap(hashBytes);

        } catch (Exception e) {
            log.error("Failed to calculate RandomX hash", e);
            // Return max value (worst possible hash)
            return Bytes32.fromHexString("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        }
    }

    /**
     * Calculate SHA256 hash
     *
     * <p>Formula: hash = SHA256(block data with new nonce)
     *
     * @param nonce Nonce to hash
     * @param task Mining task containing candidate block
     * @return Bytes32 SHA256 hash
     */
    private Bytes32 calculateSHA256Hash(Bytes32 nonce, MiningTask task) {
        try {
            // Get candidate block and create block data with new nonce
            Block candidate = task.getCandidateBlock();
            Block blockWithNonce = candidate.withNonce(nonce);

            // Get the block's hash (Block.getHash() calculates SHA256)
            return blockWithNonce.getHash();

        } catch (Exception e) {
            log.error("Failed to calculate SHA256 hash", e);
            // Return max value (worst possible hash)
            return Bytes32.fromHexString("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        }
    }

    /**
     * Create a mined block with the best share found
     *
     * <p>This method takes the mining task's candidate block and returns
     * a new block with the best nonce set.
     *
     * @param task Mining task containing candidate block
     * @return Block mined block with best nonce, or null if no valid share found
     */
    public Block createMinedBlock(MiningTask task) {
        if (task == null) {
            throw new IllegalArgumentException("MiningTask cannot be null");
        }

        Bytes32 nonce = bestShare.get();
        if (nonce == null || nonce.equals(Bytes32.ZERO)) {
            log.warn("No valid share found yet, cannot create mined block");
            return null;
        }

        // Get candidate block from task
        Block candidate = task.getCandidateBlock();

        // Set the best nonce (Block is immutable, returns new instance)
        Block minedBlock = candidate.withNonce(nonce);

        log.info("Created mined block: hash={}, nonce={}",
                minedBlock.getHash().toHexString(),
                nonce.toHexString());

        return minedBlock;
    }

    /**
     * Reset the validator state
     *
     * <p>Clears best share/hash and resets counters. Used when starting
     * a new mining task.
     */
    public void reset() {
        bestShare.set(Bytes32.ZERO);
        bestHash.set(Bytes32.fromHexString("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"));
        totalSharesValidated.set(0);
        improvedSharesCount.set(0);

        log.debug("ShareValidator reset");
    }

    /**
     * Check if a valid share has been found
     *
     * @return true if at least one valid share has been found
     */
    public boolean hasValidShare() {
        Bytes32 nonce = bestShare.get();
        return nonce != null && !nonce.equals(Bytes32.ZERO);
    }

    /**
     * Get validation statistics summary
     *
     * @return Human-readable statistics
     */
    public String getStatistics() {
        long total = totalSharesValidated.get();
        long improved = improvedSharesCount.get();
        double improveRate = total > 0 ? (improved * 100.0 / total) : 0.0;

        return String.format(
                "ShareValidator Stats: total=%d, improved=%d (%.2f%%), bestHash=%s",
                total,
                improved,
                improveRate,
                bestHash.get().toHexString().substring(0, 18) + "..."
        );
    }
}
