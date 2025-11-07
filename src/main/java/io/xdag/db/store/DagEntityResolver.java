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

import io.xdag.core.Block;
import io.xdag.core.Link;
import io.xdag.core.Transaction;
import io.xdag.db.DagStore;
import io.xdag.db.TransactionStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * DagEntityResolver - Unified Link Resolution Facade
 *
 * <p>This class provides a unified interface for resolving {@link Link} references
 * across both {@link DagStore} and {@link TransactionStore}.
 *
 * <h2>Purpose</h2>
 * <p>In XDAG v5.1, a Block's Links can reference two types of entities:
 * <ul>
 *   <li><strong>Block</strong> - Stored in DagStore</li>
 *   <li><strong>Transaction</strong> - Stored in TransactionStore</li>
 * </ul>
 *
 * <p>This resolver hides the complexity of querying multiple stores and provides
 * a simple, unified interface for link resolution.
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Unified Resolution</strong>: Single method call resolves all links</li>
 *   <li><strong>Batch Optimization</strong>: Efficient batch queries for multiple links</li>
 *   <li><strong>Missing Detection</strong>: Identifies missing dependencies</li>
 *   <li><strong>Type Safety</strong>: No instanceof checks needed</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * // Resolve all links in a block
 * ResolvedLinks resolved = resolver.resolveAllLinks(block);
 *
 * if (!resolved.hasAllReferences()) {
 *     // Handle missing dependencies
 *     return DagImportResult.missingDependency(...);
 * }
 *
 * // Process resolved blocks
 * for (Block refBlock : resolved.getReferencedBlocks()) {
 *     // Validate block...
 * }
 *
 * // Process resolved transactions
 * for (Transaction tx : resolved.getReferencedTransactions()) {
 *     // Validate transaction...
 * }
 * </pre>
 *
 * @since v5.1 Phase 8
 * @see DagStore
 * @see TransactionStore
 * @see ResolvedLinks
 */
@Slf4j
public class DagEntityResolver {

    private final DagStore dagStore;
    private final TransactionStore transactionStore;

    public DagEntityResolver(DagStore dagStore, TransactionStore transactionStore) {
        this.dagStore = dagStore;
        this.transactionStore = transactionStore;
    }

    // ==================== Single Link Resolution ====================

    /**
     * Resolve a single link to its target entity
     *
     * <p>This method determines the link type and queries the appropriate store.
     *
     * @param link Link to resolve
     * @return ResolvedEntity containing the target (Block or Transaction)
     */
    public ResolvedEntity resolveLink(Link link) {
        if (link == null) {
            return ResolvedEntity.notFound();
        }

        if (link.isTransaction()) {
            // Query TransactionStore
            Transaction tx = transactionStore.getTransaction(link.getTargetHash());
            return ResolvedEntity.transaction(tx);
        } else {
            // Query DagStore
            Block block = dagStore.getBlockByHash(link.getTargetHash(), false);
            return ResolvedEntity.block(block);
        }
    }

    /**
     * Check if a link target exists
     *
     * <p>Fast existence check without loading full entity data.
     *
     * @param link Link to check
     * @return true if target exists
     */
    public boolean linkExists(Link link) {
        if (link == null) {
            return false;
        }

        if (link.isTransaction()) {
            return transactionStore.hasTransaction(link.getTargetHash());
        } else {
            return dagStore.hasBlock(link.getTargetHash());
        }
    }

    // ==================== Batch Link Resolution ====================

    /**
     * Resolve all links in a block
     *
     * <p>This method efficiently resolves all links in a single call:
     * <ol>
     *   <li>Separates links into Block links and Transaction links</li>
     *   <li>Batch queries each store</li>
     *   <li>Identifies missing references</li>
     *   <li>Returns organized result</li>
     * </ol>
     *
     * @param block Block containing links to resolve
     * @return ResolvedLinks with all resolved entities and missing references
     */
    public ResolvedLinks resolveAllLinks(Block block) {
        if (block == null || block.getLinks() == null || block.getLinks().isEmpty()) {
            return ResolvedLinks.builder().build();
        }

        List<Block> resolvedBlocks = new ArrayList<>();
        List<Transaction> resolvedTransactions = new ArrayList<>();
        List<Bytes32> missingReferences = new ArrayList<>();

        // Separate links by type
        List<Link> blockLinks = new ArrayList<>();
        List<Link> txLinks = new ArrayList<>();

        for (Link link : block.getLinks()) {
            if (link.isTransaction()) {
                txLinks.add(link);
            } else {
                blockLinks.add(link);
            }
        }

        // Resolve block links
        for (Link link : blockLinks) {
            Block refBlock = dagStore.getBlockByHash(link.getTargetHash(), false);
            if (refBlock != null) {
                resolvedBlocks.add(refBlock);
            } else {
                missingReferences.add(link.getTargetHash());
                log.debug("Missing block reference: {}", link.getTargetHash().toHexString());
            }
        }

        // Resolve transaction links
        for (Link link : txLinks) {
            Transaction tx = transactionStore.getTransaction(link.getTargetHash());
            if (tx != null) {
                resolvedTransactions.add(tx);
            } else {
                missingReferences.add(link.getTargetHash());
                log.debug("Missing transaction reference: {}", link.getTargetHash().toHexString());
            }
        }

        return ResolvedLinks.builder()
                .referencedBlocks(resolvedBlocks)
                .referencedTransactions(resolvedTransactions)
                .missingReferences(missingReferences)
                .build();
    }

    /**
     * Batch check link existence
     *
     * <p>Efficiently checks if multiple links exist without loading entity data.
     *
     * @param links List of links to check
     * @return Map of Link → exists
     */
    public Map<Link, Boolean> checkLinksExist(List<Link> links) {
        if (links == null || links.isEmpty()) {
            return new HashMap<>();
        }

        Map<Link, Boolean> result = new HashMap<>();

        // Separate by type
        List<Bytes32> blockHashes = links.stream()
                .filter(link -> !link.isTransaction())
                .map(Link::getTargetHash)
                .collect(Collectors.toList());

        List<Bytes32> txHashes = links.stream()
                .filter(Link::isTransaction)
                .map(Link::getTargetHash)
                .collect(Collectors.toList());

        // Batch check blocks
        Map<Bytes32, Boolean> blockExists = checkBlocksExist(blockHashes);

        // Batch check transactions
        Map<Bytes32, Boolean> txExists = checkTransactionsExist(txHashes);

        // Combine results
        for (Link link : links) {
            boolean exists = link.isTransaction()
                    ? txExists.getOrDefault(link.getTargetHash(), false)
                    : blockExists.getOrDefault(link.getTargetHash(), false);
            result.put(link, exists);
        }

        return result;
    }

    /**
     * Batch check if blocks exist
     *
     * @param hashes List of block hashes
     * @return Map of hash → exists
     */
    private Map<Bytes32, Boolean> checkBlocksExist(List<Bytes32> hashes) {
        Map<Bytes32, Boolean> result = new HashMap<>();
        for (Bytes32 hash : hashes) {
            result.put(hash, dagStore.hasBlock(hash));
        }
        return result;
    }

    /**
     * Batch check if transactions exist
     *
     * @param hashes List of transaction hashes
     * @return Map of hash → exists
     */
    private Map<Bytes32, Boolean> checkTransactionsExist(List<Bytes32> hashes) {
        Map<Bytes32, Boolean> result = new HashMap<>();
        for (Bytes32 hash : hashes) {
            result.put(hash, transactionStore.hasTransaction(hash));
        }
        return result;
    }

    // ==================== Validation Helpers ====================

    /**
     * Validate all links in a block
     *
     * <p>This method performs complete link validation:
     * <ol>
     *   <li>Resolves all links</li>
     *   <li>Checks for missing dependencies</li>
     *   <li>Returns validation result with details</li>
     * </ol>
     *
     * @param block Block to validate
     * @return true if all links are valid
     */
    public boolean validateAllLinks(Block block) {
        if (block == null || block.getLinks() == null || block.getLinks().isEmpty()) {
            // Genesis block or blocks without links are valid
            return true;
        }

        ResolvedLinks resolved = resolveAllLinks(block);
        return resolved.hasAllReferences();
    }

    /**
     * Get statistics about link resolution
     *
     * @param block Block to analyze
     * @return Human-readable statistics string
     */
    public String getLinkResolutionStats(Block block) {
        if (block == null || block.getLinks() == null || block.getLinks().isEmpty()) {
            return "No links";
        }

        ResolvedLinks resolved = resolveAllLinks(block);
        return String.format(
                "Links[Total:%d, Blocks:%d, Txs:%d, Missing:%d]",
                block.getLinks().size(),
                resolved.getReferencedBlocks().size(),
                resolved.getReferencedTransactions().size(),
                resolved.getMissingCount()
        );
    }
}
