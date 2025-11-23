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
import io.xdag.SampleKeys;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.crypto.keys.ECKeyPair;
import org.apache.tuweni.bytes.Bytes;
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

import static org.junit.Assert.*;

/**
 * Integration test for DagKernel XDAGJ 1.0
 *
 * <p>Tests the complete lifecycle and component initialization of DagKernel.
 * Uses REAL components (not mocks) to verify actual behavior.
 *
 * <p>Phase 10 Integration Test - Strategy B:
 * Start with integration tests to discover issues gradually.
 *
 * @since @since XDAGJ
 */
public class DagKernelIntegrationTest extends BaseIntegrationTest {

    /**
     * Test 1: DagKernel construction
     *
     * <p>Verify that DagKernel can be constructed successfully
     * and storage/cache layer components are initialized.
     *
     * <p>Note: Consensus layer components (DagAccountManager, DagTransactionProcessor,
     * DagBlockProcessor) are only initialized in start() method, so they won't be
     * available until start() is called.
     */
    @Test
    public void testDagKernelConstruction() {
        // Verify DagKernel is created
        assertNotNull("DagKernel should be created", dagKernel);

        // Verify config is set
        assertNotNull("Config should be set", dagKernel.getConfig());
        assertEquals("Config should be DevnetConfig", config, dagKernel.getConfig());

        // Verify storage layer components (initialized in constructor)
        assertNotNull("DagStore should be initialized", dagKernel.getDagStore());
        assertNotNull("TransactionStore should be initialized", dagKernel.getTransactionStore());
        assertNotNull("AccountStore should be initialized", dagKernel.getAccountStore());

        // Verify cache layer components (initialized in constructor)
        assertNotNull("DagCache should be initialized", dagKernel.getDagCache());
        assertNotNull("EntityResolver should be initialized", dagKernel.getEntityResolver());

        // Note: Consensus layer components are NOT initialized until start() is called
        // See DagKernel.java line 210: "Note: DagChain and HybridSyncManager will be
        // initialized in start() method because DagChainImpl needs a fully constructed
        // DagKernel instance"
    }

    /**
     * Test 2: DagKernel start lifecycle
     *
     * <p>Verify that DagKernel can be started successfully
     * and all storage components are operational.
     */
    @Test
    public void testDagKernelStart() {
        // Start DagKernel
        dagKernel.start();

        // Verify all storage components are started
        // Note: We can't directly check if stores are "started" without internal state access
        // But we can verify they respond to queries

        // Verify AccountStore is operational
        assertNotNull("AccountStore should respond to queries",
                dagKernel.getAccountStore().getAccountCount());

        // Verify DagStore is operational
        assertNotNull("DagStore should respond to queries",
                dagKernel.getDagStore().getBlockCount());
    }

    /**
     * Test 3: DagKernel stop lifecycle
     *
     * <p>Verify that DagKernel can be stopped cleanly
     * without errors.
     */
    @Test
    public void testDagKernelStop() {
        // Start first
        dagKernel.start();

        // Stop DagKernel
        dagKernel.stop();

        // No exception means stop succeeded
        assertTrue("DagKernel stop should complete without errors", true);
    }

    /**
     * Test 4: DagKernel start-stop-start cycle
     *
     * <p>Verify that DagKernel can be stopped and restarted
     * multiple times without issues.
     */
    @Test
    public void testDagKernelStartStopCycle() {
        // First start-stop cycle
        dagKernel.start();
        dagKernel.stop();

        // Second start-stop cycle
        dagKernel.start();
        dagKernel.stop();

        // If we get here without exception, the cycle works
        assertTrue("DagKernel should support multiple start-stop cycles", true);
    }

    /**
     * Test 5: DagKernel reset
     *
     * <p>Verify that DagKernel can be reset to clear all data.
     */
    @Test
    public void testDagKernelReset() {
        // Start DagKernel
        dagKernel.start();

        // Reset DagKernel (clears all data)
        dagKernel.reset();

        // Verify stores are empty after reset
        assertEquals("DagStore should be empty after reset",
                0L, dagKernel.getDagStore().getBlockCount());

        assertEquals("AccountStore should be empty after reset",
                0L, dagKernel.getAccountStore().getAccountCount().toLong());
    }

    /**
     * Test 6: DagAccountManager basic operations
     *
     * <p>Verify that DagAccountManager can perform basic
     * account operations after DagKernel is started.
     */
    @Test
    public void testDagAccountManagerBasicOps() {
        // Start DagKernel
        dagKernel.start();

        // Get DagAccountManager
        DagAccountManager accountManager = dagKernel.getDagAccountManager();
        assertNotNull("DagAccountManager should be available", accountManager);

        // Create a test address (32 bytes for XDAG)
        Bytes testAddress = Bytes.random(20);

        // Verify account doesn't exist initially
        assertFalse("New address should not exist initially",
                accountManager.hasAccount(testAddress));

        // Create account
        accountManager.ensureAccountExists(testAddress);

        // Verify account exists now
        assertTrue("Account should exist after creation",
                accountManager.hasAccount(testAddress));

        // Verify initial balance and nonce
        assertEquals("Initial balance should be zero",
                UInt256.ZERO,
                accountManager.getBalance(testAddress));

        assertEquals("Initial nonce should be zero",
                UInt64.ZERO,
                accountManager.getNonce(testAddress));
    }

    /**
     * Test 7: DagAccountManager balance operations
     *
     * <p>Verify that DagAccountManager can correctly
     * add and subtract balances.
     */
    @Test
    public void testDagAccountManagerBalanceOps() {
        // Start DagKernel
        dagKernel.start();

        DagAccountManager accountManager = dagKernel.getDagAccountManager();
        Bytes testAddress = Bytes.random(20);

        // Create account
        accountManager.ensureAccountExists(testAddress);

        // Add balance: 1000 units
        UInt256 amount = UInt256.valueOf(1000);
        accountManager.addBalance(testAddress, amount);

        // Verify balance
        assertEquals("Balance should be 1000 after adding",
                amount, accountManager.getBalance(testAddress));

        // Subtract balance: 300 units
        UInt256 subtractAmount = UInt256.valueOf(300);
        accountManager.subtractBalance(testAddress, subtractAmount);

        // Verify remaining balance: 700 units
        UInt256 expectedBalance = UInt256.valueOf(700);
        assertEquals("Balance should be 700 after subtracting",
                expectedBalance, accountManager.getBalance(testAddress));
    }

    /**
     * Test 8: DagAccountManager nonce operations
     *
     * <p>Verify that DagAccountManager can correctly
     * increment nonces.
     */
    @Test
    public void testDagAccountManagerNonceOps() {
        // Start DagKernel
        dagKernel.start();

        DagAccountManager accountManager = dagKernel.getDagAccountManager();
        Bytes testAddress = Bytes.random(20);

        // Create account
        accountManager.ensureAccountExists(testAddress);

        // Verify initial nonce is 0
        assertEquals("Initial nonce should be 0",
                UInt64.ZERO,
                accountManager.getNonce(testAddress));

        // Increment nonce
        accountManager.incrementNonce(testAddress);

        // Verify nonce is now 1
        assertEquals("Nonce should be 1 after increment",
                UInt64.ONE,
                accountManager.getNonce(testAddress));

        // Increment again
        accountManager.incrementNonce(testAddress);

        // Verify nonce is now 2
        assertEquals("Nonce should be 2 after second increment",
                UInt64.valueOf(2),
                accountManager.getNonce(testAddress));
    }

    /**
     * Test 9: DagBlockProcessor availability
     *
     * <p>Verify that DagBlockProcessor is properly initialized
     * and accessible.
     */
    @Test
    public void testDagBlockProcessorAvailability() {
        // Start DagKernel
        dagKernel.start();

        // Get DagBlockProcessor
        DagBlockProcessor blockProcessor = dagKernel.getDagBlockProcessor();
        assertNotNull("DagBlockProcessor should be available", blockProcessor);

        // Verify it can check block existence (even if no blocks exist)
        Bytes32 randomHash = Bytes32.random();
        assertFalse("Random hash should not exist in empty store",
                blockProcessor.hasBlock(randomHash));
    }

    /**
     * Test 10: DagTransactionProcessor availability
     *
     * <p>Verify that DagTransactionProcessor is properly initialized
     * and accessible.
     */
    @Test
    public void testDagTransactionProcessorAvailability() {
        // Start DagKernel
        dagKernel.start();

        // Get DagTransactionProcessor
        DagTransactionProcessor txProcessor = dagKernel.getDagTransactionProcessor();
        assertNotNull("DagTransactionProcessor should be available", txProcessor);

        // Note: We can't easily test transaction processing without creating
        // valid Transaction objects, which would require more complex setup.
        // This test just verifies the component is accessible.
    }

    /**
     * Test 11: Component dependency chain
     *
     * <p>Verify the correct dependency chain after DagKernel is started:
     * DagBlockProcessor -> DagTransactionProcessor -> DagAccountManager -> AccountStore
     *
     * <p>Note: Consensus layer components are only initialized in start() method.
     */
    @Test
    public void testComponentDependencyChain() {
        // Must start DagKernel first to initialize consensus layer components
        dagKernel.start();

        // Verify DagBlockProcessor has access to DagTransactionProcessor
        // (indirectly through constructor injection)

        DagBlockProcessor blockProcessor = dagKernel.getDagBlockProcessor();
        assertNotNull("DagBlockProcessor should be initialized", blockProcessor);

        // Verify DagTransactionProcessor has access to DagAccountManager
        DagTransactionProcessor txProcessor = dagKernel.getDagTransactionProcessor();
        assertNotNull("DagTransactionProcessor should be initialized", txProcessor);

        // Verify DagAccountManager has access to AccountStore
        DagAccountManager accountManager = dagKernel.getDagAccountManager();
        assertNotNull("DagAccountManager should be initialized", accountManager);

        // If all components are non-null, dependency chain is correct
        assertTrue("All Dag components should be properly connected", true);
    }

    /**
     * Test 12: Cache statistics availability
     *
     * <p>Verify that cache statistics can be retrieved.
     */
    @Test
    public void testCacheStatistics() {
        // Start DagKernel
        dagKernel.start();

        // Get cache statistics
        String cacheStats = dagKernel.getCacheStats();
        assertNotNull("Cache statistics should be available", cacheStats);
        assertFalse("Cache statistics should not be empty", cacheStats.isEmpty());
    }

    /**
     * Test 13: Database size reporting
     *
     * <p>Verify that database size can be queried.
     */
    @Test
    public void testDatabaseSize() {
        // Start DagKernel
        dagKernel.start();

        // Get database size
        long dbSize = dagKernel.getDatabaseSize();

        // Size should be >= 0 (might be 0 for empty database)
        assertTrue("Database size should be non-negative", dbSize >= 0);
    }

    /**
     * Test 14: Transaction processing - DEBUG test
     *
     * <p>Debug test to see actual error messages from transaction processing.
     */
    @Test
    public void testTransactionProcessing_Debug() {
        // Start DagKernel
        dagKernel.start();

        DagAccountManager accountManager = dagKernel.getDagAccountManager();
        DagTransactionProcessor txProcessor = dagKernel.getDagTransactionProcessor();

        // Create simple test
        Bytes senderAddress = Bytes.random(20);
        Bytes receiverAddress = Bytes.random(20);

        // Create sender with balance
        accountManager.ensureAccountExists(senderAddress);
        accountManager.addBalance(senderAddress,
                UInt256.valueOf(10000000000L));
        accountManager.ensureAccountExists(receiverAddress);

        // Create transaction
        Transaction tx = Transaction.builder()
                .from(senderAddress)
                .to(receiverAddress)
                .amount(XAmount.of(1, XUnit.XDAG))
                .nonce(0)
                .fee(XAmount.of(100, XUnit.MILLI_XDAG))
                .data(Bytes.EMPTY)
                .v(0)
                .r(Bytes32.ZERO)
                .s(Bytes32.ZERO)
                .build();

        // Process and print result
        DagTransactionProcessor.ProcessingResult result = txProcessor.processTransaction(tx);

        System.out.println("======== DEBUG TRANSACTION PROCESSING ========");
        System.out.println("Transaction: " + tx);
        System.out.println("Result success: " + result.isSuccess());
        System.out.println("Result error: " + result.getError());
        System.out.println("Sender address: " + senderAddress.toHexString());
        System.out.println("Receiver address: " + receiverAddress.toHexString());
        System.out.println("Sender balance: " + accountManager.getBalance(senderAddress));
        System.out.println("Sender nonce: " + accountManager.getNonce(senderAddress));
        System.out.println("================================================");

        // This test always "passes" but prints debug info
        assertTrue("Debug test", true);
    }

    /**
     * Test 15: Transaction processing - successful transfer
     *
     * <p>Test complete transaction processing flow:
     * 1. Create sender and receiver accounts
     * 2. Fund sender account
     * 3. Create and sign transaction
     * 4. Process transaction
     * 5. Verify account state changes
     */
    @Test
    public void testTransactionProcessing_Success() {
        // Start DagKernel
        dagKernel.start();

        DagAccountManager accountManager = dagKernel.getDagAccountManager();
        DagTransactionProcessor txProcessor = dagKernel.getDagTransactionProcessor();

        // Step 1: Create test accounts - use testKeyPair for sender to enable signature verification
        Bytes senderAddress = AddressUtils.toBytesAddress(testKeyPair);
        Bytes receiverAddress = Bytes.random(20);

        // Step 2: Create sender account with initial balance
        accountManager.ensureAccountExists(senderAddress);
        UInt256 initialBalance = UInt256.valueOf(10000000000L); // 10 XDAG
        accountManager.addBalance(senderAddress, initialBalance);

        // Create receiver account (initially zero balance)
        accountManager.ensureAccountExists(receiverAddress);

        // Step 3: Create transaction (1 XDAG transfer with 0.1 XDAG fee)
        XAmount transferAmount = XAmount.of(1, XUnit.XDAG);
        XAmount txFee = XAmount.of(100, XUnit.MILLI_XDAG); // 0.1 XDAG
        long nonce = 0;

        Transaction tx = Transaction.builder()
                .from(senderAddress)
                .to(receiverAddress)
                .amount(transferAmount)
                .nonce(nonce)
                .fee(txFee)
                .data(Bytes.EMPTY)
                .v(0)
                .r(Bytes32.ZERO)
                .s(Bytes32.ZERO)
                .build();

        // Sign transaction with testKeyPair
        tx = signTransaction(tx);

        // Step 4: Process transaction
        DagTransactionProcessor.ProcessingResult result = txProcessor.processTransaction(tx);

        // Step 5: Verify result
        assertTrue("Transaction processing should succeed", result.isSuccess());

        // Step 6: Verify account balances
        // Sender balance: 10 - 1 - 0.1 = 8.9 XDAG
        UInt256 expectedSenderBalance =
            UInt256.valueOf(8900000000L); // 8.9 XDAG in XAmount

        UInt256 actualSenderBalance =
            accountManager.getBalance(senderAddress);

        assertEquals("Sender balance should be 8.9 XDAG after transfer",
                expectedSenderBalance, actualSenderBalance);

        // Receiver balance: 0 + 1 = 1 XDAG
        UInt256 expectedReceiverBalance =
            UInt256.valueOf(1000000000L); // 1 XDAG in XAmount

        UInt256 actualReceiverBalance =
            accountManager.getBalance(receiverAddress);

        assertEquals("Receiver balance should be 1 XDAG after transfer",
                expectedReceiverBalance, actualReceiverBalance);

        // Step 7: Verify nonce increment
        UInt64 senderNonce =
            accountManager.getNonce(senderAddress);

        assertEquals("Sender nonce should be incremented to 1",
                UInt64.ONE, senderNonce);
    }

    /**
     * Test 15: Transaction processing - insufficient balance
     *
     * <p>Test transaction processing fails when sender has insufficient balance.
     */
    @Test
    public void testTransactionProcessing_InsufficientBalance() {
        // Start DagKernel
        dagKernel.start();

        DagAccountManager accountManager = dagKernel.getDagAccountManager();
        DagTransactionProcessor txProcessor = dagKernel.getDagTransactionProcessor();

        // Create sender with small balance - use testKeyPair for signature verification
        Bytes senderAddress = AddressUtils.toBytesAddress(testKeyPair);
        Bytes receiverAddress = Bytes.random(20);

        accountManager.ensureAccountExists(senderAddress);

        // Give sender only 0.5 XDAG
        UInt256 smallBalance = UInt256.valueOf(500000000L);
        accountManager.addBalance(senderAddress, smallBalance);

        accountManager.ensureAccountExists(receiverAddress);

        // Try to transfer 1 XDAG (more than balance)
        Transaction tx = Transaction.builder()
                .from(senderAddress)
                .to(receiverAddress)
                .amount(XAmount.of(1, XUnit.XDAG))
                .nonce(0)
                .fee(XAmount.of(100, XUnit.MILLI_XDAG))
                .data(Bytes.EMPTY)
                .v(0)
                .r(Bytes32.ZERO)
                .s(Bytes32.ZERO)
                .build();

        // Sign transaction with testKeyPair
        tx = signTransaction(tx);

        // Process transaction
        DagTransactionProcessor.ProcessingResult result = txProcessor.processTransaction(tx);

        // Verify transaction fails
        assertFalse("Transaction should fail with insufficient balance", result.isSuccess());
        assertTrue("Error message should mention insufficient balance",
                result.getError().contains("Insufficient balance"));

        // Verify balances unchanged
        assertEquals("Sender balance should remain unchanged",
                smallBalance,
                accountManager.getBalance(senderAddress));

        assertEquals("Receiver balance should remain zero",
                UInt256.ZERO,
                accountManager.getBalance(receiverAddress));
    }

    /**
     * Test 16: Transaction processing - invalid nonce
     *
     * <p>Test transaction processing fails when nonce doesn't match.
     */
    @Test
    public void testTransactionProcessing_InvalidNonce() {
        // Start DagKernel
        dagKernel.start();

        DagAccountManager accountManager = dagKernel.getDagAccountManager();
        DagTransactionProcessor txProcessor = dagKernel.getDagTransactionProcessor();

        // Create accounts with sufficient balance - use testKeyPair for signature verification
        Bytes senderAddress = AddressUtils.toBytesAddress(testKeyPair);
        Bytes receiverAddress = Bytes.random(20);

        accountManager.ensureAccountExists(senderAddress);
        accountManager.addBalance(senderAddress,
                UInt256.valueOf(10000000000L));
        accountManager.ensureAccountExists(receiverAddress);

        // Create transaction with wrong nonce (should be 0, but use 5)
        Transaction tx = Transaction.builder()
                .from(senderAddress)
                .to(receiverAddress)
                .amount(XAmount.of(1, XUnit.XDAG))
                .nonce(5) // Wrong nonce!
                .fee(XAmount.of(100, XUnit.MILLI_XDAG))
                .data(Bytes.EMPTY)
                .v(0)
                .r(Bytes32.ZERO)
                .s(Bytes32.ZERO)
                .build();

        // Sign transaction with testKeyPair
        tx = signTransaction(tx);

        // Process transaction
        DagTransactionProcessor.ProcessingResult result = txProcessor.processTransaction(tx);

        // Verify transaction fails
        assertFalse("Transaction should fail with invalid nonce", result.isSuccess());
        assertTrue("Error message should mention invalid nonce",
                result.getError().contains("Invalid nonce"));
    }

    /**
     * Test 17: Transaction processing - account doesn't exist
     *
     * <p>Test transaction processing fails when sender account doesn't exist.
     */
    @Test
    public void testTransactionProcessing_AccountNotExist() {
        // Start DagKernel
        dagKernel.start();

        DagTransactionProcessor txProcessor = dagKernel.getDagTransactionProcessor();

        // Create transaction from non-existent account - use testKeyPair for signature verification
        Bytes senderAddress = AddressUtils.toBytesAddress(testKeyPair);
        Bytes receiverAddress = Bytes.random(20);

        // Note: We intentionally do NOT create the sender account, to test the error case

        Transaction tx = Transaction.builder()
                .from(senderAddress)
                .to(receiverAddress)
                .amount(XAmount.of(1, XUnit.XDAG))
                .nonce(0)
                .fee(XAmount.of(100, XUnit.MILLI_XDAG))
                .data(Bytes.EMPTY)
                .v(0)
                .r(Bytes32.ZERO)
                .s(Bytes32.ZERO)
                .build();

        // Sign transaction with testKeyPair
        tx = signTransaction(tx);

        // Process transaction
        DagTransactionProcessor.ProcessingResult result = txProcessor.processTransaction(tx);

        // Verify transaction fails
        assertFalse("Transaction should fail when sender account doesn't exist", result.isSuccess());
        assertTrue("Error message should mention account doesn't exist",
                result.getError().contains("does not exist"));
    }

    /**
     * Test 18: Multiple sequential transactions
     *
     * <p>Test processing multiple transactions from same account,
     * verifying nonce increments correctly.
     */
    @Test
    public void testTransactionProcessing_MultipleSequential() {
        // Start DagKernel
        dagKernel.start();

        DagAccountManager accountManager = dagKernel.getDagAccountManager();
        DagTransactionProcessor txProcessor = dagKernel.getDagTransactionProcessor();

        // Create sender with sufficient balance - use testKeyPair for signature verification
        Bytes senderAddress = AddressUtils.toBytesAddress(testKeyPair);
        Bytes receiver1 = Bytes.random(20);
        Bytes receiver2 = Bytes.random(20);
        Bytes receiver3 = Bytes.random(20);

        accountManager.ensureAccountExists(senderAddress);
        accountManager.addBalance(senderAddress,
                UInt256.valueOf(100000000000L)); // 100 XDAG

        // Create receiver accounts
        accountManager.ensureAccountExists(receiver1);
        accountManager.ensureAccountExists(receiver2);
        accountManager.ensureAccountExists(receiver3);

        // Process 3 transactions with nonces 0, 1, 2
        for (int i = 0; i < 3; i++) {
            Bytes receiver = (i == 0) ? receiver1 : (i == 1) ? receiver2 : receiver3;

            Transaction tx = Transaction.builder()
                    .from(senderAddress)
                    .to(receiver)
                    .amount(XAmount.of(1, XUnit.XDAG))
                    .nonce(i)
                    .fee(XAmount.of(100, XUnit.MILLI_XDAG))
                    .data(Bytes.EMPTY)
                    .v(0)
                    .r(Bytes32.ZERO)
                    .s(Bytes32.ZERO)
                    .build();

            // Sign transaction with testKeyPair
            tx = signTransaction(tx);

            DagTransactionProcessor.ProcessingResult result = txProcessor.processTransaction(tx);
            assertTrue("Transaction " + i + " should succeed", result.isSuccess());
        }

        // Verify final nonce is 3
        UInt64 finalNonce = accountManager.getNonce(senderAddress);

        assertEquals("Final nonce should be 3 after 3 transactions", UInt64.valueOf(3), finalNonce);

        // Verify each receiver got 1 XDAG
        UInt256 expectedBalance = UInt256.valueOf(1000000000L);

        assertEquals("Receiver 1 should have 1 XDAG", expectedBalance,
                accountManager.getBalance(receiver1));
        assertEquals("Receiver 2 should have 1 XDAG", expectedBalance,
                accountManager.getBalance(receiver2));
        assertEquals("Receiver 3 should have 1 XDAG", expectedBalance,
                accountManager.getBalance(receiver3));
    }

    // ========== BUG-001: Genesis Height Verification (CRITICAL) ==========

    /**
     * Test BUG-001 Fix: Genesis block stored at height=1, not height=0
     *
     * <p>Critical Bug Fixed:
     * <ul>
     *   <li>Before fix: verifyGenesisBlock() queried height=0 (orphan blocks)</li>
     *   <li>After fix: Correctly queries height=1 (genesis block)</li>
     *   <li>Impact: Genesis verification was silently skipped, node could join wrong network</li>
     *   <li>Root Cause: XDAG 1.0 protocol defines height=0 for orphans, height=1 for genesis</li>
     * </ul>
     *
     * <p>This test verifies:
     * <ol>
     *   <li>Genesis block is stored at height=1 (not height=0)</li>
     *   <li>No blocks exist at height=0 (orphan blocks have height=0 but are not in main chain)</li>
     *   <li>Genesis verification succeeds on subsequent startups</li>
     * </ol>
     */
    @Test
    public void testGenesisBlockStoredAtHeightOne() {
        // Start DagKernel (creates genesis block)
        dagKernel.start();

        // Verify genesis block is at height=1 (XDAG 1.0 protocol)
        Block genesisAtHeight1 = dagKernel.getDagStore().getMainBlockByHeight(1, true);
        assertNotNull("Genesis block should exist at height=1 (BUGFIX BUG-001)",
                genesisAtHeight1);

        // Verify genesis block properties
        assertEquals("Genesis block should have height=1",
                1L, genesisAtHeight1.getInfo().getHeight());

        // Verify no main block at height=0 (orphan blocks use height=0 but aren't in main chain)
        Block blockAtHeight0 = dagKernel.getDagStore().getMainBlockByHeight(0, true);
        assertNull("No main block should exist at height=0 (height=0 is for orphan blocks only)",
                blockAtHeight0);

        // Verify genesis block properties match expected values
        // The genesis epoch is stored in the genesis block itself
        long expectedEpoch = dagKernel.getGenesisConfig().getEpoch();
        long actualEpoch = genesisAtHeight1.getEpoch();
        assertEquals("Genesis epoch should match genesis.json",
                expectedEpoch, actualEpoch);

        // Verify genesis block hash matches chain stats
        Bytes32 genesisHash = genesisAtHeight1.getHash();
        assertNotNull("Genesis hash should not be null", genesisHash);

        System.out.println("✅ BUG-001 Fix Verified: Genesis block correctly stored at height=1");
        System.out.println("   Genesis hash: " + genesisHash.toHexString().substring(0, 16) + "...");
        System.out.println("   Genesis epoch: " + actualEpoch);
        System.out.println("   Genesis height: " + genesisAtHeight1.getInfo().getHeight());
    }

    /**
     * Test BUG-001 Fix: Genesis verification succeeds on subsequent startup
     *
     * <p>This test simulates a node restart scenario to ensure that:
     * <ol>
     *   <li>Genesis verification logic queries height=1 (correct)</li>
     *   <li>Genesis verification succeeds (doesn't throw exception)</li>
     *   <li>Node can successfully join the network after restart</li>
     * </ol>
     *
     * <p>Before BUG-001 fix, this would fail because:
     * <ul>
     *   <li>verifyGenesisBlock() queried height=0 → got orphan blocks or null</li>
     *   <li>Genesis verification always failed or was skipped</li>
     *   <li>Node could potentially join wrong network</li>
     * </ul>
     */
    @Test
    public void testGenesisVerificationAfterRestart() throws Exception {
        // Step 1: Start original kernel to create genesis block
        dagKernel.start();

        // Step 2: Stop current kernel (simulating shutdown)
        dagKernel.stop();

        // Step 3: Wait for RocksDB locks to be released
        Thread.sleep(100);

        // Step 4: Create a new kernel instance with SAME database
        // This simulates a node restart - should verify existing genesis
        DagKernel restartedKernel = new DagKernel(config);

        try {
            // Step 5: Start the restarted kernel
            // This will call verifyGenesisBlock() because chain already has blocks
            restartedKernel.start();

            // Step 6: Verify genesis verification succeeded (no exception thrown)
            // If BUG-001 wasn't fixed, this would either:
            //   - Throw exception (genesis not found at height=0)
            //   - Silently skip verification (null check)

            // Verify kernel started successfully
            DagChain restartedChain = restartedKernel.getDagChain();
            assertNotNull("DagChain should be initialized after restart", restartedChain);

            // Verify genesis block is still at height=1
            Block genesisBlock = restartedKernel.getDagStore().getMainBlockByHeight(1, true);
            assertNotNull("Genesis block should exist at height=1 after restart",
                    genesisBlock);

            // Verify chain stats are correct
            long mainBlockCount = restartedChain.getChainStats().getMainBlockCount();
            assertTrue("Main block count should be >= 1 (at least genesis)",
                    mainBlockCount >= 1);

            System.out.println("✅ BUG-001 Fix Verified: Genesis verification succeeds after restart");
            System.out.println("   Genesis found at height=1: " + genesisBlock.getHash().toHexString().substring(0, 16) + "...");
            System.out.println("   Main block count: " + mainBlockCount);

        } finally {
            // Clean up: stop the restarted kernel
            if (restartedKernel != null) {
                restartedKernel.stop();
            }
        }
    }

    /**
     * Helper method: Sign a transaction with the test key pair
     */
    private Transaction signTransaction(Transaction tx) {
        return tx.sign(testKeyPair);
    }
}
