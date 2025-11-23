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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.SampleKeys;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.store.TransactionStore;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for transaction execution flow (Phase 1)
 *
 * <p>Tests the complete flow using mocks:
 * <ol>
 *   <li>Transaction creation and validation</li>
 *   <li>Transaction processing</li>
 *   <li>Account state updates</li>
 *   <li>Transaction execution marking</li>
 * </ol>
 *
 * <p>Uses mocks instead of real database for faster testing
 */
public class TransactionExecutionIntegrationTest {

    private DagAccountManager accountManager;
    private TransactionStore transactionStore;
    private DagTransactionProcessor transactionProcessor;

    private ECKeyPair aliceKeyPair;
    private Bytes alice;
    private Bytes bob;

    @Before
    public void setUp() {
        // Create mocks
        accountManager = mock(DagAccountManager.class);
        transactionStore = mock(TransactionStore.class);
        transactionProcessor = new DagTransactionProcessor(accountManager, transactionStore);

        // Setup test accounts with valid key pair for signing
        aliceKeyPair = SampleKeys.KEY_PAIR;
        alice = AddressUtils.toBytesAddress(aliceKeyPair);
        bob = Bytes.random(20);

        // Mock account initial states
        when(accountManager.hasAccount(alice)).thenReturn(true);
        when(accountManager.getBalance(alice)).thenReturn(UInt256.valueOf(1000000000000L));  // 1000 XDAG
        when(accountManager.getNonce(alice)).thenReturn(UInt64.ZERO);

        when(accountManager.hasAccount(bob)).thenReturn(true);
        when(accountManager.getBalance(bob)).thenReturn(UInt256.ZERO);
        when(accountManager.getNonce(bob)).thenReturn(UInt64.ZERO);

        // Mock transaction store
        when(transactionStore.isTransactionExecuted(any())).thenReturn(false);

        // Mock account manager operations
        when(accountManager.subtractBalance(any(), any())).thenReturn(UInt256.ZERO);
        when(accountManager.addBalance(any(), any())).thenReturn(UInt256.ZERO);
        when(accountManager.incrementNonce(any())).thenReturn(UInt64.ONE);
    }

    // ========== Basic Transaction Execution ==========

    @Test
    public void testSimpleTransfer() {
        // Create transaction: Alice sends 100 XDAG to Bob
        Transaction tx = createSignedTransaction(
                alice,
                bob,
                XAmount.of(100, XUnit.XDAG),
                0,
                XAmount.of(1, XUnit.MILLI_XDAG)
        );

        // Process transaction
        DagTransactionProcessor.ProcessingResult result = transactionProcessor.processTransaction(tx);

        assertTrue("Transaction should succeed", result.isSuccess());
    }

    @Test
    public void testMultipleSequentialTransactions() {
        // Alice sends 3 transactions
        Transaction tx1 = createSignedTransaction(alice, bob, XAmount.of(100, XUnit.XDAG), 0, XAmount.of(1, XUnit.MILLI_XDAG));
        Transaction tx2 = createSignedTransaction(alice, bob, XAmount.of(50, XUnit.XDAG), 1, XAmount.of(1, XUnit.MILLI_XDAG));
        Transaction tx3 = createSignedTransaction(alice, bob, XAmount.of(25, XUnit.XDAG), 2, XAmount.of(1, XUnit.MILLI_XDAG));

        // Update mock for sequential nonces
        when(accountManager.getNonce(alice))
                .thenReturn(UInt64.ZERO)
                .thenReturn(UInt64.ONE)
                .thenReturn(UInt64.valueOf(2));

        DagTransactionProcessor.ProcessingResult result1 = transactionProcessor.processTransaction(tx1);
        DagTransactionProcessor.ProcessingResult result2 = transactionProcessor.processTransaction(tx2);
        DagTransactionProcessor.ProcessingResult result3 = transactionProcessor.processTransaction(tx3);

        assertTrue("Transaction 1 should succeed", result1.isSuccess());
        assertTrue("Transaction 2 should succeed", result2.isSuccess());
        assertTrue("Transaction 3 should succeed", result3.isSuccess());
    }

    // ========== Block Transaction Processing ==========

    @Test
    public void testBlockTransactionExecution() {
        // Create block
        Block block = createTestBlock(100);

        // Create transactions
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(createSignedTransaction(alice, bob, XAmount.of(100, XUnit.XDAG), 0, XAmount.of(1, XUnit.MILLI_XDAG)));
        transactions.add(createSignedTransaction(alice, bob, XAmount.of(50, XUnit.XDAG), 1, XAmount.of(1, XUnit.MILLI_XDAG)));

        // Update mock for sequential nonces
        when(accountManager.getNonce(alice))
                .thenReturn(UInt64.ZERO)
                .thenReturn(UInt64.ONE);

        // Process transactions
        DagTransactionProcessor.ProcessingResult result = transactionProcessor.processBlockTransactions(block, transactions);

        assertTrue("Block processing should succeed", result.isSuccess());
    }

    // ========== Error Handling ==========

    @Test
    public void testInsufficientBalanceRejection() {
        // Mock insufficient balance
        when(accountManager.getBalance(alice)).thenReturn(UInt256.valueOf(50000000000L));  // Only 50 XDAG

        // Alice tries to send more than she has
        Transaction tx = createSignedTransaction(
                alice,
                bob,
                XAmount.of(100, XUnit.XDAG),  // More than 50 XDAG balance
                0,
                XAmount.of(1, XUnit.MILLI_XDAG)
        );

        DagTransactionProcessor.ProcessingResult result = transactionProcessor.processTransaction(tx);

        assertTrue("Transaction should fail", result.isError());
        assertTrue("Error should mention balance", result.getError().contains("Insufficient balance"));
    }

    @Test
    public void testInvalidNonceRejection() {
        // Mock nonce = 5
        when(accountManager.getNonce(alice)).thenReturn(UInt64.valueOf(5));

        // Alice tries to send transaction with wrong nonce
        Transaction tx = createSignedTransaction(alice, bob, XAmount.of(100, XUnit.XDAG), 3, XAmount.of(1, XUnit.MILLI_XDAG));

        DagTransactionProcessor.ProcessingResult result = transactionProcessor.processTransaction(tx);

        assertTrue("Transaction should fail", result.isError());
        assertTrue("Error should mention nonce", result.getError().contains("Invalid nonce"));
    }

    @Test
    public void testSenderNotExistsRejection() {
        // Mock sender doesn't exist
        when(accountManager.hasAccount(alice)).thenReturn(false);

        Transaction tx = createSignedTransaction(alice, bob, XAmount.of(100, XUnit.XDAG), 0, XAmount.of(1, XUnit.MILLI_XDAG));

        DagTransactionProcessor.ProcessingResult result = transactionProcessor.processTransaction(tx);

        assertTrue("Transaction should fail", result.isError());
        assertTrue("Error should mention account", result.getError().contains("does not exist"));
    }

    // ========== Helper Methods ==========

    /**
     * Create and sign a transfer transaction using Alice's key pair
     */
    private Transaction createSignedTransaction(Bytes from, Bytes to, XAmount amount, long nonce, XAmount fee) {
        Transaction tx = Transaction.createTransfer(from, to, amount, nonce, fee, 1L);
        return tx.sign(aliceKeyPair);
    }

    private Block createTestBlock(long height) {
        BlockInfo info = BlockInfo.builder()
                .hash(Bytes32.random())
                .epoch(System.currentTimeMillis() / 1000)
                .height(height)
                .difficulty(UInt256.valueOf(1000))
                .build();

        return Block.builder()
                .info(info)
                .header(BlockHeader.builder()
                        .difficulty(UInt256.ONE)
                        .nonce(Bytes32.ZERO)
                        .coinbase(Bytes.wrap(new byte[20]))
                        .build())
                .links(new ArrayList<>())
                .build();
    }
}
