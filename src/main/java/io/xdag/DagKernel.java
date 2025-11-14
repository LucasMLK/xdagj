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
import io.xdag.config.GenesisConfig;
import io.xdag.consensus.sync.HybridSyncManager;
import io.xdag.consensus.sync.HybridSyncP2pAdapter;
import io.xdag.consensus.pow.PowAlgorithm;
import io.xdag.consensus.pow.RandomXPow;
import io.xdag.consensus.pow.Sha256Pow;
import io.xdag.consensus.miner.MiningManager;
import io.xdag.core.*;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.AccountStore;
import io.xdag.db.DagStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.TransactionStore;
import io.xdag.db.rocksdb.impl.AccountStoreImpl;
import io.xdag.db.rocksdb.impl.DagStoreImpl;
import io.xdag.db.rocksdb.config.DatabaseFactory;
import io.xdag.db.rocksdb.config.DatabaseName;
import io.xdag.db.rocksdb.impl.OrphanBlockStoreImpl;
import io.xdag.db.rocksdb.config.RocksdbFactory;
import io.xdag.db.rocksdb.impl.TransactionStoreImpl;
import io.xdag.db.cache.DagCache;
import io.xdag.db.cache.DagEntityResolver;
import io.xdag.p2p.P2pConfigFactory;
import io.xdag.p2p.P2pService;
import io.xdag.p2p.config.P2pConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.units.bigints.UInt256;

import java.io.File;
import java.util.Map;

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
 * Block block = dagKernel.getDagStore().getBlockByHash(hash, true);
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
  private final OrphanBlockStore orphanBlockStore;

  private final DagCache dagCache;
  private final DagEntityResolver entityResolver;

  private DagAccountManager dagAccountManager;
  private DagTransactionProcessor dagTransactionProcessor;
  private DagBlockProcessor dagBlockProcessor;

  private DagChain dagChain;
  private HybridSyncManager hybridSyncManager;
  private HybridSyncP2pAdapter hybridSyncP2pAdapter;

  // Mining component (4)
  private MiningManager miningManager;
  private PowAlgorithm powAlgorithm;  // RandomXPow instance

  // Mining RPC service (for pool server integration)
  private io.xdag.rpc.service.MiningRpcServiceImpl miningRpcService;

  // P2P service (5)
  private P2pService p2pService;

  // Genesis configuration
  private GenesisConfig genesisConfig;

  // Wallet for genesis block creation (optional)
  private Wallet wallet;

  private volatile boolean running = false;
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
      log.info("DagKernel initialization complete");
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

      // 5. Create HybridSyncP2pAdapter (bridge to P2P layer)
      this.hybridSyncP2pAdapter = new HybridSyncP2pAdapter();
      log.info("   ✓ HybridSyncP2pAdapter initialized");

      // 6. Create HybridSyncManager (inject adapter)
      this.hybridSyncManager = new HybridSyncManager(this, dagChain, hybridSyncP2pAdapter);
      log.info("   ✓ HybridSyncManager initialized");

      // 7. Create PoW Algorithm (choose one):
      // Option A: RandomX (default - for blocks after fork)
      this.powAlgorithm = new RandomXPow(config, dagChain);
      log.info("   ✓ RandomXPow initialized");

      // Option B: SHA256 (for blocks before fork, uncomment to use)
      // this.powAlgorithm = new Sha256Pow(config);
      // log.info("   ✓ Sha256Pow initialized");

      // 8. Create MiningManager (4)
      // TTL is taken from config (default is 8)
      if (wallet != null) {
          int ttl = config.getNodeSpec() != null ? config.getNodeSpec().getTTL() : 8;
          this.miningManager = new MiningManager(
                  this, wallet, powAlgorithm, ttl);
          log.info("   ✓ MiningManager initialized (TTL={})", ttl);
      } else {
          log.warn("   ⚠ MiningManager not initialized (wallet required)");
      }

      // 9. Create Mining RPC Service (for pool server integration)
      if (wallet != null) {
          this.miningRpcService = new io.xdag.rpc.service.MiningRpcServiceImpl(
                  dagChain, wallet, powAlgorithm);
          log.info("   ✓ MiningRpcService initialized (pool server interface ready)");
      } else {
          log.warn("   ⚠ MiningRpcService not initialized (wallet required)");
      }

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

          // Bootstrap genesis block if needed
          bootstrapGenesis();

          // Start P2P service (5)
          startP2pService();

          // Start HybridSyncManager (auto-sync)
          if (hybridSyncManager != null) {
              hybridSyncManager.start();
              log.info("✓ HybridSyncManager started (auto-sync enabled)");
          }

          // Start PoW Algorithm (RandomX or SHA256)
          if (powAlgorithm != null) {
              powAlgorithm.start();
              log.info("✓ PoW Algorithm started: {}", powAlgorithm.getName());
          }

          // Start MiningManager (4)
          if (miningManager != null) {
              miningManager.start();
              log.info("✓ MiningManager started (mining enabled)");
          }

          running = true;
          log.info("========================================");
          log.info("✓ DagKernel started successfully");
          log.info("  - Storage: DagStore + TransactionStore + AccountStore + OrphanBlockStore");
          log.info("  - Cache: DagCache (13.8 MB L1) + DagEntityResolver");
          log.info("  - Consensus: DagChain + HybridSyncManager");
          if (miningManager != null) {
              log.info("  - Mining: MiningManager (enabled)");
          }
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
          // Stop MiningManager first (4)
          if (miningManager != null) {
              miningManager.stop();
              log.info("✓ MiningManager stopped");
          }

          // Stop PoW Algorithm (RandomX or SHA256)
          if (powAlgorithm != null) {
              powAlgorithm.stop();
              log.info("✓ PoW Algorithm stopped: {}", powAlgorithm.getName());
          }

          // Stop P2P service (5)
          stopP2pService();

          // Stop HybridSyncManager first (if present)
          if (hybridSyncManager != null) {
              hybridSyncManager.stop();
              log.info("✓ HybridSyncManager stopped");
          }

          // Stop HybridSyncP2pAdapter
          if (hybridSyncP2pAdapter != null) {
              // hybridSyncP2pAdapter cleanup if needed
              log.info("✓ HybridSyncP2pAdapter stopped");
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

  // ========== P2P Service Management (5) ==========

  /**
   * Start P2P service for block broadcasting
   *
   * <p>5+: P2P integration with application layer event handler.
   * Registers XdagP2pEventHandler to enable HybridSync protocol.
   */
  private void startP2pService() {
      // P2P service requires a wallet/coinbase key
      if (wallet == null || wallet.getDefKey() == null) {
          log.warn("⚠ P2P service not started (wallet required)");
          return;
      }

      try {
          log.info("Initializing P2P service...");

          // Create P2P configuration
          ECKeyPair coinbase = wallet.getDefKey();
          P2pConfig p2pConfig = P2pConfigFactory.createP2pConfig(config, coinbase);

          // Create application layer event handler
          io.xdag.p2p.XdagP2pEventHandler eventHandler = new io.xdag.p2p.XdagP2pEventHandler(this);

          // Connect HybridSyncP2pAdapter to event handler
          eventHandler.setHybridSyncAdapter(hybridSyncP2pAdapter);

          // Register event handler with P2P config
          p2pConfig.addP2pEventHandle(eventHandler);

          // Create and start P2P service
          this.p2pService = new P2pService(p2pConfig);
          this.p2pService.start();

          // Connect P2P service to HybridSyncP2pAdapter for channel management
          if (hybridSyncP2pAdapter != null) {
              hybridSyncP2pAdapter.setP2pService(this.p2pService);
              log.info("✓ P2P service connected to HybridSyncP2pAdapter");
          }

          log.info("✓ P2P service started (broadcasting enabled)");

      } catch (Throwable e) {
          // Catch both Exception and Error (including NoSuchFieldError from P2P refactoring)
          log.error("Failed to start P2P service: {}", e.getMessage(), e);
          log.warn("⚠ Continuing without P2P (block broadcasting disabled)");
          this.p2pService = null;
      }
  }

  /**
   * Stop P2P service
   */
  private void stopP2pService() {
      if (p2pService != null) {
          log.info("Stopping P2P service...");
          try {
              p2pService.stop();
              log.info("✓ P2P service stopped");
          } catch (Exception e) {
              log.error("Error stopping P2P service: {}", e.getMessage());
          }
          p2pService = null;
      }
  }

  // ========== Genesis Configuration and Bootstrap ==========

  /**
   * Load genesis configuration from genesis.json
   *
   * <p>REQUIRED: genesis.json must exist. This follows Ethereum's approach where
   * each network has an explicit genesis configuration.
   *
   * <p>Search order:
   * <ol>
   *   <li>{rootDir}/genesis.json</li>
   *   <li>{rootDir}/config/genesis.json</li>
   *   <li>FAIL: genesis.json is required</li>
   * </ol>
   *
   * @throws RuntimeException if genesis.json not found
   */
  private void loadGenesisConfig() {
      String rootDir = config.getRootDir();

      // Try loading from rootDir/genesis.json
      File genesisFile = new File(rootDir, "genesis.json");
      if (!genesisFile.exists()) {
          // Try config subdirectory
          genesisFile = new File(rootDir, "config/genesis.json");
      }

      if (!genesisFile.exists()) {
          // CRITICAL: genesis.json is REQUIRED (like Ethereum)
          String errorMsg = String.format(
              "genesis.json not found! Searched:\n" +
              "  1. %s/genesis.json\n" +
              "  2. %s/config/genesis.json\n\n" +
              "XDAG requires explicit genesis configuration (like Ethereum).\n" +
              "Please create genesis.json for your network:\n" +
              "  - Mainnet: cp config/genesis-mainnet.json ./genesis.json\n" +
              "  - Testnet: cp config/genesis-testnet.json ./genesis.json\n" +
              "  - Devnet:  cp config/genesis-devnet.json ./genesis.json",
              rootDir, rootDir
          );
          log.error(errorMsg);
          throw new RuntimeException("genesis.json is required but not found");
      }

      try {
          genesisConfig = GenesisConfig.load(genesisFile);
          genesisConfig.validate();

          log.info("========================================");
          log.info("Loaded genesis configuration:");
          log.info("  File: {}", genesisFile.getAbsolutePath());
          log.info("  Network: {}", genesisConfig.getNetworkId());
          log.info("  Chain ID: {}", genesisConfig.getChainId());
          log.info("  Genesis Timestamp: {} ({})",
                  genesisConfig.getTimestamp(),
                  new java.util.Date(genesisConfig.getTimestamp() * 1000));
          log.info("  Initial Difficulty: {}", genesisConfig.getInitialDifficulty());
          log.info("  Epoch Length: {} seconds", genesisConfig.getEpochLength());

          if (genesisConfig.hasAllocations()) {
              log.info("  Initial Allocations: {} addresses", genesisConfig.getAlloc().size());
              long totalAlloc = genesisConfig.getAlloc().values().stream()
                      .map(s -> new java.math.BigInteger(s))
                      .reduce(java.math.BigInteger.ZERO, java.math.BigInteger::add)
                      .divide(java.math.BigInteger.valueOf(1_000_000_000))
                      .longValue();
              log.info("  Total Allocated: {} XDAG", totalAlloc);
          }

          if (genesisConfig.hasSnapshot()) {
              log.info("  Snapshot Import: ENABLED");
              log.info("    Height: {}", genesisConfig.getSnapshot().getHeight());
              log.info("    Hash: {}", genesisConfig.getSnapshot().getHash().substring(0, 18) + "...");
              log.info("    File: {}", genesisConfig.getSnapshot().getDataFile());
          }
          log.info("========================================");

      } catch (Exception e) {
          log.error("Failed to load or validate genesis.json from {}: {}",
                  genesisFile, e.getMessage());
          throw new RuntimeException("Invalid genesis.json", e);
      }
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
   *   <li>Snapshot import if configured</li>
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
              // Check for snapshot import
              if (genesisConfig.hasSnapshot()) {
                  importSnapshot();
              } else {
                  createGenesisBlock();
              }

              // Apply initial allocations
              if (genesisConfig.hasAllocations()) {
                  applyInitialAllocations();
              }

              log.info("========================================");
              log.info("✓ Genesis bootstrap complete");
              log.info("  - Network: {}", genesisConfig.getNetworkId());
              log.info("  - Chain ID: {}", genesisConfig.getChainId());
              log.info("  - Main chain height: {}", dagChain.getMainChainLength());
              log.info("  - Max difficulty: {}", dagChain.getChainStats().getMaxDifficulty().toDecimalString());
              log.info("========================================");

          } catch (Exception e) {
              log.error("Failed to bootstrap genesis", e);
              throw new RuntimeException("Genesis bootstrap failed", e);
          }

      } else {
          // Subsequent startup: Verify existing genesis matches genesis.json
          log.info("Chain already initialized ({} blocks), verifying genesis...", mainBlockCount);
          verifyGenesisBlock();
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
      // Get genesis block (height 0 means orphan block)
      Block genesisBlock = dagStore.getMainBlockByHeight(0, true);
      if (genesisBlock == null) {
          log.warn("Cannot verify genesis block: main chain height 0 not found");
          log.warn("This may happen if main chain index is not yet built");
          return;
      }

      log.info("Verifying genesis block against genesis.json...");

      // Verify timestamp
      long expectedTimestamp = genesisConfig.getTimestamp();
      long actualTimestamp = genesisBlock.getTimestamp();
      long timeDiff = Math.abs(actualTimestamp - expectedTimestamp);

      if (timeDiff > 64) {  // Allow 1 epoch tolerance
          String error = String.format(
              "Genesis block timestamp mismatch!\n" +
              "  Expected (from genesis.json): %d\n" +
              "  Actual (from blockchain): %d\n" +
              "  Difference: %d seconds\n\n" +
              "This means you're trying to run a node with a different genesis.json\n" +
              "than the one used to create this chain. This is not allowed.\n\n" +
              "Solutions:\n" +
              "  1. Use the correct genesis.json for this network\n" +
              "  2. Delete the chain data and start fresh with current genesis.json",
              expectedTimestamp, actualTimestamp, timeDiff
          );
          log.error(error);
          throw new RuntimeException("Genesis block verification failed: timestamp mismatch");
      }

      // Verify difficulty
      UInt256 expectedDifficulty = genesisConfig.getInitialDifficultyUInt256();
      UInt256 actualDifficulty = genesisBlock.getHeader().getDifficulty();

      if (!expectedDifficulty.equals(actualDifficulty)) {
          String error = String.format(
              "Genesis block difficulty mismatch!\n" +
              "  Expected (from genesis.json): %s\n" +
              "  Actual (from blockchain): %s\n\n" +
              "Chain was created with different genesis configuration.",
              expectedDifficulty.toHexString(), actualDifficulty.toHexString()
          );
          log.error(error);
          throw new RuntimeException("Genesis block verification failed: difficulty mismatch");
      }

      log.info("✓ Genesis block verification passed");
      log.info("  - Timestamp: {} (matches config)", actualTimestamp);
      log.info("  - Difficulty: {} (matches config)", actualDifficulty.toHexString());
      log.info("  - Network: {}", genesisConfig.getNetworkId());
  }

  /**
   * Create genesis block from configuration
   *
   * <p>5+: REQUIRES deterministic genesisCoinbase from genesis.json (Bitcoin/Ethereum approach).
   * All nodes must create identical genesis blocks using the network-defined coinbase address.
   *
   * @throws RuntimeException if creation fails or if genesisCoinbase is not configured
   */
  private void createGenesisBlock() {
      log.info("Creating genesis block...");

      // Use configured timestamp from genesis.json
      long timestamp = genesisConfig.getTimestamp();
      log.info("  - Genesis timestamp: {}", timestamp);

      // 5+: genesisCoinbase is REQUIRED for deterministic genesis (Bitcoin/Ethereum approach)
      if (!genesisConfig.hasGenesisCoinbase()) {
          throw new RuntimeException(
              "genesisCoinbase is required in genesis.json!\n\n" +
              "XDAG requires deterministic genesis block creation.\n" +
              "All nodes must create IDENTICAL genesis blocks (Bitcoin/Ethereum approach).\n\n" +
              "Please add to your genesis.json:\n" +
              "  \"genesisCoinbase\": \"4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi\"\n\n" +
              "Format: base58check encoded 20-byte XDAG address (recommended)\n" +
              "Legacy: 0x-prefixed 32-byte hex (backward compatibility only)"
          );
      }

      // Use deterministic coinbase from genesis.json (20-byte address)
      org.apache.tuweni.bytes.Bytes genesisCoinbase = genesisConfig.getGenesisCoinbaseBytes();
      if (genesisCoinbase == null || genesisCoinbase.size() != 20) {
          throw new RuntimeException(
              "Invalid genesisCoinbase in genesis.json!\n" +
              "Expected 20-byte address, got: " +
              (genesisCoinbase != null ? genesisCoinbase.size() + " bytes" : "null")
          );
      }

      log.info("  - Using deterministic genesisCoinbase from genesis.json");
      log.info("  - Coinbase: {}", genesisCoinbase.toHexString());
      log.info("  - This ensures all nodes create IDENTICAL genesis blocks");

      // Create genesis block via DagChain using deterministic coinbase
      Block genesisBlock = dagChain.createGenesisBlock(genesisCoinbase, timestamp);
      log.info("  - Genesis block created: {}", genesisBlock.getHash().toHexString());

      // Import genesis block
      DagImportResult result = dagChain.tryToConnect(genesisBlock);
      if (!result.isMainBlock()) {
          throw new RuntimeException("Failed to import genesis block: " + result.getStatus() +
                  (result.getErrorMessage() != null ? " - " + result.getErrorMessage() : ""));
      }

      log.info("✓ Genesis block imported successfully");
  }

  /**
   * Import snapshot from old XDAG chain
   *
   * @throws RuntimeException if import fails
   */
  private void importSnapshot() {
      log.info("Importing snapshot...");
      log.info("  - {}", genesisConfig.getSnapshot().getDescription());

      // TODO  Implement snapshot import
      //  1. Read snapshot data file
      //  2. Parse blocks and accounts
      //  3. Import to DagStore and AccountStore
      //  4. Create genesis block referencing snapshot state

      throw new UnsupportedOperationException("Snapshot import not yet implemented (5)");
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

      log.info("✓ Applied {} initial allocations", successCount);
  }

}
