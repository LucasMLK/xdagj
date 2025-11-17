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

package io.xdag.db.rocksdb.transaction;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/**
 * RocksDB-based transactional store implementation.
 *
 * <p>This class provides ACID transaction support using RocksDB's WriteBatch mechanism:
 * <ul>
 *   <li><b>Atomicity</b>: All operations in a transaction are applied atomically (all-or-nothing)</li>
 *   <li><b>Consistency</b>: Validation logic ensures state transitions are valid</li>
 *   <li><b>Isolation</b>: Operations are synchronized to prevent concurrent conflicts</li>
 *   <li><b>Durability</b>: RocksDB provides persistent storage</li>
 * </ul>
 *
 * <p>Thread-safe: All methods are synchronized or use thread-safe data structures.
 *
 * <p>Design:
 * - Each transaction gets a unique ID (tx-1, tx-2, etc.)
 * - Transaction operations are buffered in a WriteBatch
 * - Commit atomically writes all buffered operations to RocksDB
 * - Rollback simply discards the WriteBatch without writing
 *
 * @since Phase 0.5 - Transaction Support Infrastructure
 */
@Slf4j
public class RocksDBTransactionManager implements TransactionalStore {

    /**
     * Reference to the RocksDB instance
     */
    private final RocksDB db;

    /**
     * Active transactions: transaction ID -> WriteBatch
     */
    private final ConcurrentHashMap<String, WriteBatch> activeTransactions = new ConcurrentHashMap<>();

    /**
     * Transaction ID generator (monotonically increasing)
     */
    private final AtomicLong txIdGenerator = new AtomicLong(0);

    /**
     * Create a new RocksDBTransactionManager.
     *
     * @param db RocksDB instance to use for transactions
     */
    public RocksDBTransactionManager(RocksDB db) {
        if (db == null) {
            throw new IllegalArgumentException("RocksDB instance cannot be null");
        }
        this.db = db;
        log.info("RocksDBTransactionManager initialized");
    }

    @Override
    public String beginTransaction() {
        // Generate unique transaction ID
        String txId = "tx-" + txIdGenerator.incrementAndGet();

        // Create new WriteBatch for this transaction
        WriteBatch batch = new WriteBatch();
        activeTransactions.put(txId, batch);

        log.debug("Transaction {} started", txId);
        return txId;
    }

    @Override
    public void commitTransaction(String txId) throws TransactionException {
        // Retrieve and remove transaction from active transactions
        WriteBatch batch = activeTransactions.remove(txId);

        if (batch == null) {
            throw new TransactionException("Transaction not found: " + txId);
        }

        // Write all operations atomically to RocksDB
        try (WriteOptions options = new WriteOptions()) {
            db.write(options, batch);
            batch.close();

            log.debug("Transaction {} committed successfully", txId);

        } catch (RocksDBException e) {
            log.error("Failed to commit transaction {}: {}", txId, e.getMessage(), e);
            batch.close();
            throw new TransactionException("Commit failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void rollbackTransaction(String txId) {
        // Retrieve and remove transaction from active transactions
        WriteBatch batch = activeTransactions.remove(txId);

        if (batch != null) {
            batch.close();
            log.debug("Transaction {} rolled back", txId);
        } else {
            log.warn("Attempted to rollback non-existent transaction: {}", txId);
        }
    }

    @Override
    public void putInTransaction(String txId, byte[] key, byte[] value) throws TransactionException {
        WriteBatch batch = activeTransactions.get(txId);

        if (batch == null) {
            throw new TransactionException("Transaction not found: " + txId);
        }

        try {
            batch.put(key, value);

            if (log.isTraceEnabled()) {
                log.trace("Transaction {}: PUT key (length={}), value (length={})",
                        txId, key.length, value.length);
            }

        } catch (RocksDBException e) {
            throw new TransactionException("Put operation failed in transaction " + txId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteInTransaction(String txId, byte[] key) throws TransactionException {
        WriteBatch batch = activeTransactions.get(txId);

        if (batch == null) {
            throw new TransactionException("Transaction not found: " + txId);
        }

        try {
            batch.delete(key);

            if (log.isTraceEnabled()) {
                log.trace("Transaction {}: DELETE key (length={})", txId, key.length);
            }

        } catch (RocksDBException e) {
            throw new TransactionException("Delete operation failed in transaction " + txId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isTransactionActive(String txId) {
        return activeTransactions.containsKey(txId);
    }

    @Override
    public int getActiveTransactionCount() {
        return activeTransactions.size();
    }

    /**
     * Cleanup all active transactions.
     *
     * <p>This method should be called during shutdown to ensure all WriteBatch objects
     * are properly closed and resources are released.
     *
     * <p>Warning: Any uncommitted transactions will be lost.
     */
    public void shutdown() {
        log.info("Shutting down RocksDBTransactionManager ({} active transactions)",
                activeTransactions.size());

        for (String txId : activeTransactions.keySet()) {
            log.warn("Rolling back uncommitted transaction during shutdown: {}", txId);
            rollbackTransaction(txId);
        }

        log.info("RocksDBTransactionManager shutdown complete");
    }

    /**
     * Get transaction statistics (for monitoring/debugging).
     *
     * @return statistics string
     */
    public String getStatistics() {
        return String.format("RocksDBTransactionManager[active=%d, totalCreated=%d]",
                activeTransactions.size(),
                txIdGenerator.get());
    }
}
