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

package io.xdag.db.rocksdb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.db.rocksdb.base.RocksdbKVSource;
import io.xdag.db.rocksdb.config.DatabaseFactory;
import io.xdag.db.rocksdb.config.DatabaseName;
import io.xdag.db.rocksdb.config.RocksdbFactory;
import io.xdag.db.rocksdb.transaction.RocksDBTransactionManager;
import io.xdag.db.rocksdb.transaction.TransactionException;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;

/**
 * Unit tests for RocksDBTransactionManager (Task 0.5.4)
 *
 * <p>Tests the transaction infrastructure that enables atomic rollback
 * during chain reorganization. Verifies:
 * <ul>
 *   <li>Transaction lifecycle (begin, commit, rollback)</li>
 *   <li>Atomic operations (all-or-nothing guarantee)</li>
 *   <li>Transaction isolation (changes not visible until commit)</li>
 *   <li>Error handling and resource cleanup</li>
 * </ul>
 *
 * @since Phase 0.5 - Transaction Support Infrastructure
 */
public class RocksDBTransactionManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private RocksDB db;
    private RocksDBTransactionManager txManager;
    private Config config;

    @Before
    public void setUp() throws IOException, RocksDBException {
        // Create temporary database
        config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(tempFolder.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(tempFolder.newFolder().getAbsolutePath());

        // Initialize RocksDB
        DatabaseFactory factory = new RocksdbFactory(config);
        RocksdbKVSource kvSource = (RocksdbKVSource) factory.getDB(DatabaseName.BLOCK);
        kvSource.init();  // Initialize the database
        db = kvSource.getDb();

        // Create transaction manager
        txManager = new RocksDBTransactionManager(db);
    }

    @After
    public void tearDown() {
        if (txManager != null) {
            txManager.shutdown();
        }
        // RocksDB will be closed by factory
    }

    // ========== Transaction Lifecycle Tests ==========

    /**
     * Test Case 1: Basic transaction lifecycle - begin, put, commit
     *
     * <p>Verifies that:
     * 1. Transaction can be created and returns unique ID
     * 2. Operations can be added to transaction
     * 3. Transaction can be committed successfully
     * 4. Data is persisted after commit
     */
    @Test
    public void testBasicTransactionLifecycle() throws Exception {
        // Begin transaction
        String txId = txManager.beginTransaction();
        assertNotNull("Transaction ID should not be null", txId);
        assertTrue("Transaction should be active", txManager.isTransactionActive(txId));
        assertEquals("Should have 1 active transaction", 1, txManager.getActiveTransactionCount());

        // Add operation to transaction
        byte[] key = "test-key".getBytes();
        byte[] value = "test-value".getBytes();
        txManager.putInTransaction(txId, key, value);

        // Data should NOT be visible before commit
        assertNull("Data should not be visible before commit", db.get(key));

        // Commit transaction
        txManager.commitTransaction(txId);
        assertFalse("Transaction should no longer be active", txManager.isTransactionActive(txId));
        assertEquals("Should have 0 active transactions", 0, txManager.getActiveTransactionCount());

        // Data should be visible after commit
        byte[] retrieved = db.get(key);
        assertNotNull("Data should be visible after commit", retrieved);
        assertEquals("Value should match", "test-value", new String(retrieved));
    }

    /**
     * Test Case 2: Transaction rollback discards all operations
     *
     * <p>Verifies that:
     * 1. Operations added to transaction
     * 2. Rollback discards all operations
     * 3. No data is persisted after rollback
     */
    @Test
    public void testTransactionRollback() throws Exception {
        // Begin transaction
        String txId = txManager.beginTransaction();

        // Add operations to transaction
        byte[] key1 = "rollback-key-1".getBytes();
        byte[] value1 = "rollback-value-1".getBytes();
        byte[] key2 = "rollback-key-2".getBytes();
        byte[] value2 = "rollback-value-2".getBytes();

        txManager.putInTransaction(txId, key1, value1);
        txManager.putInTransaction(txId, key2, value2);

        // Rollback transaction
        txManager.rollbackTransaction(txId);
        assertFalse("Transaction should no longer be active", txManager.isTransactionActive(txId));

        // Data should NOT be visible after rollback
        assertNull("First key should not exist after rollback", db.get(key1));
        assertNull("Second key should not exist after rollback", db.get(key2));
    }

    /**
     * Test Case 3: Multiple operations committed atomically
     *
     * <p>Verifies that:
     * 1. Multiple PUT operations can be batched
     * 2. All operations committed atomically (all-or-nothing)
     * 3. All data persisted after commit
     */
    @Test
    public void testAtomicMultipleOperations() throws Exception {
        String txId = txManager.beginTransaction();

        // Add multiple operations
        for (int i = 0; i < 10; i++) {
            byte[] key = ("atomic-key-" + i).getBytes();
            byte[] value = ("atomic-value-" + i).getBytes();
            txManager.putInTransaction(txId, key, value);
        }

        // Commit
        txManager.commitTransaction(txId);

        // Verify all operations persisted
        for (int i = 0; i < 10; i++) {
            byte[] key = ("atomic-key-" + i).getBytes();
            byte[] value = db.get(key);
            assertNotNull("Key " + i + " should exist", value);
            assertEquals("Value " + i + " should match",
                    "atomic-value-" + i,
                    new String(value));
        }
    }

    /**
     * Test Case 4: DELETE operations in transaction
     *
     * <p>Verifies that:
     * 1. DELETE operations can be added to transaction
     * 2. DELETE is executed atomically on commit
     */
    @Test
    public void testDeleteInTransaction() throws Exception {
        // Pre-populate database
        byte[] key = "delete-key".getBytes();
        byte[] value = "delete-value".getBytes();
        db.put(key, value);

        // Verify key exists
        assertNotNull("Key should exist before delete", db.get(key));

        // Begin transaction and delete
        String txId = txManager.beginTransaction();
        txManager.deleteInTransaction(txId, key);

        // Key should still exist before commit
        assertNotNull("Key should still exist before commit", db.get(key));

        // Commit
        txManager.commitTransaction(txId);

        // Key should be deleted after commit
        assertNull("Key should be deleted after commit", db.get(key));
    }

    /**
     * Test Case 5: Mixed PUT and DELETE operations
     *
     * <p>Verifies that:
     * 1. PUT and DELETE can be mixed in same transaction
     * 2. All operations applied atomically
     */
    @Test
    public void testMixedOperations() throws Exception {
        // Pre-populate
        db.put("existing-1".getBytes(), "value-1".getBytes());
        db.put("existing-2".getBytes(), "value-2".getBytes());

        // Begin transaction with mixed operations
        String txId = txManager.beginTransaction();

        // Delete existing keys
        txManager.deleteInTransaction(txId, "existing-1".getBytes());

        // Update existing key
        txManager.putInTransaction(txId, "existing-2".getBytes(), "updated-value".getBytes());

        // Add new key
        txManager.putInTransaction(txId, "new-key".getBytes(), "new-value".getBytes());

        // Commit
        txManager.commitTransaction(txId);

        // Verify results
        assertNull("existing-1 should be deleted", db.get("existing-1".getBytes()));
        assertEquals("existing-2 should be updated",
                "updated-value",
                new String(db.get("existing-2".getBytes())));
        assertEquals("new-key should be added",
                "new-value",
                new String(db.get("new-key".getBytes())));
    }

    // ========== Error Handling Tests ==========

    /**
     * Test Case 6: Commit non-existent transaction throws exception
     */
    @Test
    public void testCommitNonExistentTransaction() {
        try {
            txManager.commitTransaction("non-existent-tx");
            fail("Should throw TransactionException for non-existent transaction");
        } catch (TransactionException e) {
            assertTrue("Exception message should mention transaction not found",
                    e.getMessage().contains("not found"));
        }
    }

    /**
     * Test Case 7: Rollback non-existent transaction is safe (no-op)
     */
    @Test
    public void testRollbackNonExistentTransaction() {
        // Should not throw exception
        txManager.rollbackTransaction("non-existent-tx");
    }

    /**
     * Test Case 8: Put operation on non-existent transaction throws exception
     */
    @Test
    public void testPutInNonExistentTransaction() {
        try {
            txManager.putInTransaction("non-existent-tx",
                    "key".getBytes(),
                    "value".getBytes());
            fail("Should throw TransactionException");
        } catch (TransactionException e) {
            assertTrue("Exception message should mention transaction not found",
                    e.getMessage().contains("not found"));
        }
    }

    /**
     * Test Case 9: Delete operation on non-existent transaction throws exception
     */
    @Test
    public void testDeleteInNonExistentTransaction() {
        try {
            txManager.deleteInTransaction("non-existent-tx", "key".getBytes());
            fail("Should throw TransactionException");
        } catch (TransactionException e) {
            assertTrue("Exception message should mention transaction not found",
                    e.getMessage().contains("not found"));
        }
    }

    // ========== Transaction Isolation Tests ==========

    /**
     * Test Case 10: Transaction isolation - concurrent transactions don't interfere
     *
     * <p>Verifies that:
     * 1. Multiple transactions can exist simultaneously
     * 2. Operations in one transaction don't affect another
     * 3. Each transaction commits independently
     */
    @Test
    public void testTransactionIsolation() throws Exception {
        // Begin two transactions
        String tx1 = txManager.beginTransaction();
        String tx2 = txManager.beginTransaction();

        assertEquals("Should have 2 active transactions", 2, txManager.getActiveTransactionCount());

        // Add different operations to each
        txManager.putInTransaction(tx1, "tx1-key".getBytes(), "tx1-value".getBytes());
        txManager.putInTransaction(tx2, "tx2-key".getBytes(), "tx2-value".getBytes());

        // Commit tx1, rollback tx2
        txManager.commitTransaction(tx1);
        txManager.rollbackTransaction(tx2);

        // Only tx1 data should be visible
        assertNotNull("tx1 data should be committed", db.get("tx1-key".getBytes()));
        assertNull("tx2 data should be rolled back", db.get("tx2-key".getBytes()));
    }

    /**
     * Test Case 11: Transaction ID uniqueness
     *
     * <p>Verifies that each transaction gets a unique ID
     */
    @Test
    public void testTransactionIdUniqueness() {
        String tx1 = txManager.beginTransaction();
        String tx2 = txManager.beginTransaction();
        String tx3 = txManager.beginTransaction();

        assertNotNull("tx1 should not be null", tx1);
        assertNotNull("tx2 should not be null", tx2);
        assertNotNull("tx3 should not be null", tx3);

        assertFalse("tx1 and tx2 should be different", tx1.equals(tx2));
        assertFalse("tx2 and tx3 should be different", tx2.equals(tx3));
        assertFalse("tx1 and tx3 should be different", tx1.equals(tx3));

        // Clean up
        txManager.rollbackTransaction(tx1);
        txManager.rollbackTransaction(tx2);
        txManager.rollbackTransaction(tx3);
    }

    // ========== Resource Management Tests ==========

    /**
     * Test Case 12: Shutdown cleans up active transactions
     *
     * <p>Verifies that:
     * 1. Uncommitted transactions are rolled back on shutdown
     * 2. Resources are properly released
     */
    @Test
    public void testShutdownCleansUpActiveTransactions() throws Exception {
        // Create transactions without committing
        String tx1 = txManager.beginTransaction();
        String tx2 = txManager.beginTransaction();

        txManager.putInTransaction(tx1, "shutdown-key-1".getBytes(), "value-1".getBytes());
        txManager.putInTransaction(tx2, "shutdown-key-2".getBytes(), "value-2".getBytes());

        assertEquals("Should have 2 active transactions", 2, txManager.getActiveTransactionCount());

        // Shutdown
        txManager.shutdown();

        // Verify transactions were rolled back (data not committed)
        assertNull("shutdown-key-1 should not be committed", db.get("shutdown-key-1".getBytes()));
        assertNull("shutdown-key-2 should not be committed", db.get("shutdown-key-2".getBytes()));
    }

    /**
     * Test Case 13: Statistics tracking
     *
     * <p>Verifies that transaction statistics are correctly maintained
     */
    @Test
    public void testStatistics() throws Exception {
        String stats1 = txManager.getStatistics();
        assertNotNull("Statistics should not be null", stats1);
        assertTrue("Statistics should show 0 active", stats1.contains("active=0"));

        String tx1 = txManager.beginTransaction();
        String tx2 = txManager.beginTransaction();

        String stats2 = txManager.getStatistics();
        assertTrue("Statistics should show 2 active", stats2.contains("active=2"));

        txManager.commitTransaction(tx1);
        txManager.rollbackTransaction(tx2);

        String stats3 = txManager.getStatistics();
        assertTrue("Statistics should show 0 active after completion", stats3.contains("active=0"));
    }

    // ========== Chain Reorganization Simulation Tests ==========

    /**
     * Test Case 14: Simulate chain reorganization rollback
     *
     * <p>This simulates the critical use case for transactions:
     * During chain reorg, we need to atomically rollback multiple
     * account state changes.
     *
     * Scenario:
     * 1. Original chain has blocks with transactions
     * 2. Chain reorg detected - need to rollback to fork point
     * 3. Atomically revert all account/transaction changes
     * 4. Apply new chain's blocks
     */
    @Test
    public void testChainReorganizationSimulation() throws Exception {
        // Setup: Initial state (accounts A and B exist)
        db.put("account-A".getBytes(), "balance-1000".getBytes());
        db.put("account-B".getBytes(), "balance-500".getBytes());

        // Original chain: Transaction transfers 100 from A to B
        db.put("account-A".getBytes(), "balance-900".getBytes());
        db.put("account-B".getBytes(), "balance-600".getBytes());
        db.put("tx-hash-1".getBytes(), "tx-data-1".getBytes());

        // CHAIN REORG DETECTED!
        // Need to rollback to fork point atomically

        // Step 1: Begin rollback transaction
        String rollbackTx = txManager.beginTransaction();

        // Step 2: Restore account states
        txManager.putInTransaction(rollbackTx, "account-A".getBytes(), "balance-1000".getBytes());
        txManager.putInTransaction(rollbackTx, "account-B".getBytes(), "balance-500".getBytes());

        // Step 3: Remove old transaction
        txManager.deleteInTransaction(rollbackTx, "tx-hash-1".getBytes());

        // Step 4: Commit rollback atomically
        txManager.commitTransaction(rollbackTx);

        // Verify rollback succeeded
        assertEquals("Account A balance restored", "balance-1000",
                new String(db.get("account-A".getBytes())));
        assertEquals("Account B balance restored", "balance-500",
                new String(db.get("account-B".getBytes())));
        assertNull("Old transaction removed", db.get("tx-hash-1".getBytes()));

        // Step 5: Apply new chain's transaction (transfer 200 from A to B)
        String newChainTx = txManager.beginTransaction();
        txManager.putInTransaction(newChainTx, "account-A".getBytes(), "balance-800".getBytes());
        txManager.putInTransaction(newChainTx, "account-B".getBytes(), "balance-700".getBytes());
        txManager.putInTransaction(newChainTx, "tx-hash-2".getBytes(), "tx-data-2".getBytes());
        txManager.commitTransaction(newChainTx);

        // Verify new chain applied
        assertEquals("Account A new balance", "balance-800",
                new String(db.get("account-A".getBytes())));
        assertEquals("Account B new balance", "balance-700",
                new String(db.get("account-B".getBytes())));
        assertNotNull("New transaction exists", db.get("tx-hash-2".getBytes()));
    }
}
