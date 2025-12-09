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

import io.xdag.config.Config;
import io.xdag.store.AccountStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * DagAccountManager - Pure account state management for Dag layer
 *
 * <p>This class provides CRUD operations for account state management.
 * It does NOT contain transaction processing logic - that's handled by DagTransactionProcessor.
 *
 * <h2>Key Responsibilities</h2>
 * <ul>
 *   <li>Query account information (balance, nonce)</li>
 *   <li>Update account state (balance, nonce)</li>
 *   <li>Create new accounts</li>
 *   <li>Check account existence</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * DagAccountManager manager = new DagAccountManager(accountStore, config);
 *
 * // Query account
 * UInt256 balance = manager.getBalance(address);
 * UInt64 nonce = manager.getNonce(address);
 *
 * // Update account
 * manager.addBalance(address, amount);
 * manager.incrementNonce(address);
 * </pre>
 *
 * @since XDAGJ
 */
@Slf4j
public class DagAccountManager {

  private final AccountStore accountStore;
  private final Config config;

  /**
   * Create DagAccountManager
   *
   * @param accountStore account storage
   * @param config       XDAG configuration
   */
  public DagAccountManager(AccountStore accountStore, Config config) {
    this.accountStore = accountStore;
    this.config = config;
  }

  // ==================== Query Operations ====================

  /**
   * Get account balance
   *
   * @param address account address (20 bytes)
   * @return balance (zero if account doesn't exist)
   */
  public UInt256 getBalance(Bytes address) {
    return accountStore.getBalance(address);
  }

  /**
   * Get account nonce
   *
   * @param address account address (20 bytes)
   * @return nonce (zero if account doesn't exist)
   */
  public UInt64 getNonce(Bytes address) {
    return accountStore.getNonce(address);
  }

  /**
   * Check if account exists
   *
   * @param address account address (20 bytes)
   * @return true if account exists
   */
  public boolean hasAccount(Bytes address) {
    return accountStore.hasAccount(address);
  }

  /**
   * Get total balance of all accounts
   *
   * @return sum of all account balances
   */
  public UInt256 getTotalBalance() {
    return accountStore.getTotalBalance();
  }

  /**
   * Get total number of accounts
   *
   * @return account count
   */
  public UInt64 getAccountCount() {
    return accountStore.getAccountCount();
  }

  // ==================== Update Operations ====================

  /**
   * Add to account balance
   *
   * @param address account address (20 bytes)
   * @param amount  amount to add
   * @return new balance
   */
  public UInt256 addBalance(Bytes address, UInt256 amount) {
    return accountStore.addBalance(address, amount);
  }

  /**
   * Subtract from account balance
   *
   * @param address account address (20 bytes)
   * @param amount  amount to subtract
   * @return new balance
   * @throws IllegalArgumentException if insufficient balance
   */
  public UInt256 subtractBalance(Bytes address, UInt256 amount) {
    return accountStore.subtractBalance(address, amount);
  }

  /**
   * Set account balance
   *
   * @param address account address (20 bytes)
   * @param balance new balance
   */
  public void setBalance(Bytes address, UInt256 balance) {
    accountStore.setBalance(address, balance);
  }

  /**
   * Increment account nonce by 1
   *
   * @param address account address (20 bytes)
   * @return new nonce value
   */
  public UInt64 incrementNonce(Bytes address) {
    return accountStore.incrementNonce(address);
  }

  /**
   * Decrement account nonce by 1
   *
   * <p>Used during transaction rollback when restoring account state.
   *
   * @param address account address (20 bytes)
   * @return new nonce value
   * @throws IllegalStateException if nonce is already zero
   */
  public UInt64 decrementNonce(Bytes address) {
    return accountStore.decrementNonce(address);
  }

  // ==================== Account Creation ====================

  /**
   * Ensure account exists, create if not
   *
   * <p>If the account doesn't exist, creates a new EOA account
   * with zero balance and nonce.
   *
   * @param address account address (20 bytes)
   */
  public void ensureAccountExists(Bytes address) {
    if (!accountStore.hasAccount(address)) {
      Account account = Account.createEOA(address);
      accountStore.saveAccount(account);
      log.debug("Created new EOA account: {}", address.toHexString());
    }
  }

  // ==================== Transactional Operations (NEW - Atomic Block Processing) ====================

  /**
   * Add to account balance in a transaction.
   *
   * <p><strong>THREAD SAFETY WARNING </strong>:
   * This method uses a non-atomic read-modify-write pattern:
   * <pre>{@code
   *   currentBalance = getBalance(address);      // Non-transactional read
   *   newBalance = currentBalance + amount;
   *   setBalanceInTransaction(txId, address, newBalance);  // Transactional write
   * }</pre>
   *
   * <p><strong>Current Safety</strong>: Protected by {@code DagChainImpl.tryToConnect()}
   * synchronized block, ensuring sequential block processing.
   *
   * <p><strong>Future Risk</strong>: HIGH - Will cause balance loss if parallel block
   * processing is enabled. Two concurrent calls could read the same balance, both calculate
   * new values, and the last write would overwrite the first, losing the first update.
   *
   * <p><strong>TODO</strong>: Before enabling parallel processing, refactor to use:
   * <ul>
   *   <li>Option A: Add {@code getBalanceInTransaction(txId, address)} for transactional reads</li>
   *   <li>Option B: Implement atomic {@code addBalanceInTransaction()} in AccountStore using RocksDB Merge</li>
   * </ul>
   *
   * @param txId    transaction ID from RocksDBTransactionManager
   * @param address account address
   * @param amount  amount to add
   * @throws io.xdag.store.rocksdb.transaction.TransactionException if transaction operation fails
   */
  public void addBalanceInTransaction(String txId, Bytes address, UInt256 amount)
      throws io.xdag.store.rocksdb.transaction.TransactionException {
    UInt256 currentBalance = getBalance(address);
    UInt256 newBalance = currentBalance.add(amount);
    accountStore.setBalanceInTransaction(txId, address, newBalance);

    if (log.isDebugEnabled()) {
      log.debug("Transaction {}: addBalance({}, {}) -> {}",
          txId, address.toHexString().substring(0, 16),
          amount.toDecimalString(), newBalance.toDecimalString());
    }
  }

  /**
   * Subtract from account balance in a transaction.
   *
   * <p><strong>THREAD SAFETY WARNING </strong>:
   * This method uses a non-atomic read-modify-write pattern. See {@link #addBalanceInTransaction}
   * for detailed explanation of the concurrency risk.
   *
   * <p><strong>Current Safety</strong>: Protected by {@code DagChainImpl.tryToConnect()}
   * synchronized block.
   *
   * <p><strong>Future Risk</strong>: HIGH - Balance loss risk in parallel block processing.
   *
   * @param txId    transaction ID from RocksDBTransactionManager
   * @param address account address
   * @param amount  amount to subtract
   * @throws io.xdag.store.rocksdb.transaction.TransactionException if transaction operation fails
   * @throws IllegalArgumentException                               if insufficient balance
   * @see #addBalanceInTransaction
   */
  public void subtractBalanceInTransaction(String txId, Bytes address, UInt256 amount)
      throws io.xdag.store.rocksdb.transaction.TransactionException {
    UInt256 currentBalance = getBalance(address);
    if (currentBalance.compareTo(amount) < 0) {
      throw new IllegalArgumentException(String.format(
          "Insufficient balance: have %s, need %s",
          currentBalance.toDecimalString(), amount.toDecimalString()));
    }

    UInt256 newBalance = currentBalance.subtract(amount);
    accountStore.setBalanceInTransaction(txId, address, newBalance);

    if (log.isDebugEnabled()) {
      log.debug("Transaction {}: subtractBalance({}, {}) -> {}",
          txId, address.toHexString().substring(0, 16),
          amount.toDecimalString(), newBalance.toDecimalString());
    }
  }

  /**
   * Increment account nonce in a transaction.
   *
   * <p><strong>THREAD SAFETY WARNING </strong>:
   * This method uses a non-atomic read-modify-write pattern. See {@link #addBalanceInTransaction}
   * for detailed explanation of the concurrency risk.
   *
   * <p><strong>Current Safety</strong>: Protected by {@code DagChainImpl.tryToConnect()}
   * synchronized block.
   *
   * <p><strong>Future Risk</strong>: HIGH - Nonce desync risk in parallel block processing.
   *
   * @param txId    transaction ID from RocksDBTransactionManager
   * @param address account address
   * @throws io.xdag.store.rocksdb.transaction.TransactionException if transaction operation fails
   * @see #addBalanceInTransaction
   */
  public void incrementNonceInTransaction(String txId, Bytes address)
      throws io.xdag.store.rocksdb.transaction.TransactionException {
    // Get current nonce
    UInt64 currentNonce = getNonce(address);
    UInt64 newNonce = currentNonce.add(UInt64.ONE);

    // Convert UInt64 to UInt256 for AccountStore method
    UInt256 nonceAsUInt256 = UInt256.valueOf(newNonce.toBigInteger());
    accountStore.setNonceInTransaction(txId, address, nonceAsUInt256);

    if (log.isDebugEnabled()) {
      log.debug("Transaction {}: incrementNonce({}) from {} to {}",
          txId, address.toHexString().substring(0, 16),
          currentNonce.toLong(), newNonce.toLong());
    }
  }

}
