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

package io.xdag.consensus.sync.protocol;

import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.core.Link;
import io.xdag.db.DagStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.util.*;

/**
 * Chain Solidification Utility
 *
 * <p>This class provides methods to verify and solidify the main chain and its
 * referenced DAG blocks. "Solidification" ensures that all blocks in the finalized
 * region are complete and their references are validated.
 *
 * <p><strong>Core Concept</strong>:
 * <ul>
 *   <li>Finalized blocks (beyond FINALITY_EPOCHS) should have complete DAG references</li>
 *   <li>Solidification verifies the integrity of the main chain and its DAG</li>
 *   <li>Prevents missing block references in the finalized region</li>
 * </ul>
 *
 * <p><strong>Design Principles (from HYBRID_SYNC_PROTOCOL.md)</strong>:
 * <ul>
 *   <li>Does NOT modify block content or hash</li>
 *   <li>Does NOT add BI_FINALIZED flag (v5.1 uses FinalityBoundary.isFinalized())</li>
 *   <li>Only verifies and validates block references</li>
 *   <li>Updates main chain index (height → hash mapping)</li>
 * </ul>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * ChainSolidification solidifier = new ChainSolidification(dagStore);
 *
 * // Verify main block and its references
 * SolidificationResult result = solidifier.verifyMainBlock(mainBlock, currentHeight);
 * if (result.isComplete()) {
 *     System.out.println("Block is solidified with " + result.getVerifiedCount() + " refs");
 * }
 *
 * // Detect missing references
 * Set<Bytes32> missing = solidifier.detectMissingReferences(mainBlock);
 * }</pre>
 *
 * @see FinalityBoundary
 * @see <a href="docs/refactor-design/HYBRID_SYNC_PROTOCOL.md">Hybrid Sync Protocol</a>
 * @since v5.1
 */
@Slf4j
public class ChainSolidification {

    /**
     * Maximum blocks to traverse during solidification
     * Prevents malicious deep DAG attacks
     */
    private static final int MAX_SOLIDIFY_BLOCKS = 100_000;

    /**
     * Maximum recursion depth for reference verification
     * Prevents stack overflow from deep reference chains
     */
    private static final int MAX_RECURSION_DEPTH = 1000;

    private final DagStore dagStore;

    /**
     * Constructor
     *
     * @param dagStore DagStore instance for block storage operations
     */
    public ChainSolidification(DagStore dagStore) {
        this.dagStore = Objects.requireNonNull(dagStore, "DagStore cannot be null");
    }

    /**
     * Verify main block and its referenced blocks
     *
     * <p>This method checks:
     * <ul>
     *   <li>Block is on main chain (height > 0)</li>
     *   <li>All referenced blocks exist in storage</li>
     *   <li>Reference depth is within limits</li>
     *   <li>No circular references</li>
     * </ul>
     *
     * @param mainBlock Main block to verify
     * @param currentMainHeight Current main chain height
     * @return Solidification result with verification details
     */
    public SolidificationResult verifyMainBlock(Block mainBlock, long currentMainHeight) {
        // Validate input
        if (mainBlock == null) {
            return SolidificationResult.invalid("Main block is null");
        }

        BlockInfo info = mainBlock.getInfo();
        if (info == null || !info.isMainBlock()) {
            return SolidificationResult.invalid("Block is not on main chain");
        }

        // Check if block is finalized
        boolean isFinalized = FinalityBoundary.isFinalized(info.getHeight(), currentMainHeight);

        log.debug("Verifying main block at height {}, finalized={}", info.getHeight(), isFinalized);

        // Verify all referenced blocks
        Set<Bytes32> visited = new HashSet<>();
        Set<Bytes32> missing = new HashSet<>();

        int verifiedCount = verifyReferencedBlocks(mainBlock, visited, missing, 0);

        if (!missing.isEmpty()) {
            log.warn("Main block {} has {} missing references",
                    mainBlock.getHash().toHexString().substring(0, 16),
                    missing.size());
            return SolidificationResult.incomplete(verifiedCount, missing);
        }

        log.debug("Main block verified: {} references checked", verifiedCount);
        return SolidificationResult.complete(verifiedCount);
    }

    /**
     * Recursively verify referenced blocks
     *
     * <p>Traverses the DAG from the given block, checking that all referenced
     * blocks exist. Includes protection against:
     * <ul>
     *   <li>Circular references (via visited set)</li>
     *   <li>Deep recursion (via depth limit)</li>
     *   <li>Excessive blocks (via count limit)</li>
     * </ul>
     *
     * @param block Block to verify
     * @param visited Set of already visited block hashes (prevents cycles)
     * @param missing Set to collect missing block hashes
     * @param depth Current recursion depth
     * @return Number of blocks verified
     */
    private int verifyReferencedBlocks(
            Block block,
            Set<Bytes32> visited,
            Set<Bytes32> missing,
            int depth) {

        Bytes32 blockHash = block.getHash();

        // Protection 1: Check if already visited (prevent circular references)
        if (visited.contains(blockHash)) {
            return 0;
        }

        // Protection 2: Check depth limit (prevent stack overflow)
        if (depth > MAX_RECURSION_DEPTH) {
            log.warn("Solidification exceeded max recursion depth: {}", MAX_RECURSION_DEPTH);
            return 0;
        }

        // Protection 3: Check total blocks limit (prevent memory exhaustion)
        if (visited.size() >= MAX_SOLIDIFY_BLOCKS) {
            log.warn("Solidification exceeded max blocks limit: {}", MAX_SOLIDIFY_BLOCKS);
            return 0;
        }

        visited.add(blockHash);
        int count = 1;

        // Verify all block references
        for (Link link : block.getLinks()) {
            // Only verify block links (skip transaction links)
            if (!link.isBlock()) {
                continue;
            }

            Bytes32 refHash = link.getTargetHash();

            // Skip if already visited
            if (visited.contains(refHash)) {
                continue;
            }

            // Check if referenced block exists
            Block refBlock = dagStore.getBlockByHash(refHash, false);
            if (refBlock == null) {
                missing.add(refHash);
                continue;
            }

            // Recursively verify this block's references
            count += verifyReferencedBlocks(refBlock, visited, missing, depth + 1);
        }

        return count;
    }

    /**
     * Detect missing block references
     *
     * <p>Scans the given block and returns hashes of all referenced blocks
     * that don't exist in storage.
     *
     * @param block Block to check
     * @return Set of missing block hashes (empty if all references exist)
     */
    public Set<Bytes32> detectMissingReferences(Block block) {
        if (block == null) {
            return Collections.emptySet();
        }

        Set<Bytes32> visited = new HashSet<>();
        Set<Bytes32> missing = new HashSet<>();

        verifyReferencedBlocks(block, visited, missing, 0);

        return missing;
    }

    /**
     * Verify main chain continuity in a height range
     *
     * <p>Checks that:
     * <ul>
     *   <li>All heights in the range have main blocks</li>
     *   <li>Each block references the previous main block</li>
     *   <li>No missing blocks in the chain</li>
     * </ul>
     *
     * @param fromHeight Start height (inclusive)
     * @param toHeight End height (inclusive)
     * @return true if chain is continuous, false otherwise
     */
    public boolean verifyMainChainContinuity(long fromHeight, long toHeight) {
        if (fromHeight < 0 || toHeight < fromHeight) {
            return false;
        }

        try {
            // Use DagStore's built-in continuity check
            return dagStore.verifyMainChainContinuity(fromHeight, toHeight);
        } catch (Exception e) {
            log.error("Failed to verify main chain continuity", e);
            return false;
        }
    }

    /**
     * Get all referenced block hashes
     *
     * <p>Recursively collects all block hashes referenced by the given block.
     * Useful for understanding the DAG structure and dependencies.
     *
     * @param block Starting block
     * @param maxDepth Maximum recursion depth (prevents deep traversal)
     * @return Set of all referenced block hashes
     */
    public Set<Bytes32> getAllReferencedBlocks(Block block, int maxDepth) {
        if (block == null || maxDepth <= 0) {
            return Collections.emptySet();
        }

        Set<Bytes32> result = new HashSet<>();
        Set<Bytes32> visited = new HashSet<>();

        collectReferencedBlocks(block, result, visited, 0, maxDepth);

        return result;
    }

    /**
     * Recursively collect referenced block hashes
     */
    private void collectReferencedBlocks(
            Block block,
            Set<Bytes32> result,
            Set<Bytes32> visited,
            int depth,
            int maxDepth) {

        Bytes32 blockHash = block.getHash();

        if (visited.contains(blockHash) || depth >= maxDepth) {
            return;
        }

        visited.add(blockHash);

        for (Link link : block.getLinks()) {
            if (!link.isBlock()) {
                continue;
            }

            Bytes32 refHash = link.getTargetHash();
            result.add(refHash);

            if (visited.contains(refHash)) {
                continue;
            }

            Block refBlock = dagStore.getBlockByHash(refHash, false);
            if (refBlock != null) {
                collectReferencedBlocks(refBlock, result, visited, depth + 1, maxDepth);
            }
        }
    }

    /**
     * Solidification Result
     *
     * <p>Represents the result of a solidification verification operation.
     */
    public static class SolidificationResult {
        private final boolean complete;
        private final int verifiedCount;
        private final Set<Bytes32> missingBlocks;
        private final String errorMessage;

        private SolidificationResult(
                boolean complete,
                int verifiedCount,
                Set<Bytes32> missingBlocks,
                String errorMessage) {
            this.complete = complete;
            this.verifiedCount = verifiedCount;
            this.missingBlocks = missingBlocks != null ? missingBlocks : Collections.emptySet();
            this.errorMessage = errorMessage;
        }

        /**
         * Create a complete result (all references verified)
         */
        public static SolidificationResult complete(int verifiedCount) {
            return new SolidificationResult(true, verifiedCount, null, null);
        }

        /**
         * Create an incomplete result (some references missing)
         */
        public static SolidificationResult incomplete(int verifiedCount, Set<Bytes32> missingBlocks) {
            return new SolidificationResult(false, verifiedCount, missingBlocks,
                    "Missing " + missingBlocks.size() + " referenced blocks");
        }

        /**
         * Create an invalid result (verification failed)
         */
        public static SolidificationResult invalid(String errorMessage) {
            return new SolidificationResult(false, 0, null, errorMessage);
        }

        public boolean isComplete() {
            return complete;
        }

        public int getVerifiedCount() {
            return verifiedCount;
        }

        public Set<Bytes32> getMissingBlocks() {
            return Collections.unmodifiableSet(missingBlocks);
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            if (complete) {
                return String.format("SolidificationResult{complete=true, verified=%d}",
                        verifiedCount);
            } else if (!missingBlocks.isEmpty()) {
                return String.format("SolidificationResult{complete=false, verified=%d, missing=%d}",
                        verifiedCount, missingBlocks.size());
            } else {
                return String.format("SolidificationResult{complete=false, error=%s}",
                        errorMessage);
            }
        }
    }
}
