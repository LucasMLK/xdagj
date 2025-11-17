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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.xdag.db.TransactionStore;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for DagTransactionProcessor (Phase 1 - Task 1.3)
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Single transaction processing</li>
 *   <li>Block transaction processing</li>
 *   <li>Account state validation and updates</li>
 *   <li>Transaction execution marking</li>
 *   <li>Error handling</li>
 * </ul>
 */
public class DagTransactionProcessorTest {

    private DagTransactionProcessor processor;
    private DagAccountManager accountManager;
    private TransactionStore transactionStore;

    private Bytes senderAddress;
    private Bytes receiverAddress;

    @Before
    public void setUp() {
        accountManager = mock(DagAccountManager.class);
        transactionStore = mock(TransactionStore.class);
        processor = new DagTransactionProcessor(accountManager, transactionStore);

        senderAddress = Bytes.random(20);
        receiverAddress = Bytes.random(20);
    }

    // ========== Single Transaction Processing ==========

    @Test
    public void testProcessValidTransaction() {
        // Setup: sender has sufficient balance and correct nonce
        when(accountManager.hasAccount(senderAddress)).thenReturn(true);
        when(accountManager.getBalance(senderAddress)).thenReturn(UInt256.valueOf(200000000000L));  // 200 XDAG
        when(accountManager.getNonce(senderAddress)).thenReturn(UInt64.ZERO);

        Transaction tx = createTransaction(senderAddress, receiverAddress, 100, 0, 1);

        DagTransactionProcessor.ProcessingResult result = processor.processTransaction(tx);

        assertTrue("Transaction should succeed", result.isSuccess());

        // Verify account state updates
        verify(accountManager).ensureAccountExists(receiverAddress);
        verify(accountManager).subtractBalance(eq(senderAddress), any(UInt256.class));
        verify(accountManager).incrementNonce(senderAddress);
        verify(accountManager).addBalance(eq(receiverAddress), any(UInt256.class));

        // Verify transaction saved
        verify(transactionStore).saveTransaction(tx);
    }

    @Test
    public void testProcessTransactionWithInsufficientBalance() {
        when(accountManager.hasAccount(senderAddress)).thenReturn(true);
        when(accountManager.getBalance(senderAddress)).thenReturn(UInt256.valueOf(50000000000L));  // 50 XDAG
        when(accountManager.getNonce(senderAddress)).thenReturn(UInt64.ZERO);

        Transaction tx = createTransaction(senderAddress, receiverAddress, 100, 0, 1);  // Need 101 XDAG

        DagTransactionProcessor.ProcessingResult result = processor.processTransaction(tx);

        assertFalse("Transaction should fail", result.isSuccess());
        assertTrue("Error should mention balance", result.getError().contains("Insufficient balance"));

        // Verify no state updates
        verify(accountManager, never()).subtractBalance(any(), any());
        verify(accountManager, never()).incrementNonce(any());
        verify(transactionStore, never()).saveTransaction(any());
    }

    @Test
    public void testProcessTransactionWithInvalidNonce() {
        when(accountManager.hasAccount(senderAddress)).thenReturn(true);
        when(accountManager.getBalance(senderAddress)).thenReturn(UInt256.valueOf(200000000000L));
        when(accountManager.getNonce(senderAddress)).thenReturn(UInt64.valueOf(5));

        Transaction tx = createTransaction(senderAddress, receiverAddress, 100, 3, 1);  // Nonce 3 but expected 5

        DagTransactionProcessor.ProcessingResult result = processor.processTransaction(tx);

        assertFalse("Transaction should fail", result.isSuccess());
        assertTrue("Error should mention nonce", result.getError().contains("Invalid nonce"));

        verify(accountManager, never()).subtractBalance(any(), any());
        verify(accountManager, never()).incrementNonce(any());
    }

    @Test
    public void testProcessTransactionSenderNotExists() {
        when(accountManager.hasAccount(senderAddress)).thenReturn(false);

        Transaction tx = createTransaction(senderAddress, receiverAddress, 100, 0, 1);

        DagTransactionProcessor.ProcessingResult result = processor.processTransaction(tx);

        assertFalse("Transaction should fail", result.isSuccess());
        assertTrue("Error should mention account", result.getError().contains("does not exist"));
    }

    // ========== Block Transaction Processing ==========

    @Test
    public void testProcessBlockTransactionsSuccess() {
        // Setup valid account states
        when(accountManager.hasAccount(senderAddress)).thenReturn(true);
        when(accountManager.getBalance(senderAddress)).thenReturn(UInt256.valueOf(500000000000L));  // 500 XDAG
        when(accountManager.getNonce(senderAddress))
                .thenReturn(UInt64.ZERO)   // First transaction expects nonce=0
                .thenReturn(UInt64.ONE);    // Second transaction expects nonce=1

        // Create block
        Block block = createTestBlock();

        // Create multiple transactions
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(createTransaction(senderAddress, receiverAddress, 100, 0, 1));
        transactions.add(createTransaction(senderAddress, receiverAddress, 50, 1, 1));

        DagTransactionProcessor.ProcessingResult result = processor.processBlockTransactions(block, transactions);

        assertTrue("Block processing should succeed", result.isSuccess());

        // Verify all transactions were processed
        verify(transactionStore, times(2)).saveTransaction(any(Transaction.class));
        verify(transactionStore, times(2)).markTransactionExecuted(
                any(Bytes32.class), eq(block.getHash()), anyLong());
    }

    @Test
    public void testProcessBlockTransactionsWithFailure() {
        // First transaction will succeed, second will fail (insufficient balance)
        when(accountManager.hasAccount(senderAddress)).thenReturn(true);
        when(accountManager.getBalance(senderAddress))
                .thenReturn(UInt256.valueOf(200000000000L))  // 200 XDAG for first call
                .thenReturn(UInt256.valueOf(50000000000L));  // 50 XDAG for second call (insufficient)
        when(accountManager.getNonce(senderAddress))
                .thenReturn(UInt64.ZERO)
                .thenReturn(UInt64.ONE);

        Block block = createTestBlock();

        List<Transaction> transactions = new ArrayList<>();
        transactions.add(createTransaction(senderAddress, receiverAddress, 100, 0, 1));
        transactions.add(createTransaction(senderAddress, receiverAddress, 100, 1, 1));  // Will fail

        DagTransactionProcessor.ProcessingResult result = processor.processBlockTransactions(block, transactions);

        assertFalse("Block processing should fail", result.isSuccess());
        assertTrue("Error should mention failure", result.getError().contains("failed"));
    }

    @Test
    public void testProcessBlockTransactionsEmpty() {
        Block block = createTestBlock();
        List<Transaction> transactions = new ArrayList<>();

        DagTransactionProcessor.ProcessingResult result = processor.processBlockTransactions(block, transactions);

        assertTrue("Empty block should succeed", result.isSuccess());
        verify(transactionStore, never()).markTransactionExecuted(any(), any(), anyLong());
    }

    // ========== Account State Updates ==========

    @Test
    public void testAccountStateUpdatesCorrectAmounts() {
        when(accountManager.hasAccount(senderAddress)).thenReturn(true);
        when(accountManager.getBalance(senderAddress)).thenReturn(UInt256.valueOf(200000000000L));  // 200 XDAG
        when(accountManager.getNonce(senderAddress)).thenReturn(UInt64.ZERO);

        Transaction tx = createTransaction(senderAddress, receiverAddress, 100, 0, 5);  // 100 XDAG + 5 mXDAG fee

        processor.processTransaction(tx);

        // Verify amounts (100 XDAG = 100,000,000,000 nano XDAG, 5 mXDAG = 5,000,000 nano XDAG)
        UInt256 expectedDeducted = UInt256.valueOf(100005000000L);  // 100.005 XDAG
        UInt256 expectedAdded = UInt256.valueOf(100000000000L);     // 100 XDAG

        verify(accountManager).subtractBalance(senderAddress, expectedDeducted);
        verify(accountManager).addBalance(receiverAddress, expectedAdded);
        verify(accountManager).incrementNonce(senderAddress);
    }

    @Test
    public void testEnsureReceiverAccountCreated() {
        when(accountManager.hasAccount(senderAddress)).thenReturn(true);
        when(accountManager.getBalance(senderAddress)).thenReturn(UInt256.valueOf(200000000000L));
        when(accountManager.getNonce(senderAddress)).thenReturn(UInt64.ZERO);

        Transaction tx = createTransaction(senderAddress, receiverAddress, 100, 0, 1);

        processor.processTransaction(tx);

        verify(accountManager).ensureAccountExists(receiverAddress);
    }

    // ========== Transaction Execution Marking ==========

    @Test
    public void testTransactionExecutionMarking() {
        when(accountManager.hasAccount(senderAddress)).thenReturn(true);
        when(accountManager.getBalance(senderAddress)).thenReturn(UInt256.valueOf(200000000000L));
        when(accountManager.getNonce(senderAddress)).thenReturn(UInt64.ZERO);

        Block block = createTestBlock();
        Transaction tx = createTransaction(senderAddress, receiverAddress, 100, 0, 1);

        processor.processBlockTransactions(block, List.of(tx));

        verify(transactionStore).markTransactionExecuted(
                tx.getHash(),
                block.getHash(),
                block.getInfo().getHeight()
        );
    }

    // ========== Helper Methods ==========

    private Transaction createTransaction(Bytes from, Bytes to, long amount, long nonce, long fee) {
        return Transaction.builder()
                .from(from)
                .to(to)
                .amount(XAmount.of(amount, XUnit.XDAG))
                .nonce(nonce)
                .fee(XAmount.of(fee, XUnit.MILLI_XDAG))
                .data(Bytes.EMPTY)
                .v(0)
                .r(Bytes32.ZERO)
                .s(Bytes32.ZERO)
                .build();
    }

    private Block createTestBlock() {
        BlockInfo info = BlockInfo.builder()
                .hash(Bytes32.random())
                .timestamp(System.currentTimeMillis() / 1000)
                .height(100)
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
