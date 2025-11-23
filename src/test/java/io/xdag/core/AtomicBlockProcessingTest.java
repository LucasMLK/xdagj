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
import io.xdag.utils.TimeUtils;
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

import static org.junit.Assert.*;

/**
 * Atomic Block Processing Test
 *
 * <p>Tests the atomic block import implementation to ensure:
 * <ul>
 *   <li>Block save + transaction execution happen atomically</li>
 *   <li>Rollback works correctly on failure</li>
 *   <li>Cache updates happen AFTER successful commit</li>
 *   <li>Orphan blocks don't execute transactions</li>
 * </ul>
 *
 * <p>This is the CRITICAL test for the atomic block processing implementation.
 *
 * @since XDAGJ 5.1 - Atomic Block Processing
 */
public class AtomicBlockProcessingTest {

    private DagKernel dagKernel;
    private Config config;
    private Path tempDir;
    private Wallet testWallet;
    private DagChain dagChain;

    @Before
    public void setUp() throws IOException {
        // Create unique temporary directory for each test
        tempDir = Files.createTempDirectory("atomic-block-test-");

        // Create test genesis.json
        createTestGenesisFile();

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

        // Create test wallet
        testWallet = new Wallet(config);
        testWallet.unlock("test-password");
        testWallet.addAccountRandom();

        // Create and start DagKernel
        dagKernel = new DagKernel(config, testWallet);
        dagKernel.start();

        // Get DagChain instance
        dagChain = dagKernel.getDagChain();
    }

    private void createTestGenesisFile() throws IOException {
        String genesisJson = "{\n" +
                "  \"networkId\": \"test\",\n" +
                "  \"chainId\": 999,\n" +
                "  \"epoch\": 1,\n" +
                "  \"initialDifficulty\": \"0x1000\",\n" +
                "  \"epochLength\": 64,\n" +
                "  \"extraData\": \"Atomic Block Processing Test\",\n" +
                "  \"genesisCoinbase\": \"0x0000000000000000000000001111111111111111111111111111111111111111\",\n" +
                "  \"randomXSeed\": \"0x0000000000000000000000000000000000000000000000000000000000000001\",\n" +
                "  \"alloc\": {}\n" +
                "}";

        Path genesisFile = tempDir.resolve("genesis-devnet.json");
        Files.writeString(genesisFile, genesisJson);
    }

    @After
    public void tearDown() {
        if (dagKernel != null) {
            try {
                dagKernel.stop();
            } catch (Exception e) {
                System.err.println("Error stopping DagKernel: " + e.getMessage());
            }
        }

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

    /**
     * Test 1: Basic atomic block import
     *
     * <p>Verify that a simple block (without transactions) can be imported atomically.
     */
    @Test
    public void testAtomicBlockImport_Simple() {
        // Create a simple candidate block
        Block candidateBlock = dagChain.createCandidateBlock();

        // Import the block
        DagImportResult result = dagChain.tryToConnect(candidateBlock);

        // Debug output
        System.out.println("=== Test testAtomicBlockImport_Simple ===");
        System.out.println("Import result status: " + result.getStatus());
        System.out.println("Error message: " + result.getErrorMessage());
        System.out.println("Is main block: " + result.isMainBlock());
        System.out.println("Is orphan: " + result.isOrphan());

        // Verify successful import
        assertTrue("Block should be imported successfully (status=" + result.getStatus() + ", error=" + result.getErrorMessage() + ")",
                result.isMainBlock() || result.isOrphan());

        // Verify block exists in DagStore
        Block retrievedBlock = dagKernel.getDagStore().getBlockByHash(
                candidateBlock.getHash(), true);
        assertNotNull("Block should exist in DagStore after atomic import", retrievedBlock);
    }

    /**
     * Test 2: Atomic block import with transactions
     *
     * <p>Verify that block save + transaction execution happen atomically.
     * Either BOTH succeed or BOTH are rolled back.
     */
    @Test
    public void testAtomicBlockImport_WithTransactions() {
        DagAccountManager accountManager = dagKernel.getDagAccountManager();

        // Step 1: Create sender keypair and derive address from public key
        io.xdag.crypto.keys.ECKeyPair senderKey = io.xdag.crypto.keys.ECKeyPair.generate();
        Bytes senderAddress = senderKey.toAddress();  // Derive address from public key
        Bytes receiverAddress = Bytes.random(20);

        accountManager.ensureAccountExists(senderAddress);
        accountManager.addBalance(senderAddress, UInt256.valueOf(10000000000L)); // 10 XDAG

        accountManager.ensureAccountExists(receiverAddress);

        // Step 2: Create and sign transaction
        Transaction unsignedTx = Transaction.builder()
                .from(senderAddress)
                .to(receiverAddress)
                .amount(XAmount.of(1, XUnit.XDAG))
                .nonce(0)
                .fee(XAmount.of(100, XUnit.MILLI_XDAG))
                .data(Bytes.EMPTY)
                .build();

        Transaction tx = unsignedTx.sign(senderKey);  // Sign with sender's private key

        // Save transaction to store
        dagKernel.getTransactionStore().saveTransaction(tx);

        // Step 3: Create block with transaction link
        List<Link> links = new ArrayList<>();
        links.add(Link.toTransaction(tx.getHash()));

        long currentEpoch = TimeUtils.getCurrentEpochNumber();
        Block blockWithTx = Block.createCandidate(
                currentEpoch,
                dagChain.getChainStats().getBaseDifficultyTarget(),
                testWallet.getDefKey().toAddress(),
                links
        );

        // Record balances BEFORE import
        UInt256 senderBalanceBefore = accountManager.getBalance(senderAddress);
        UInt256 receiverBalanceBefore = accountManager.getBalance(receiverAddress);

        // Step 4: Import block (should execute transaction atomically)
        DagImportResult result = dagChain.tryToConnect(blockWithTx);

        // Step 5: Verify atomic execution
        if (result.isMainBlock()) {
            // If block is main block, transaction MUST be executed
            UInt256 senderBalanceAfter = accountManager.getBalance(senderAddress);
            UInt256 receiverBalanceAfter = accountManager.getBalance(receiverAddress);

            // Verify sender balance decreased (amount + fee)
            UInt256 expectedDeduction = UInt256.valueOf(1100000000L); // 1 XDAG + 0.1 XDAG fee
            assertEquals("Sender balance should decrease atomically",
                    senderBalanceBefore.subtract(expectedDeduction),
                    senderBalanceAfter);

            // Verify receiver balance increased (amount only)
            UInt256 expectedIncrease = UInt256.valueOf(1000000000L); // 1 XDAG
            assertEquals("Receiver balance should increase atomically",
                    receiverBalanceBefore.add(expectedIncrease),
                    receiverBalanceAfter);

            // Verify transaction is marked as executed
            assertTrue("Transaction should be marked as executed",
                    dagKernel.getTransactionStore().isTransactionExecuted(tx.getHash()));

        } else {
            // If block is orphan, transaction should NOT be executed
            UInt256 senderBalanceAfter = accountManager.getBalance(senderAddress);
            UInt256 receiverBalanceAfter = accountManager.getBalance(receiverAddress);

            assertEquals("Sender balance should be unchanged for orphan block",
                    senderBalanceBefore, senderBalanceAfter);
            assertEquals("Receiver balance should be unchanged for orphan block",
                    receiverBalanceBefore, receiverBalanceAfter);

            assertFalse("Transaction should NOT be marked as executed for orphan",
                    dagKernel.getTransactionStore().isTransactionExecuted(tx.getHash()));
        }
    }

    /**
     * Test 3: Transaction manager availability
     *
     * <p>Verify that TransactionManager is properly initialized and available.
     */
    @Test
    public void testTransactionManager_Availability() {
        io.xdag.store.rocksdb.transaction.RocksDBTransactionManager txManager =
                dagKernel.getTransactionManager();

        assertNotNull("TransactionManager should be initialized", txManager);
        assertEquals("Should have no active transactions initially",
                0, txManager.getActiveTransactionCount());
    }

    /**
     * Test 4: Orphan blocks don't execute transactions
     *
     * <p>CRITICAL: Verify that orphan blocks don't execute their transactions.
     * Only main blocks should execute transactions.
     */
    @Test
    public void testOrphanBlock_NoTransactionExecution() {
        DagAccountManager accountManager = dagKernel.getDagAccountManager();

        // Create sender keypair and derive address from public key
        io.xdag.crypto.keys.ECKeyPair senderKey = io.xdag.crypto.keys.ECKeyPair.generate();
        Bytes senderAddress = senderKey.toAddress();  // Derive address from public key
        Bytes receiverAddress = Bytes.random(20);

        accountManager.ensureAccountExists(senderAddress);
        accountManager.addBalance(senderAddress, UInt256.valueOf(10000000000L));
        accountManager.ensureAccountExists(receiverAddress);

        // Create and sign transaction
        Transaction unsignedTx = Transaction.builder()
                .from(senderAddress)
                .to(receiverAddress)
                .amount(XAmount.of(1, XUnit.XDAG))
                .nonce(0)
                .fee(XAmount.of(100, XUnit.MILLI_XDAG))
                .data(Bytes.EMPTY)
                .build();

        Transaction tx = unsignedTx.sign(senderKey);  // Sign with sender's private key
        dagKernel.getTransactionStore().saveTransaction(tx);

        // Create 2 blocks in the same epoch (one will be orphan)
        // IMPORTANT:
        // 1. Use different coinbase addresses so blocks have different hashes
        // 2. Must reference genesis block (all blocks must have 1-16 block links)
        long currentEpoch = TimeUtils.getCurrentEpochNumber();

        // Get genesis block to reference
        Block genesis = dagChain.getMainBlockByHeight(1);
        assertNotNull("Genesis must exist before creating test blocks", genesis);

        // Create two different coinbase addresses to ensure different hashes
        Bytes coinbase1 = Bytes.random(20);
        Bytes coinbase2 = Bytes.random(20);

        // Create links for block1: genesis + transaction
        List<Link> links1 = new ArrayList<>();
        links1.add(Link.toBlock(genesis.getHash()));
        links1.add(Link.toTransaction(tx.getHash()));

        Block block1 = Block.createCandidate(
                currentEpoch,
                dagChain.getChainStats().getBaseDifficultyTarget(),
                coinbase1,  // Different coinbase
                links1
        );

        // Create links for block2: genesis + transaction
        List<Link> links2 = new ArrayList<>();
        links2.add(Link.toBlock(genesis.getHash()));
        links2.add(Link.toTransaction(tx.getHash()));

        Block block2 = Block.createCandidate(
                currentEpoch,
                dagChain.getChainStats().getBaseDifficultyTarget(),
                coinbase2,  // Different coinbase
                links2
        );

        // Import both blocks
        DagImportResult result1 = dagChain.tryToConnect(block1);
        DagImportResult result2 = dagChain.tryToConnect(block2);

        // Debug output
        System.out.println("=== Test testOrphanBlock_NoTransactionExecution ===");
        System.out.println("Block1: hash=" + block1.getHash().toHexString().substring(0, 16) +
                           ", epoch=" + block1.getEpoch() +
                           ", isMain=" + result1.isMainBlock() +
                           ", status=" + result1.getStatus() +
                           ", error=" + result1.getErrorMessage());
        System.out.println("Block2: hash=" + block2.getHash().toHexString().substring(0, 16) +
                           ", epoch=" + block2.getEpoch() +
                           ", isMain=" + result2.isMainBlock() +
                           ", status=" + result2.getStatus() +
                           ", error=" + result2.getErrorMessage());

        // Check which block won epoch competition (smaller hash wins)
        int comparison = block1.getHash().compareTo(block2.getHash());
        System.out.println("Hash comparison: block1 " +
                           (comparison < 0 ? "<" : (comparison > 0 ? ">" : "==")) +
                           " block2 (smaller wins)");

        // BUGFIX: Query actual block state AFTER both imports (not stale import result)
        // Import results are immutable snapshots - block1's result was created before block2 demoted it
        Block block1Final = dagKernel.getDagStore().getBlockByHash(block1.getHash(), false);
        Block block2Final = dagKernel.getDagStore().getBlockByHash(block2.getHash(), false);

        // One should be main (height > 0), one should be orphan (height == 0)
        boolean block1IsMain = block1Final != null && block1Final.getInfo() != null &&
                              block1Final.getInfo().getHeight() > 0;
        boolean block2IsMain = block2Final != null && block2Final.getInfo() != null &&
                              block2Final.getInfo().getHeight() > 0;

        System.out.println("Expected: block1IsMain=" + (comparison < 0) +
                           ", block2IsMain=" + (comparison > 0));
        System.out.println("Actual: block1IsMain=" + block1IsMain +
                           ", block2IsMain=" + block2IsMain);

        assertTrue("One block should be main, one orphan",
                (block1IsMain && !block2IsMain) || (!block1IsMain && block2IsMain));

        // Verify transaction is executed exactly ONCE (only by main block)
        UInt256 senderBalance = accountManager.getBalance(senderAddress);
        UInt256 receiverBalance = accountManager.getBalance(receiverAddress);

        // If transaction was executed exactly once: sender = 10 - 1.1 = 8.9, receiver = 1
        UInt256 expectedSenderBalance = UInt256.valueOf(8900000000L);
        UInt256 expectedReceiverBalance = UInt256.valueOf(1000000000L);

        assertEquals("Transaction should be executed exactly once by main block only",
                expectedSenderBalance, senderBalance);
        assertEquals("Receiver should receive amount exactly once",
                expectedReceiverBalance, receiverBalance);
    }

    /**
     * Test 5: Cache consistency after atomic commit
     *
     * <p>Verify that cache updates happen AFTER successful commit.
     * Reading from cache should always return committed data.
     */
    @Test
    public void testCacheConsistency_AfterCommit() {
        // Create a block
        Block block = dagChain.createCandidateBlock();
        Bytes32 blockHash = block.getHash();

        // Verify block is NOT in cache before import
        Block cachedBlockBefore = dagKernel.getDagCache().getBlock(blockHash);
        assertNull("Block should NOT be in cache before import", cachedBlockBefore);

        // Import block
        DagImportResult result = dagChain.tryToConnect(block);
        assertTrue("Block import should succeed",
                result.isMainBlock() || result.isOrphan());

        // Verify block IS in cache after successful import
        Block cachedBlockAfter = dagKernel.getDagCache().getBlock(blockHash);
        assertNotNull("Block MUST be in cache after atomic commit", cachedBlockAfter);

        // Verify cache data matches disk data
        Block diskBlock = dagKernel.getDagStore().getBlockByHash(blockHash, true);
        assertNotNull("Block must exist on disk", diskBlock);
        assertEquals("Cache and disk data must be consistent",
                diskBlock.getHash(), cachedBlockAfter.getHash());
    }

    /**
     * Test 6: Multiple concurrent transactions in single block
     *
     * <p>Verify that multiple transactions in a single block are all executed atomically.
     */
    @Test
    public void testAtomicBlockImport_MultipleTransactions() {
        DagAccountManager accountManager = dagKernel.getDagAccountManager();

        // Create 3 sender keypairs and derive addresses
        io.xdag.crypto.keys.ECKeyPair senderKey1 = io.xdag.crypto.keys.ECKeyPair.generate();
        io.xdag.crypto.keys.ECKeyPair senderKey2 = io.xdag.crypto.keys.ECKeyPair.generate();
        io.xdag.crypto.keys.ECKeyPair senderKey3 = io.xdag.crypto.keys.ECKeyPair.generate();

        Bytes sender1 = senderKey1.toAddress();
        Bytes sender2 = senderKey2.toAddress();
        Bytes sender3 = senderKey3.toAddress();

        Bytes receiver1 = Bytes.random(20);
        Bytes receiver2 = Bytes.random(20);
        Bytes receiver3 = Bytes.random(20);

        // Fund all senders
        for (Bytes sender : List.of(sender1, sender2, sender3)) {
            accountManager.ensureAccountExists(sender);
            accountManager.addBalance(sender, UInt256.valueOf(10000000000L));
        }

        // Create all receivers
        for (Bytes receiver : List.of(receiver1, receiver2, receiver3)) {
            accountManager.ensureAccountExists(receiver);
        }

        // Create 3 signed transactions
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Bytes sender = (i == 0) ? sender1 : (i == 1) ? sender2 : sender3;
            Bytes receiver = (i == 0) ? receiver1 : (i == 1) ? receiver2 : receiver3;
            io.xdag.crypto.keys.ECKeyPair senderKey = (i == 0) ? senderKey1 : (i == 1) ? senderKey2 : senderKey3;

            Transaction unsignedTx = Transaction.builder()
                    .from(sender)
                    .to(receiver)
                    .amount(XAmount.of(1, XUnit.XDAG))
                    .nonce(0)
                    .fee(XAmount.of(100, XUnit.MILLI_XDAG))
                    .data(Bytes.EMPTY)
                    .build();

            Transaction tx = unsignedTx.sign(senderKey);  // Sign with sender's private key
            dagKernel.getTransactionStore().saveTransaction(tx);
            transactions.add(tx);
        }

        // Create block with all 3 transactions
        List<Link> links = new ArrayList<>();
        for (Transaction tx : transactions) {
            links.add(Link.toTransaction(tx.getHash()));
        }

        long currentEpoch = TimeUtils.getCurrentEpochNumber();
        Block blockWithMultipleTx = Block.createCandidate(
                currentEpoch,
                dagChain.getChainStats().getBaseDifficultyTarget(),
                testWallet.getDefKey().toAddress(),
                links
        );

        // Import block
        DagImportResult result = dagChain.tryToConnect(blockWithMultipleTx);

        // If main block, verify ALL transactions executed atomically
        if (result.isMainBlock()) {
            // All receivers should have received 1 XDAG
            UInt256 expectedReceiverBalance = UInt256.valueOf(1000000000L);

            for (Bytes receiver : List.of(receiver1, receiver2, receiver3)) {
                assertEquals("All receivers should have 1 XDAG if block is main",
                        expectedReceiverBalance,
                        accountManager.getBalance(receiver));
            }

            // All senders should have paid 1.1 XDAG (1 + 0.1 fee)
            UInt256 expectedSenderBalance = UInt256.valueOf(8900000000L);

            for (Bytes sender : List.of(sender1, sender2, sender3)) {
                assertEquals("All senders should have 8.9 XDAG if block is main",
                        expectedSenderBalance,
                        accountManager.getBalance(sender));
            }
        }
    }

    /**
     * Test 7: Transaction manager statistics
     *
     * <p>Verify that transaction manager tracks active transactions correctly.
     */
    @Test
    public void testTransactionManager_Statistics() {
        io.xdag.store.rocksdb.transaction.RocksDBTransactionManager txManager =
                dagKernel.getTransactionManager();

        // Initially no active transactions
        assertEquals("Should start with 0 active transactions",
                0, txManager.getActiveTransactionCount());

        // Statistics should be available
        String stats = txManager.getStatistics();
        assertNotNull("Statistics should be available", stats);
        assertTrue("Statistics should contain active count",
                stats.contains("active=0"));
    }
}
