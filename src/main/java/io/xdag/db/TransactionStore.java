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

package io.xdag.db;

import io.xdag.core.Transaction;
import io.xdag.core.XdagLifecycle;
import org.apache.tuweni.bytes.Bytes32;

import java.util.List;

/**
 * TransactionStore for XDAG v5.1
 *
 * Storage layer for Transaction objects. In v5.1 architecture, Transactions are:
 * 1. Independent objects (not embedded in blocks)
 * 2. Broadcast and stored separately from blocks
 * 3. Referenced by blocks via Link (only hash reference)
 *
 * This store enables:
 * - Transaction lookup by hash (for block validation)
 * - Transaction retrieval for display/verification
 * - Support for mempool (pending transactions)
 *
 * Design principles:
 * - Simple key-value storage: hash -> Transaction
 * - Fast lookup by hash (O(1) with RocksDB)
 * - Optional indexing by block hash (for block transaction retrieval)
 *
 * @see Transaction
 * @see io.xdag.core.Link
 * @since Phase 4 - v5.1 Architecture
 */
public interface TransactionStore extends XdagLifecycle {

    // ========== Database Key Prefixes ==========

    /**
     * Primary storage: hash -> Transaction
     * Format: 0xe0 + txHash(32) -> Transaction bytes
     */
    byte TX_DATA = (byte) 0xe0;

    /**
     * Block-to-Transactions index: blockHash -> List<txHash>
     * Format: 0xe1 + blockHash(32) -> concatenated txHashes
     * Enables fast retrieval of all transactions in a block
     */
    byte TX_BLOCK_INDEX = (byte) 0xe1;

    /**
     * Address-to-Transactions index: address -> List<txHash>
     * Format: 0xe2 + address(32) -> concatenated txHashes
     * Enables transaction history queries by address
     */
    byte TX_ADDRESS_INDEX = (byte) 0xe2;

    // ========== Core Operations ==========

    /**
     * Save a transaction to the database
     *
     * @param tx The transaction to save
     */
    void saveTransaction(Transaction tx);

    /**
     * Get a transaction by its hash
     *
     * @param hash Transaction hash
     * @return Transaction object, or null if not found
     */
    Transaction getTransaction(Bytes32 hash);

    /**
     * Check if a transaction exists in the database
     *
     * @param hash Transaction hash
     * @return true if transaction exists
     */
    boolean hasTransaction(Bytes32 hash);

    /**
     * Delete a transaction from the database
     * (for mempool cleanup or reorganizations)
     *
     * @param hash Transaction hash
     */
    void deleteTransaction(Bytes32 hash);

    // ========== Indexing Operations ==========

    /**
     * Get all transactions referenced by a block
     *
     * This method uses the TX_BLOCK_INDEX to efficiently retrieve
     * all transaction hashes associated with a specific block.
     *
     * @param blockHash The block hash
     * @return List of Transactions (empty list if none found)
     */
    List<Transaction> getTransactionsByBlock(Bytes32 blockHash);

    /**
     * Get transaction hashes associated with a block (without loading full transactions)
     *
     * @param blockHash The block hash
     * @return List of transaction hashes (empty list if none found)
     */
    List<Bytes32> getTransactionHashesByBlock(Bytes32 blockHash);

    /**
     * Index a transaction as belonging to a block
     * This is called when a block is added to the chain
     *
     * @param blockHash The block hash
     * @param txHash The transaction hash
     */
    void indexTransactionToBlock(Bytes32 blockHash, Bytes32 txHash);

    /**
     * Get all transactions involving a specific address (from or to)
     *
     * This method uses the TX_ADDRESS_INDEX to retrieve transaction history.
     *
     * @param address The address (from or to)
     * @return List of Transactions (empty list if none found)
     */
    List<Transaction> getTransactionsByAddress(Bytes32 address);

    /**
     * Index a transaction as involving an address
     * This is called when a transaction is saved
     *
     * @param address The address (from or to)
     * @param txHash The transaction hash
     */
    void indexTransactionToAddress(Bytes32 address, Bytes32 txHash);

    // ========== Batch Operations ==========

    /**
     * Batch save multiple transactions
     * More efficient than calling saveTransaction() multiple times
     *
     * @param transactions List of transactions to save
     */
    void saveTransactions(List<Transaction> transactions);

    /**
     * Batch get multiple transactions by hashes
     *
     * @param hashes List of transaction hashes
     * @return List of Transactions (null entries for missing transactions)
     */
    List<Transaction> getTransactionsByHashes(List<Bytes32> hashes);

    // ========== Statistics ==========

    /**
     * Get total number of transactions in the database
     *
     * @return Transaction count
     */
    long getTransactionCount();

    /**
     * Reset the transaction database (for testing or migration)
     */
    void reset();
}
