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

package io.xdag.core;

import java.util.List;
import lombok.Getter;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * TransactionPool interface for managing pending transactions in XDAG.
 *
 * <p>The TransactionPool is responsible for:
 * <ul>
 *   <li>Accepting and validating new transactions</li>
 *   <li>Maintaining pending transactions until they are included in blocks</li>
 *   <li>Providing transactions for block creation (mining)</li>
 *   <li>Managing transaction lifecycle (add, remove, expire)</li>
 *   <li>Enforcing nonce ordering and preventing duplicates</li>
 * </ul>
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><b>High Performance</b>: Uses Caffeine cache for efficient storage and retrieval</li>
 *   <li><b>Thread-Safe</b>: All operations are safe for concurrent access</li>
 *   <li><b>Nonce Ordering</b>: Maintains strict nonce sequence per account</li>
 *   <li><b>Automatic Cleanup</b>: Expired transactions are automatically removed</li>
 * </ul>
 *
 * @since Phase 1 - TransactionPool Implementation
 */
public interface TransactionPool {

    /**
     * Add a transaction to the pool.
     *
     * <p>The transaction will be validated before being added:
     * <ul>
     *   <li>Signature verification</li>
     *   <li>Sufficient balance check</li>
     *   <li>Nonce continuity check</li>
     *   <li>Minimum fee requirement</li>
     * </ul>
     *
     * @param tx transaction to add
     * @return true if successfully added, false if rejected (invalid, duplicate, or pool full)
     */
    boolean addTransaction(Transaction tx);

    /**
     * Remove a transaction from the pool.
     *
     * <p>This is typically called when:
     * <ul>
     *   <li>Transaction is included in a main block</li>
     *   <li>Transaction execution fails</li>
     *   <li>Transaction is manually rejected by user</li>
     * </ul>
     *
     * @param txHash transaction hash
     * @return true if removed, false if not found
     */
    boolean removeTransaction(Bytes32 txHash);

    /**
     * Remove multiple transactions from the pool.
     *
     * <p>Batch removal for efficiency.
     *
     * @param txHashes list of transaction hashes
     * @return number of transactions removed
     */
    int removeTransactions(List<Bytes32> txHashes);

    /**
     * Remove all transactions from a specific account.
     *
     * <p>Used when an account becomes frozen or invalid.
     *
     * @param address account address
     * @return number of transactions removed
     */
    int removeTransactionsByAccount(Bytes address);

    /**
     * Check if a transaction exists in the pool.
     *
     * @param txHash transaction hash
     * @return true if in pool
     */
    boolean contains(Bytes32 txHash);

    /**
     * Get a transaction from the pool.
     *
     * @param txHash transaction hash
     * @return transaction, or null if not found
     */
    Transaction getTransaction(Bytes32 txHash);

    /**
     * Select transactions for block creation.
     *
     * <p>Returns transactions ordered by:
     * <ol>
     *   <li>Fee (highest first)</li>
     *   <li>Nonce (sequential per account)</li>
     *   <li>Timestamp (oldest first as tiebreaker)</li>
     * </ol>
     *
     * <p>Only transactions with valid nonce sequences are selected.
     *
     * @param maxCount maximum number of transactions to select
     * @return list of selected transactions (maybe less than maxCount)
     */
    List<Transaction> selectTransactions(int maxCount);

    /**
     * Get all transactions from a specific account.
     *
     * @param address account address
     * @return list of transactions from this account, ordered by nonce
     */
    List<Transaction> getTransactionsByAccount(Bytes address);

  /**
     * Get the number of pending transactions in the pool.
     *
     * @return transaction count
     */
    int size();

    /**
     * Clear all transactions from the pool.
     *
     * <p>Warning: This is destructive and should only be used for testing or emergency cleanup.
     */
    void clear();

    /**
     * Get pool statistics.
     *
     * @return statistics object
     */
    PoolStatistics getStatistics();

    /**
     * Pool statistics for monitoring.
     */
    @Getter
    class PoolStatistics {
        private final int currentSize;
        private final long totalAdded;
        private final long totalRemoved;
        private final long totalRejected;
        private final long cacheHits;
        private final long cacheMisses;
        private final double hitRate;

        public PoolStatistics(
                int currentSize,
                long totalAdded,
                long totalRemoved,
                long totalRejected,
                long cacheHits,
                long cacheMisses,
                double hitRate) {
            this.currentSize = currentSize;
            this.totalAdded = totalAdded;
            this.totalRemoved = totalRemoved;
            this.totalRejected = totalRejected;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.hitRate = hitRate;
        }

      @Override
        public String toString() {
            return String.format(
                    "PoolStatistics{size=%d, added=%d, removed=%d, rejected=%d, hitRate=%.2f%%}",
                    currentSize, totalAdded, totalRemoved, totalRejected, hitRate * 100);
        }
    }
}
