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

import io.xdag.store.TransactionStore;
import lombok.extern.slf4j.Slf4j;

/**
 * Transaction Persister - Persists transaction data and execution status
 *
 * <p>Responsible for saving transaction data and tracking execution status:
 * <ul>
 *   <li>Save transaction to store</li>
 *   <li>Mark transaction as executed by block</li>
 *   <li>Index transaction to block</li>
 * </ul>
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Single Responsibility: Only handles persistence, no validation or execution</li>
 *   <li>Two modes: Direct persistence and transactional persistence</li>
 *   <li>Error handling: Persistence failures are logged and returned</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Save transaction
 * PersistenceResult result = persister.saveTransaction(tx);
 *
 * // Mark as executed
 * PersistenceResult result = persister.markExecuted(tx, block);
 *
 * // Transactional marking
 * PersistenceResult result = persister.markExecutedInTransaction(txId, tx, block);
 * }</pre>
 *
 * @since P3 Refactoring
 */
@Slf4j
public class TransactionPersister {

  private final TransactionStore transactionStore;

  /**
   * Create TransactionPersister
   *
   * @param transactionStore transaction store for persistence
   */
  public TransactionPersister(TransactionStore transactionStore) {
    this.transactionStore = transactionStore;
  }

  /**
   * Save transaction to store
   *
   * <p>Persists the transaction data for later retrieval.
   *
   * @param tx transaction to save
   * @return persistence result
   */
  public PersistenceResult saveTransaction(Transaction tx) {
    try {
      transactionStore.saveTransaction(tx);
      log.debug("Transaction saved: {}", tx.getHash().toHexString().substring(0, 16));
      return PersistenceResult.success();
    } catch (Exception e) {
      log.error("Failed to save transaction {}: {}",
          tx.getHash().toHexString().substring(0, 16), e.getMessage());
      return PersistenceResult.error("Failed to save transaction: " + e.getMessage());
    }
  }

  /**
   * Mark transaction as executed by block
   *
   * <p>Records that this transaction was executed by the given block.
   * Also creates an index from block to transaction for efficient queries.
   *
   * <p><strong>Note</strong>: Marking execution status is informational.
   * Failures are logged but do not cause the overall transaction processing to fail.
   *
   * @param tx    transaction that was executed
   * @param block block that executed the transaction
   * @return persistence result
   */
  public PersistenceResult markExecuted(Transaction tx, Block block) {
    try {
      transactionStore.markTransactionExecuted(
          tx.getHash(),
          block.getHash(),
          block.getInfo().getHeight()
      );

      transactionStore.indexTransactionToBlock(block.getHash(), tx.getHash());

      log.debug("Marked transaction {} as executed by block {} at height {}",
          tx.getHash().toHexString().substring(0, 16),
          block.getHash().toHexString().substring(0, 16),
          block.getInfo().getHeight());

      return PersistenceResult.success();

    } catch (Exception e) {
      // Execution marking is informational - log but don't fail
      log.warn("Failed to mark transaction {} as executed: {}",
          tx.getHash().toHexString().substring(0, 16), e.getMessage());
      return PersistenceResult.success();  // Non-critical failure
    }
  }

  /**
   * Mark transaction as executed IN TRANSACTION (atomic)
   *
   * <p>This method buffers the execution mark in a transaction.
   * The caller must commit the transaction to persist changes.
   *
   * @param txId  transaction ID from RocksDBTransactionManager
   * @param tx    transaction that was executed
   * @param block block that executed the transaction
   * @return persistence result
   * @throws io.xdag.store.rocksdb.transaction.TransactionException if transaction operation fails
   */
  public PersistenceResult markExecutedInTransaction(String txId, Transaction tx, Block block)
      throws io.xdag.store.rocksdb.transaction.TransactionException {
    try {
      transactionStore.markTransactionExecutedInTransaction(
          txId,
          tx.getHash(),
          block.getHash(),
          block.getInfo().getHeight()
      );

      log.debug("Buffered execution mark for transaction {} in transaction {}",
          tx.getHash().toHexString().substring(0, 16), txId);

      return PersistenceResult.success();

    } catch (io.xdag.store.rocksdb.transaction.TransactionException e) {
      // Re-throw transaction exceptions (caller should rollback)
      throw e;
    } catch (Exception e) {
      log.error("Failed to mark transaction {} as executed in transaction {}: {}",
          tx.getHash().toHexString().substring(0, 16), txId, e.getMessage());
      throw new io.xdag.store.rocksdb.transaction.TransactionException(
          "Failed to mark execution: " + e.getMessage(), e);
    }
  }

  /**
   * PersistenceResult - Transaction persistence result
   *
   * <p>Wrapper for persistence outcome with error details.
   */
  @lombok.Getter
  @lombok.AllArgsConstructor
  @lombok.EqualsAndHashCode
  public static class PersistenceResult {

    boolean success;
    String error;

    /**
     * Create success result
     *
     * @return success result
     */
    public static PersistenceResult success() {
      return new PersistenceResult(true, null);
    }

    /**
     * Create error result
     *
     * @param error error message
     * @return error result
     */
    public static PersistenceResult error(String error) {
      return new PersistenceResult(false, error);
    }

    /**
     * Check if persistence failed
     *
     * @return true if error
     */
    public boolean isError() {
      return !success;
    }

    @Override
    public String toString() {
      return success ? "PersistenceResult{success}" : "PersistenceResult{error='" + error + "'}";
    }
  }
}
