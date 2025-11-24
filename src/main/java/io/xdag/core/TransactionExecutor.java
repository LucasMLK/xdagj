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

import static io.xdag.core.XUnit.NANO_XDAG;

import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Transaction Executor - Executes transaction account state updates
 *
 * <p>Responsible for updating account states when transactions are processed:
 * <ul>
 *   <li>Ensure receiver account exists</li>
 *   <li>Deduct amount + fee from sender</li>
 *   <li>Increment sender nonce</li>
 *   <li>Add amount to receiver</li>
 * </ul>
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Single Responsibility: Only executes transactions, does not validate</li>
 *   <li>Two modes: Direct execution and transactional execution</li>
 *   <li>Immutable: Returns execution result, does not modify state directly</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Direct execution
 * ExecutionResult result = executor.executeTransaction(tx);
 * if (!result.isSuccess()) {
 *     log.error("Execution failed: {}", result.getError());
 * }
 *
 * // Transactional execution (atomic)
 * ExecutionResult result = executor.executeTransactionInTransaction(txId, tx);
 * }</pre>
 *
 * @since P3 Refactoring
 */
@Slf4j
public class TransactionExecutor {

  private final DagAccountManager accountManager;

  /**
   * Create TransactionExecutor
   *
   * @param accountManager account manager for state updates
   */
  public TransactionExecutor(DagAccountManager accountManager) {
    this.accountManager = accountManager;
  }

  /**
   * Execute transaction (update account states)
   *
   * <p>Steps:
   * <ol>
   *   <li>Ensure receiver account exists</li>
   *   <li>Deduct amount + fee from sender</li>
   *   <li>Increment sender nonce</li>
   *   <li>Add amount to receiver</li>
   * </ol>
   *
   * @param tx transaction to execute
   * @return execution result
   */
  public ExecutionResult executeTransaction(Transaction tx) {
    try {
      // Convert XAmount to UInt256 (nano units)
      UInt256 txAmount = UInt256.valueOf(tx.getAmount().toDecimal(0, NANO_XDAG).longValue());
      UInt256 txFee = UInt256.valueOf(tx.getFee().toDecimal(0, NANO_XDAG).longValue());

      // 1. Ensure receiver account exists
      accountManager.ensureAccountExists(tx.getTo());

      // 2. Update sender account
      accountManager.subtractBalance(tx.getFrom(), txAmount.add(txFee));
      accountManager.incrementNonce(tx.getFrom());

      // 3. Update receiver account
      accountManager.addBalance(tx.getTo(), txAmount);

      log.debug("Account state updated: from={}, to={}, amount={}, fee={}",
          tx.getFrom().toHexString(),
          tx.getTo().toHexString(),
          tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
          tx.getFee().toDecimal(9, XUnit.XDAG).toPlainString());

      return ExecutionResult.success();

    } catch (Exception e) {
      log.error("Failed to execute transaction {}: {}",
          tx.getHash().toHexString().substring(0, 16), e.getMessage());
      return ExecutionResult.error("Failed to update account states: " + e.getMessage());
    }
  }

  /**
   * Execute transaction IN TRANSACTION (atomic)
   *
   * <p>This method buffers all account state updates in a transaction.
   * The caller must commit the transaction to persist changes.
   *
   * <p>Steps are the same as {@link #executeTransaction}, but use
   * transactional methods (e.g., subtractBalanceInTransaction).
   *
   * @param txId transaction ID from RocksDBTransactionManager
   * @param tx   transaction to execute
   * @return execution result
   * @throws io.xdag.store.rocksdb.transaction.TransactionException if transaction operation fails
   */
  public ExecutionResult executeTransactionInTransaction(String txId, Transaction tx)
      throws io.xdag.store.rocksdb.transaction.TransactionException {
    try {
      // Convert XAmount to UInt256 (nano units)
      UInt256 txAmount = UInt256.valueOf(tx.getAmount().toDecimal(0, NANO_XDAG).longValue());
      UInt256 txFee = UInt256.valueOf(tx.getFee().toDecimal(0, NANO_XDAG).longValue());

      // 1. Ensure receiver account exists
      accountManager.ensureAccountExists(tx.getTo());

      // 2. Update sender account IN TRANSACTION
      accountManager.subtractBalanceInTransaction(txId, tx.getFrom(), txAmount.add(txFee));
      accountManager.incrementNonceInTransaction(txId, tx.getFrom());

      // 3. Update receiver account IN TRANSACTION
      accountManager.addBalanceInTransaction(txId, tx.getTo(), txAmount);

      log.debug("Buffered account state updates in transaction {}: from={}, to={}, amount={}, fee={}",
          txId,
          tx.getFrom().toHexString().substring(0, 16),
          tx.getTo().toHexString().substring(0, 16),
          tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
          tx.getFee().toDecimal(9, XUnit.XDAG).toPlainString());

      return ExecutionResult.success();

    } catch (io.xdag.store.rocksdb.transaction.TransactionException e) {
      // Re-throw transaction exceptions (caller should rollback)
      throw e;
    } catch (Exception e) {
      log.error("Failed to execute transaction {} in transaction {}: {}",
          tx.getHash().toHexString().substring(0, 16), txId, e.getMessage());
      throw new io.xdag.store.rocksdb.transaction.TransactionException(
          "Failed to update account states: " + e.getMessage(), e);
    }
  }

  /**
   * ExecutionResult - Transaction execution result
   *
   * <p>Wrapper for execution outcome with error details.
   */
  @lombok.Getter
  @lombok.AllArgsConstructor
  @lombok.EqualsAndHashCode
  public static class ExecutionResult {

    boolean success;
    String error;

    /**
     * Create success result
     *
     * @return success result
     */
    public static ExecutionResult success() {
      return new ExecutionResult(true, null);
    }

    /**
     * Create error result
     *
     * @param error error message
     * @return error result
     */
    public static ExecutionResult error(String error) {
      return new ExecutionResult(false, error);
    }

    /**
     * Check if execution failed
     *
     * @return true if error
     */
    public boolean isError() {
      return !success;
    }

    @Override
    public String toString() {
      return success ? "ExecutionResult{success}" : "ExecutionResult{error='" + error + "'}";
    }
  }
}
