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
import io.xdag.consensus.SyncManager;
import io.xdag.consensus.XdagPow;
import io.xdag.consensus.XdagSync;
import io.xdag.core.*;
import io.xdag.consensus.RandomX;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.*;
import io.xdag.db.mysql.TransactionHistoryStoreImpl;
import io.xdag.db.rocksdb.*;
import io.xdag.db.store.*;
import io.xdag.net.*;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.node.NodeManager;
import io.xdag.p2p.P2pConfigFactory;
import io.xdag.p2p.XdagP2pEventHandler;
import io.xdag.p2p.config.P2pConfig;
import io.xdag.pool.WebSocketServer;
import io.xdag.pool.PoolAwardManagerImpl;
import io.xdag.rpc.api.XdagApi;
import io.xdag.rpc.api.impl.XdagApiImpl;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Broadcast a new block to all connected peers via P2P service
     */
    public void broadcastBlock(Block block, int ttl) {
        if (p2pService == null || p2pEventHandler == null) {
            log.warn("P2P service not initialized, cannot broadcast block");
            return;
        }

        // Broadcast to all channels via P2P service
        for (io.xdag.p2p.channel.Channel channel : p2pService.getChannelManager().getChannels().values()) {
            if (channel.isFinishHandshake()) {
                p2pEventHandler.sendNewBlock(channel, block, ttl);
            }
        }
    }

    /**
     * Get active P2P channels count
     */
    public int getActiveChannelsCount() {
        if (p2pService == null) {
            return 0;
        }
        return (int) p2pService.getChannelManager().getChannels().values().stream()
                .filter(io.xdag.p2p.channel.Channel::isFinishHandshake)
                .count();
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

    /**
     * Broadcast a BlockWrapper to all connected peers (compatibility method)
     * @deprecated Use broadcastBlock(Block, int) instead
     */
    @Deprecated
    public void broadcastBlockWrapper(BlockWrapper wrapper) {
        broadcastBlock(wrapper.getBlock(), wrapper.getTtl());
    }

    protected XdagSync sync;
    protected XdagPow pow;
    private SyncManager syncMgr;

    protected Bytes firstAccount;
    protected Block firstBlock;
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
        XdagStats xdagStats = blockchain.getXdagStats();
        
        // Create genesis block if first startup
        if (xdagStats.getOurLastBlockHash() == null) {
            firstAccount = toBytesAddress(wallet.getDefKey().getPublicKey());
            firstBlock = new Block(config, XdagTime.getCurrentTimestamp(), null, null, false,
                    null, null, -1, XAmount.ZERO, null);
            firstBlock.signOut(wallet.getDefKey());
            xdagStats.setOurLastBlockHash(firstBlock.getHash().toArray());
            if (xdagStats.getGlobalMiner() == null) {
                xdagStats.setGlobalMiner(firstAccount.toArray());
            }
            blockchain.tryToConnect(firstBlock);
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
