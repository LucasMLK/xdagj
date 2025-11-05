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

import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;

import io.xdag.cli.TelnetServer;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.MainnetConfig;
import io.xdag.config.TestnetConfig;
import io.xdag.consensus.RandomX;
import io.xdag.consensus.SyncManager;
import io.xdag.consensus.XdagPow;
import io.xdag.consensus.XdagSync;
import io.xdag.core.BlockFinalizationService;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.core.BlockchainImpl;
import io.xdag.core.ChainStats;
import io.xdag.core.ImportResult;
import io.xdag.core.XdagState;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.AddressStore;
import io.xdag.db.BlockStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.SnapshotStore;
import io.xdag.db.TransactionHistoryStore;
import io.xdag.db.TransactionStore;
import io.xdag.db.mysql.TransactionHistoryStoreImpl;
import io.xdag.db.rocksdb.AddressStoreImpl;
import io.xdag.db.rocksdb.BlockStoreImpl;
import io.xdag.db.rocksdb.DatabaseFactory;
import io.xdag.db.rocksdb.DatabaseName;
import io.xdag.db.rocksdb.OrphanBlockStoreImpl;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.store.BloomFilterBlockStore;
import io.xdag.db.store.CachedBlockStore;
import io.xdag.db.store.FinalizedBlockStore;
import io.xdag.db.store.FinalizedBlockStoreImpl;
import io.xdag.net.ChannelManager;
import io.xdag.net.NetDB;
import io.xdag.net.NetDBManager;
import io.xdag.net.PeerClient;
import io.xdag.net.PeerServer;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.node.NodeManager;
import io.xdag.p2p.P2pConfigFactory;
import io.xdag.p2p.XdagP2pEventHandler;
import io.xdag.p2p.config.P2pConfig;
import io.xdag.pool.PoolAwardManagerImpl;
import io.xdag.pool.WebSocketServer;
import io.xdag.rpc.api.XdagApi;
import io.xdag.rpc.api.impl.XdagApiImpl;
import io.xdag.utils.XdagTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;

@Slf4j
@Getter
@Setter
public class Kernel {

    // Node status
    protected Status status = Status.STOPPED;
    protected Config config;
    protected Wallet wallet;
    protected ECKeyPair coinbase;
    protected DatabaseFactory dbFactory;
    protected AddressStore addressStore;
    protected BlockStore blockStore;
    protected OrphanBlockStore orphanBlockStore;
    protected TransactionHistoryStore txHistoryStore;
    protected TransactionStore transactionStore;  // Phase 4 - v5.1 Transaction storage

    protected SnapshotStore snapshotStore;

    // Finalized block storage (Phase 2 refactor)
    protected FinalizedBlockStore finalizedBlockStore;

    // Block finalization service (Phase 3)
    protected BlockFinalizationService blockFinalizationService;

    protected Blockchain blockchain;
    protected NetDB netDB;
    protected PeerClient client;
    protected ChannelManager channelMgr;
    protected NodeManager nodeMgr;
    protected NetDBManager netDBMgr;
    protected PeerServer p2p;

    // New P2P service components (xdagj-p2p library)
    protected io.xdag.p2p.P2pService p2pService;
    protected XdagP2pEventHandler p2pEventHandler;

    public void broadcastBlock(Block block, int ttl) {
        if (p2pService == null || p2pEventHandler == null) {
            log.warn("P2P service not initialized, cannot broadcast Block");
            return;
        }

        try {
            // Serialize Block
            byte[] blockBytes = block.toBytes();

            // Create message manually (temporary - should use dedicated NewBlockMessage)
            io.xdag.utils.SimpleEncoder enc = new io.xdag.utils.SimpleEncoder();
            enc.writeBytes(blockBytes);
            enc.writeInt(ttl);
            byte[] messageBody = enc.toBytes();

            // Phase 7.3.0: Use NEW_BLOCK_V5 message code for Block
            byte[] fullMessage = new byte[messageBody.length + 1];
            fullMessage[0] = io.xdag.net.message.MessageCode.NEW_BLOCK.toByte();
            System.arraycopy(messageBody, 0, fullMessage, 1, messageBody.length);

            // Broadcast to all channels
            int sentCount = 0;
            for (io.xdag.p2p.channel.Channel channel : p2pService.getChannelManager().getChannels().values()) {
                if (channel.isFinishHandshake()) {
                    try {
                        channel.send(Bytes.wrap(fullMessage));
                        sentCount++;
                    } catch (Exception e) {
                        log.error("Error broadcasting Block to {}: {}",
                                channel.getRemoteAddress(), e.getMessage());
                    }
                }
            }

            log.debug("Block {} broadcasted to {} peers (ttl={})",
                    block.getHash().toHexString().substring(0, 16) + "...", sentCount, ttl);

        } catch (Exception e) {
            log.error("Error broadcasting Block: {}", e.getMessage(), e);
        }
    }

    /**
     * Get list of active P2P channels (for sync protocol)
     */
    public java.util.List<io.xdag.p2p.channel.Channel> getActiveP2pChannels() {
        if (p2pService == null) {
            return java.util.Collections.emptyList();
        }
        return p2pService.getChannelManager().getChannels().values().stream()
                .filter(io.xdag.p2p.channel.Channel::isFinishHandshake)
                .collect(java.util.stream.Collectors.toList());
    }

    protected XdagSync sync;
    protected XdagPow pow;
    private SyncManager syncMgr;

    protected Bytes firstAccount;
//    protected Block firstBlock;
    protected WebSocketServer webSocketServer;
    protected PoolAwardManagerImpl poolAwardManager;
    protected XdagState xdagState;

    // Counter for connected channels
    protected AtomicInteger channelsAccount = new AtomicInteger(0);

    protected TelnetServer telnetServer;

    protected RandomX randomx;

    // Running status flag
    protected AtomicBoolean isRunning = new AtomicBoolean(false);
    
    // Start time epoch
    protected long startEpoch;

    // RPC related components
    protected XdagApi api;

    public Kernel(Config config, Wallet wallet) {
        this.config = config;
        this.wallet = wallet;
        this.coinbase = wallet.getDefKey();
        this.xdagState = XdagState.INIT;
    }

    public Kernel(Config config, ECKeyPair coinbase) {
        this.config = config;
        this.coinbase = coinbase;
    }

    /**
     * Start the kernel.
     */
    public synchronized void testStart() {
        if (isRunning.get()) {
            return;
        }
        isRunning.set(true);
        startEpoch = XdagTime.getCurrentEpoch();

        // Initialize channel manager
        channelMgr = new ChannelManager(this);
        channelMgr.start();

        netDBMgr = new NetDBManager(this.config);
        netDBMgr.start();

        // Initialize database components
        dbFactory = new RocksdbFactory(this.config);
        blockStore = new BlockStoreImpl(
                dbFactory.getDB(DatabaseName.INDEX),
                dbFactory.getDB(DatabaseName.BLOCK),
                dbFactory.getDB(DatabaseName.TIME),
                dbFactory.getDB(DatabaseName.TXHISTORY));
        log.info("Block Store init.");
        blockStore.start();

        addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
        addressStore.start();


        orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND));
        orphanBlockStore.start();

        // Initialize TransactionStore (Phase 4 - v5.1)
        transactionStore = new io.xdag.db.rocksdb.TransactionStoreImpl(
                dbFactory.getDB(DatabaseName.TRANSACTION),
                dbFactory.getDB(DatabaseName.INDEX));
        transactionStore.start();
        log.info("Transaction Store init.");

        if (config.getEnableTxHistory()) {
            long txPageSizeLimit = config.getTxPageSizeLimit();
            txHistoryStore = new TransactionHistoryStoreImpl(txPageSizeLimit);
            log.info("Transaction History Store init.");
        }

        // Initialize finalized block store (Phase 2 refactor)
        try {
            String finalizedStorePath = config.getRootDir() + "/finalized";
            FinalizedBlockStore baseStore = new FinalizedBlockStoreImpl(finalizedStorePath);
            finalizedBlockStore = new CachedBlockStore(
                    new BloomFilterBlockStore(baseStore)
            );
            log.info("Finalized Block Store init at: {}", finalizedStorePath);
        } catch (Exception e) {
            log.error("Failed to initialize Finalized Block Store", e);
            throw new RuntimeException("Failed to initialize storage layer", e);
        }

        // Initialize network components
        netDB = new NetDB();

        // Initialize RandomX
        randomx = new RandomX(config);
        randomx.start();

        // Initialize blockchain
        blockchain = new BlockchainImpl(this);
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        ChainStats chainStats = blockchain.getChainStats();

        // Create genesis block if first startup
        // Phase 7.5: Genesis Block creation restored
        // Phase 7.3: Check mainBlockCount == 0 instead of ourLastBlockHash (field removed)
        if (chainStats.getMainBlockCount() == 0) {
            firstAccount = toBytesAddress(wallet.getDefKey().getPublicKey());

            // Create genesis Block
            Block genesisBlock = blockchain.createGenesisBlock(
                wallet.getDefKey(),
                XdagTime.getCurrentTimestamp()
            );

            // Phase 7.3: ourLastBlockHash and globalMiner fields removed from ChainStats
            // Genesis block tracking is now handled by mainBlockCount

            // Import genesis block to blockchain
            ImportResult result = blockchain.tryToConnect(genesisBlock);
            log.info("Genesis Block import result: {}", result);

            // Store the genesis block reference
//            firstBlock = null;  // No legacy Block for genesis (Block only)
        } else {
            firstAccount = toBytesAddress(wallet.getDefKey().getPublicKey());
        }

        // Initialize RandomX based on snapshot configuration
        if (config.getSnapshotSpec().isSnapshotJ()) {
            randomx.randomXLoadingSnapshotJ();
            blockStore.setSnapshotBoot();
        } else {
            if (config.getSnapshotSpec().isSnapshotEnabled() && !blockStore.isSnapshotBoot()) {
                System.out.println("pre seed:" + Bytes.wrap(blockchain.getPreSeed()).toHexString());
                randomx.randomXLoadingSnapshot(blockchain.getPreSeed());
                blockStore.setSnapshotBoot();
            } else if (config.getSnapshotSpec().isSnapshotEnabled() && blockStore.isSnapshotBoot()) {
                System.out.println("pre seed:" + Bytes.wrap(blockchain.getPreSeed()).toHexString());
                randomx.randomXLoadingForkTimeSnapshot(blockchain.getPreSeed());
            } else {
                randomx.randomXLoadingForkTime();
            }
        }

        // Set initial state based on network type
        if (config instanceof MainnetConfig) {
            xdagState = XdagState.WAIT;
        } else if (config instanceof TestnetConfig) {
            xdagState = XdagState.WTST;
        } else if (config instanceof DevnetConfig) {
            xdagState = XdagState.WDST;
        }

        // Initialize P2P networking with xdagj-p2p library
        log.info("Initializing P2P service...");
        P2pConfig p2pConfig = P2pConfigFactory.createP2pConfig(config, coinbase);

        // Create event handler
        p2pEventHandler = new XdagP2pEventHandler(this);

        // Register event handler
        try {
            p2pConfig.addP2pEventHandle(p2pEventHandler);
        } catch (Exception e) {
            log.error("Failed to register P2P event handler", e);
            throw new RuntimeException("Failed to initialize P2P service", e);
        }

        // Create and start P2P service
        p2pService = new io.xdag.p2p.P2pService(p2pConfig);
        p2pService.start();
        log.info("P2P service started successfully");

        // Initialize synchronization
        sync = new XdagSync(this);
        sync.start();

        syncMgr = new SyncManager(this);
        syncMgr.start();

        poolAwardManager = new PoolAwardManagerImpl(this);

        // Initialize mining
        pow = new XdagPow(this);

        if (webSocketServer == null) {
            webSocketServer = new WebSocketServer(this, config.getPoolWhiteIPList(), config.getWebsocketServerPort());
        }
        webSocketServer.start();

        // Start RPC
        api = new XdagApiImpl(this);
        api.start();

        // Start Telnet Server
        telnetServer = new TelnetServer(this);
        telnetServer.start();

        blockchain.registerListener(pow);

        // Start block finalization service (Phase 3)
        if (finalizedBlockStore != null) {
            blockFinalizationService = new BlockFinalizationService(this);
            blockFinalizationService.start();
            log.info("Block Finalization Service started");
        }

        Launcher.registerShutdownHook("kernel", this::testStop);
    }

    /**
     * Stops the kernel in an orderly fashion.
     */
    public synchronized void testStop() {
        if (!isRunning.get()) {
            return;
        }

        isRunning.set(false);

        // Stop Api
        if (api != null) {
            api.stop();
        }

        // Stop consensus
        sync.stop();
        syncMgr.stop();
        pow.stop();

        // Stop P2P service
        if (p2pService != null) {
            log.info("Stopping P2P service...");
            p2pService.stop();
        }

        // Stop networking layer (legacy, will be removed)
        if (channelMgr != null) {
            channelMgr.stop();
        }
        if (nodeMgr != null) {
            nodeMgr.stop();
        }

        // Close message queue timer
        MessageQueue.timer.shutdown();

        // Close P2P networking (legacy, will be removed)
        if (p2p != null) {
            p2p.close();
        }
        if (client != null) {
            client.close();
        }

        // Stop data layer
        blockchain.stopCheckMain();

        // Stop block finalization service (Phase 3)
        if (blockFinalizationService != null) {
            blockFinalizationService.stop();
            log.info("Block Finalization Service stopped");
        }

        // Close finalized block store
        if (finalizedBlockStore != null) {
            log.info("Closing Finalized Block Store...");
            finalizedBlockStore.close();
        }

        // Close all databases
        for (DatabaseName name : DatabaseName.values()) {
            dbFactory.getDB(name).close();
        }

        // Stop remaining services
        if(webSocketServer != null) {
            webSocketServer.stop();

        }
        poolAwardManager.stop();
    }

    public enum Status {
        STOPPED, SYNCING, BLOCK_PRODUCTION_ON, SYNCDONE
    }
}
