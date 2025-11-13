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
}
