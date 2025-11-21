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

package io.xdag.store.rocksdb.transaction;

/**
 * Interface for transactional database operations.
 *
 * <p>Provides atomic write operations using RocksDB's WriteBatch mechanism.
 * All operations within a transaction are guaranteed to be applied atomically (all-or-nothing) when
 * the transaction is committed.
 *
 * <p>Usage example:
 * <pre>{@code
 * // Begin a new transaction
 * String txId = transactionalStore.beginTransaction();
 * try {
 *     // Perform multiple operations
 *     transactionalStore.putInTransaction(txId, key1, value1);
 *     transactionalStore.putInTransaction(txId, key2, value2);
 *     transactionalStore.deleteInTransaction(txId, key3);
 *
 *     // Commit all operations atomically
 *     transactionalStore.commitTransaction(txId);
 * } catch (TransactionException e) {
 *     // Rollback on error
 *     transactionalStore.rollbackTransaction(txId);
 *     throw e;
 * }
 * }</pre>
 *
 * @since Phase 0.5 - Transaction Support Infrastructure
 */
public interface TransactionalStore {

  /**
   * Begin a new transaction.
   *
   * <p>Creates a new transaction context that can hold multiple database operations.
   * All operations added to this transaction will be executed atomically when committed.
   *
   * @return transaction ID (unique identifier for this transaction)
   */
  String beginTransaction();

  /**
   * Commit a transaction.
   *
   * <p>Atomically applies all operations in the transaction to the database.
   * If the commit fails, no changes are applied (all-or-nothing guarantee).
   *
   * @param txId transaction ID returned by {@link #beginTransaction()}
   * @throws TransactionException if the commit fails or transaction not found
   */
  void commitTransaction(String txId) throws TransactionException;

  /**
   * Rollback a transaction.
   *
   * <p>Discards all operations in the transaction without applying them to the database.
   * This is a safe operation that does not throw exceptions.
   *
   * @param txId transaction ID returned by {@link #beginTransaction()}
   */
  void rollbackTransaction(String txId);

  /**
   * Add a PUT operation to the transaction.
   *
   * <p>The operation will be queued and executed atomically when the transaction is committed.
   *
   * @param txId  transaction ID
   * @param key   database key
   * @param value database value
   * @throws TransactionException if transaction not found or operation fails
   */
  void putInTransaction(String txId, byte[] key, byte[] value) throws TransactionException;

  /**
   * Add a DELETE operation to the transaction.
   *
   * <p>The operation will be queued and executed atomically when the transaction is committed.
   *
   * @param txId transaction ID
   * @param key  database key to delete
   * @throws TransactionException if transaction not found or operation fails
   */
  void deleteInTransaction(String txId, byte[] key) throws TransactionException;

  /**
   * Check if a transaction exists and is still active.
   *
   * @param txId transaction ID
   * @return true if the transaction exists and is active
   */
  boolean isTransactionActive(String txId);

  /**
   * Get the number of active transactions.
   *
   * @return number of currently active (uncommitted, non-rolled-back) transactions
   */
  int getActiveTransactionCount();
}
