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

import io.xdag.DagKernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.listener.Listener;
import io.xdag.listener.Message;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Integration test for DagChain Phase 11.1 and 11.2 functionality
 *
 * <p>Tests the new methods implemented in Phase 11:
 * <ul>
 *   <li>Phase 11.1: Sync statistics and listener system</li>
 *   <li>Phase 11.2: Block creation methods</li>
 * </ul>
 *
 * @since v5.1 Phase 11
 */
public class DagChainPhase11Test {

    private DagKernel dagKernel;
    private DagChainImpl dagChain;
    private Config config;
    private Path tempDir;
    private Wallet testWallet;

    @Before
    public void setUp() throws IOException {
        // Create unique temporary directory
        tempDir = Files.createTempDirectory("dagchain-phase11-test-");

        // Create test genesis.json file
        TestGenesisHelper.createTestGenesisFile(tempDir);

        // Use DevnetConfig with custom database directory
        config = new DevnetConfig() {
            @Override
            public String getStoreDir() {
                return tempDir.toString();
            }

            @Override
            public String getRootDir() {
                return tempDir.toString();
            }
        };

        // Create test wallet with random account
        testWallet = new Wallet(config);
        testWallet.unlock("test-password");
        testWallet.addAccountRandom();

        // Create and start DagKernel with wallet
        dagKernel = new DagKernel(config, testWallet);
        dagKernel.start();

        // Create DagChainImpl instance
        dagChain = new DagChainImpl(dagKernel);
    }

    @After
    public void tearDown() {
        // Stop DagKernel
        if (dagKernel != null) {
            try {
                dagKernel.stop();
            } catch (Exception e) {
                System.err.println("Error stopping DagKernel: " + e.getMessage());
            }
        }

        // Delete temporary directory
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                try (var walk = Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(file -> {
                                if (!file.delete()) {
                                    System.err.println("Failed to delete: " + file);
                                }
                            });
                }
            } catch (Exception e) {
                System.err.println("Error deleting temp directory: " + e.getMessage());
            }
        }
    }

    // ==================== Phase 11.1: Sync Statistics Tests ====================

    /**
     * Test 1: incrementWaitingSyncCount() and decrementWaitingSyncCount()
     */
    @Test
    public void testPhase11_1_SyncCountTracking() {
        System.out.println("\n========== Test 1: Phase 11.1 - Sync Count Tracking ==========");

        // Get initial count
        ChainStats initialStats = dagChain.getChainStats();
        long initialCount = initialStats.getWaitingSyncCount();

        System.out.println("Initial waiting sync count: " + initialCount);

        // Increment count
        dagChain.incrementWaitingSyncCount();
        ChainStats afterIncrement = dagChain.getChainStats();
        long countAfterIncrement = afterIncrement.getWaitingSyncCount();

        System.out.println("After increment: " + countAfterIncrement);
        assertEquals("Count should increment by 1",
                initialCount + 1, countAfterIncrement);

        // Increment again
        dagChain.incrementWaitingSyncCount();
        ChainStats afterSecondIncrement = dagChain.getChainStats();
        long countAfterSecondIncrement = afterSecondIncrement.getWaitingSyncCount();

        System.out.println("After second increment: " + countAfterSecondIncrement);
        assertEquals("Count should increment by 2",
                initialCount + 2, countAfterSecondIncrement);

        // Decrement count
        dagChain.decrementWaitingSyncCount();
        ChainStats afterDecrement = dagChain.getChainStats();
        long countAfterDecrement = afterDecrement.getWaitingSyncCount();

        System.out.println("After decrement: " + countAfterDecrement);
        assertEquals("Count should be back to initial + 1",
                initialCount + 1, countAfterDecrement);

        // Decrement to initial
        dagChain.decrementWaitingSyncCount();
        ChainStats finalStats = dagChain.getChainStats();
        long finalCount = finalStats.getWaitingSyncCount();

        System.out.println("After second decrement: " + finalCount);
        assertEquals("Count should be back to initial",
                initialCount, finalCount);

        System.out.println("✓ Sync count tracking works correctly");
        System.out.println("\n========== Test 1 PASSED ==========\n");
    }

    /**
     * Test 2: updateStatsFromRemote()
     */
    @Test
    public void testPhase11_1_UpdateStatsFromRemote() {
        System.out.println("\n========== Test 2: Phase 11.1 - Update Stats From Remote ==========");

        // Get initial stats
        ChainStats localStats = dagChain.getChainStats();
        System.out.println("Initial local stats:");
        System.out.println("  Total hosts: " + localStats.getTotalHostCount());
        System.out.println("  Total blocks: " + localStats.getTotalBlockCount());
        System.out.println("  Total main blocks: " + localStats.getTotalMainBlockCount());
        System.out.println("  Max difficulty: " + localStats.getMaxDifficulty().toDecimalString());

        // Create remote stats with higher values
        ChainStats remoteStats = ChainStats.builder()
                .totalHostCount(100)
                .totalBlockCount(5000)
                .totalMainBlockCount(2500)
                .maxDifficulty(UInt256.valueOf(1000000))
                .difficulty(UInt256.valueOf(1000000))
                .build();

        System.out.println("\nRemote stats:");
        System.out.println("  Total hosts: " + remoteStats.getTotalHostCount());
        System.out.println("  Total blocks: " + remoteStats.getTotalBlockCount());
        System.out.println("  Total main blocks: " + remoteStats.getTotalMainBlockCount());
        System.out.println("  Max difficulty: " + remoteStats.getMaxDifficulty().toDecimalString());

        // Update local stats from remote
        dagChain.updateStatsFromRemote(remoteStats);

        // Verify stats were updated (should take maximum)
        ChainStats updatedStats = dagChain.getChainStats();
        System.out.println("\nUpdated local stats:");
        System.out.println("  Total hosts: " + updatedStats.getTotalHostCount());
        System.out.println("  Total blocks: " + updatedStats.getTotalBlockCount());
        System.out.println("  Total main blocks: " + updatedStats.getTotalMainBlockCount());
        System.out.println("  Max difficulty: " + updatedStats.getMaxDifficulty().toDecimalString());

        assertEquals("Should use max host count", 100, updatedStats.getTotalHostCount());
        assertEquals("Should use max block count", 5000, updatedStats.getTotalBlockCount());
        assertEquals("Should use max main block count", 2500, updatedStats.getTotalMainBlockCount());
        assertEquals("Should use max difficulty", UInt256.valueOf(1000000), updatedStats.getMaxDifficulty());

        System.out.println("\n✓ Stats update from remote works correctly");
        System.out.println("\n========== Test 2 PASSED ==========\n");
    }

    /**
     * Test 3: registerListener() and notifyListeners()
     */
    @Test
    public void testPhase11_1_ListenerSystem() throws InterruptedException {
        System.out.println("\n========== Test 3: Phase 11.1 - Listener System ==========");

        // Create test listener
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger notificationCount = new AtomicInteger(0);
        List<Bytes32> receivedHashes = new ArrayList<>();

        Listener testListener = message -> {
            notificationCount.incrementAndGet();
            System.out.println("  → Listener received notification: " + message.getType());

            // Extract block hash from message if available
            Object data = message.getData();
            if (data instanceof Bytes && ((Bytes) data).size() >= 32) {
                Bytes32 hash = Bytes32.wrap(((Bytes) data).slice(0, 32));
                receivedHashes.add(hash);
            }

            latch.countDown();
        };

        // Register listener
        dagChain.registerListener(testListener);
        System.out.println("Registered test listener");

        // Create and import a genesis block to trigger notification
        // Use deterministic coinbase for test (Phase 12.5+)
        Bytes32 testGenesisCoinbase = Bytes32.fromHexString("0x1111111111111111111111111111111111111111111111111111111111111111");
        long timestamp = config.getXdagEra();
        Block genesisBlock = dagChain.createGenesisBlock(testGenesisCoinbase, timestamp);

        System.out.println("\nCreated genesis block:");
        System.out.println("  Hash: " + genesisBlock.getHash().toHexString());
        System.out.println("  Timestamp: " + timestamp);

        // Save block to trigger notifications (via tryToConnect)
        DagImportResult result = dagChain.tryToConnect(genesisBlock);

        System.out.println("\nImport result:");
        System.out.println("  Status: " + result.getStatus());
        System.out.println("  Is main block: " + result.isMainBlock());

        // Wait for notification (with timeout)
        boolean notified = latch.await(5, TimeUnit.SECONDS);

        System.out.println("\nNotification status:");
        System.out.println("  Notified: " + notified);
        System.out.println("  Notification count: " + notificationCount.get());

        assertTrue("Listener should be notified", notified);
        assertEquals("Should receive exactly 1 notification", 1, notificationCount.get());
        assertFalse("Should have received block hash", receivedHashes.isEmpty());

        System.out.println("\n✓ Listener system works correctly");
        System.out.println("\n========== Test 3 PASSED ==========\n");
    }

    // ==================== Phase 11.2: Block Creation Tests ====================

    /**
     * Test 4: createGenesisBlock() - Deterministic genesis (Phase 12.5+)
     */
    @Test
    public void testPhase11_2_CreateGenesisBlock() {
        System.out.println("\n========== Test 4: Phase 11.2 - Create Genesis Block (Deterministic) ==========");

        // Create deterministic genesis block
        Bytes32 testGenesisCoinbase = Bytes32.fromHexString("0x2222222222222222222222222222222222222222222222222222222222222222");
        long timestamp = config.getXdagEra();

        System.out.println("Creating deterministic genesis block:");
        System.out.println("  Timestamp: " + timestamp);
        System.out.println("  Coinbase: " + testGenesisCoinbase.toHexString().substring(0, 16) + "...");

        Block genesisBlock = dagChain.createGenesisBlock(testGenesisCoinbase, timestamp);

        // Verify genesis block properties
        assertNotNull("Genesis block should not be null", genesisBlock);
        assertEquals("Genesis block timestamp should match", timestamp, genesisBlock.getTimestamp());
        assertTrue("Genesis block should have no links", genesisBlock.getLinks().isEmpty());
        assertTrue("Genesis block should be valid", genesisBlock.isValid());

        System.out.println("\nGenesis block created:");
        System.out.println("  Hash: " + genesisBlock.getHash().toHexString());
        System.out.println("  Epoch: " + genesisBlock.getEpoch());
        System.out.println("  Links: " + genesisBlock.getLinks().size());
        System.out.println("  Valid: " + genesisBlock.isValid());

        // Verify block structure
        assertNotNull("Genesis block should have header", genesisBlock.getHeader());
        assertEquals("Genesis block should have difficulty 1",
                UInt256.ONE, genesisBlock.getHeader().getDifficulty());

        System.out.println("\n✓ Genesis block creation works correctly");
        System.out.println("\n========== Test 4 PASSED ==========\n");
    }

    /**
     * Test 5: setMiningCoinbase() and createCandidateBlock()
     */
    @Test
    public void testPhase11_2_CreateCandidateBlock() {
        System.out.println("\n========== Test 5: Phase 11.2 - Create Candidate Block ==========");

        // Set mining coinbase address
        Bytes32 miningAddress = Bytes32.random();
        dagChain.setMiningCoinbase(miningAddress);

        System.out.println("Set mining coinbase:");
        System.out.println("  Address: " + miningAddress.toHexString().substring(0, 16) + "...");

        // Create candidate block
        Block candidateBlock = dagChain.createCandidateBlock();

        // Verify candidate block properties
        assertNotNull("Candidate block should not be null", candidateBlock);
        assertTrue("Candidate block should be valid", candidateBlock.isValid());
        assertEquals("Candidate block nonce should be zero",
                Bytes32.ZERO, candidateBlock.getHeader().getNonce());

        System.out.println("\nCandidate block created:");
        System.out.println("  Hash: " + candidateBlock.getHash().toHexString());
        System.out.println("  Epoch: " + candidateBlock.getEpoch());
        System.out.println("  Links: " + candidateBlock.getLinks().size());
        System.out.println("  Difficulty: " + candidateBlock.getHeader().getDifficulty().toDecimalString());
        System.out.println("  Nonce: " + candidateBlock.getHeader().getNonce().toHexString());

        // Verify block structure
        assertNotNull("Candidate block should have header", candidateBlock.getHeader());
        assertNotNull("Candidate block should have coinbase", candidateBlock.getHeader().getCoinbase());

        // Verify links
        assertTrue("Candidate block should have 0-16 links",
                candidateBlock.getLinks().size() >= 0 && candidateBlock.getLinks().size() <= 16);

        System.out.println("\n✓ Candidate block creation works correctly");
        System.out.println("\n========== Test 5 PASSED ==========\n");
    }

    /**
     * Test 6: createCandidateBlock() with existing main chain
     */
    @Test
    public void testPhase11_2_CreateCandidateBlock_WithMainChain() {
        System.out.println("\n========== Test 6: Phase 11.2 - Candidate Block with Main Chain ==========");

        // First, create and import a deterministic genesis block
        Bytes32 testGenesisCoinbase = Bytes32.fromHexString("0x3333333333333333333333333333333333333333333333333333333333333333");
        long timestamp = config.getXdagEra();
        Block genesisBlock = dagChain.createGenesisBlock(testGenesisCoinbase, timestamp);

        System.out.println("Creating genesis block:");
        System.out.println("  Hash: " + genesisBlock.getHash().toHexString());

        DagImportResult genesisResult = dagChain.tryToConnect(genesisBlock);
        System.out.println("  Import status: " + genesisResult.getStatus());
        assertTrue("Genesis block should be imported as main block",
                genesisResult.isMainBlock());

        // Set mining coinbase
        Bytes32 miningAddress = Bytes32.random();
        dagChain.setMiningCoinbase(miningAddress);

        // Create candidate block (should reference genesis block)
        Block candidateBlock = dagChain.createCandidateBlock();

        System.out.println("\nCandidate block created:");
        System.out.println("  Hash: " + candidateBlock.getHash().toHexString());
        System.out.println("  Links: " + candidateBlock.getLinks().size());

        // Verify candidate block references previous main block
        assertTrue("Candidate block should have at least 1 link (prevMainBlock)",
                !candidateBlock.getLinks().isEmpty());

        // Check if first link references genesis block
        boolean referencesGenesis = candidateBlock.getLinks().stream()
                .filter(link -> !link.isTransaction())
                .anyMatch(link -> link.getTargetHash().equals(genesisBlock.getHash()));

        assertTrue("Candidate block should reference genesis block", referencesGenesis);

        System.out.println("  References genesis: " + referencesGenesis);

        System.out.println("\n✓ Candidate block with main chain references works correctly");
        System.out.println("\n========== Test 6 PASSED ==========\n");
    }

    /**
     * Test 7: Thread safety of sync count operations
     */
    @Test
    public void testPhase11_1_SyncCountThreadSafety() throws InterruptedException {
        System.out.println("\n========== Test 7: Phase 11.1 - Sync Count Thread Safety ==========");

        int threadCount = 10;
        int operationsPerThread = 100;

        // Get initial count
        long initialCount = dagChain.getChainStats().getWaitingSyncCount();
        System.out.println("Initial count: " + initialCount);

        // Create threads that increment count
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(() -> {
                try {
                    startLatch.await(); // Wait for start signal
                    for (int j = 0; j < operationsPerThread; j++) {
                        dagChain.incrementWaitingSyncCount();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }

        System.out.println("Starting " + threadCount + " threads, each incrementing " + operationsPerThread + " times");

        // Start all threads
        startLatch.countDown();

        // Wait for all threads to complete
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        assertTrue("All threads should complete", completed);

        // Verify final count
        long finalCount = dagChain.getChainStats().getWaitingSyncCount();
        long expectedCount = initialCount + (threadCount * operationsPerThread);

        System.out.println("\nFinal count: " + finalCount);
        System.out.println("Expected count: " + expectedCount);

        assertEquals("Count should match expected (thread-safe)",
                expectedCount, finalCount);

        System.out.println("\n✓ Thread safety verified");
        System.out.println("\n========== Test 7 PASSED ==========\n");
    }
}
