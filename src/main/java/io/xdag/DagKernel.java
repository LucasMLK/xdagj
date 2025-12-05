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

import io.xdag.api.service.MiningApiService;
import io.xdag.config.Config;
import io.xdag.consensus.epoch.EpochConsensusManager;
import io.xdag.config.GenesisConfig;
import io.xdag.config.spec.TransactionPoolSpec;
import io.xdag.consensus.pow.PowAlgorithm;
import io.xdag.consensus.pow.RandomXPow;
import io.xdag.core.Block;
import io.xdag.core.DagAccountManager;
import io.xdag.core.DagBlockProcessor;
import io.xdag.core.DagChain;
import io.xdag.core.DagChainImpl;
import io.xdag.core.DagImportResult;
import io.xdag.core.DagTransactionProcessor;
import io.xdag.core.TransactionBroadcastManager;
import io.xdag.core.TransactionPool;
import io.xdag.core.TransactionPoolImpl;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.p2p.P2pConfigFactory;
import io.xdag.p2p.P2pService;
import io.xdag.p2p.SyncManager;
import io.xdag.p2p.config.P2pConfig;
import io.xdag.store.AccountStore;
import io.xdag.store.DagStore;
import io.xdag.store.TransactionStore;
import io.xdag.store.cache.DagCache;
import io.xdag.store.cache.DagEntityResolver;
import io.xdag.store.rocksdb.base.KVSource;
import io.xdag.store.rocksdb.config.DatabaseFactory;
import io.xdag.store.rocksdb.config.DatabaseName;
import io.xdag.store.rocksdb.config.RocksdbFactory;
import io.xdag.store.rocksdb.impl.AccountStoreImpl;
import io.xdag.store.rocksdb.impl.DagStoreImpl;
import io.xdag.store.rocksdb.impl.TransactionStoreImpl;
import io.xdag.store.rocksdb.transaction.RocksDBTransactionManager;
import java.io.File;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.tuweni.units.bigints.UInt256;
import org.rocksdb.RocksDB;

/**
 * DagKernel - Standalone kernel for XDAG DagStore architecture
 *
 * <p>This is a completely new kernel designed specifically for the data structures.
 * It does NOT depend on the legacy io.xdag.Kernel and manages all components independently.
 *
 * <h2>Architecture</h2>
 * <pre>
 * DagKernel (Standalone Complete Kernel)
 *   ├── Storage Layer
 *   │   ├── DagStore (Block persistence with pending block management)
 *   │   ├── TransactionStore (Transaction persistence)
 *   │   └── AccountStore (Account state - EVM compatible)
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
 *   <li>Designed specifically for data structures</li>
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
 * Block block = dagKernel.getDagStore().getBlockByHash(hash);
 * Transaction tx = dagKernel.getTransactionStore().getTransaction(txHash);
 *
 * // Use consensus layer
 * DagImportResult result = dagKernel.getDagChain().tryToConnect(newBlock);
 *
 * dagKernel.stop();
 * </pre>
 *
 * @since XDAGJ Final
 */
@Slf4j
@Getter
public class DagKernel {

  private final Config config;
  private final DatabaseFactory dbFactory;
  private final DagStore dagStore;
  private final TransactionStore transactionStore;
  private final AccountStore accountStore;

  private final RocksDBTransactionManager transactionManager;

  private final DagCache dagCache;
  private final DagEntityResolver entityResolver;

  private DagAccountManager dagAccountManager;
  private DagTransactionProcessor dagTransactionProcessor;
  private DagBlockProcessor dagBlockProcessor;
  private TransactionPool transactionPool;
  private TransactionBroadcastManager transactionBroadcastManager;

  private DagChain dagChain;

  // PoW Algorithm (RandomX only)
  private PowAlgorithm powAlgorithm;

  // Mining API service (for pool server integration)
  private io.xdag.api.service.MiningApiService miningApiService;

  // Epoch consensus manager (for BUG-CONSENSUS-001 and BUG-CONSENSUS-002 fix)
  private EpochConsensusManager epochConsensusManager;

  // P2P service (5)
  private P2pService p2pService;
  private SyncManager syncManager;

  // Genesis configuration
  private GenesisConfig genesisConfig;

  // Wallet for genesis block creation (optional)
  private final Wallet wallet;

  private boolean p2pEnabled = true;
  private volatile boolean running = false;

  /**
   * WAL sync scheduler for periodic durability (BUG-PERSISTENCE-001 fix).
   * Syncs RocksDB WAL every 10 seconds to prevent data loss on unexpected restarts.
   */
  private ScheduledExecutorService walSyncScheduler;

  /**
   * Create a new standalone DagKernel
   *
   * @param config XDAG configuration (Mainnet/Testnet/Devnet)
   */
  public DagKernel(Config config) {
    this(config, null);
  }

  /**
   * Create a new standalone DagKernel with wallet
   *
   * @param config XDAG configuration (Mainnet/Testnet/Devnet)
   * @param wallet Wallet for genesis block creation (optional, can be null)
   */
  public DagKernel(Config config, Wallet wallet) {
    this.config = config;
    this.wallet = wallet;

    log.info("========================================");
    log.info("Initializing DagKernel");
    log.info("========================================");

    // Load genesis configuration
    loadGenesisConfig();

    // ========== Storage Layer ==========

    log.info("1. Initializing Storage Layer...");

    // Database factory for RocksDB management
    this.dbFactory = new RocksdbFactory(config);
    log.info("   - DatabaseFactory initialized");

    // Initialize transaction manager (NEW - for atomic block processing)
    KVSource<byte[], byte[]> indexDb = dbFactory.getDB(DatabaseName.INDEX);
    if (!(indexDb instanceof io.xdag.store.rocksdb.base.RocksdbKVSource)) {
      throw new RuntimeException("INDEX database is not a RocksdbKVSource");
    }

    // IMPORTANT: Initialize the database to create RocksDB instance
    // RocksDB instance is null until init() is called
    indexDb.init();

    RocksDB mainDb = ((io.xdag.store.rocksdb.base.RocksdbKVSource) indexDb).getDb();
    this.transactionManager = new RocksDBTransactionManager(mainDb);
    log.info("   - RocksDBTransactionManager initialized");

    // ========== Cache Layer (Initialize BEFORE Storage for shared cache) ==========

    log.info("2. Initializing Cache Layer...");

    // L1 Caffeine cache (created first so stores can share it)
    this.dagCache = new DagCache();
    log.info("   - DagCache initialized");

    // ========== Continue Storage Layer with Shared Cache ==========

    // DagStore for Block persistence (with atomic operation support + shared cache)
    this.dagStore = new DagStoreImpl(config, transactionManager, this.dagCache);
    log.info("   - DagStore initialized");

    // TransactionStore for Transaction persistence (with atomic operation support)
    this.transactionStore = new TransactionStoreImpl(
        dbFactory.getDB(DatabaseName.TRANSACTION),
        dbFactory.getDB(DatabaseName.INDEX),
        transactionManager
    );
    log.info("   - TransactionStore initialized");

    // AccountStore for account state (EVM compatible, with atomic operation support)
    this.accountStore = new AccountStoreImpl(config, transactionManager);
    log.info("   - AccountStore initialized");

    // Unified facade for Block/Transaction resolution
    this.entityResolver = new DagEntityResolver(dagStore, transactionStore);
    log.info("   - DagEntityResolver initialized");

    // Note: DagChain and SyncManager will be initialized in start() method
    // because DagChainImpl needs a fully constructed DagKernel instance

    // Register shutdown hook for graceful termination (BUG-STORAGE-002 fix)
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        log.info("Shutdown hook triggered: syncing WAL before exit...");
        if (dagStore != null && dagStore.isRunning()) {
          dagStore.syncWal();
          log.info("WAL synced in shutdown hook");
        }
      } catch (Exception e) {
        log.error("Failed to sync WAL in shutdown hook: {}", e.getMessage(), e);
      }
    }, "DagKernel-ShutdownHook"));
    log.debug("Shutdown hook registered");

    log.info("========================================");
    log.info("DagKernel initialization complete");
    log.info("========================================");
  }

  /**
   * Initialize DagChain and SyncManager
   *
   * <p>This method should be called after DagKernel construction but before start().
   * It creates the consensus layer components that depend on a fully constructed DagKernel.
   */
  private void initializeConsensusLayer() {
    log.info("3. Initializing Consensus Layer...");

    // 1. Create DagAccountManager
    this.dagAccountManager = new DagAccountManager(accountStore, config);
    log.info("   - DagAccountManager initialized");

    // 2. Create DagTransactionProcessor
    this.dagTransactionProcessor = new DagTransactionProcessor(
        dagAccountManager,
        transactionStore
    );
    log.info("   - DagTransactionProcessor initialized");

    // 3. Create DagBlockProcessor
    this.dagBlockProcessor = new DagBlockProcessor(
        dagStore,
        transactionStore,
        dagTransactionProcessor,
        dagAccountManager
    );
    log.info("   - DagBlockProcessor initialized");

    // 4. Create TransactionPool
    this.transactionPool = new TransactionPoolImpl(
        TransactionPoolSpec.createDefault(),
        dagAccountManager,
        transactionStore
    );
    log.info("   - TransactionPool initialized");

    // 5. Create TransactionBroadcastManager (Phase 3)
    this.transactionBroadcastManager = new TransactionBroadcastManager();
    log.info("   - TransactionBroadcastManager initialized");

    // 6. Create DagChainImpl (requires fully constructed DagKernel)
    this.dagChain = new DagChainImpl(this);
    log.info("   - DagChain initialized");

    // 9. Create PoW Algorithm: RandomX only
    this.powAlgorithm = new RandomXPow(config, dagChain);
    log.info("   - RandomXPow initialized");

    // Create Mining API Service (for pool server integration)
    if (wallet != null) {
      this.miningApiService = new MiningApiService(dagChain, wallet, powAlgorithm);
      log.info("   - MiningApiService initialized");

      // Initialize Epoch Consensus Manager (for BUG-CONSENSUS fix)
      // Configuration: 2 backup mining threads, minimum difficulty from config
      // NOTE: Using very low difficulty for testing (only first 8 bits must be zero)
      org.apache.tuweni.units.bigints.UInt256 minimumDifficulty =
          org.apache.tuweni.units.bigints.UInt256.fromHexString("0x00ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

      this.epochConsensusManager = new EpochConsensusManager(
          this,  // ✅ Pass DagKernel for SyncManager access (BUG-SYNC-001 fix)
          dagChain,
          dagStore,  // ✅ Pass DagStore for WAL sync (BUG-STORAGE-002 fix)
          miningApiService.getBlockGenerator(),  // ✅ Inject BlockGenerator for candidate block creation
          2,  // backup mining threads
          minimumDifficulty
      );

      // Connect consensus manager to mining API service
      this.miningApiService.setEpochConsensusManager(this.epochConsensusManager);
      log.info("   - EpochConsensusManager initialized");
    } else {
      log.warn("   MiningApiService not initialized (wallet required)");
    }

    log.info("   Consensus layer initialization complete");
  }

  /**
   * Enable or disable P2P service (must be called before start())
   * @param enabled true to enable P2P (default), false to disable
   */
  public void setP2pEnabled(boolean enabled) {
    this.p2pEnabled = enabled;
  }

  /**
   * Start the DagKernel and all managed components
   *
   * <p>Components are started in dependency order:
   * 1. OrphanBlockStore (orphan block management) 2. DagStore (Block persistence) 3.
   * TransactionStore (Transaction persistence) 4. AccountStore (Account state) 5. DagChain +
   * SyncManager (Consensus layer)
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
      // Start DagStore (Block persistence layer)
      dagStore.start();
      log.info("DagStore started");

      // Start TransactionStore (Transaction persistence layer)
      transactionStore.start();
      log.info("TransactionStore started");

      // Start AccountStore (Account state layer)
      accountStore.start();
      log.info("AccountStore started: {} accounts, total balance {}",
          accountStore.getAccountCount().toLong(),
          accountStore.getTotalBalance().toDecimalString());

      // DagCache and DagEntityResolver don't need explicit startup
      // They are ready to use after construction

      // Initialize consensus layer (DagChain)
      if (dagChain == null) {
        initializeConsensusLayer();
      }

      // Bootstrap genesis block if needed
      bootstrapGenesis();

      // Start P2P service (5)
      startP2pService();

      // Start PoW Algorithm (RandomX)
      if (powAlgorithm != null) {
        powAlgorithm.start();
        log.info("PoW Algorithm started: {}", powAlgorithm.getName());

        // Initialize RandomX seed from genesis config if available
        if (powAlgorithm instanceof RandomXPow randomXPow) {
          if (genesisConfig.hasRandomXSeed()) {
            try {
              byte[] genesisSeed = genesisConfig.getRandomXSeedBytes();
              randomXPow.getSeedManager().initializeFromPreseed(genesisSeed, 1);
              log.info("RandomX seed initialized from genesis config");
              log.debug("  - Seed: {}...",
                  org.apache.tuweni.bytes.Bytes.wrap(genesisSeed).toHexString().substring(0, 18));
            } catch (Exception e) {
              log.error("Failed to initialize RandomX seed from genesis", e);
              throw new RuntimeException("Failed to initialize RandomX seed", e);
            }
          } else {
            log.warn(
                "No randomXSeed in genesis config - mining will not work until epoch boundary");
          }
        }
      }

      // Start Epoch Consensus Manager (for BUG-CONSENSUS fix)
      if (epochConsensusManager != null) {
        epochConsensusManager.start();
        log.info("EpochConsensusManager started");
      }

      // Start periodic WAL sync scheduler (BUG-PERSISTENCE-001 fix)
      startWalSyncScheduler();

      running = true;
      log.info("========================================");
      log.info("DagKernel started successfully");
      log.info("  - Main Chain Height: {}", dagChain.getMainChainLength());
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
   * 1. SyncManager 2. DagChain 3. TransactionStore 4. DagStore 5. OrphanBlockStore 6.
   * AccountStore 7. DatabaseFactory (RocksDB cleanup)
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
      // Stop WAL sync scheduler first (BUG-PERSISTENCE-001 fix)
      stopWalSyncScheduler();

      // Stop PoW Algorithm (RandomX)
      if (powAlgorithm != null) {
        powAlgorithm.stop();
        log.info("PoW Algorithm stopped: {}", powAlgorithm.getName());
      }

      // Stop Epoch Consensus Manager (for BUG-CONSENSUS fix)
      if (epochConsensusManager != null) {
        epochConsensusManager.stop();
        log.info("EpochConsensusManager stopped");
      }

      // Stop P2P service (5)
      stopP2pService();

      // Stop DagChain (if present)
      if (dagChain != null) {
        dagChain.stop();
        log.info("DagChain stopped");
      }

      // Stop TransactionStore
      transactionStore.stop();
      log.info("TransactionStore stopped");

      // Sync WAL before stopping DagStore (BUG-STORAGE-002 fix)
      try {
        if (dagStore != null && dagStore.isRunning()) {
          dagStore.syncWal();
          log.info("WAL synced before DagStore shutdown");
        }
      } catch (Exception e) {
        log.error("Failed to sync WAL before DagStore shutdown: {}", e.getMessage());
      }

      // Stop DagStore
      dagStore.stop();
      log.info("DagStore stopped");

      // Stop AccountStore
      accountStore.stop();
      log.info("AccountStore stopped");

      // Shutdown transaction manager (rollback any uncommitted transactions)
      if (transactionManager != null) {
        transactionManager.shutdown();
        log.info("RocksDBTransactionManager stopped");
      }

      // Close all RocksDB databases
      for (DatabaseName name : DatabaseName.values()) {
        try {
          dbFactory.getDB(name).close();
        } catch (Exception e) {
          log.warn("Error closing database {}: {}", name, e.getMessage());
        }
      }
      log.info("All databases closed");

      running = false;
      log.info("========================================");
      log.info("DagKernel stopped successfully");
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
   * 1. Resets DagStore (deletes all Blocks and indexes) 2. Resets TransactionStore (deletes all
   * Transactions and indexes) 3. Resets AccountStore (deletes all Accounts and contract code) 4.
   * Invalidates all L1 cache entries
   */
  public void reset() {
    log.warn("Resetting DagKernel - ALL DATA WILL BE LOST");

    // Reset DagStore
    dagStore.reset();
    log.info("DagStore reset completed");

    // Reset TransactionStore
    transactionStore.reset();
    log.info("TransactionStore reset completed");

    // Reset AccountStore
    accountStore.reset();
    log.info("AccountStore reset completed");

    // Invalidate all cache entries
    dagCache.invalidateAll();
    log.info("DagCache cleared");

    log.info("DagKernel reset completed");
  }

  // ========== P2P Service Management (5) ==========

  /**
   * Start P2P service for block broadcasting
   *
   * <p>5+: P2P integration with application layer event handler.
   * Registers XdagP2pEventHandler to enable FastDAG Sync Protocol (v3.0).
   */
  private void startP2pService() {
    if (!p2pEnabled) {
      log.info("P2P service disabled by configuration");
      return;
    }

    // P2P service requires a wallet/coinbase key
    if (wallet == null || wallet.getDefKey() == null) {
      log.warn("P2P service not started (wallet required)");
      return;
    }

    try {
      log.info("Initializing P2P service...");

      // Create P2P configuration
      ECKeyPair coinbase = wallet.getDefKey();
      P2pConfig p2pConfig = P2pConfigFactory.createP2pConfig(config, coinbase);

      // Create application layer event handler
      io.xdag.p2p.XdagP2pEventHandler eventHandler = new io.xdag.p2p.XdagP2pEventHandler(this);

      // Register event handler with P2P config
      p2pConfig.addP2pEventHandle(eventHandler);

      // Create and start P2P service
      this.p2pService = new P2pService(p2pConfig);
      this.p2pService.start();

      // Connect P2P service to TransactionBroadcastManager (Phase 3)
      if (transactionBroadcastManager != null) {
        transactionBroadcastManager.setP2pService(this.p2pService);
        log.debug("P2P connected to TransactionBroadcastManager");
      }

      // Start FastDAG sync loop
      if (this.syncManager == null) {
        this.syncManager = new SyncManager(this);
      }
      this.syncManager.start();
      log.info("SyncManager started");

      // BUG-SYNC-001 fix: Connect SyncManager to MiningApiService for sync gate
      if (this.miningApiService != null) {
        this.miningApiService.setSyncManager(this.syncManager);
        log.debug("SyncManager connected to MiningApiService");
      }

      log.info("P2P service started");

    } catch (Throwable e) {
      // Catch both Exception and Error (including NoSuchFieldError from P2P refactoring)
      log.error("Failed to start P2P service: {}", e.getMessage(), e);
      log.warn("Continuing without P2P (block broadcasting disabled)");
      this.p2pService = null;
    }
  }

  /**
   * Stop P2P service
   */
  private void stopP2pService() {
    if (syncManager != null) {
      try {
        syncManager.stop();
        log.info("SyncManager stopped");
      } catch (Exception e) {
        log.warn("Error stopping SyncManager: {}", e.getMessage());
      }
      syncManager = null;
    }

    if (p2pService != null) {
      log.info("Stopping P2P service...");
      try {
        p2pService.stop();
        log.info("P2P service stopped");
      } catch (Exception e) {
        log.error("Error stopping P2P service: {}", e.getMessage());
      }
      p2pService = null;
    }
  }

  // ========== WAL Sync Scheduler (BUG-PERSISTENCE-001 fix) ==========

  /**
   * Start periodic WAL sync scheduler.
   *
   * <p>Syncs RocksDB WAL every 10 seconds to minimize data loss window on unexpected restarts.
   * Without this, data written since the last epoch end (up to 64 seconds) could be lost.
   */
  private void startWalSyncScheduler() {
    if (dagStore == null) {
      log.warn("DagStore not available, skipping WAL sync scheduler");
      return;
    }

    walSyncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "WALSyncScheduler");
      t.setDaemon(true);
      return t;
    });

    // Sync WAL every 10 seconds
    walSyncScheduler.scheduleAtFixedRate(() -> {
      try {
        if (dagStore != null && dagStore.isRunning()) {
          dagStore.syncWal();
          log.debug("Periodic WAL sync completed");
        }
      } catch (Exception e) {
        log.error("Periodic WAL sync failed: {}", e.getMessage());
      }
    }, 10, 10, TimeUnit.SECONDS);

    log.info("WAL sync scheduler started (interval=10s)");
  }

  /**
   * Stop WAL sync scheduler with final sync.
   */
  private void stopWalSyncScheduler() {
    if (walSyncScheduler != null) {
      log.info("Stopping WAL sync scheduler...");
      walSyncScheduler.shutdown();
      try {
        if (!walSyncScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
          walSyncScheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        walSyncScheduler.shutdownNow();
        Thread.currentThread().interrupt();
      }
      walSyncScheduler = null;
      log.info("WAL sync scheduler stopped");
    }
  }

  // ========== Genesis Configuration and Bootstrap ==========

  /**
   * Load genesis configuration from genesis-{network}.json
   *
   * <p>REQUIRED: genesis configuration must exist. This follows Ethereum's approach where
   * each network has an explicit genesis configuration.
   *
   * <p>Search order:
   * <ol>
   *   <li>Classpath: /genesis-{network}.json (embedded in JAR resources)</li>
   *   <li>{rootDir}/genesis-{network}.json (external custom configuration)</li>
   *   <li>{rootDir}/config/genesis-{network}.json (legacy location)</li>
   *   <li>FAIL: genesis-{network}.json is required</li>
   * </ol>
   *
   * @throws RuntimeException if genesis configuration not found
   */
  private void loadGenesisConfig() {
    String network = config.getNodeSpec().getNetwork().name().toLowerCase();
    String networkSpecificName = "genesis-" + network + ".json";

    // Try 1: Load from classpath (embedded in JAR)
    try (var inputStream = getClass().getResourceAsStream("/" + networkSpecificName)) {
      if (inputStream != null) {
        log.info("Loading genesis from classpath: /{}", networkSpecificName);
        String json = IOUtils.toString(inputStream, java.nio.charset.StandardCharsets.UTF_8);
        genesisConfig = GenesisConfig.fromJson(json);
        genesisConfig.validate();
        logGenesisConfig("classpath:/" + networkSpecificName);
        return;
      }
    } catch (Exception e) {
      log.warn("Failed to load genesis from classpath: {}", e.getMessage());
    }

    // Try 2: Load from external file in rootDir
    String rootDir = config.getRootDir();
    File genesisFile = new File(rootDir, networkSpecificName);

    if (!genesisFile.exists()) {
      // Try 3: Load from config subdirectory (legacy)
      genesisFile = new File(rootDir, "config/" + networkSpecificName);
    }

    if (genesisFile.exists()) {
      try {
        log.info("Loading genesis from external file: {}", genesisFile.getAbsolutePath());
        genesisConfig = GenesisConfig.load(genesisFile);
        genesisConfig.validate();
        logGenesisConfig(genesisFile.getAbsolutePath());
        return;
      } catch (Exception e) {
        log.error("Failed to load or validate genesis from {}: {}",
            genesisFile, e.getMessage());
        throw new RuntimeException("Invalid genesis.json", e);
      }
    }

    // No genesis found - FAIL
    String errorMsg = String.format(
        """
            genesis-%s.json not found! Searched:
              1. classpath:/genesis-%s.json (embedded in JAR)
              2. %s/genesis-%s.json (external custom config)
              3. %s/config/genesis-%s.json (legacy location)

            XDAG requires explicit genesis configuration (like Ethereum).

            Built-in networks (mainnet/testnet/devnet) include embedded genesis configs.
            For custom networks, create a genesis file in your root directory:

              Example: %s/genesis-custom.json
              {
                "networkId": "custom",
                "chainId": 999,
                "epoch": 27555273,
                "initialDifficulty": "0x1",
                "randomXSeed": "0x0000000000000000000000000000000000000000000000000000000000000001",
                "alloc": {}
              }""",
        network, network, rootDir, network, rootDir, network, rootDir
    );
    log.error(errorMsg);
    throw new RuntimeException("genesis-" + network + ".json is required but not found");
  }

  /**
   * Log genesis configuration details
   */
  private void logGenesisConfig(String source) {
    log.info("========================================");
    log.info("Loaded genesis configuration:");
    log.info("  Source: {}", source);
    log.info("  Network: {}", genesisConfig.getNetworkId());
    log.info("  Chain ID: {}", genesisConfig.getChainId());
    log.info("  Genesis Epoch: {}", genesisConfig.getEpoch());
    log.info("  Difficulty: {}", genesisConfig.getDifficulty());

    if (genesisConfig.hasAllocations()) {
      log.info("  Initial Allocations: {} addresses", genesisConfig.getAlloc().size());
      long totalAlloc = genesisConfig.getAlloc().values().stream()
          .map(BigInteger::new)
          .reduce(BigInteger.ZERO, BigInteger::add)
          .divide(BigInteger.valueOf(1_000_000_000))
          .longValue();
      log.info("  Total Allocated: {} XDAG", totalAlloc);
    }

    log.info("========================================");
  }

  /**
   * Bootstrap genesis block if chain is empty
   *
   * <p>This method follows Ethereum's approach:
   * <ol>
   *   <li>Check if chain is empty (first startup)</li>
   *   <li>If empty: Create genesis block from genesis.json</li>
   *   <li>If not empty: Verify existing genesis matches genesis.json</li>
   * </ol>
   *
   * <p>Genesis block is completely defined by genesis.json:
   * <ul>
   *   <li>Timestamp from config (not current time)</li>
   *   <li>Difficulty from config</li>
   *   <li>Initial allocations from config</li>
   * </ul>
   *
   * @throws RuntimeException if genesis creation fails or validation fails
   */
  private void bootstrapGenesis() {
    if (dagChain == null) {
      throw new IllegalStateException("DagChain not initialized");
    }

    // Check if chain already initialized
    long mainBlockCount = dagChain.getChainStats().getMainBlockCount();

    if (mainBlockCount == 0) {
      // First startup: Create genesis block from genesis.json
      log.info("========================================");
      log.info("First Startup: Creating Genesis Block");
      log.info("========================================");

      try {
        // Create genesis block
        createGenesisBlock();

        // Apply initial allocations
        if (genesisConfig.hasAllocations()) {
          applyInitialAllocations();
        }

        log.info("========================================");
        log.info("Genesis bootstrap complete");
        log.info("  - Network: {}", genesisConfig.getNetworkId());
        log.info("  - Chain ID: {}", genesisConfig.getChainId());
        log.info("  - Main chain height: {}", dagChain.getMainChainLength());
        log.info("========================================");

      } catch (Exception e) {
        log.error("Failed to bootstrap genesis", e);
        throw new RuntimeException("Genesis bootstrap failed", e);
      }

    } else {
      // Subsequent startup: Verify existing genesis matches genesis.json
      log.info("Chain already initialized ({} blocks), verifying genesis...", mainBlockCount);
      verifyGenesisBlock();

      // Verify data consistency (BUG-PERSISTENCE-001 fix)
      verifyDataConsistency();
    }
  }

  /**
   * Verify that existing genesis block matches genesis.json
   *
   * <p>This ensures that the node is running on the correct network.
   * Like Ethereum, genesis.json defines the network identity.
   *
   * @throws RuntimeException if genesis block doesn't match config
   */
  private void verifyGenesisBlock() {
    // Get genesis block (genesis has height 1, orphan blocks have height 0)
    Block genesisBlock = dagStore.getMainBlockByHeight(1);
    if (genesisBlock == null) {
      String error = String.format(
          """
              Genesis block not found at height 1, but main chain has %d blocks.

              This indicates corrupted chain data or broken height index.
              The genesis block (height=1) must exist if the chain has blocks.

              Solutions:
                1. Rebuild the chain from genesis
                2. Check database integrity
                3. Restore from backup""",
          dagChain.getChainStats().getMainBlockCount()
      );
      log.error(error);
      throw new RuntimeException("Genesis block verification failed: genesis not found");
    }

    log.info("Verifying genesis block against genesis.json...");

    // Verify epoch
    long expectedEpoch = genesisConfig.getEpoch();
    long actualEpoch = genesisBlock.getEpoch();

    if (expectedEpoch != actualEpoch) {
      String error = String.format(
          """
              Genesis block epoch mismatch!
                Expected (from genesis.json): %d
                Actual (from blockchain): %d
              
              This means you're trying to run a node with a different genesis.json
              than the one used to create this chain. This is not allowed.
              
              Solutions:
                1. Use the correct genesis.json for this network
                2. Delete the chain data and start fresh with current genesis.json""",
          expectedEpoch, actualEpoch
      );
      log.error(error);
      throw new RuntimeException("Genesis block verification failed: epoch mismatch");
    }

    // Verify difficulty
    UInt256 expectedDifficulty = genesisConfig.getDifficultyUInt256();
    UInt256 actualDifficulty = genesisBlock.getHeader().getDifficulty();

    if (!expectedDifficulty.equals(actualDifficulty)) {
      String error = String.format(
          """
              Genesis block difficulty mismatch!
                Expected (from genesis.json): %s
                Actual (from blockchain): %s
              
              Chain was created with different genesis configuration.""",
          expectedDifficulty.toHexString(), actualDifficulty.toHexString()
      );
      log.error(error);
      throw new RuntimeException("Genesis block verification failed: difficulty mismatch");
    }

    log.info("Genesis block verification passed");
    log.info("  - Epoch: {} (matches config)", actualEpoch);
    log.info("  - Difficulty: {} (matches config)", actualDifficulty.toHexString());
    log.info("  - Network: {}", genesisConfig.getNetworkId());
  }

  /**
   * Verify data consistency after restart (BUG-PERSISTENCE-001 fix).
   *
   * <p>Checks that all blocks in the chain have valid link targets.
   * If blocks with missing dependencies are found, they are removed to allow resync.
   *
   * <p>This handles the case where:
   * <ol>
   *   <li>Node receives P2P blocks (written to WAL buffer)</li>
   *   <li>Candidate block created with links to these blocks</li>
   *   <li>Node restarts before WAL sync</li>
   *   <li>P2P blocks lost, but candidate block still references them</li>
   * </ol>
   */
  private void verifyDataConsistency() {
    log.info("Verifying data consistency after restart...");

    long mainChainHeight = dagChain.getMainChainLength();
    if (mainChainHeight <= 1) {
      log.info("Chain only has genesis block, skipping consistency check");
      return;
    }

    int invalidBlockCount = 0;
    int checkedBlockCount = 0;

    // Check recent blocks (last 100 heights or all if less)
    long startHeight = Math.max(2, mainChainHeight - 100);

    for (long height = mainChainHeight; height >= startHeight; height--) {
      Block block = dagStore.getMainBlockByHeight(height);
      if (block == null) {
        log.warn("Block at height {} not found during consistency check", height);
        continue;
      }

      checkedBlockCount++;

      // Check all link targets exist
      var links = block.getLinks();
      if (links != null) {
        for (var link : links) {
          var targetHash = link.getTargetHash();
          if (targetHash != null && !targetHash.isZero()) {
            Block targetBlock = dagStore.getBlockByHash(targetHash);
            if (targetBlock == null) {
              log.error("CONSISTENCY ERROR: Block {} (height={}) references missing block {}",
                  block.getHash().toHexString().substring(0, 18),
                  height,
                  targetHash.toHexString());
              invalidBlockCount++;

              // Mark this block for removal
              try {
                removeInconsistentBlock(block, height);
              } catch (Exception e) {
                log.error("Failed to remove inconsistent block: {}", e.getMessage());
              }
              break; // Move to next block
            }
          }
        }
      }
    }

    if (invalidBlockCount > 0) {
      log.warn("========================================");
      log.warn("DATA CONSISTENCY CHECK COMPLETED");
      log.warn("  - Checked blocks: {}", checkedBlockCount);
      log.warn("  - Inconsistent blocks found: {}", invalidBlockCount);
      log.warn("  - Action: Removed invalid blocks, will resync from peers");
      log.warn("========================================");
    } else {
      log.info("Data consistency check passed ({} blocks verified)", checkedBlockCount);
    }
  }

  /**
   * Remove an inconsistent block from the chain.
   *
   * <p>This demotes the block to orphan status and updates chain stats.
   * BlockInfo with height=0 indicates orphan status.
   *
   * @param block the block to remove
   * @param height the block's current height
   */
  private void removeInconsistentBlock(Block block, long height) {
    log.warn("Removing inconsistent block {} at height {}",
        block.getHash().toHexString().substring(0, 18), height);

    try {
      // Update block info to mark as orphan (height = 0)
      // BlockInfo is immutable, use toBuilder() to create new instance
      var blockInfo = dagStore.getBlockInfo(block.getHash());
      if (blockInfo != null) {
        var orphanBlockInfo = blockInfo.toBuilder()
            .height(0)  // height=0 indicates orphan status
            .build();
        dagStore.saveBlockInfo(orphanBlockInfo);
        log.info("Block {} demoted to orphan status", block.getHash().toHexString().substring(0, 18));
      }

      // Update chain stats (ChainStats is immutable, use with* methods)
      var chainStats = dagChain.getChainStats();
      if (chainStats.getMainBlockCount() > 0) {
        var updatedStats = chainStats.withMainBlockCount(chainStats.getMainBlockCount() - 1);
        dagStore.saveChainStats(updatedStats);
      }

    } catch (Exception e) {
      log.error("Error removing inconsistent block {}: {}",
          block.getHash().toHexString().substring(0, 18), e.getMessage());
      throw e;
    }
  }

  /**
   * Create genesis block from configuration
   *
   * @throws RuntimeException if creation fails
   */
  private void createGenesisBlock() {
    log.info("Creating genesis block...");

    // Use configured epoch from genesis.json
    long epoch = genesisConfig.getEpoch();
    log.info("  - Genesis epoch: {}", epoch);

    // Create genesis block via DagChain
    Block genesisBlock = dagChain.createGenesisBlock(epoch);
    log.info("  - Genesis block created: {}", genesisBlock.getHash().toHexString());

    // Import genesis block
    DagImportResult result = dagChain.tryToConnect(genesisBlock);
    if (!result.isMainBlock()) {
      throw new RuntimeException("Failed to import genesis block: " + result.getStatus() +
          (result.getErrorMessage() != null ? " - " + result.getErrorMessage() : ""));
    }

    log.info("Genesis block imported successfully");
  }

  /**
   * Apply initial balance allocations from genesis config
   */
  private void applyInitialAllocations() {
    Map<String, String> alloc = genesisConfig.getAlloc();
    if (alloc == null || alloc.isEmpty()) {
      return;
    }

    log.info("Applying initial allocations ({} addresses)...", alloc.size());

    int successCount = 0;
    for (Map.Entry<String, String> entry : alloc.entrySet()) {
      try {
        String addressStr = entry.getKey();
        UInt256 balance = genesisConfig.getAllocation(addressStr);

        // Parse address (supports both base58check and hex formats)
        org.apache.tuweni.bytes.Bytes address = GenesisConfig.parseAddress(addressStr);

        // Set balance directly (creates account if doesn't exist)
        dagAccountManager.setBalance(address, balance);
        successCount++;

        log.debug("  - Allocated {} to {}", balance.toDecimalString(),
            addressStr.length() > 20 ? addressStr.substring(0, 20) + "..." : addressStr);

      } catch (Exception e) {
        log.error("Failed to allocate balance for {}: {}", entry.getKey(), e.getMessage());
      }
    }

    log.info("Applied {} initial allocations", successCount);
  }

}
