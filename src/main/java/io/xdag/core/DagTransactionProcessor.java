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

import io.xdag.store.TransactionStore;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * DagTransactionProcessor - Complete transaction processing coordinator (P3 Refactored)
 *
 * <p>Coordinates transaction processing by delegating to specialized components:
 * <ul>
 *   <li>{@link TransactionValidator} - Validates transactions</li>
 *   <li>{@link TransactionExecutor} - Executes account state updates</li>
 *   <li>{@link TransactionPersister} - Persists transactions</li>
 * </ul>
 *
 * <h2>Processing Flow</h2>
 * <pre>
 * 1. Validate (delegate to TransactionValidator)
 * 2. Execute (delegate to TransactionExecutor)
 * 3. Persist (delegate to TransactionPersister)
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * DagTransactionProcessor processor = new DagTransactionProcessor(
 *     accountManager,
 *     transactionStore
 * );
 *
 * // Process single transaction
 * ProcessingResult result = processor.processTransaction(tx);
 * if (result.isSuccess()) {
 *     // Transaction processed successfully
 * }
 *
 * // Process block transactions
 * ProcessingResult result = processor.processBlockTransactions(block, txs);
 * </pre>
 *
 * @since XDAGJ (P3 Refactored: 2025-11-24)
 */
@Slf4j
public class DagTransactionProcessor {

  // Legacy dependencies (kept for backward compatibility)
  private final DagAccountManager accountManager;
  private final TransactionStore transactionStore;

  // P3 Refactored components
  private final TransactionValidator transactionValidator;
  private final TransactionExecutor transactionExecutor;
  private final TransactionPersister transactionPersister;

  /**
   * Create DagTransactionProcessor (P3 Refactored)
   *
   * <p>Internally creates specialized components:
   * <ul>
   *   <li>TransactionValidator - for validation</li>
   *   <li>TransactionExecutor - for account updates</li>
   *   <li>TransactionPersister - for persistence</li>
   * </ul>
   *
   * @param accountManager   account state manager
   * @param transactionStore transaction storage
   */
  public DagTransactionProcessor(
      DagAccountManager accountManager,
      TransactionStore transactionStore
  ) {
    this.accountManager = accountManager;
    this.transactionStore = transactionStore;

    // Initialize P3 refactored components
    this.transactionValidator = new TransactionValidatorImpl(
        accountManager,
        transactionStore,
        XAmount.ZERO,  // minFee (TODO: make configurable)
        XAmount.of(1000000000000L, NANO_XDAG)  // maxFee = 1000 XDAG (anti-DoS)
    );
    this.transactionExecutor = new TransactionExecutor(accountManager);
    this.transactionPersister = new TransactionPersister(transactionStore);

    log.info("DagTransactionProcessor initialized (P3 refactored)");
    log.info("  - TransactionValidator: {}", transactionValidator.getClass().getSimpleName());
    log.info("  - TransactionExecutor: {}", transactionExecutor.getClass().getSimpleName());
    log.info("  - TransactionPersister: {}", transactionPersister.getClass().getSimpleName());
  }

  /**
   * Process a single transaction (P3 Refactored)
   *
   * <p>This method delegates to specialized components:
   * <ol>
   *   <li>Validates transaction (TransactionValidator)</li>
   *   <li>Executes account updates (TransactionExecutor)</li>
   *   <li>Persists transaction (TransactionPersister)</li>
   * </ol>
   *
   * @param tx transaction to process
   * @return processing result
   */
  public ProcessingResult processTransaction(Transaction tx) {
    // 1. Validate (delegate to TransactionValidator)
    ValidationResult validation = transactionValidator.validate(tx);
    if (!validation.isValid()) {
      log.warn("Transaction validation failed: {}, error: {}",
          tx.getHash().toHexString(), validation.getErrorMessage());
      return ProcessingResult.error(validation.getErrorMessage());
    }

    // 2. Execute (delegate to TransactionExecutor)
    TransactionExecutor.ExecutionResult execution = transactionExecutor.executeTransaction(tx);
    if (!execution.isSuccess()) {
      log.error("Transaction execution failed: {}, error: {}",
          tx.getHash().toHexString(), execution.getError());
      return ProcessingResult.error(execution.getError());
    }

    // 3. Persist (delegate to TransactionPersister)
    TransactionPersister.PersistenceResult persistence = transactionPersister.saveTransaction(tx);
    if (!persistence.isSuccess()) {
      log.error("Transaction persistence failed: {}, error: {}",
          tx.getHash().toHexString(), persistence.getError());
      return ProcessingResult.error(persistence.getError());
    }

    log.debug("Transaction processed: hash={}, from={}, to={}, amount={}",
        tx.getHash().toHexString(),
        tx.getFrom().toHexString(),
        tx.getTo().toHexString(),
        tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString());

    return ProcessingResult.success();
  }

  /**
   * Process all transactions in a block (P3 Refactored)
   *
   * <p>Processes transactions sequentially, delegating to specialized components.
   * If any transaction fails, the entire block processing fails.
   *
   * <p>For each transaction:
   * <ol>
   *   <li>Validates (TransactionValidator)</li>
   *   <li>Executes account updates (TransactionExecutor)</li>
   *   <li>Persists and marks as executed (TransactionPersister)</li>
   * </ol>
   *
   * @param block        block being processed
   * @param transactions transactions to process
   * @return processing result
   */
  public ProcessingResult processBlockTransactions(Block block, List<Transaction> transactions) {
    List<String> processedTxHashes = new ArrayList<>();

    for (Transaction tx : transactions) {
      // 1. Validate (delegate to TransactionValidator)
      ValidationResult validation = transactionValidator.validate(tx);
      if (!validation.isValid()) {
        log.warn("Transaction validation failed in block {}, tx {}: {}",
            block.getHash().toHexString(),
            tx.getHash().toHexString(),
            validation.getErrorMessage());
        return ProcessingResult.error(
            String.format("Block transaction validation failed: %s", validation.getErrorMessage())
        );
      }

      // 2. Execute (delegate to TransactionExecutor)
      TransactionExecutor.ExecutionResult execution = transactionExecutor.executeTransaction(tx);
      if (!execution.isSuccess()) {
        log.warn("Transaction execution failed in block {}, tx {}: {}",
            block.getHash().toHexString(),
            tx.getHash().toHexString(),
            execution.getError());
        return ProcessingResult.error(
            String.format("Block transaction execution failed: %s", execution.getError())
        );
      }

      // 3. Persist (delegate to TransactionPersister)
      transactionPersister.saveTransaction(tx);
      transactionPersister.markExecuted(tx, block);

      processedTxHashes.add(tx.getHash().toHexString());
    }

    log.info(
        "Successfully processed {} transactions for block {} (validated + executed + persisted)",
        transactions.size(), block.getHash().toHexString());

    return ProcessingResult.success();
  }

  /**
   * Process all transactions in a block within a transaction context (ATOMIC) (P3 Refactored)
   *
   * <p>This is the atomic version that buffers all operations in a transaction.
   * Delegates to specialized components:
   * <ol>
   *   <li>Validates all transactions first (TransactionValidator)</li>
   *   <li>Executes in transaction (TransactionExecutor)</li>
   *   <li>Marks execution in transaction (TransactionPersister)</li>
   * </ol>
   *
   * <p><strong>IMPORTANT</strong>: This method assumes transactions are already saved
   * separately. It only validates, executes, and marks execution status.
   *
   * @param txId         transaction ID from RocksDBTransactionManager
   * @param block        block being processed
   * @param transactions transactions to process
   * @return processing result
   * @throws io.xdag.store.rocksdb.transaction.TransactionException if transaction operation fails
   */
  public ProcessingResult processBlockTransactionsInTransaction(
      String txId,
      Block block,
      List<Transaction> transactions
  ) throws io.xdag.store.rocksdb.transaction.TransactionException {

    // Step 1: Validate all transactions FIRST (fail-fast before any modifications)
    for (Transaction tx : transactions) {
      ValidationResult validation = transactionValidator.validate(tx);
      if (!validation.isValid()) {
        log.warn("Transaction validation failed: {}, error: {}",
            tx.getHash().toHexString(), validation.getErrorMessage());
        return ProcessingResult.error(validation.getErrorMessage());
      }
    }

    // Step 2: Execute all transactions in transaction
    for (Transaction tx : transactions) {
      try {
        // Execute IN TRANSACTION (delegate to TransactionExecutor)
        TransactionExecutor.ExecutionResult execution =
            transactionExecutor.executeTransactionInTransaction(txId, tx);
        if (!execution.isSuccess()) {
          throw new io.xdag.store.rocksdb.transaction.TransactionException(execution.getError());
        }

        // Mark executed IN TRANSACTION (delegate to TransactionPersister)
        transactionPersister.markExecutedInTransaction(txId, tx, block);

        log.debug("Buffered transaction processing for {} in transaction {}",
            tx.getHash().toHexString().substring(0, 16), txId);

      } catch (Exception e) {
        log.error("Failed to process transaction {} in transaction {}: {}",
            tx.getHash().toHexString().substring(0, 16), txId, e.getMessage());
        throw new io.xdag.store.rocksdb.transaction.TransactionException(
            "Failed to process transaction: " + e.getMessage(), e);
      }
    }

    log.info("Buffered {} transaction state updates in transaction {} (atomic)",
        transactions.size(), txId);

    return ProcessingResult.success();
  }

  /**
   * ProcessingResult - Transaction processing result
   *
   * <p>Wrapper for transaction processing outcome with error details.
   */
  @lombok.Getter
  @lombok.AllArgsConstructor
  @lombok.EqualsAndHashCode
  public static class ProcessingResult {

    boolean success;
    String error;

    /**
     * Create success result
     *
     * @return success result
     */
    public static ProcessingResult success() {
      return new ProcessingResult(true, null);
    }

    /**
     * Create error result
     *
     * @param error error message
     * @return error result
     */
    public static ProcessingResult error(String error) {
      return new ProcessingResult(false, error);
    }

    /**
     * Check if processing failed
     *
     * @return true if error
     */
    public boolean isError() {
      return !success;
    }

    @Override
    public String toString() {
      return success ? "ProcessingResult{success}" : "ProcessingResult{error='" + error + "'}";
    }
  }
}
