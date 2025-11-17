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
import io.xdag.db.AccountStore;
import io.xdag.db.rocksdb.transaction.TransactionException;
import io.xdag.db.rocksdb.transaction.TransactionalStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * DagAccountManager - Pure account state management for Dag layer
 *
 * <p>This class provides CRUD operations for account state management.
 * It does NOT contain transaction processing logic - that's handled by
 * DagTransactionProcessor.
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
    private TransactionalStore transactionalStore;  // Optional: for transactional operations

    /**
     * Create DagAccountManager
     *
     * @param accountStore account storage
     * @param config XDAG configuration
     */
    public DagAccountManager(AccountStore accountStore, Config config) {
        this.accountStore = accountStore;
        this.config = config;
    }

    /**
     * Set the transactional store for atomic operations.
     *
     * <p>This must be called to enable transactional operations
     * (xxxInTransaction methods). If not set, calling transactional
     * methods will throw IllegalStateException.
     *
     * @param transactionalStore transactional store instance
     * @since Phase 0.5 - Transaction Support Infrastructure
     */
    public void setTransactionalStore(TransactionalStore transactionalStore) {
        this.transactionalStore = transactionalStore;
        log.info("TransactionalStore configured for DagAccountManager");
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
     * @param amount amount to add
     * @return new balance
     */
    public UInt256 addBalance(Bytes address, UInt256 amount) {
        return accountStore.addBalance(address, amount);
    }

    /**
     * Subtract from account balance
     *
     * @param address account address (20 bytes)
     * @param amount amount to subtract
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

    /**
     * Set account nonce
     *
     * @param address account address (20 bytes)
     * @param nonce new nonce value
     */
    public void setNonce(Bytes address, UInt64 nonce) {
        accountStore.setNonce(address, nonce);
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

    /**
     * Create a new account with initial balance
     *
     * @param address account address (20 bytes)
     * @param initialBalance initial balance
     */
    public void createAccount(Bytes address, UInt256 initialBalance) {
        Account account = Account.builder()
                .address(address)
                .balance(initialBalance)
                .nonce(UInt64.ZERO)
                .build();
        accountStore.saveAccount(account);
        log.debug("Created new account: {}, balance={}",
                address.toHexString(), initialBalance.toDecimalString());
    }

    // ==================== Transactional Operations (Phase 0.5) ====================

    /**
     * Check if transactional store is available.
     *
     * @throws IllegalStateException if transactional store not configured
     */
    private void ensureTransactionalStoreAvailable() {
        if (transactionalStore == null) {
            throw new IllegalStateException(
                    "TransactionalStore not configured. Call setTransactionalStore() first.");
        }
    }

    /**
     * Set account balance in a transaction.
     *
     * <p>This operation is queued and will be executed atomically when the transaction commits.
     *
     * @param txId transaction ID
     * @param address account address
     * @param balance new balance
     * @throws TransactionException if transaction operation fails
     * @since Phase 0.5 - Transaction Support Infrastructure
     */
    public void setBalanceInTransaction(String txId, Bytes address, UInt256 balance)
            throws TransactionException {
        ensureTransactionalStoreAvailable();

        // Get current account state
        Account account = accountStore.getAccount(address).orElse(null);
        if (account == null) {
            account = Account.createEOA(address);
        }

        // Update balance
        Account updatedAccount = account.toBuilder()
                .balance(balance)
                .build();

        // Serialize and store in transaction
        byte[] key = address.toArray();
        byte[] value = updatedAccount.toBytes();
        transactionalStore.putInTransaction(txId, key, value);

        if (log.isDebugEnabled()) {
            log.debug("Transaction {}: setBalance({}, {})",
                    txId, address.toHexString().substring(0, 16), balance.toDecimalString());
        }
    }

    /**
     * Add to account balance in a transaction.
     *
     * @param txId transaction ID
     * @param address account address
     * @param amount amount to add
     * @throws TransactionException if transaction operation fails
     * @since Phase 0.5 - Transaction Support Infrastructure
     */
    public void addBalanceInTransaction(String txId, Bytes address, UInt256 amount)
            throws TransactionException {
        ensureTransactionalStoreAvailable();

        UInt256 currentBalance = getBalance(address);
        UInt256 newBalance = currentBalance.add(amount);
        setBalanceInTransaction(txId, address, newBalance);
    }

    /**
     * Subtract from account balance in a transaction.
     *
     * @param txId transaction ID
     * @param address account address
     * @param amount amount to subtract
     * @throws TransactionException if transaction operation fails
     * @throws IllegalArgumentException if insufficient balance
     * @since Phase 0.5 - Transaction Support Infrastructure
     */
    public void subtractBalanceInTransaction(String txId, Bytes address, UInt256 amount)
            throws TransactionException {
        ensureTransactionalStoreAvailable();

        UInt256 currentBalance = getBalance(address);
        if (currentBalance.compareTo(amount) < 0) {
            throw new IllegalArgumentException(String.format(
                    "Insufficient balance: have %s, need %s",
                    currentBalance.toDecimalString(), amount.toDecimalString()));
        }

        UInt256 newBalance = currentBalance.subtract(amount);
        setBalanceInTransaction(txId, address, newBalance);
    }

    /**
     * Increment account nonce in a transaction.
     *
     * @param txId transaction ID
     * @param address account address
     * @throws TransactionException if transaction operation fails
     * @since Phase 0.5 - Transaction Support Infrastructure
     */
    public void incrementNonceInTransaction(String txId, Bytes address)
            throws TransactionException {
        ensureTransactionalStoreAvailable();

        // Get current account state
        Account account = accountStore.getAccount(address).orElse(null);
        if (account == null) {
            account = Account.createEOA(address);
        }

        // Increment nonce
        UInt64 newNonce = account.getNonce().add(UInt64.ONE);
        Account updatedAccount = account.toBuilder()
                .nonce(newNonce)
                .build();

        // Serialize and store in transaction
        byte[] key = address.toArray();
        byte[] value = updatedAccount.toBytes();
        transactionalStore.putInTransaction(txId, key, value);

        if (log.isDebugEnabled()) {
            log.debug("Transaction {}: incrementNonce({}) to {}",
                    txId, address.toHexString().substring(0, 16), newNonce.toLong());
        }
    }

    /**
     * Decrement account nonce in a transaction.
     *
     * <p>Used during transaction rollback when restoring account state.
     *
     * @param txId transaction ID
     * @param address account address
     * @throws TransactionException if transaction operation fails or nonce is already zero
     * @since Phase 0.5 - Transaction Support Infrastructure
     */
    public void decrementNonceInTransaction(String txId, Bytes address)
            throws TransactionException {
        ensureTransactionalStoreAvailable();

        // Get current account state
        Account account = accountStore.getAccount(address).orElse(null);
        if (account == null) {
            throw new TransactionException("Cannot decrement nonce: account does not exist");
        }

        if (account.getNonce().equals(UInt64.ZERO)) {
            throw new TransactionException("Cannot decrement nonce: already at zero");
        }

        // Decrement nonce
        UInt64 newNonce = account.getNonce().subtract(UInt64.ONE);
        Account updatedAccount = account.toBuilder()
                .nonce(newNonce)
                .build();

        // Serialize and store in transaction
        byte[] key = address.toArray();
        byte[] value = updatedAccount.toBytes();
        transactionalStore.putInTransaction(txId, key, value);

        if (log.isDebugEnabled()) {
            log.debug("Transaction {}: decrementNonce({}) to {}",
                    txId, address.toHexString().substring(0, 16), newNonce.toLong());
        }
    }
}
