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
import lombok.Data;

/**
 * ResolvedEntity - Wrapper for a resolved link target
 *
 * <p>Represents the result of resolving a {@link Link} to its target entity.
 * The entity can be either a {@link Block} or a {@link Transaction}.
 *
 * <p>This class provides type-safe access to the resolved entity without
 * requiring instanceof checks.
 *
 * <h2>Usage Example</h2>
 * <pre>
 * ResolvedEntity entity = resolver.resolveLink(link);
 * if (entity.isFound()) {
 *     if (entity.isBlock()) {
 *         Block block = entity.getBlock();
 *         // Process block...
 *     } else {
 *         Transaction tx = entity.getTransaction();
 *         // Process transaction...
 *     }
 * } else {
 *     // Handle missing entity
 * }
 * </pre>
 *
 * @since v5.1 Phase 8
 * @see DagEntityResolver
 * @see Link
 */
@Data
public class ResolvedEntity {

    private final Block block;
    private final Transaction transaction;

    private ResolvedEntity(Block block, Transaction transaction) {
        this.block = block;
        this.transaction = transaction;
    }

    /**
     * Create a resolved entity for a Block
     *
     * @param block Resolved block (may be null if not found)
     * @return ResolvedEntity containing the block
     */
    public static ResolvedEntity block(Block block) {
        return new ResolvedEntity(block, null);
    }

    /**
     * Create a resolved entity for a Transaction
     *
     * @param transaction Resolved transaction (may be null if not found)
     * @return ResolvedEntity containing the transaction
     */
    public static ResolvedEntity transaction(Transaction transaction) {
        return new ResolvedEntity(null, transaction);
    }

    /**
     * Create a resolved entity representing a missing target
     *
     * @return ResolvedEntity with null block and transaction
     */
    public static ResolvedEntity notFound() {
        return new ResolvedEntity(null, null);
    }

    /**
     * Check if the entity was found
     *
     * @return true if entity is not null (either block or transaction)
     */
    public boolean isFound() {
        return block != null || transaction != null;
    }

    /**
     * Check if the resolved entity is a Block
     *
     * @return true if entity is a Block
     */
    public boolean isBlock() {
        return block != null;
    }

    /**
     * Check if the resolved entity is a Transaction
     *
     * @return true if entity is a Transaction
     */
    public boolean isTransaction() {
        return transaction != null;
    }
}
