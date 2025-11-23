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

package io.xdag.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Transaction;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.store.rocksdb.config.DatabaseFactory;
import io.xdag.store.rocksdb.config.DatabaseName;
import io.xdag.store.rocksdb.base.KVSource;
import io.xdag.store.rocksdb.config.RocksdbFactory;
import io.xdag.store.rocksdb.impl.TransactionStoreImpl;
import java.util.List;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for TransactionStore ()
 *
 * Tests the RocksDB-based storage layer for Transaction objects.
 * Verifies core operations, indexing, batch operations, and serialization.
 */
public class TransactionStoreTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private TransactionStore transactionStore;
    private Config config;

    @Before
    public void setUp() throws Exception {
        config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(tempFolder.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(tempFolder.newFolder().getAbsolutePath());

        DatabaseFactory factory = new RocksdbFactory(config);
        KVSource<byte[], byte[]> txSource = factory.getDB(DatabaseName.BLOCK);
        KVSource<byte[], byte[]> indexSource = factory.getDB(DatabaseName.INDEX);

        transactionStore = new TransactionStoreImpl(txSource, indexSource);
        transactionStore.start();
    }

    @After
    public void tearDown() {
        if (transactionStore != null) {
            transactionStore.stop();
        }
        // TemporaryFolder automatically cleans up after tests
    }

    // ========== Core Operations Tests ==========

    @Test
    public void testSaveAndRetrieveTransaction() {
        Transaction tx = Transaction.builder()
                .from(Bytes.random(20))
                .to(Bytes.random(20))
                .amount(XAmount.of(100, XUnit.XDAG))
                .nonce(1)
                .fee(XAmount.of(1, XUnit.MILLI_XDAG))
                .data(Bytes.EMPTY)
                .v(0)
                .r(Bytes32.ZERO)
                .s(Bytes32.ZERO)
                .build();

        transactionStore.saveTransaction(tx);

        Transaction retrieved = transactionStore.getTransaction(tx.getHash());
        assertNotNull(retrieved);
        assertEquals(tx.getFrom(), retrieved.getFrom());
        assertEquals(tx.getTo(), retrieved.getTo());
        assertEquals(tx.getAmount(), retrieved.getAmount());
        assertEquals(tx.getNonce(), retrieved.getNonce());
        assertEquals(tx.getFee(), retrieved.getFee());
        assertEquals(tx.getHash(), retrieved.getHash());
    }

    @Test
    public void testSaveTransactionWithData() {
        Bytes data = Bytes.wrap("Hello XDAGJ 1.0".getBytes());
        Transaction tx = Transaction.builder()
                .from(Bytes.random(20))
                .to(Bytes.random(20))
                .amount(XAmount.of(50, XUnit.XDAG))
                .nonce(5)
                .fee(XAmount.of(2, XUnit.MILLI_XDAG))
                .data(data)
                .v(27)
                .r(Bytes32.random())
                .s(Bytes32.random())
                .build();

        transactionStore.saveTransaction(tx);

        Transaction retrieved = transactionStore.getTransaction(tx.getHash());
        assertNotNull(retrieved);
        assertEquals(data, retrieved.getData());
        assertEquals(27, retrieved.getV());
    }

    @Test
    public void testHasTransaction() {
        Transaction tx = Transaction.builder()
                .from(Bytes.random(20))
                .to(Bytes.random(20))
                .amount(XAmount.of(10, XUnit.XDAG))
                .nonce(1)
                .fee(XAmount.of(1, XUnit.MILLI_XDAG))
                .build();

        assertFalse(transactionStore.hasTransaction(tx.getHash()));

        transactionStore.saveTransaction(tx);

        assertTrue(transactionStore.hasTransaction(tx.getHash()));
    }

    @Test
    public void testDeleteTransaction() {
        Transaction tx = Transaction.builder()
                .from(Bytes.random(20))
                .to(Bytes.random(20))
                .amount(XAmount.of(100, XUnit.XDAG))
                .nonce(1)
                .fee(XAmount.of(1, XUnit.MILLI_XDAG))
                .build();

        transactionStore.saveTransaction(tx);
        assertTrue(transactionStore.hasTransaction(tx.getHash()));

        transactionStore.deleteTransaction(tx.getHash());
        assertFalse(transactionStore.hasTransaction(tx.getHash()));
    }

    @Test
    public void testTransactionNotFound() {
        Bytes32 randomHash = Bytes32.random();
        Transaction tx = transactionStore.getTransaction(randomHash);
        assertNull(tx);
    }

    // ========== Indexing Tests ==========

    @Test
    public void testIndexTransactionToBlock() {
        Bytes32 blockHash = Bytes32.random();
        Transaction tx1 = createTestTransaction(1);
        Transaction tx2 = createTestTransaction(2);

        transactionStore.saveTransaction(tx1);
        transactionStore.saveTransaction(tx2);

        transactionStore.indexTransactionToBlock(blockHash, tx1.getHash());
        transactionStore.indexTransactionToBlock(blockHash, tx2.getHash());

        List<Transaction> txs = transactionStore.getTransactionsByBlock(blockHash);
        assertEquals(2, txs.size());
        assertTrue(txs.stream().anyMatch(tx -> tx.getHash().equals(tx1.getHash())));
        assertTrue(txs.stream().anyMatch(tx -> tx.getHash().equals(tx2.getHash())));
    }

    @Test
    public void testGetTransactionHashesByBlock() {
        Bytes32 blockHash = Bytes32.random();
        Transaction tx1 = createTestTransaction(1);
        Transaction tx2 = createTestTransaction(2);

        transactionStore.saveTransaction(tx1);
        transactionStore.saveTransaction(tx2);

        transactionStore.indexTransactionToBlock(blockHash, tx1.getHash());
        transactionStore.indexTransactionToBlock(blockHash, tx2.getHash());

        List<Bytes32> hashes = transactionStore.getTransactionHashesByBlock(blockHash);
        assertEquals(2, hashes.size());
        assertTrue(hashes.contains(tx1.getHash()));
        assertTrue(hashes.contains(tx2.getHash()));
    }

    @Test
    public void testGetTransactionsByAddress() {
        Bytes address = Bytes.random(20);

        // Transaction from address
        Transaction tx1 = Transaction.builder()
                .from(address)
                .to(Bytes.random(20))
                .amount(XAmount.of(100, XUnit.XDAG))
                .nonce(1)
                .fee(XAmount.of(1, XUnit.MILLI_XDAG))
                .build();

        // Transaction to address
        Transaction tx2 = Transaction.builder()
                .from(Bytes.random(20))
                .to(address)
                .amount(XAmount.of(50, XUnit.XDAG))
                .nonce(2)
                .fee(XAmount.of(1, XUnit.MILLI_XDAG))
                .build();

        transactionStore.saveTransaction(tx1);
        transactionStore.saveTransaction(tx2);

        // TransactionStoreImpl automatically indexes by from and to
        List<Transaction> txs = transactionStore.getTransactionsByAddress(address);
        assertEquals(2, txs.size());
    }

    @Test
    public void testEmptyBlockIndex() {
        Bytes32 blockHash = Bytes32.random();
        List<Transaction> txs = transactionStore.getTransactionsByBlock(blockHash);
        assertNotNull(txs);
        assertTrue(CollectionUtils.isEmpty(txs));
    }

    // ========== Batch Operations Tests ==========

    @Test
    public void testSaveMultipleTransactions() {
        List<Transaction> transactions = List.of(
                createTestTransaction(1),
                createTestTransaction(2),
                createTestTransaction(3)
        );

        transactionStore.saveTransactions(transactions);

        for (Transaction tx : transactions) {
            assertTrue(transactionStore.hasTransaction(tx.getHash()));
        }
    }

    @Test
    public void testGetTransactionsByHashes() {
        Transaction tx1 = createTestTransaction(1);
        Transaction tx2 = createTestTransaction(2);
        Transaction tx3 = createTestTransaction(3);

        transactionStore.saveTransaction(tx1);
        transactionStore.saveTransaction(tx2);
        transactionStore.saveTransaction(tx3);

        List<Bytes32> hashes = List.of(tx1.getHash(), tx2.getHash(), tx3.getHash());
        List<Transaction> txs = transactionStore.getTransactionsByHashes(hashes);

        assertEquals(3, txs.size());
        assertNotNull(txs.get(0));
        assertNotNull(txs.get(1));
        assertNotNull(txs.get(2));
    }

    @Test
    public void testGetTransactionsByHashesWithMissing() {
        Transaction tx1 = createTestTransaction(1);
        transactionStore.saveTransaction(tx1);

        List<Bytes32> hashes = List.of(tx1.getHash(), Bytes32.random());
        List<Transaction> txs = transactionStore.getTransactionsByHashes(hashes);

        // After BUG-015 fix: method skips missing transactions instead of returning null
        assertEquals(1, txs.size());  // Only found transaction is returned
        assertNotNull(txs.get(0));
        assertEquals(tx1.getHash(), txs.get(0).getHash());
    }

    // ========== Statistics Tests ==========

    @Test
    public void testTransactionCount() {
        assertEquals(0, transactionStore.getTransactionCount());

        transactionStore.saveTransaction(createTestTransaction(1));
        assertEquals(1, transactionStore.getTransactionCount());

        transactionStore.saveTransaction(createTestTransaction(2));
        assertEquals(2, transactionStore.getTransactionCount());

        transactionStore.saveTransaction(createTestTransaction(3));
        assertEquals(3, transactionStore.getTransactionCount());
    }

    @Test
    public void testReset() {
        transactionStore.saveTransaction(createTestTransaction(1));
        transactionStore.saveTransaction(createTestTransaction(2));

        assertEquals(2, transactionStore.getTransactionCount());

        transactionStore.reset();

        assertEquals(0, transactionStore.getTransactionCount());
    }

    // ========== Serialization Tests ==========

    @Test
    public void testSerializationRoundTrip() {
        Transaction original = Transaction.builder()
                .from(Bytes.random(20))
                .to(Bytes.random(20))
                .amount(XAmount.of(123, XUnit.XDAG))
                .nonce(456)
                .fee(XAmount.of(78, XUnit.MILLI_XDAG))
                .data(Bytes.wrap("test data".getBytes()))
                .v(27)
                .r(Bytes32.random())
                .s(Bytes32.random())
                .build();

        byte[] bytes = original.toBytes();
        Transaction deserialized = Transaction.fromBytes(bytes);

        assertEquals(original.getFrom(), deserialized.getFrom());
        assertEquals(original.getTo(), deserialized.getTo());
        assertEquals(original.getAmount(), deserialized.getAmount());
        assertEquals(original.getNonce(), deserialized.getNonce());
        assertEquals(original.getFee(), deserialized.getFee());
        assertEquals(original.getData(), deserialized.getData());
        assertEquals(original.getV(), deserialized.getV());
        assertEquals(original.getR(), deserialized.getR());
        assertEquals(original.getS(), deserialized.getS());
        assertEquals(original.getHash(), deserialized.getHash());
    }

    // ========== Helper Methods ==========

    private Transaction createTestTransaction(long nonce) {
        return Transaction.builder()
                .from(Bytes.random(20))
                .to(Bytes.random(20))
                .amount(XAmount.of(nonce * 10, XUnit.XDAG))
                .nonce(nonce)
                .fee(XAmount.of(1, XUnit.MILLI_XDAG))
                .data(Bytes.EMPTY)
                .v(0)
                .r(Bytes32.ZERO)
                .s(Bytes32.ZERO)
                .build();
    }
}
