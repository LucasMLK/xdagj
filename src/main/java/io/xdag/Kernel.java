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

import io.xdag.cli.TelnetServer;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.MainnetConfig;
import io.xdag.config.TestnetConfig;
import io.xdag.consensus.SyncManager;
import io.xdag.consensus.XdagPow;
import io.xdag.consensus.XdagSync;
import io.xdag.core.*;
import io.xdag.crypto.Keys;
import io.xdag.crypto.RandomX;
import io.xdag.db.*;
import io.xdag.db.mysql.TransactionHistoryStoreImpl;
import io.xdag.db.rocksdb.*;
import io.xdag.net.*;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.node.NodeManager;
import io.xdag.pool.WebSocketServer;
import io.xdag.pool.PoolAwardManagerImpl;
import io.xdag.rpc.Web3XdagChain;
import io.xdag.rpc.JsonRpcServer;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.crypto.KeyPair;

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
    protected KeyPair coinbase;
    protected DatabaseFactory dbFactory;
    protected AddressStore addressStore;
    protected BlockStore blockStore;
    protected OrphanBlockStore orphanBlockStore;
    protected TransactionHistoryStore txHistoryStore;

    protected SnapshotStore snapshotStore;
    protected Blockchain blockchain;
    protected NetDB netDB;
    protected PeerClient client;
    protected ChannelManager channelMgr;
    protected NodeManager nodeMgr;
    protected NetDBManager netDBMgr;
    protected PeerServer p2p;
    protected XdagSync sync;
    protected XdagPow pow;
    private SyncManager syncMgr;

    protected byte[] firstAccount;
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
    @Getter
    protected long startEpoch;

    // RPC related components
    protected Web3XdagChain web3;
    protected JsonRpcServer rpcServer;

    public Kernel(Config config, Wallet wallet) {
        this.config = config;
        this.wallet = wallet;
        this.coinbase = wallet.getDefKey();
        this.xdagState = XdagState.INIT;
        this.telnetServer = new TelnetServer(config.getAdminSpec().getAdminTelnetIp(), config.getAdminSpec().getAdminTelnetPort(),
                this);
    }

    public Kernel(Config config, KeyPair coinbase) {
        this.config = config;
        this.coinbase = coinbase;
    }

    /**
     * Start the kernel.
     */
    public synchronized void testStart() throws Exception {
        if (isRunning.get()) {
            return;
        }
        isRunning.set(true);
        startEpoch = XdagTime.getCurrentEpoch();

        // Initialize channel manager
        channelMgr = new ChannelManager(this);
        channelMgr.start();
        log.info("Channel Manager start...");
        netDBMgr = new NetDBManager(this.config);
        netDBMgr.init();
        log.info("NetDB Manager init.");

        // Initialize wallet
        log.info("Wallet init.");

        // Initialize database components
        dbFactory = new RocksdbFactory(this.config);
        blockStore = new BlockStoreImpl(
                dbFactory.getDB(DatabaseName.INDEX),
                dbFactory.getDB(DatabaseName.BLOCK),
                dbFactory.getDB(DatabaseName.TIME),
                dbFactory.getDB(DatabaseName.TXHISTORY));
        log.info("Block Store init.");
        blockStore.init();

        addressStore = new AddressStoreImpl(dbFactory.getDB(DatabaseName.ADDRESS));
        addressStore.init();
        log.info("Address Store init");

        orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND));
        log.info("Orphan Pool init.");
        orphanBlockStore.init();

        if (config.getEnableTxHistory()) {
            long txPageSizeLimit = config.getTxPageSizeLimit();
            txHistoryStore = new TransactionHistoryStoreImpl(txPageSizeLimit);
            log.info("Transaction History Store init.");
        }

        // Initialize network components
        netDB = new NetDB();
        log.info("NetDB init");

        // Initialize RandomX
        randomx = new RandomX(config);
        randomx.init();
        log.info("RandomX init");

        // Initialize blockchain
        blockchain = new BlockchainImpl(this);
        XdagStats xdagStats = blockchain.getXdagStats();
        
        // Create genesis block if first startup
        if (xdagStats.getOurLastBlockHash() == null) {
            firstAccount = Keys.toBytesAddress(wallet.getDefKey().getPublicKey());
            firstBlock = new Block(config, XdagTime.getCurrentTimestamp(), null, null, false, null, null, -1, XAmount.ZERO);
            firstBlock.signOut(wallet.getDefKey());
            xdagStats.setOurLastBlockHash(firstBlock.getHashLow().toArray());
            if (xdagStats.getGlobalMiner() == null) {
                xdagStats.setGlobalMiner(firstAccount);
            }
            blockchain.tryToConnect(firstBlock);
        } else {
            firstAccount = Keys.toBytesAddress(wallet.getDefKey().getPublicKey());
        }
        log.info("Blockchain init");

        // Initialize RandomX based on snapshot configuration
        if (config.getSnapshotSpec().isSnapshotJ()) {
            randomx.randomXLoadingSnapshotJ();
            blockStore.setSnapshotBoot();
        } else {
            if (config.getSnapshotSpec().isSnapshotEnabled() && !blockStore.isSnapshotBoot()) {
                System.out.println("pre seed:" + Bytes.wrap(blockchain.getPreSeed()).toHexString());
                randomx.randomXLoadingSnapshot(blockchain.getPreSeed(), 0);
                blockStore.setSnapshotBoot();
            } else if (config.getSnapshotSpec().isSnapshotEnabled() && blockStore.isSnapshotBoot()) {
                System.out.println("pre seed:" + Bytes.wrap(blockchain.getPreSeed()).toHexString());
                randomx.randomXLoadingForkTimeSnapshot(blockchain.getPreSeed(), 0);
            } else {
                randomx.randomXLoadingForkTime();
            }
        }

        log.info("RandomX reload");

        // Initialize P2P networking
        p2p = new PeerServer(this);
        p2p.start();
        log.info("Node server start...");
        client = new PeerClient(this.config, this.coinbase);

        // Initialize node management
        nodeMgr = new NodeManager(this);
        nodeMgr.start();
        log.info("Node manager start...");

        // Initialize synchronization
        sync = new XdagSync(this);
        sync.start();
        log.info("XdagSync start...");

        syncMgr = new SyncManager(this);
        syncMgr.start();
        log.info("SyncManager start...");
        poolAwardManager = new PoolAwardManagerImpl(this);

        // Initialize mining
        pow = new XdagPow(this);
        //getWsServer().start();
        log.info("Node to pool websocket start...");
        blockchain.registerListener(pow);

        // Set initial state based on network type
        if (config instanceof MainnetConfig) {
            xdagState = XdagState.WAIT;
        } else if (config instanceof TestnetConfig) {
            xdagState = XdagState.WTST;
        } else if (config instanceof DevnetConfig) {
            xdagState = XdagState.WDST;
        }

        // Start RPC if enabled
        startWeb3();

        // Start telnet server
        telnetServer.start();

        Launcher.registerShutdownHook("kernel", this::testStop);
    }

    protected void startWeb3() {
        if (config.getRPCSpec().isRpcHttpEnabled()) {
            //web3 = new Web3Impl(this);
            //rpcServer = new XdagJsonRpcServer(web3, config.getRPCSpec().getRpcHttpHost(), config.getRPCSpec().getRpcHttpPort());
            rpcServer.start();
            log.info("RPC server started at {}:{}", config.getRPCSpec().getRpcHttpHost(), config.getRPCSpec().getRpcHttpPort());
        }
    }

    protected void stopWeb3() {
        if (rpcServer != null) {
            rpcServer.stop();
            rpcServer = null;
            web3 = null;
            log.info("RPC server stopped");
        }
    }

    /**
     * Stops the kernel in an orderly fashion.
     */
    public synchronized void testStop() {
        if (!isRunning.get()) {
            return;
        }

        isRunning.set(false);

        // Stop RPC services
        stopWeb3();

        // Stop consensus layer
        sync.stop();
        log.info("XdagSync stop.");
        syncMgr.stop();
        log.info("SyncManager stop.");
        pow.stop();
        log.info("Block production stop.");

        // Stop networking layer
        channelMgr.stop();
        log.info("ChannelMgr stop.");
        nodeMgr.stop();
        log.info("Node manager stop.");
        log.info("ChannelManager stop.");

        // Close message queue timer
        MessageQueue.timer.shutdown();

        // Close P2P networking
        p2p.close();
        log.info("Node server stop.");
        client.close();
        log.info("Node client stop.");

        // Stop data layer
        blockchain.stopCheckMain();

        // Close all databases
        for (DatabaseName name : DatabaseName.values()) {
            dbFactory.getDB(name).close();
        }

        // Stop remaining services
        webSocketServer.stop();
        log.info("WebSocket server stop.");
        poolAwardManager.stop();
        log.info("Pool award manager stop.");
    }

    public enum Status {
        STOPPED, SYNCING, BLOCK_PRODUCTION_ON, SYNCDONE
    }
}
