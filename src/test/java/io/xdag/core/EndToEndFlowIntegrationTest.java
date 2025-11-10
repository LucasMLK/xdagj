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
import io.xdag.db.TransactionStore;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static io.xdag.core.XUnit.NANO_XDAG;
import static org.junit.Assert.*;

/**
 * End-to-End Flow Integration Test for XDAG v5.1
 *
 * <p>Tests complete flow: Transaction → Block → Account Update → Retrieval
 *
 * <p>This test validates the entire data pipeline:
 * <ol>
 *   <li>Create and sign Transaction</li>
 *   <li>Save Transaction to TransactionStore</li>
 *   <li>Create Block referencing Transaction</li>
 *   <li>Process Block through DagBlockProcessor</li>
 *   <li>Verify Account state updates (balance, nonce)</li>
 *   <li>Verify Block and Transaction retrieval</li>
 * </ol>
 *
 * @since v5.1 Phase 10
 */
public class EndToEndFlowIntegrationTest {

    private DagKernel dagKernel;
    private Config config;
    private Path tempDir;
    private Wallet testWallet;

    // Core components
    private DagAccountManager accountManager;
    private DagTransactionProcessor txProcessor;
    private DagBlockProcessor blockProcessor;
    private TransactionStore transactionStore;

    // Test accounts
    private org.apache.tuweni.bytes.Bytes32 senderAddress;
    private org.apache.tuweni.bytes.Bytes32 receiverAddress;
    private ECKeyPair senderKey;

    @Before
    public void setUp() throws IOException {
        // Create unique temporary directory
        tempDir = Files.createTempDirectory("e2e-flow-test-");

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

        // Get components
        accountManager = dagKernel.getDagAccountManager();
        txProcessor = dagKernel.getDagTransactionProcessor();
        blockProcessor = dagKernel.getDagBlockProcessor();
        transactionStore = dagKernel.getTransactionStore();

        // Create test accounts
        senderKey = ECKeyPair.generate();
        senderAddress = org.apache.tuweni.bytes.Bytes32.random();
        receiverAddress = org.apache.tuweni.bytes.Bytes32.random();

        // Initialize sender account with balance
        accountManager.ensureAccountExists(senderAddress);
        accountManager.setBalance(senderAddress, UInt256.valueOf(10_000_000_000L)); // 10 XDAG
        accountManager.ensureAccountExists(receiverAddress);
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

    /**
     * Test 1: Complete flow - Single transaction
     *
     * <p>Flow:
     * 1. Create Transaction (1 XDAG transfer)
     * 2. Save Transaction to TransactionStore
     * 3. Create Block referencing Transaction
     * 4. Process Block through DagBlockProcessor
     * 5. Verify Account updates
     * 6. Verify retrieval
     */
    @Test
    public void testCompleteFlow_SingleTransaction() {
        System.out.println("\n========== Test 1: Complete Flow - Single Transaction ==========");

        // Initial balances
        UInt256 initialSenderBalance = accountManager.getBalance(senderAddress);
        UInt256 initialReceiverBalance = accountManager.getBalance(receiverAddress);
        UInt64 initialSenderNonce = accountManager.getNonce(senderAddress);

        System.out.println("Initial state:");
        System.out.println("  Sender balance: " + initialSenderBalance.toDecimalString());
        System.out.println("  Receiver balance: " + initialReceiverBalance.toDecimalString());
        System.out.println("  Sender nonce: " + initialSenderNonce.toLong());

        // Step 1: Create Transaction
        XAmount txAmount = XAmount.of(1, XUnit.XDAG);  // 1 XDAG
        XAmount txFee = XAmount.of(100, XUnit.MILLI_XDAG);  // 0.1 XDAG

        Transaction tx = Transaction.builder()
                .from(senderAddress)
                .to(receiverAddress)
                .amount(txAmount)
                .nonce(initialSenderNonce.toLong())
                .fee(txFee)
                .build();

        // Sign transaction
        Transaction signedTx = tx.sign(senderKey);

        System.out.println("\nStep 1: Created transaction:");
        System.out.println("  Hash: " + signedTx.getHash().toHexString());
        System.out.println("  Amount: " + txAmount.toDecimal(9, XUnit.XDAG).toPlainString() + " XDAG");
        System.out.println("  Fee: " + txFee.toDecimal(9, XUnit.XDAG).toPlainString() + " XDAG");

        // Step 2: Save Transaction to TransactionStore
        transactionStore.saveTransaction(signedTx);
        System.out.println("\nStep 2: Transaction saved to TransactionStore");

        // Verify transaction retrieval
        Transaction retrievedTx = transactionStore.getTransaction(signedTx.getHash());
        assertNotNull("Transaction should be retrievable", retrievedTx);
        assertEquals("Retrieved transaction should match", signedTx.getHash(), retrievedTx.getHash());

        // Step 3: Create Block referencing Transaction
        BlockInfo blockInfo = BlockInfo.builder()
                .hash(Bytes32.random())
                .height(1L)
                .difficulty(UInt256.ONE)
                .timestamp(System.currentTimeMillis())
                .build();

        // Create block with transaction link
        List<Link> links = List.of(Link.toTransaction(signedTx.getHash()));
        Block block = Block.createWithNonce(
                blockInfo.getTimestamp(),
                UInt256.ONE,
                Bytes32.ZERO,
                Bytes32.random(),
                links
        );

        // Update blockInfo with actual block hash
        blockInfo = blockInfo.toBuilder()
                .hash(block.getHash())
                .build();

        block = block.withInfo(blockInfo);

        System.out.println("\nStep 3: Created block:");
        System.out.println("  Hash: " + block.getHash().toHexString());
        System.out.println("  Transaction links: " + block.getTransactionLinks().size());

        // Step 4: Process Block through DagBlockProcessor
        DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);

        System.out.println("\nStep 4: Block processing result:");
        System.out.println("  Success: " + result.isSuccess());
        if (!result.isSuccess()) {
            System.out.println("  Error: " + result.getError());
        }

        assertTrue("Block processing should succeed", result.isSuccess());

        // Step 5: Verify Account updates
        UInt256 finalSenderBalance = accountManager.getBalance(senderAddress);
        UInt256 finalReceiverBalance = accountManager.getBalance(receiverAddress);
        UInt64 finalSenderNonce = accountManager.getNonce(senderAddress);

        System.out.println("\nStep 5: Account state after processing:");
        System.out.println("  Sender balance: " + finalSenderBalance.toDecimalString());
        System.out.println("  Receiver balance: " + finalReceiverBalance.toDecimalString());
        System.out.println("  Sender nonce: " + finalSenderNonce.toLong());

        // Calculate expected values
        UInt256 txAmountNano = UInt256.valueOf(txAmount.toDecimal(0, NANO_XDAG).longValue());
        UInt256 txFeeNano = UInt256.valueOf(txFee.toDecimal(0, NANO_XDAG).longValue());
        UInt256 expectedSenderBalance = initialSenderBalance.subtract(txAmountNano).subtract(txFeeNano);
        UInt256 expectedReceiverBalance = initialReceiverBalance.add(txAmountNano);

        // Verify balances
        assertEquals("Sender balance should decrease by amount + fee",
                expectedSenderBalance, finalSenderBalance);
        assertEquals("Receiver balance should increase by amount",
                expectedReceiverBalance, finalReceiverBalance);

        // Verify nonce
        assertEquals("Sender nonce should increment",
                initialSenderNonce.toLong() + 1, finalSenderNonce.toLong());

        // Step 6: Verify Block retrieval
        assertTrue("Block should exist", blockProcessor.hasBlock(block.getHash()));

        Block retrievedBlock = blockProcessor.getBlock(block.getHash(), false);
        assertNotNull("Block should be retrievable", retrievedBlock);
        assertEquals("Retrieved block hash should match", block.getHash(), retrievedBlock.getHash());

        System.out.println("\nStep 6: Verification complete!");
        System.out.println("  Block exists: true");
        System.out.println("  Block retrievable: true");
        System.out.println("\n========== Test 1 PASSED ==========\n");
    }

    /**
     * Test 2: Complete flow - Multiple transactions in one block
     *
     * <p>Tests processing multiple transactions atomically in a single block.
     */
    @Test
    public void testCompleteFlow_MultipleTransactions() {
        System.out.println("\n========== Test 2: Complete Flow - Multiple Transactions ==========");

        // Create three receivers
        Bytes32 receiver1 = Bytes32.random();
        Bytes32 receiver2 = Bytes32.random();
        Bytes32 receiver3 = Bytes32.random();

        accountManager.ensureAccountExists(receiver1);
        accountManager.ensureAccountExists(receiver2);
        accountManager.ensureAccountExists(receiver3);

        UInt256 initialSenderBalance = accountManager.getBalance(senderAddress);
        UInt64 initialSenderNonce = accountManager.getNonce(senderAddress);

        System.out.println("Initial sender balance: " + initialSenderBalance.toDecimalString());
        System.out.println("Initial sender nonce: " + initialSenderNonce.toLong());

        // Create three transactions
        Transaction tx1 = createAndSaveTransaction(receiver1, XAmount.of(1, XUnit.XDAG),
                initialSenderNonce.toLong());
        Transaction tx2 = createAndSaveTransaction(receiver2, XAmount.of(2, XUnit.XDAG),
                initialSenderNonce.toLong() + 1);
        Transaction tx3 = createAndSaveTransaction(receiver3, XAmount.of(3, XUnit.XDAG),
                initialSenderNonce.toLong() + 2);

        System.out.println("\nCreated 3 transactions:");
        System.out.println("  TX1: 1 XDAG to receiver1");
        System.out.println("  TX2: 2 XDAG to receiver2");
        System.out.println("  TX3: 3 XDAG to receiver3");

        // Create block with all three transactions
        List<Link> links = List.of(
                Link.toTransaction(tx1.getHash()),
                Link.toTransaction(tx2.getHash()),
                Link.toTransaction(tx3.getHash())
        );

        Block block = createBlockWithLinks(links);

        System.out.println("\nCreated block with 3 transaction links");

        // Process block
        DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);

        assertTrue("Block processing should succeed: " + result.getError(), result.isSuccess());

        System.out.println("Block processed successfully");

        // Verify all account updates
        assertEquals("Receiver1 should have 1 XDAG",
                UInt256.valueOf(1_000_000_000L),
                accountManager.getBalance(receiver1));

        assertEquals("Receiver2 should have 2 XDAG",
                UInt256.valueOf(2_000_000_000L),
                accountManager.getBalance(receiver2));

        assertEquals("Receiver3 should have 3 XDAG",
                UInt256.valueOf(3_000_000_000L),
                accountManager.getBalance(receiver3));

        // Sender nonce should have incremented by 3
        assertEquals("Sender nonce should increment by 3",
                initialSenderNonce.toLong() + 3,
                accountManager.getNonce(senderAddress).toLong());

        System.out.println("\nFinal state:");
        System.out.println("  Receiver1 balance: 1.0 XDAG ✓");
        System.out.println("  Receiver2 balance: 2.0 XDAG ✓");
        System.out.println("  Receiver3 balance: 3.0 XDAG ✓");
        System.out.println("  Sender nonce: " + accountManager.getNonce(senderAddress).toLong() + " ✓");
        System.out.println("\n========== Test 2 PASSED ==========\n");
    }

    /**
     * Test 3: Transaction validation failure
     *
     * <p>Verifies that invalid transactions are rejected without affecting account state.
     */
    @Test
    public void testCompleteFlow_InvalidTransaction() {
        System.out.println("\n========== Test 3: Invalid Transaction Rejection ==========");

        UInt256 initialSenderBalance = accountManager.getBalance(senderAddress);
        UInt64 initialSenderNonce = accountManager.getNonce(senderAddress);

        // Create transaction with insufficient balance
        XAmount hugeAmount = XAmount.of(100, XUnit.XDAG);  // 100 XDAG (sender only has 10)
        XAmount txFee = XAmount.of(100, XUnit.MILLI_XDAG);

        Transaction tx = Transaction.builder()
                .from(senderAddress)
                .to(receiverAddress)
                .amount(hugeAmount)
                .nonce(initialSenderNonce.toLong())
                .fee(txFee)
                .build();

        Transaction signedTx = tx.sign(senderKey);
        transactionStore.saveTransaction(signedTx);

        System.out.println("Created transaction with insufficient balance:");
        System.out.println("  Amount: 100 XDAG (balance: 10 XDAG)");

        // Create and process block
        List<Link> links = List.of(Link.toTransaction(signedTx.getHash()));
        Block block = createBlockWithLinks(links);

        DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);

        // Block processing should fail
        assertFalse("Block processing should fail for insufficient balance", result.isSuccess());
        assertTrue("Error should mention insufficient balance",
                result.getError().contains("Insufficient balance"));

        System.out.println("\nBlock processing failed (expected):");
        System.out.println("  Error: " + result.getError());

        // Verify account state unchanged
        assertEquals("Sender balance should not change",
                initialSenderBalance,
                accountManager.getBalance(senderAddress));
        assertEquals("Sender nonce should not change",
                initialSenderNonce,
                accountManager.getNonce(senderAddress));

        System.out.println("\nAccount state unchanged (verified):");
        System.out.println("  Sender balance: " + initialSenderBalance.toDecimalString() + " ✓");
        System.out.println("  Sender nonce: " + initialSenderNonce.toLong() + " ✓");
        System.out.println("\n========== Test 3 PASSED ==========\n");
    }

    // ==================== Helper Methods ====================

    /**
     * Create, sign, and save a transaction
     */
    private Transaction createAndSaveTransaction(Bytes32 to,
                                                  XAmount amount, long nonce) {
        Transaction tx = Transaction.builder()
                .from(senderAddress)
                .to(to)
                .amount(amount)
                .nonce(nonce)
                .fee(XAmount.of(100, XUnit.MILLI_XDAG))
                .build();

        Transaction signedTx = tx.sign(senderKey);
        transactionStore.saveTransaction(signedTx);

        return signedTx;
    }

    /**
     * Create a block with specified links
     */
    private Block createBlockWithLinks(List<Link> links) {
        long timestamp = System.currentTimeMillis();

        Block block = Block.createWithNonce(
                timestamp,
                UInt256.ONE,
                Bytes32.ZERO,
                Bytes32.random(),
                links
        );

        BlockInfo blockInfo = BlockInfo.builder()
                .hash(block.getHash())
                .height(1L)
                .difficulty(UInt256.ONE)
                .timestamp(timestamp)
                .build();

        return block.withInfo(blockInfo);
    }
}
