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

import io.xdag.core.Account;
import io.xdag.core.XdagLifecycle;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AccountStore - EVM-compatible account state storage for XDAG
 *
 * <p>This is the primary storage interface for managing account state in XDAG.
 * It provides CRUD operations for accounts with EVM-compatible semantics.
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li>EVM-compatible account model (address, balance, nonce)</li>
 *   <li>Support for both EOA and contract accounts</li>
 *   <li>Efficient RocksDB storage with caching</li>
 *   <li>Atomic batch operations</li>
 *   <li>Balance and nonce management</li>
 * </ul>
 *
 * <h2>Storage Layout</h2>
 * <pre>
 * RocksDB Keys:
 *   0x01 + address(20) → Account data (61-125 bytes)
 *   0x02 + codeHash(32) → Contract code bytes
 *   0x03 → Total account count (UInt64)
 *   0x04 → Total balance sum (UInt256)
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * AccountStore store = new AccountStoreImpl(db);
 * store.start();
 *
 * // Create new account
 * Account account = Account.createEOA(address);
 * store.saveAccount(account);
 *
 * // Get account
 * Optional&lt;Account&gt; retrieved = store.getAccount(address);
 *
 * // Update balance
 * Account updated = account.withBalance(UInt256.valueOf(1000));
 * store.saveAccount(updated);
 *
 * // Increment nonce
 * store.incrementNonce(address);
 * </pre>
 *
 * @since AccountStore
 */
public interface AccountStore extends XdagLifecycle {

    // ==================== CRUD Operations ====================

    /**
     * Save or update an account
     *
     * <p>If account already exists, it will be overwritten.
     * This operation is atomic.
     *
     * @param account account to save
     */
    void saveAccount(Account account);

    /**
     * Get account by address
     *
     * @param address account address (20 bytes, hash160)
     * @return Optional containing Account if exists, empty otherwise
     */
    Optional<Account> getAccount(Bytes address);

    /**
     * Check if account exists
     *
     * @param address account address (20 bytes)
     * @return true if account exists
     */
    boolean hasAccount(Bytes address);

    /**
     * Delete account
     *
     * <p>This removes the account from storage. Use with caution as this
     * operation cannot be undone.
     *
     * @param address account address (20 bytes)
     * @return true if account was deleted, false if it didn't exist
     */
    boolean deleteAccount(Bytes address);

    // ==================== Balance Operations ====================

    /**
     * Get account balance
     *
     * @param address account address (20 bytes)
     * @return balance (UInt256.ZERO if account doesn't exist)
     */
    UInt256 getBalance(Bytes address);

    /**
     * Update account balance
     *
     * <p>If account doesn't exist, creates a new EOA account with the balance.
     * If account exists, updates its balance.
     *
     * @param address account address (20 bytes)
     * @param balance new balance
     */
    void setBalance(Bytes address, UInt256 balance);

    /**
     * Add to account balance
     *
     * @param address account address (20 bytes)
     * @param amount amount to add
     * @return new balance after addition
     */
    UInt256 addBalance(Bytes address, UInt256 amount);

    /**
     * Subtract from account balance
     *
     * @param address account address (20 bytes)
     * @param amount amount to subtract
     * @return new balance after subtraction
     * @throws IllegalArgumentException if insufficient balance
     */
    UInt256 subtractBalance(Bytes address, UInt256 amount);

    /**
     * Get total balance of all accounts
     *
     * @return sum of all account balances
     */
    UInt256 getTotalBalance();

    // ==================== Nonce Operations ====================

    /**
     * Get account nonce
     *
     * @param address account address (20 bytes)
     * @return nonce (UInt64.ZERO if account doesn't exist)
     */
    UInt64 getNonce(Bytes address);

    /**
     * Set account nonce
     *
     * <p>If account doesn't exist, creates a new EOA account with the nonce.
     *
     * @param address account address (20 bytes)
     * @param nonce new nonce value
     */
    void setNonce(Bytes address, UInt64 nonce);

    /**
     * Increment account nonce by 1
     *
     * <p>Used when processing transactions. If account doesn't exist,
     * creates a new EOA with nonce = 1.
     *
     * @param address account address (20 bytes)
     * @return new nonce value after increment
     */
    UInt64 incrementNonce(Bytes address);

    /**
     * Decrement account nonce by 1
     *
     * <p>Used during transaction rollback when restoring account state.
     * If account doesn't exist or nonce is already zero, throws IllegalStateException.
     *
     * @param address account address (20 bytes)
     * @return new nonce value after decrement
     * @throws IllegalStateException if account doesn't exist or nonce is zero
     */
    UInt64 decrementNonce(Bytes address);

    // ==================== Contract Operations ====================

    /**
     * Save contract code
     *
     * @param codeHash hash of contract code (used as key)
     * @param code contract bytecode
     */
    void saveContractCode(Bytes32 codeHash, byte[] code);

    /**
     * Get contract code by hash
     *
     * @param codeHash hash of contract code
     * @return Optional containing code bytes if exists
     */
    Optional<byte[]> getContractCode(Bytes32 codeHash);

    /**
     * Check if contract code exists
     *
     * @param codeHash hash of contract code
     * @return true if code exists
     */
    boolean hasContractCode(Bytes32 codeHash);

    /**
     * Create a new contract account
     *
     * @param address contract address (20 bytes)
     * @param codeHash hash of contract code
     * @param storageRoot root of contract storage
     * @return created contract Account
     */
    Account createContractAccount(Bytes address, Bytes32 codeHash, Bytes32 storageRoot);

    // ==================== Batch Operations ====================

    /**
     * Save multiple accounts atomically
     *
     * <p>All accounts are saved in a single WriteBatch for atomicity.
     *
     * @param accounts list of accounts to save
     */
    void saveAccounts(List<Account> accounts);

    /**
     * Get multiple accounts by addresses
     *
     * @param addresses list of account addresses (20 bytes each)
     * @return map of address to Account (excludes non-existent accounts)
     */
    Map<Bytes, Account> getAccounts(List<Bytes> addresses);

    // ==================== Statistics ====================

    /**
     * Get total number of accounts
     *
     * @return account count
     */
    UInt64 getAccountCount();

    /**
     * Get all account addresses (use with caution on large datasets)
     *
     * @param limit maximum number of addresses to return
     * @return list of account addresses (20 bytes each)
     */
    List<Bytes> getAllAddresses(int limit);

    // ==================== Maintenance ====================

    /**
     * Reset all account data
     *
     * <p>WARNING: This deletes ALL accounts and cannot be undone!
     * Only use for testing or migration.
     */
    void reset();

    /**
     * Get database size in bytes
     *
     * @return approximate size of AccountStore data on disk
     */
    long getDatabaseSize();
}
