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

package io.xdag.db.cache;

import io.xdag.core.Block;
import io.xdag.core.Link;
import io.xdag.core.Transaction;
import io.xdag.db.DagStore;
import io.xdag.db.TransactionStore;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * DagEntityResolver - Unified Link Resolution Facade
 *
 * <p>This class provides a unified interface for resolving {@link Link} references
 * across both {@link DagStore} and {@link TransactionStore}.
 *
 * <h2>Purpose</h2>
 * <p>In XDAG, a Block's Links can reference two types of entities:
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
 * @since XDAGJ
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

  // ==================== Validation Helpers ====================

}
