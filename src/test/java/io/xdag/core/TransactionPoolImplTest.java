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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.config.spec.TransactionPoolSpec;
import io.xdag.store.TransactionStore;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for TransactionPoolImpl (Phase 1 - Task 1.1)
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Transaction addition and removal</li>
 *   <li>Nonce ordering and validation</li>
 *   <li>Balance validation</li>
 *   <li>Transaction selection by fee</li>
 *   <li>Duplicate execution prevention</li>
 *   <li>Pool statistics</li>
 * </ul>
 */
public class TransactionPoolImplTest {

    private TransactionPoolImpl pool;
    private DagAccountManager accountManager;
    private TransactionStore transactionStore;
    private TransactionPoolSpec config;

    private Bytes address1;
    private Bytes address2;

    @Before
    public void setUp() {
        // Create mock dependencies
        accountManager = mock(DagAccountManager.class);
        transactionStore = mock(TransactionStore.class);

        // Configure pool settings
        config = mock(TransactionPoolSpec.class);
        when(config.getMaxPoolSize()).thenReturn(1000);
        when(config.getMaxTxAgeMillis()).thenReturn(3600000L);  // 1 hour
        when(config.getMinFee()).thenReturn(XAmount.of(1, XUnit.MILLI_XDAG));

        // Create pool
        pool = new TransactionPoolImpl(config, accountManager, transactionStore);

        // Setup test addresses
        address1 = Bytes.random(20);
        address2 = Bytes.random(20);

        // Mock account balances and nonces
        when(accountManager.getBalance(any())).thenReturn(UInt256.valueOf(1000000000000L));  // 1000 XDAG (1000 * 10^9 nano)
        when(accountManager.getNonce(any())).thenReturn(UInt64.ZERO);
        when(transactionStore.isTransactionExecuted(any())).thenReturn(false);
    }

    // ========== Basic Operations ==========

    @Test
    public void testAddTransaction() {
        Transaction tx = createTransaction(address1, address2, 100, 1, 10);

        boolean added = pool.addTransaction(tx);

        assertTrue("Transaction should be added", added);
        assertEquals(1, pool.size());
        assertTrue(pool.contains(tx.getHash()));
    }

    @Test
    public void testAddDuplicateTransaction() {
        Transaction tx = createTransaction(address1, address2, 100, 1, 10);

        assertTrue(pool.addTransaction(tx));
        assertFalse("Duplicate should be rejected", pool.addTransaction(tx));
        assertEquals(1, pool.size());
    }

    @Test
    public void testAddTransactionWithInsufficientBalance() {
        // Mock insufficient balance
        when(accountManager.getBalance(address1)).thenReturn(UInt256.valueOf(50000000000L));  // 50 XDAG (50 * 10^9 nano)

        Transaction tx = createTransaction(address1, address2, 100, 1, 10);  // Need 100.01 XDAG

        assertFalse("Transaction with insufficient balance should be rejected", pool.addTransaction(tx));
        assertEquals(0, pool.size());
    }

    @Test
    public void testAddTransactionWithLowFee() {
        Transaction tx = Transaction.builder()
                .from(address1)
                .to(address2)
                .amount(XAmount.of(100, XUnit.XDAG))
                .nonce(1)
                .fee(XAmount.of(500, XUnit.MICRO_XDAG))  // 0.5 mXDAG = 500 μXDAG, below minimum
                .data(Bytes.EMPTY)
                .v(0)
                .r(Bytes32.ZERO)
                .s(Bytes32.ZERO)
                .build();

        assertFalse("Transaction with low fee should be rejected", pool.addTransaction(tx));
        assertEquals(0, pool.size());
    }

    @Test
    public void testAddAlreadyExecutedTransaction() {
        Transaction tx = createTransaction(address1, address2, 100, 1, 10);
        when(transactionStore.isTransactionExecuted(tx.getHash())).thenReturn(true);

        assertFalse("Executed transaction should be rejected", pool.addTransaction(tx));
        assertEquals(0, pool.size());
    }

    @Test
    public void testRemoveTransaction() {
        Transaction tx = createTransaction(address1, address2, 100, 1, 10);

        pool.addTransaction(tx);
        assertTrue(pool.removeTransaction(tx.getHash()));
        assertEquals(0, pool.size());
        assertFalse(pool.contains(tx.getHash()));
    }

    @Test
    public void testRemoveNonexistentTransaction() {
        assertFalse(pool.removeTransaction(Bytes32.random()));
    }

    @Test
    public void testGetTransaction() {
        Transaction tx = createTransaction(address1, address2, 100, 1, 10);

        pool.addTransaction(tx);
        Transaction retrieved = pool.getTransaction(tx.getHash());

        assertNotNull(retrieved);
        assertEquals(tx.getHash(), retrieved.getHash());
    }

    @Test
    public void testGetNonexistentTransaction() {
        assertNull(pool.getTransaction(Bytes32.random()));
    }

    @Test
    public void testClear() {
        pool.addTransaction(createTransaction(address1, address2, 100, 1, 10));
        pool.addTransaction(createTransaction(address1, address2, 50, 2, 5));

        pool.clear();

        assertEquals(0, pool.size());
    }

    // ========== Nonce Ordering ==========

    @Test
    public void testNonceOrdering() {
        // Add transactions with sequential nonces
        when(accountManager.getNonce(address1)).thenReturn(UInt64.ZERO);

        Transaction tx1 = createTransaction(address1, address2, 100, 1, 10);
        Transaction tx2 = createTransaction(address1, address2, 50, 2, 5);

        assertTrue(pool.addTransaction(tx1));
        assertTrue(pool.addTransaction(tx2));

        List<Transaction> accountTxs = pool.getTransactionsByAccount(address1);
        assertEquals(2, accountTxs.size());
        assertEquals(1, accountTxs.get(0).getNonce());
        assertEquals(2, accountTxs.get(1).getNonce());
    }

    @Test
    public void testNonceGapRejection() {
        // Try to add transaction with nonce gap
        when(accountManager.getNonce(address1)).thenReturn(UInt64.ZERO);

        Transaction tx1 = createTransaction(address1, address2, 100, 1, 10);
        Transaction tx3 = createTransaction(address1, address2, 50, 3, 5);  // Gap: missing nonce 2

        assertTrue(pool.addTransaction(tx1));
        assertFalse("Transaction with nonce gap should be rejected", pool.addTransaction(tx3));
        assertEquals(1, pool.size());
    }

    @Test
    public void testNonceContinuityAfterExecution() {
        // Simulate account nonce = 5 (transactions 1-5 already executed)
        when(accountManager.getNonce(address1)).thenReturn(UInt64.valueOf(5));

        Transaction tx6 = createTransaction(address1, address2, 100, 6, 10);
        Transaction tx7 = createTransaction(address1, address2, 50, 7, 5);

        assertTrue("Nonce 6 should be accepted (account nonce + 1)", pool.addTransaction(tx6));
        assertTrue("Nonce 7 should be accepted (following nonce 6)", pool.addTransaction(tx7));
        assertEquals(2, pool.size());
    }

    // ========== Transaction Selection ==========

    @Test
    public void testSelectTransactionsByFee() {
        // Add transactions with different fees
        Transaction txHighFee = createTransaction(address1, address2, 100, 1, 100);  // 100 mXDAG
        Transaction txMediumFee = createTransaction(address1, address2, 50, 2, 50);  // 50 mXDAG
        Transaction txLowFee = createTransaction(address2, address1, 25, 1, 10);     // 10 mXDAG

        pool.addTransaction(txLowFee);
        pool.addTransaction(txHighFee);
        pool.addTransaction(txMediumFee);

        // Select top 2 transactions
        List<Transaction> selected = pool.selectTransactions(2);

        assertEquals(2, selected.size());
        assertTrue("Highest fee should be first", selected.get(0).getFee().compareTo(selected.get(1).getFee()) >= 0);
        assertEquals(txHighFee.getHash(), selected.get(0).getHash());
    }

    @Test
    public void testSelectTransactionsEmpty() {
        List<Transaction> selected = pool.selectTransactions(10);
        assertEquals(0, selected.size());
    }

    @Test
    public void testSelectTransactionsLimitExceedsSize() {
        pool.addTransaction(createTransaction(address1, address2, 100, 1, 10));

        List<Transaction> selected = pool.selectTransactions(100);
        assertEquals(1, selected.size());
    }

    // ========== Account Operations ==========

    @Test
    public void testGetTransactionsByAccount() {
        Transaction tx1 = createTransaction(address1, address2, 100, 1, 10);
        Transaction tx2 = createTransaction(address1, address2, 50, 2, 5);
        Transaction tx3 = createTransaction(address2, address1, 25, 1, 2);

        pool.addTransaction(tx1);
        pool.addTransaction(tx2);
        pool.addTransaction(tx3);

        List<Transaction> account1Txs = pool.getTransactionsByAccount(address1);
        assertEquals(2, account1Txs.size());

        List<Transaction> account2Txs = pool.getTransactionsByAccount(address2);
        assertEquals(1, account2Txs.size());
    }

    @Test
    public void testRemoveTransactionsByAccount() {
        pool.addTransaction(createTransaction(address1, address2, 100, 1, 10));
        pool.addTransaction(createTransaction(address1, address2, 50, 2, 5));
        pool.addTransaction(createTransaction(address2, address1, 25, 1, 2));

        int removed = pool.removeTransactionsByAccount(address1);

        assertEquals(2, removed);
        assertEquals(1, pool.size());
    }

    @Test
    public void testRemoveTransactionsByNonexistentAccount() {
        int removed = pool.removeTransactionsByAccount(Bytes.random(20));
        assertEquals(0, removed);
    }

    // ========== Statistics ==========

    @Test
    public void testStatistics() {
        pool.addTransaction(createTransaction(address1, address2, 100, 1, 10));
        pool.addTransaction(createTransaction(address1, address2, 50, 2, 5));

        TransactionPool.PoolStatistics stats = pool.getStatistics();

        assertNotNull(stats);
        assertEquals(2, stats.getCurrentSize());
        assertEquals(2, stats.getTotalAdded());
        assertEquals(0, stats.getTotalRemoved());
        assertEquals(0, stats.getTotalRejected());
    }

    @Test
    public void testStatisticsAfterRejection() {
        // Add one valid transaction
        pool.addTransaction(createTransaction(address1, address2, 100, 1, 10));

        // Try to add invalid transaction (insufficient balance)
        when(accountManager.getBalance(address1)).thenReturn(UInt256.valueOf(10000000000L));  // 10 XDAG (10 * 10^9 nano)
        pool.addTransaction(createTransaction(address1, address2, 100, 2, 10));  // Need 100.01 XDAG

        TransactionPool.PoolStatistics stats = pool.getStatistics();

        assertEquals(1, stats.getCurrentSize());
        assertEquals(1, stats.getTotalAdded());
        assertEquals(1, stats.getTotalRejected());
    }

    // ========== Helper Methods ==========

    /**
     * Create a test transaction with default signature
     *
     * @param from sender address
     * @param to receiver address
     * @param amount amount in XDAG
     * @param nonce nonce value
     * @param fee fee in mXDAG
     * @return transaction
     */
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
}
