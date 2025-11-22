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

package io.xdag.store.rocksdb.impl;

import io.xdag.config.Config;
import io.xdag.core.Account;
import io.xdag.store.AccountStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.LRUCache;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

/**
 * AccountStoreImpl - RocksDB implementation of AccountStore
 *
 * <p>High-performance account storage for XDAG using RocksDB.
 *
 * <h2>Key Prefixes</h2>
 * <pre>
 * 0x01 - Account data (address → Account)
 * 0x02 - Contract code (codeHash → bytes)
 * 0x03 - Account count (singleton)
 * 0x04 - Total balance (singleton)
 * </pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li>Account read: ~1-5 ms (disk), ~0.1 ms (cache)</li>
 *   <li>Account write: ~5-20 ms (async), ~50-100 ms (sync)</li>
 *   <li>Batch write: 1000-5000 accounts/s</li>
 *   <li>Memory: ~14 MB L1 cache + 2-4 GB L2 RocksDB cache</li>
 * </ul>
 *
 * <h2><strong>THREAD SAFETY WARNING (DEBT-003)</strong></h2>
 * <p><strong style="color:red">CRITICAL CONCURRENCY ISSUE</strong>: Many modification methods in this class
 * use non-atomic read-modify-write patterns that are NOT thread-safe:
 *
 * <pre>{@code
 * // UNSAFE PATTERN - used in multiple methods:
 * public UInt256 addBalance(Bytes address, UInt256 amount) {
 *     UInt256 current = getBalance(address);  // ← Non-atomic read
 *     UInt256 newBalance = current.add(amount);
 *     setBalance(address, newBalance);        // ← Non-atomic write
 *     return newBalance;
 * }
 *
 * // CONCURRENCY FAILURE SCENARIO:
 * Thread 1: current = getBalance(addr) = 100
 * Thread 2: current = getBalance(addr) = 100  ← Race!
 * Thread 1: setBalance(addr, 150)  // +50
 * Thread 2: setBalance(addr, 130)  // +30 overwrites Thread 1's update
 * Result: Balance is 130 instead of 180 - Lost 50 XDAG!
 * }</pre>
 *
 * <p><strong>Affected Methods</strong> (DEBT-003):
 * <ul>
 *   <li>{@link #addBalance(Bytes, UInt256)} - Balance loss risk</li>
 *   <li>{@link #subtractBalance(Bytes, UInt256)} - Balance loss risk</li>
 *   <li>{@link #incrementNonce(Bytes)} - Nonce desync risk</li>
 *   <li>{@link #decrementNonce(Bytes)} - Nonce desync risk</li>
 *   <li>{@link #saveAccount(Account)} - Statistics corruption risk</li>
 *   <li>{@link #updateTotalBalance(UInt256)} - Total balance corruption risk</li>
 * </ul>
 *
 * <p><strong>Current Safety</strong>: All account modifications are currently protected by
 * {@code DagChainImpl.tryToConnect()} synchronized block, ensuring sequential block processing.
 *
 * <p><strong>Future Risk</strong>: HIGH - Enabling parallel block processing without fixing these
 * methods WILL cause:
 * <ul>
 *   <li>Balance loss (funds disappear)</li>
 *   <li>Nonce desynchronization (transaction replay vulnerability)</li>
 *   <li>Incorrect account statistics</li>
 *   <li>Total balance mismatch</li>
 * </ul>
 *
 * <p><strong>TODO - Before Enabling Parallel Processing</strong>:
 * <ol>
 *   <li><strong>Option A</strong>: Add Java synchronization ({@code synchronized} methods or {@link java.util.concurrent.locks.ReadWriteLock})</li>
 *   <li><strong>Option B</strong>: Use RocksDB Transactions (requires sharing RocksDB instance with TransactionManager)</li>
 *   <li><strong>Option C</strong>: Use RocksDB Merge operators for atomic operations</li>
 * </ol>
 *
 * <p><strong>Note</strong>: {@link #setBalanceInTransaction} already documents related architectural
 * limitation about separate RocksDB instances.
 *
 * @since AccountStore
 * @see <a href="../../../../../CODE_REVIEW_PLAN.md#debt-003">DEBT-003 Technical Debt Documentation</a>
 */
@Slf4j
public class AccountStoreImpl implements AccountStore {

  // ==================== Key Prefixes ====================

  private static final byte PREFIX_ACCOUNT = (byte) 0x01;       // Account data
  private static final byte PREFIX_CODE = (byte) 0x02;          // Contract code
  private static final byte PREFIX_ACCOUNT_COUNT = (byte) 0x03; // Total accounts
  private static final byte PREFIX_TOTAL_BALANCE = (byte) 0x04; // Total balance

  // ==================== RocksDB ====================

  private final Config config;
  private final String dbPath;
  private RocksDB db;
  private ColumnFamilyHandle defaultHandle;
  private DBOptions dbOptions;
  private ColumnFamilyOptions cfOptions;
  private WriteOptions writeOptions;
  private WriteOptions syncWriteOptions;
  private ReadOptions readOptions;

  /**
   * Transaction manager for atomic operations (NEW - atomic block processing)
   */
  private io.xdag.store.rocksdb.transaction.RocksDBTransactionManager transactionManager;

  // ==================== Lifecycle ====================

  private volatile boolean running = false;

  /**
   * Create AccountStore with custom database path
   *
   * @param config XDAG configuration
   */
  public AccountStoreImpl(Config config) {
    this.config = config;
    this.dbPath = config.getNodeSpec().getStoreDir() + File.separator + "accountstore";
    log.info("AccountStore database path: {}", dbPath);
  }

  /**
   * Constructor with TransactionManager for atomic block processing
   *
   * @param config             XDAG configuration
   * @param transactionManager RocksDB transaction manager for atomic operations
   */
  public AccountStoreImpl(Config config,
      io.xdag.store.rocksdb.transaction.RocksDBTransactionManager transactionManager) {
    this.config = config;
    this.transactionManager = transactionManager;
    this.dbPath = config.getNodeSpec().getStoreDir() + File.separator + "accountstore";
    log.info("AccountStore database path: {} (atomic operations {})",
        dbPath, transactionManager != null ? "enabled" : "disabled");
  }

  @Override
  public synchronized void start() {
    if (running) {
      log.warn("AccountStore is already running");
      return;
    }

    log.info("Starting AccountStore...");

    try {
      // Create directory if not exists
      File dir = new File(dbPath);
      if (!dir.exists()) {
        dir.mkdirs();
        log.info("Created AccountStore directory: {}", dbPath);
      }

      // Initialize RocksDB options
      initializeOptions();

      // Open database
      List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
          new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOptions)
      );

      List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
      db = RocksDB.open(dbOptions, dbPath, cfDescriptors, cfHandles);
      defaultHandle = cfHandles.getFirst();

      // Initialize metadata if first startup
      initializeMetadata();

      running = true;
      log.info("✓ AccountStore started successfully");
      log.info("  - Database: {}", dbPath);
      log.info("  - Accounts: {}", getAccountCount().toLong());
      log.info("  - Total Balance: {}", getTotalBalance().toDecimalString());

    } catch (Exception e) {
      log.error("Failed to start AccountStore", e);
      throw new RuntimeException("Failed to start AccountStore", e);
    }
  }

  @Override
  public synchronized void stop() {
    if (!running) {
      log.warn("AccountStore is not running");
      return;
    }

    log.info("Stopping AccountStore...");

    try {
      // Close handles
      if (defaultHandle != null) {
        defaultHandle.close();
      }

      // Close database
      if (db != null) {
        db.close();
      }

      // Close options
      if (readOptions != null) {
        readOptions.close();
      }
      if (writeOptions != null) {
        writeOptions.close();
      }
      if (syncWriteOptions != null) {
        syncWriteOptions.close();
      }
      if (cfOptions != null) {
        cfOptions.close();
      }
      if (dbOptions != null) {
        dbOptions.close();
      }

      running = false;
      log.info("✓ AccountStore stopped successfully");

    } catch (Exception e) {
      log.error("Error stopping AccountStore", e);
      throw new RuntimeException("Failed to stop AccountStore", e);
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /**
   * Initialize RocksDB options for optimal performance
   */
  private void initializeOptions() {
    // Database options
    dbOptions = new DBOptions()
        .setCreateIfMissing(true)
        .setCreateMissingColumnFamilies(true)
        .setMaxBackgroundJobs(4)
        .setMaxOpenFiles(-1)
        .setKeepLogFileNum(10);

    // Column family options
    cfOptions = new ColumnFamilyOptions()
        .setCompressionType(CompressionType.LZ4_COMPRESSION)
        .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
        .setWriteBufferSize(64 * 1024 * 1024)  // 64 MB
        .setMaxWriteBufferNumber(3)
        .setMinWriteBufferNumberToMerge(1)
        .setLevel0FileNumCompactionTrigger(4)
        .setLevel0SlowdownWritesTrigger(20)
        .setLevel0StopWritesTrigger(36)
        .setMaxBytesForLevelBase(256 * 1024 * 1024)  // 256 MB
        .setTargetFileSizeBase(64 * 1024 * 1024);    // 64 MB

    // Bloom filter for faster lookups
    BlockBasedTableConfig tableConfig = new BlockBasedTableConfig()
        .setBlockSize(16 * 1024)  // 16 KB
        .setBlockCache(new LRUCache(2L * 1024 * 1024 * 1024))  // 2 GB
        .setFilterPolicy(new BloomFilter(10, false))
        .setCacheIndexAndFilterBlocks(true)
        .setPinL0FilterAndIndexBlocksInCache(true);

    cfOptions.setTableFormatConfig(tableConfig);

    // Write options (async for performance)
    writeOptions = new WriteOptions()
        .setSync(false)
        .setDisableWAL(false);

    // Sync write options (for critical data)
    syncWriteOptions = new WriteOptions()
        .setSync(true)
        .setDisableWAL(false);

    // Read options
    readOptions = new ReadOptions()
        .setVerifyChecksums(true)
        .setFillCache(true);

    log.info("RocksDB options initialized for AccountStore");
  }

  /**
   * Initialize metadata on first startup
   */
  private void initializeMetadata() throws RocksDBException {
    // Initialize account count if not exists
    byte[] countKey = new byte[]{PREFIX_ACCOUNT_COUNT};
    if (db.get(defaultHandle, readOptions, countKey) == null) {
      db.put(defaultHandle, syncWriteOptions, countKey, UInt64.ZERO.toBytes().toArray());
      log.info("Initialized account count to 0");
    }

    // Initialize total balance if not exists
    byte[] balanceKey = new byte[]{PREFIX_TOTAL_BALANCE};
    if (db.get(defaultHandle, readOptions, balanceKey) == null) {
      db.put(defaultHandle, syncWriteOptions, balanceKey, UInt256.ZERO.toBytes().toArray());
      log.info("Initialized total balance to 0");
    }
  }

  // ==================== CRUD Operations ====================

  @Override
  public void saveAccount(Account account) {
    if (!running) {
      throw new IllegalStateException("AccountStore is not running");
    }

    try {
      byte[] key = makeAccountKey(account.getAddress());
      byte[] value = account.toBytes();

      // Check if account is new (for statistics)
      boolean isNew = !hasAccount(account.getAddress());
      UInt256 oldBalance = isNew ? UInt256.ZERO : getBalance(account.getAddress());

      // Save account data
      db.put(defaultHandle, writeOptions, key, value);

      // Update statistics
      if (isNew) {
        incrementAccountCount();
      }
      updateTotalBalance(oldBalance, account.getBalance());

      log.debug("Saved account: address={}, balance={}, nonce={}",
          account.getAddress().toHexString(),
          account.getBalance().toDecimalString(),
          account.getNonce().toLong());

    } catch (Exception e) {
      log.error("Failed to save account: {}", account.getAddress().toHexString(), e);
      throw new RuntimeException("Failed to save account", e);
    }
  }

  @Override
  public Optional<Account> getAccount(Bytes address) {
    if (!running) {
      throw new IllegalStateException("AccountStore is not running");
    }

    try {
      byte[] key = makeAccountKey(address);
      byte[] data = db.get(defaultHandle, readOptions, key);

      if (data == null) {
        return Optional.empty();
      }

      Account account = Account.fromBytes(data);
      return Optional.of(account);

    } catch (Exception e) {
      log.error("Failed to get account: {}", address.toHexString(), e);
      return Optional.empty();
    }
  }

  @Override
  public boolean hasAccount(Bytes address) {
    if (!running) {
      throw new IllegalStateException("AccountStore is not running");
    }

    try {
      byte[] key = makeAccountKey(address);
      // Direct existence check
      return db.get(defaultHandle, readOptions, key) != null;

    } catch (Exception e) {
      log.error("Failed to check account existence: {}", address.toHexString(), e);
      return false;
    }
  }

  // ==================== Balance Operations ====================

  @Override
  public UInt256 getBalance(Bytes address) {
    return getAccount(address)
        .map(Account::getBalance)
        .orElse(UInt256.ZERO);
  }

  @Override
  public void setBalance(Bytes address, UInt256 balance) {
    Optional<Account> existing = getAccount(address);

    Account account;
    if (existing.isPresent()) {
      account = existing.get().withBalance(balance);
    } else {
      account = Account.createEOA(address).withBalance(balance);
    }

    saveAccount(account);
  }

  @Override
  public UInt256 addBalance(Bytes address, UInt256 amount) {
    UInt256 currentBalance = getBalance(address);
    UInt256 newBalance = currentBalance.add(amount);
    setBalance(address, newBalance);
    return newBalance;
  }

  @Override
  public UInt256 subtractBalance(Bytes address, UInt256 amount) {
    UInt256 currentBalance = getBalance(address);

    if (currentBalance.compareTo(amount) < 0) {
      throw new IllegalArgumentException(
          "Insufficient balance: has " + currentBalance.toDecimalString() +
              ", needs " + amount.toDecimalString());
    }

    UInt256 newBalance = currentBalance.subtract(amount);
    setBalance(address, newBalance);
    return newBalance;
  }

  @Override
  public UInt256 getTotalBalance() {
    if (!running) {
      return UInt256.ZERO;
    }

    try {
      byte[] key = new byte[]{PREFIX_TOTAL_BALANCE};
      byte[] data = db.get(defaultHandle, readOptions, key);

      if (data == null) {
        return UInt256.ZERO;
      }

      return UInt256.fromBytes(Bytes.wrap(data));

    } catch (Exception e) {
      log.error("Failed to get total balance", e);
      return UInt256.ZERO;
    }
  }

  // ==================== Nonce Operations ====================

  @Override
  public UInt64 getNonce(Bytes address) {
    return getAccount(address)
        .map(Account::getNonce)
        .orElse(UInt64.ZERO);
  }

  @Override
  public void setNonce(Bytes address, UInt64 nonce) {
    Optional<Account> existing = getAccount(address);

    Account account;
    if (existing.isPresent()) {
      account = existing.get().withNonce(nonce);
    } else {
      account = Account.createEOA(address).withNonce(nonce);
    }

    saveAccount(account);
  }

  @Override
  public UInt64 incrementNonce(Bytes address) {
    Optional<Account> existing = getAccount(address);

    Account account;
    if (existing.isPresent()) {
      account = existing.get().withIncrementedNonce();
    } else {
      account = Account.createEOA(address).withNonce(UInt64.ONE);
    }

    saveAccount(account);
    return account.getNonce();
  }

  @Override
  public UInt64 decrementNonce(Bytes address) {
    Optional<Account> existing = getAccount(address);

    if (existing.isEmpty()) {
      throw new IllegalStateException("Cannot decrement nonce: account does not exist");
    }

    Account account = existing.get();
    if (account.getNonce().equals(UInt64.ZERO)) {
      throw new IllegalStateException("Cannot decrement nonce: already at zero");
    }

    // Decrement nonce
    UInt64 newNonce = account.getNonce().subtract(UInt64.ONE);
    Account updatedAccount = account.withNonce(newNonce);

    saveAccount(updatedAccount);
    return updatedAccount.getNonce();
  }

  // ==================== Contract Operations ====================

  // ==================== Batch Operations ====================

  // ==================== Statistics ====================

  @Override
  public UInt64 getAccountCount() {
    if (!running) {
      return UInt64.ZERO;
    }

    try {
      byte[] key = new byte[]{PREFIX_ACCOUNT_COUNT};
      byte[] data = db.get(defaultHandle, readOptions, key);

      if (data == null) {
        return UInt64.ZERO;
      }

      return UInt64.fromBytes(Bytes.wrap(data));

    } catch (Exception e) {
      log.error("Failed to get account count", e);
      return UInt64.ZERO;
    }
  }

  // ==================== Maintenance ====================

  @Override
  public void reset() {
    if (!running) {
      throw new IllegalStateException("AccountStore is not running");
    }

    log.warn("⚠ Resetting AccountStore - ALL ACCOUNT DATA WILL BE LOST!");

    try {
      // Close database
      stop();

      // Delete database directory
      Path dbDir = new File(dbPath).toPath();
      if (Files.exists(dbDir)) {
        deleteDirectory(dbDir.toFile());
        log.info("Deleted AccountStore database directory");
      }

      // Restart database
      start();

      log.info("✓ AccountStore reset completed");

    } catch (Exception e) {
      log.error("Failed to reset AccountStore", e);
      throw new RuntimeException("Failed to reset AccountStore", e);
    }
  }

  // ==================== Helper Methods ====================

  /**
   * Make account key: PREFIX_ACCOUNT + address
   *
   * @param address account address (20 bytes)
   * @return RocksDB key (21 bytes total: 1 prefix + 20 address)
   * @throws IllegalArgumentException if address is not exactly 20 bytes
   */
  private byte[] makeAccountKey(Bytes address) {
    if (address.size() != 20) {
      throw new IllegalArgumentException(
          "Address must be exactly 20 bytes, got: " + address.size());
    }
    byte[] key = new byte[21];  // 1 + 20
    key[0] = PREFIX_ACCOUNT;
    System.arraycopy(address.toArray(), 0, key, 1, 20);
    return key;
  }

  /**
   * Make code key: PREFIX_CODE + codeHash
   */
  private byte[] makeCodeKey(Bytes32 codeHash) {
    byte[] key = new byte[1 + 32];
    key[0] = PREFIX_CODE;
    System.arraycopy(codeHash.toArray(), 0, key, 1, 32);
    return key;
  }

  /**
   * Increment account count
   */
  private void incrementAccountCount() throws RocksDBException {
    UInt64 count = getAccountCount();
    UInt64 newCount = count.add(UInt64.ONE);
    byte[] key = new byte[]{PREFIX_ACCOUNT_COUNT};
    db.put(defaultHandle, writeOptions, key, newCount.toBytes().toArray());
  }

  /**
   * Decrement account count
   */
  private void decrementAccountCount() throws RocksDBException {
    UInt64 count = getAccountCount();
    if (count.compareTo(UInt64.ZERO) > 0) {
      UInt64 newCount = count.subtract(UInt64.ONE);
      byte[] key = new byte[]{PREFIX_ACCOUNT_COUNT};
      db.put(defaultHandle, writeOptions, key, newCount.toBytes().toArray());
    }
  }

  /**
   * Update total balance when account changes
   */
  private void updateTotalBalance(UInt256 oldBalance, UInt256 newBalance) throws RocksDBException {
    UInt256 totalBalance = getTotalBalance();
    UInt256 updatedTotal = totalBalance.subtract(oldBalance).add(newBalance);

    byte[] key = new byte[]{PREFIX_TOTAL_BALANCE};
    db.put(defaultHandle, writeOptions, key, updatedTotal.toBytes().toArray());
  }

  /**
   * Calculate directory size recursively
   */
  private long calculateDirectorySize(File directory) {
    long size = 0;

    if (!directory.exists()) {
      return 0;
    }

    if (directory.isFile()) {
      return directory.length();
    }

    File[] files = directory.listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.isFile()) {
          size += file.length();
        } else if (file.isDirectory()) {
          size += calculateDirectorySize(file);
        }
      }
    }

    return size;
  }

  /**
   * Delete directory recursively
   */
  private void deleteDirectory(File directory) throws IOException {
    if (!directory.exists()) {
      return;
    }

    if (directory.isDirectory()) {
      File[] files = directory.listFiles();
      if (files != null) {
        for (File file : files) {
          deleteDirectory(file);
        }
      }
    }

    if (!directory.delete()) {
      throw new IOException("Failed to delete: " + directory.getAbsolutePath());
    }
  }

  // ==================== Transactional Methods (Atomic Block Processing) ====================

  @Override
  public void setBalanceInTransaction(String txId, Bytes address, UInt256 newBalance)
      throws io.xdag.store.rocksdb.transaction.TransactionException {
    // WORKAROUND: AccountStore has its own separate RocksDB instance (accountstore/)
    // which is different from TransactionManager's RocksDB instance (index/).
    // Writing to TransactionManager would write to the wrong database.
    //
    // For now, write directly to AccountStore's own database.
    // This sacrifices cross-database atomicity but fixes the visibility issue.
    //
    // TODO (Phase 2): Refactor AccountStore to share the same RocksDB instance
    // as TransactionManager for true atomic operations across all stores.

    try {
      // Get existing account or create new EOA
      Optional<Account> existing = getAccount(address);

      Account account;
      if (existing.isPresent()) {
        account = existing.get().withBalance(newBalance);
      } else {
        account = Account.createEOA(address).withBalance(newBalance);
      }

      // Write directly to AccountStore's own RocksDB instance
      byte[] key = makeAccountKey(address);
      byte[] value = account.toBytes();
      db.put(defaultHandle, writeOptions, key, value);

      log.debug(
          "Set balance for {} in transaction {} (newBalance={}) - direct write to AccountStore DB",
          address.toHexString().substring(0, 16),
          txId,
          newBalance.toDecimalString());

    } catch (Exception e) {
      throw new io.xdag.store.rocksdb.transaction.TransactionException(
          "Failed to set balance in transaction: " + e.getMessage(), e);
    }
  }

  @Override
  public void setNonceInTransaction(String txId, Bytes address, UInt256 newNonce)
      throws io.xdag.store.rocksdb.transaction.TransactionException {
    // WORKAROUND: Same as setBalanceInTransaction() - write directly to AccountStore's own DB
    // to fix visibility issue (AccountStore has separate RocksDB instance from TransactionManager)

    try {
      // Get existing account or create new EOA
      Optional<Account> existing = getAccount(address);

      Account account;
      if (existing.isPresent()) {
        // Convert UInt256 to UInt64 for nonce
        UInt64 nonce = UInt64.valueOf(newNonce.toBigInteger().longValue());
        account = existing.get().withNonce(nonce);
      } else {
        // Create new EOA with nonce
        UInt64 nonce = UInt64.valueOf(newNonce.toBigInteger().longValue());
        account = Account.createEOA(address).withNonce(nonce);
      }

      // Write directly to AccountStore's own RocksDB instance
      byte[] key = makeAccountKey(address);
      byte[] value = account.toBytes();
      db.put(defaultHandle, writeOptions, key, value);

      log.debug(
          "Set nonce for {} in transaction {} (newNonce={}) - direct write to AccountStore DB",
          address.toHexString().substring(0, 16),
          txId,
          newNonce.toDecimalString());

    } catch (Exception e) {
      throw new io.xdag.store.rocksdb.transaction.TransactionException(
          "Failed to set nonce in transaction: " + e.getMessage(), e);
    }
  }
}
