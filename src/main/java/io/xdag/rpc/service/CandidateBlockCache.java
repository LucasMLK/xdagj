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

import io.xdag.core.Block;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CandidateBlockCache - Cache for candidate blocks provided to pools
 *
 * <p>This cache stores candidate blocks that have been provided to pool servers
 * via {@link NodeMiningRpcService#getCandidateBlock(String)}. When pools submit
 * mined blocks, the node validates that they are based on known candidate blocks.
 *
 * <h2>Why Cache Candidate Blocks?</h2>
 * <p>Pool servers may submit mined blocks at any time. The node needs to verify that:
 * <ul>
 *   <li>The block was based on a candidate block we provided</li>
 *   <li>The pool didn't create arbitrary blocks</li>
 *   <li>The block structure matches what we sent</li>
 * </ul>
 *
 * <h2>Cache Key</h2>
 * <p>Candidate blocks are cached by their "hash without nonce" - the hash of the
 * block before any nonce is filled in. When a pool submits a mined block with a
 * nonce, we can calculate its pre-nonce hash and look it up in the cache.
 *
 * <h2>Cache Lifecycle</h2>
 * <pre>
 * 1. Node generates candidate block (epoch start)
 * 2. Pool fetches candidate → cache.put(hashWithoutNonce, block)
 * 3. Pool mines and submits block → cache.contains(hashWithoutNonce) to validate
 * 4. Old entries expire after MAX_CACHE_SIZE reached
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe and can be accessed by multiple pool connections
 * simultaneously.
 *
 * @since XDAGJ v5.1
 * @see NodeMiningRpcService
 * @see MiningRpcServiceImpl
 */
@Slf4j
public class CandidateBlockCache {

    /**
     * Maximum number of candidate blocks to cache
     * (enough for ~100 epochs = ~1.7 hours at 64s/epoch)
     */
    private static final int MAX_CACHE_SIZE = 100;

    /**
     * Cache: hashWithoutNonce → candidate block
     */
    private final Map<Bytes32, Block> cache = new ConcurrentHashMap<>();

    /**
     * Add a candidate block to the cache
     *
     * <p>Stores the block keyed by its hash without nonce. This allows validation
     * of submitted blocks later.
     *
     * @param hashWithoutNonce Hash of block before nonce is filled in
     * @param candidateBlock The candidate block
     */
    public void put(Bytes32 hashWithoutNonce, Block candidateBlock) {
        if (hashWithoutNonce == null || candidateBlock == null) {
            log.warn("Attempted to cache null candidate block");
            return;
        }

        // Evict oldest entries if cache is full
        if (cache.size() >= MAX_CACHE_SIZE) {
            evictOldest();
        }

        cache.put(hashWithoutNonce, candidateBlock);

        log.debug("Cached candidate block: hash={}, cache_size={}",
                hashWithoutNonce.toHexString().substring(0, 16) + "...",
                cache.size());
    }

    /**
     * Check if a candidate block exists in the cache
     *
     * @param hashWithoutNonce Hash of block before nonce
     * @return true if candidate is in cache, false otherwise
     */
    public boolean contains(Bytes32 hashWithoutNonce) {
        return hashWithoutNonce != null && cache.containsKey(hashWithoutNonce);
    }

    /**
     * Get a candidate block from the cache
     *
     * @param hashWithoutNonce Hash of block before nonce
     * @return Candidate block, or null if not found
     */
    public Block get(Bytes32 hashWithoutNonce) {
        if (hashWithoutNonce == null) {
            return null;
        }
        return cache.get(hashWithoutNonce);
    }

    /**
     * Remove a candidate block from the cache
     *
     * @param hashWithoutNonce Hash of block before nonce
     * @return The removed block, or null if not found
     */
    public Block remove(Bytes32 hashWithoutNonce) {
        if (hashWithoutNonce == null) {
            return null;
        }
        return cache.remove(hashWithoutNonce);
    }

    /**
     * Clear all cached candidate blocks
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
        log.info("Cleared candidate block cache ({} entries removed)", size);
    }

    /**
     * Get current cache size
     *
     * @return Number of cached candidate blocks
     */
    public int size() {
        return cache.size();
    }

    /**
     * Evict oldest entries from cache
     *
     * <p>Removes approximately 20% of entries when cache is full.
     * In a concurrent HashMap, we don't have guaranteed ordering,
     * so we just remove the first N entries we encounter.
     */
    private void evictOldest() {
        int toRemove = MAX_CACHE_SIZE / 5;  // Remove 20%
        int removed = 0;

        for (Bytes32 key : cache.keySet()) {
            if (removed >= toRemove) {
                break;
            }
            cache.remove(key);
            removed++;
        }

        log.debug("Evicted {} oldest candidate blocks from cache", removed);
    }
}
