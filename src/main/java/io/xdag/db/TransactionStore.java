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
import java.util.List;
import org.apache.tuweni.bytes.Bytes32;

/**
 * TransactionStore for XDAG
 *
 * Storage layer for Transaction objects. In architecture, Transactions are:
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
 * @since Phase 4 - Architecture
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
     * Transaction-to-Block reverse index: txHash -> blockHash (1)
     * Format: 0xe3 + txHash(32) -> blockHash(32)
     * Enables transaction timestamp lookup and block confirmation queries
     */
    byte TRANSACTION_TO_BLOCK_INDEX = (byte) 0xe3;

    /**
     * Address-to-Transactions index: address -> List<txHash>
     * Format: 0xe2 + address(32) -> concatenated txHashes
     * Enables transaction history queries by address
     */
    byte TX_ADDRESS_INDEX = (byte) 0xe2;

    /**
     * Transaction execution status: txHash -> TransactionExecutionInfo
     * Format: 0xe4 + txHash(32) -> execution info bytes
     * Tracks which block executed each transaction (Phase 1 - Task 1.2)
     */
    byte TX_EXECUTION_STATUS = (byte) 0xe4;

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
     * Get the block hash that contains a specific transaction (1)
     *
     * This method uses the reverse index built by indexTransactionToBlock()
     * to find which block contains a given transaction. This enables:
     * - Transaction timestamp lookup (block.getTimestamp())
     * - Transaction confirmation status
     * - Transaction block height
     *
     * @param txHash The transaction hash
     * @return Block hash containing the transaction, or null if not indexed
     * @since XDAGJ
     */
    Bytes32 getBlockByTransaction(Bytes32 txHash);

    /**
     * Get all transactions involving a specific address (from or to)
     *
     * This method uses the TX_ADDRESS_INDEX to retrieve transaction history.
     *
     * @param address The address (from or to) - 20 bytes
     * @return List of Transactions (empty list if none found)
     */
    List<Transaction> getTransactionsByAddress(org.apache.tuweni.bytes.Bytes address);

    /**
     * Index a transaction as involving an address
     * This is called when a transaction is saved
     *
     * @param address The address (from or to) - 20 bytes
     * @param txHash The transaction hash
     */
    void indexTransactionToAddress(org.apache.tuweni.bytes.Bytes address, Bytes32 txHash);

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

    // ========== Transaction Execution Status Tracking (Phase 1 - Task 1.2) ==========

    /**
     * Check if a transaction has been executed.
     *
     * <p>This is used to prevent duplicate execution when the same transaction
     * is referenced by multiple blocks.
     *
     * @param txHash transaction hash
     * @return true if the transaction has been executed
     */
    boolean isTransactionExecuted(Bytes32 txHash);

    /**
     * Mark a transaction as executed.
     *
     * <p>This should be called immediately after successfully executing a transaction.
     *
     * @param txHash transaction hash
     * @param blockHash hash of the block that executed this transaction
     * @param blockHeight height of the executing block
     */
    void markTransactionExecuted(Bytes32 txHash, Bytes32 blockHash, long blockHeight);

    /**
     * Unmark a transaction as executed (for chain reorganization rollback).
     *
     * <p>This allows the transaction to be executed again when it appears
     * in a different block after chain reorganization.
     *
     * @param txHash transaction hash
     */
    void unmarkTransactionExecuted(Bytes32 txHash);

    /**
     * Get execution information for a transaction.
     *
     * @param txHash transaction hash
     * @return execution info, or null if not executed
     */
    io.xdag.core.TransactionExecutionInfo getExecutionInfo(Bytes32 txHash);
}
