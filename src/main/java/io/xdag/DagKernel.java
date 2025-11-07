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

package io.xdag;

import io.xdag.config.Config;
import io.xdag.consensus.HybridSyncManager;
import io.xdag.core.DagAccountManager;
import io.xdag.core.DagBlockProcessor;
import io.xdag.core.DagChain;
import io.xdag.core.DagChainImpl;
import io.xdag.core.DagTransactionProcessor;
import io.xdag.db.AccountStore;
import io.xdag.db.DagStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.TransactionStore;
import io.xdag.db.rocksdb.AccountStoreImpl;
import io.xdag.db.rocksdb.DagStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.rocksdb.TransactionStoreImpl;
import io.xdag.db.store.DagCache;
import io.xdag.db.store.DagEntityResolver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * DagKernel - Standalone kernel for XDAG v5.1 DagStore architecture
 *
 * <p>This is a completely new kernel designed specifically for the v5.1 data structures.
 * It does NOT depend on the legacy io.xdag.Kernel and manages all components independently.
 *
 * <h2>Architecture</h2>
 * <pre>
 * DagKernel (Standalone Complete Kernel)
 *   ├── Storage Layer
 *   │   ├── DagStore (Block persistence)
 *   │   ├── TransactionStore (Transaction persistence)
 *   │   ├── AccountStore (Account state - EVM compatible)
 *   │   └── OrphanBlockStore (Orphan block management)
 *   │
 *   ├── Cache Layer
 *   │   ├── DagCache (L1 Caffeine cache)
 *   │   └── DagEntityResolver (Unified facade)
 *   │
 *   └── Consensus Layer
 *       └── DagChain (DAG consensus implementation)
 * </pre>
 *
 * <h2>Design Goals</h2>
 * <ul>
 *   <li>Complete independence from legacy io.xdag.Kernel</li>
 *   <li>Designed specifically for v5.1 data structures</li>
 *   <li>Integrated consensus layer (DagChain) with storage</li>
 *   <li>Clean initialization and lifecycle management</li>
 *   <li>Type-safe component access</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * Config config = new MainnetConfig();
 * DagKernel dagKernel = new DagKernel(config);
 * dagKernel.start();
 *
 * // Use storage layer
 * Block block = dagKernel.getDagStore().getBlockByHash(hash, true);
 * Transaction tx = dagKernel.getTransactionStore().getTransaction(txHash);
 *
 * // Use consensus layer
 * DagImportResult result = dagKernel.getDagChain().tryToConnect(newBlock);
 *
 * dagKernel.stop();
 * </pre>
 *
 * @since v5.1 Phase 9 Final
 */
@Slf4j
@Getter
public class DagKernel {
  private final Config config;
  private final DatabaseFactory dbFactory;
  private final DagStore dagStore;
  private final TransactionStore transactionStore;
  private final AccountStore accountStore;
  private final OrphanBlockStore orphanBlockStore;

  private final DagCache dagCache;
  private final DagEntityResolver entityResolver;

  private DagAccountManager dagAccountManager;
  private DagTransactionProcessor dagTransactionProcessor;
  private DagBlockProcessor dagBlockProcessor;

  private DagChain dagChain;
  private HybridSyncManager hybridSyncManager;

  private volatile boolean running = false;
  /**
   * Create a new standalone DagKernel
   *
   * @param config XDAG configuration (Mainnet/Testnet/Devnet)
   */
  public DagKernel(Config config) {
      this.config = config;

      log.info("========================================");
      log.info("Initializing DagKernel v5.1 (Standalone)");
      log.info("========================================");

      // ========== Storage Layer ==========

      log.info("1. Initializing Storage Layer...");

      // Database factory for RocksDB management
      this.dbFactory = new RocksdbFactory(config);
      log.info("   ✓ DatabaseFactory initialized");

      // DagStore for Block persistence
      this.dagStore = new DagStoreImpl(config);
      log.info("   ✓ DagStore initialized");

      // TransactionStore for Transaction persistence
      this.transactionStore = new TransactionStoreImpl(
              dbFactory.getDB(DatabaseName.TRANSACTION),
              dbFactory.getDB(DatabaseName.INDEX)
      );
      log.info("   ✓ TransactionStore initialized");

      // OrphanBlockStore for orphan block management
      this.orphanBlockStore = new OrphanBlockStoreImpl(
              dbFactory.getDB(DatabaseName.ORPHANIND)
      );
      log.info("   ✓ OrphanBlockStore initialized");

      // AccountStore for account state (EVM compatible)
      this.accountStore = new AccountStoreImpl(config);
      log.info("   ✓ AccountStore initialized");

      // ========== Cache Layer ==========

      log.info("2. Initializing Cache Layer...");

      // L1 Caffeine cache
      this.dagCache = new DagCache();
      log.info("   ✓ DagCache initialized (13.8 MB capacity)");

      // Unified facade for Block/Transaction resolution
      this.entityResolver = new DagEntityResolver(dagStore, transactionStore);
      log.info("   ✓ DagEntityResolver initialized");

      // Note: DagChain and HybridSyncManager will be initialized in start() method
      // because DagChainImpl needs a fully constructed DagKernel instance

      log.info("========================================");
      log.info("DagKernel v5.1 initialization complete");
      log.info("========================================");
  }

  /**
   * Initialize DagChain and HybridSyncManager
   *
   * <p>This method should be called after DagKernel construction but before start().
   * It creates the consensus layer components that depend on a fully constructed DagKernel.
   */
  private void initializeConsensusLayer() {
      log.info("3. Initializing Consensus Layer...");

      // 1. Create DagAccountManager
      this.dagAccountManager = new DagAccountManager(accountStore, config);
      log.info("   ✓ DagAccountManager initialized");

      // 2. Create DagTransactionProcessor
      this.dagTransactionProcessor = new DagTransactionProcessor(
              dagAccountManager,
              transactionStore
      );
      log.info("   ✓ DagTransactionProcessor initialized");

      // 3. Create DagBlockProcessor
      this.dagBlockProcessor = new DagBlockProcessor(
              dagStore,
              transactionStore,
              dagTransactionProcessor,
              dagAccountManager
      );
      log.info("   ✓ DagBlockProcessor initialized");

      // 4. Create DagChainImpl (requires fully constructed DagKernel)
      this.dagChain = new DagChainImpl(this);
      log.info("   ✓ DagChain initialized");

      // 5. Create HybridSyncManager
      this.hybridSyncManager = new HybridSyncManager(this, dagChain);
      log.info("   ✓ HybridSyncManager initialized");

      log.info("   ✓ Consensus layer initialization complete");
  }

  /**
   * Start the DagKernel and all managed components
   *
   * <p>Components are started in dependency order:
   * 1. OrphanBlockStore (orphan block management)
   * 2. DagStore (Block persistence)
   * 3. TransactionStore (Transaction persistence)
   * 4. AccountStore (Account state)
   * 5. DagChain + HybridSyncManager (Consensus layer)
   *
   * @throws RuntimeException if startup fails
   */
  public synchronized void start() {
      if (running) {
          log.warn("DagKernel is already running");
          return;
      }

      log.info("========================================");
      log.info("Starting DagKernel");
      log.info("========================================");

      try {
          // Start OrphanBlockStore first
          orphanBlockStore.start();
          log.info("✓ OrphanBlockStore started");

          // Start DagStore (Block persistence layer)
          dagStore.start();
          log.info("✓ DagStore started");

          // Start TransactionStore (Transaction persistence layer)
          transactionStore.start();
          log.info("✓ TransactionStore started");

          // Start AccountStore (Account state layer)
          accountStore.start();
          log.info("✓ AccountStore started: {} accounts, total balance {}",
                  accountStore.getAccountCount().toLong(),
                  accountStore.getTotalBalance().toDecimalString());

          // DagCache and DagEntityResolver don't need explicit startup
          // They are ready to use after construction

          // Initialize consensus layer (DagChain + HybridSyncManager)
          if (dagChain == null) {
              initializeConsensusLayer();
          }

          running = true;
          log.info("========================================");
          log.info("✓ DagKernel started successfully");
          log.info("  - Storage: DagStore + TransactionStore + AccountStore + OrphanBlockStore");
          log.info("  - Cache: DagCache (13.8 MB L1) + DagEntityResolver");
          log.info("  - Consensus: DagChain + HybridSyncManager");
          log.info("  - Main Chain Height: {}", dagChain.getMainChainLength());
          log.info("  - Max Difficulty: {}", dagChain.getChainStats().getMaxDifficulty().toDecimalString());
          log.info("========================================");

      } catch (Exception e) {
          log.error("Failed to start DagKernel", e);
          throw new RuntimeException("Failed to start standalone DagKernel", e);
      }
  }

  /**
   * Stop the DagKernel and all managed components
   *
   * <p>Components are stopped in reverse dependency order:
   * 1. HybridSyncManager
   * 2. DagChain
   * 3. TransactionStore
   * 4. DagStore
   * 5. OrphanBlockStore
   * 6. AccountStore
   * 7. DatabaseFactory (RocksDB cleanup)
   */
  public synchronized void stop() {
      if (!running) {
          log.warn("DagKernel is not running");
          return;
      }

      log.info("========================================");
      log.info("Stopping DagKernel");
      log.info("========================================");

      try {
          // Stop HybridSyncManager first (if present)
          if (hybridSyncManager != null) {
              // HybridSyncManager cleanup if needed
              log.info("✓ HybridSyncManager stopped");
          }

          // Stop DagChain (if present)
          if (dagChain != null) {
              // DagChain cleanup if needed (currently stateless)
              log.info("✓ DagChain stopped");
          }

          // Stop TransactionStore
          transactionStore.stop();
          log.info("✓ TransactionStore stopped");

          // Stop DagStore
          dagStore.stop();
          log.info("✓ DagStore stopped");

          // Stop OrphanBlockStore
          orphanBlockStore.stop();
          log.info("✓ OrphanBlockStore stopped");

          // Stop AccountStore
          accountStore.stop();
          log.info("✓ AccountStore stopped");

          // Close all RocksDB databases
          for (DatabaseName name : DatabaseName.values()) {
              try {
                  dbFactory.getDB(name).close();
              } catch (Exception e) {
                  log.warn("Error closing database {}: {}", name, e.getMessage());
              }
          }
          log.info("✓ All databases closed");

          running = false;
          log.info("========================================");
          log.info("✓ DagKernel stopped successfully");
          log.info("========================================");

      } catch (Exception e) {
          log.error("Error stopping DagKernel", e);
          // Continue cleanup even if errors occur
          running = false;
      }
  }

  /**
   * Get cache statistics summary
   *
   * @return Human-readable cache statistics
   */
  public String getCacheStats() {
      return dagCache.getCacheSizeSummary();
  }

  /**
   * Get database size
   *
   * @return Database size in bytes
   */
  public long getDatabaseSize() {
      return dagStore.getDatabaseSize();
  }

  /**
   * Reset all storage (for testing or migration)
   *
   * <p>WARNING: This will delete all data in DagStore, TransactionStore, and AccountStore!
   *
   * <p>This operation:
   * 1. Resets DagStore (deletes all Blocks and indexes)
   * 2. Resets TransactionStore (deletes all Transactions and indexes)
   * 3. Resets AccountStore (deletes all Accounts and contract code)
   * 4. Invalidates all L1 cache entries
   */
  public void reset() {
      log.warn("⚠ Resetting DagKernel - ALL DATA WILL BE LOST!");

      // Reset DagStore
      dagStore.reset();
      log.info("✓ DagStore reset completed");

      // Reset TransactionStore
      transactionStore.reset();
      log.info("✓ TransactionStore reset completed");

      // Reset AccountStore
      accountStore.reset();
      log.info("✓ AccountStore reset completed");

      // Invalidate all cache entries
      dagCache.invalidateAll();
      log.info("✓ DagCache cleared");

      log.info("✓ DagKernel reset completed - all data erased");
  }

}
