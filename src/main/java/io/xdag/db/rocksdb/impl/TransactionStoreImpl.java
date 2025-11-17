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

package io.xdag.db.rocksdb.impl;

import com.google.common.collect.Lists;
import io.xdag.core.Transaction;
import io.xdag.core.TransactionExecutionInfo;
import io.xdag.db.TransactionStore;
import io.xdag.db.rocksdb.base.KVSource;
import io.xdag.db.rocksdb.transaction.TransactionException;
import io.xdag.db.rocksdb.transaction.TransactionalStore;
import io.xdag.utils.BytesUtils;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * RocksDB implementation of TransactionStore for XDAG
 *
 * Storage architecture:
 * 1. Primary storage: TX_DATA prefix (0xe0) + txHash -> Transaction bytes
 * 2. Block index: TX_BLOCK_INDEX prefix (0xe1) + blockHash -> concatenated txHashes
 * 3. Address index: TX_ADDRESS_INDEX prefix (0xe2) + address -> concatenated txHashes
 *
 * Performance characteristics:
 * - O(1) lookup by hash (RocksDB get)
 * - O(n) retrieval by block/address (where n = number of transactions)
 * - Batch operations supported for efficiency
 *
 * @since Phase 4 - Architecture
 */
@Slf4j
public class TransactionStoreImpl implements TransactionStore {

    /**
     * Primary transaction storage: hash -> Transaction bytes
     */
    private final KVSource<byte[], byte[]> txSource;

    /**
     * Index storage: block/address indexes
     */
    private final KVSource<byte[], byte[]> indexSource;

    /**
     * Optional transactional store for atomic operations (Phase 0.5)
     */
    private TransactionalStore transactionalStore;

    /**
     * Constructor
     *
     * @param txSource Primary transaction storage
     * @param indexSource Index storage for block and address mappings
     */
    public TransactionStoreImpl(
            KVSource<byte[], byte[]> txSource,
            KVSource<byte[], byte[]> indexSource) {
        this.txSource = txSource;
        this.indexSource = indexSource;
    }

    /**
     * Set the transactional store for atomic operations.
     *
     * <p>This must be called to enable transactional operations
     * (xxxInTransaction methods). If not set, calling transactional
     * methods will throw IllegalStateException.
     *
     * @param transactionalStore transactional store instance
     * @since Phase 0.5 - Transaction Support Infrastructure
     */
    public void setTransactionalStore(TransactionalStore transactionalStore) {
        this.transactionalStore = transactionalStore;
        log.info("TransactionalStore configured for TransactionStoreImpl");
    }

    // ========== Lifecycle Methods ==========

    @Override
    public void start() {
        txSource.init();
        indexSource.init();
        log.info("TransactionStore started");
    }

    @Override
    public void stop() {
        txSource.close();
        indexSource.close();
        log.info("TransactionStore stopped");
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public void reset() {
        txSource.reset();
        indexSource.reset();
        log.info("TransactionStore reset");
    }

    // ========== Core Operations ==========

    @Override
    public void saveTransaction(Transaction tx) {
        try {
            byte[] key = BytesUtils.merge(TX_DATA, tx.getHash().toArray());
            byte[] value = tx.toBytes();
            txSource.put(key, value);

            // Auto-index by from and to addresses
            indexTransactionToAddress(tx.getFrom(), tx.getHash());
            indexTransactionToAddress(tx.getTo(), tx.getHash());

            log.debug("Saved transaction: {}", tx.getHash().toHexString());
        } catch (Exception e) {
            log.error("Failed to save transaction: {}", tx.getHash().toHexString(), e);
            throw e;
        }
    }

    @Override
    public Transaction getTransaction(Bytes32 hash) {
        try {
            byte[] key = BytesUtils.merge(TX_DATA, hash.toArray());
            byte[] value = txSource.get(key);

            if (value == null) {
                return null;
            }

            return Transaction.fromBytes(value);
        } catch (Exception e) {
            log.error("Failed to get transaction: {}", hash.toHexString(), e);
            return null;
        }
    }

    @Override
    public boolean hasTransaction(Bytes32 hash) {
        byte[] key = BytesUtils.merge(TX_DATA, hash.toArray());
        return txSource.get(key) != null;
    }

    @Override
    public void deleteTransaction(Bytes32 hash) {
        try {
            byte[] key = BytesUtils.merge(TX_DATA, hash.toArray());
            txSource.delete(key);
            log.debug("Deleted transaction: {}", hash.toHexString());
        } catch (Exception e) {
            log.error("Failed to delete transaction: {}", hash.toHexString(), e);
        }
    }

    // ========== Indexing Operations ==========

    @Override
    public List<Transaction> getTransactionsByBlock(Bytes32 blockHash) {
        List<Bytes32> txHashes = getTransactionHashesByBlock(blockHash);
        return getTransactionsByHashes(txHashes);
    }

    @Override
    public List<Bytes32> getTransactionHashesByBlock(Bytes32 blockHash) {
        try {
            byte[] key = BytesUtils.merge(TX_BLOCK_INDEX, blockHash.toArray());
            byte[] value = indexSource.get(key);

            if (value == null) {
                return Lists.newArrayList();
            }

            // Parse concatenated hashes (each 32 bytes)
            return parseHashList(value);
        } catch (Exception e) {
            log.error("Failed to get transaction hashes by block: {}", blockHash.toHexString(), e);
            return Lists.newArrayList();
        }
    }

    @Override
    public void indexTransactionToBlock(Bytes32 blockHash, Bytes32 txHash) {
        try {
            // Forward index: blockHash -> List<txHash>
            byte[] key = BytesUtils.merge(TX_BLOCK_INDEX, blockHash.toArray());
            byte[] existingValue = indexSource.get(key);

            byte[] newValue;
            if (existingValue == null) {
                // First transaction for this block
                newValue = txHash.toArray();
            } else {
                // Append to existing list
                newValue = BytesUtils.merge(existingValue, txHash.toArray());
            }

            indexSource.put(key, newValue);

            //  Reverse index: txHash -> blockHash (for timestamp lookup)
            byte[] reverseKey = BytesUtils.merge(TRANSACTION_TO_BLOCK_INDEX, txHash.toArray());
            indexSource.put(reverseKey, blockHash.toArray());

            log.debug("Indexed transaction {} to block {} (bidirectional)",
                     txHash.toHexString(), blockHash.toHexString());
        } catch (Exception e) {
            log.error("Failed to index transaction to block", e);
        }
    }

    @Override
    public Bytes32 getBlockByTransaction(Bytes32 txHash) {
        try {
            byte[] key = BytesUtils.merge(TRANSACTION_TO_BLOCK_INDEX, txHash.toArray());
            byte[] blockHashBytes = indexSource.get(key);

            if (blockHashBytes == null) {
                return null;
            }

            return Bytes32.wrap(blockHashBytes);
        } catch (Exception e) {
            log.error("Failed to get block by transaction: {}", txHash.toHexString(), e);
            return null;
        }
    }

    @Override
    public List<Transaction> getTransactionsByAddress(org.apache.tuweni.bytes.Bytes address) {
        try {
            byte[] key = BytesUtils.merge(TX_ADDRESS_INDEX, address.toArray());
            byte[] value = indexSource.get(key);

            if (value == null) {
                return Lists.newArrayList();
            }

            // Parse concatenated hashes and retrieve transactions
            List<Bytes32> txHashes = parseHashList(value);
            return getTransactionsByHashes(txHashes);
        } catch (Exception e) {
            log.error("Failed to get transactions by address: {}", address.toHexString(), e);
            return Lists.newArrayList();
        }
    }

    @Override
    public void indexTransactionToAddress(org.apache.tuweni.bytes.Bytes address, Bytes32 txHash) {
        try {
            byte[] key = BytesUtils.merge(TX_ADDRESS_INDEX, address.toArray());
            byte[] existingValue = indexSource.get(key);

            byte[] newValue;
            if (existingValue == null) {
                // First transaction for this address
                newValue = txHash.toArray();
            } else {
                // Append to existing list
                newValue = BytesUtils.merge(existingValue, txHash.toArray());
            }

            indexSource.put(key, newValue);
            log.debug("Indexed transaction {} to address {}",
                     txHash.toHexString(), address.toHexString());
        } catch (Exception e) {
            log.error("Failed to index transaction to address", e);
        }
    }

    // ========== Batch Operations ==========

    @Override
    public void saveTransactions(List<Transaction> transactions) {
        for (Transaction tx : transactions) {
            saveTransaction(tx);
        }
        log.debug("Batch saved {} transactions", transactions.size());
    }

    @Override
    public List<Transaction> getTransactionsByHashes(List<Bytes32> hashes) {
        List<Transaction> result = Lists.newArrayList();
        for (Bytes32 hash : hashes) {
            Transaction tx = getTransaction(hash);
            result.add(tx);  // null entries for missing transactions
        }
        return result;
    }

    // ========== Statistics ==========

    @Override
    public long getTransactionCount() {
        AtomicLong count = new AtomicLong(0);
        try {
            // Count all keys with TX_DATA prefix
            byte[] prefix = new byte[]{TX_DATA};
            for (byte[] key : txSource.keys()) {
                if (key.length > 0 && key[0] == TX_DATA) {
                    count.incrementAndGet();
                }
            }
        } catch (Exception e) {
            log.error("Failed to count transactions", e);
        }
        return count.get();
    }

    // ========== Helper Methods ==========

    /**
     * Parse concatenated hash list from bytes
     *
     * @param data Concatenated hashes (N * 32 bytes)
     * @return List of hashes
     */
    private List<Bytes32> parseHashList(byte[] data) {
        List<Bytes32> result = Lists.newArrayList();

        if (data == null || data.length == 0) {
            return result;
        }

        if (data.length % 32 != 0) {
            log.warn("Invalid hash list data length: {} (not multiple of 32)", data.length);
            return result;
        }

        int numHashes = data.length / 32;
        for (int i = 0; i < numHashes; i++) {
            byte[] hashBytes = BytesUtils.subArray(data, i * 32, 32);
            result.add(Bytes32.wrap(hashBytes));
        }

        return result;
    }

    // ==================== Transactional Operations (Phase 0.5) ====================

    /**
     * Check if transactional store is available.
     *
     * @throws IllegalStateException if transactional store not configured
     */
    private void ensureTransactionalStoreAvailable() {
        if (transactionalStore == null) {
            throw new IllegalStateException(
                    "TransactionalStore not configured. Call setTransactionalStore() first.");
        }
    }

    /**
     * Save a transaction in a transaction.
     *
     * <p>This operation is queued and will be executed atomically when the transaction commits.
     *
     * @param txId transaction ID
     * @param tx transaction to save
     * @throws TransactionException if transaction operation fails
     * @since Phase 0.5 - Transaction Support Infrastructure
     */
    public void saveTransactionInTransaction(String txId, Transaction tx)
            throws TransactionException {
        ensureTransactionalStoreAvailable();

        // Serialize transaction
        byte[] key = BytesUtils.merge(TX_DATA, tx.getHash().toArray());
        byte[] value = tx.toBytes();
        transactionalStore.putInTransaction(txId, key, value);

        if (log.isDebugEnabled()) {
            log.debug("Transaction {}: saveTransaction({})",
                    txId, tx.getHash().toHexString().substring(0, 16));
        }
    }

    /**
     * Delete a transaction in a transaction.
     *
     * @param txId transaction ID
     * @param hash transaction hash to delete
     * @throws TransactionException if transaction operation fails
     * @since Phase 0.5 - Transaction Support Infrastructure
     */
    public void deleteTransactionInTransaction(String txId, Bytes32 hash)
            throws TransactionException {
        ensureTransactionalStoreAvailable();

        byte[] key = BytesUtils.merge(TX_DATA, hash.toArray());
        transactionalStore.deleteInTransaction(txId, key);

        if (log.isDebugEnabled()) {
            log.debug("Transaction {}: deleteTransaction({})",
                    txId, hash.toHexString().substring(0, 16));
        }
    }

    /**
     * Index a transaction to a block in a transaction.
     *
     * <p>This creates both forward index (blockHash -> txHash) and
     * reverse index (txHash -> blockHash) atomically.
     *
     * @param txId transaction ID
     * @param blockHash block hash
     * @param txHash transaction hash
     * @throws TransactionException if transaction operation fails
     * @since Phase 0.5 - Transaction Support Infrastructure
     */
    public void indexTransactionToBlockInTransaction(String txId, Bytes32 blockHash, Bytes32 txHash)
            throws TransactionException {
        ensureTransactionalStoreAvailable();

        // Forward index: blockHash -> List<txHash>
        byte[] blockIndexKey = BytesUtils.merge(TX_BLOCK_INDEX, blockHash.toArray());
        byte[] existingValue = indexSource.get(blockIndexKey);

        byte[] newValue;
        if (existingValue == null) {
            // First transaction for this block
            newValue = txHash.toArray();
        } else {
            // Append to existing list
            newValue = BytesUtils.merge(existingValue, txHash.toArray());
        }

        transactionalStore.putInTransaction(txId, blockIndexKey, newValue);

        // Reverse index: txHash -> blockHash
        byte[] reverseKey = BytesUtils.merge(TRANSACTION_TO_BLOCK_INDEX, txHash.toArray());
        transactionalStore.putInTransaction(txId, reverseKey, blockHash.toArray());

        if (log.isDebugEnabled()) {
            log.debug("Transaction {}: indexTransactionToBlock(block={}, tx={})",
                    txId,
                    blockHash.toHexString().substring(0, 16),
                    txHash.toHexString().substring(0, 16));
        }
    }

    // ==================== Transaction Execution Status Tracking (Phase 1 - Task 1.2) ====================

    @Override
    public boolean isTransactionExecuted(Bytes32 txHash) {
        try {
            byte[] key = BytesUtils.merge(TX_EXECUTION_STATUS, txHash.toArray());
            return indexSource.get(key) != null;
        } catch (Exception e) {
            log.error("Failed to check transaction execution status: {}", txHash.toHexString(), e);
            return false;
        }
    }

    @Override
    public void markTransactionExecuted(Bytes32 txHash, Bytes32 blockHash, long blockHeight) {
        try {
            TransactionExecutionInfo info = TransactionExecutionInfo.create(blockHash, blockHeight);
            byte[] key = BytesUtils.merge(TX_EXECUTION_STATUS, txHash.toArray());
            byte[] value = serializeExecutionInfo(info);
            indexSource.put(key, value);

            log.debug("Marked transaction {} as executed by block {} at height {}",
                    txHash.toHexString().substring(0, 16),
                    blockHash.toHexString().substring(0, 16),
                    blockHeight);
        } catch (Exception e) {
            log.error("Failed to mark transaction as executed: {}", txHash.toHexString(), e);
        }
    }

    @Override
    public void unmarkTransactionExecuted(Bytes32 txHash) {
        try {
            byte[] key = BytesUtils.merge(TX_EXECUTION_STATUS, txHash.toArray());
            indexSource.delete(key);

            log.debug("Unmarked transaction {} as executed (rollback)",
                    txHash.toHexString().substring(0, 16));
        } catch (Exception e) {
            log.error("Failed to unmark transaction execution: {}", txHash.toHexString(), e);
        }
    }

    @Override
    public TransactionExecutionInfo getExecutionInfo(Bytes32 txHash) {
        try {
            byte[] key = BytesUtils.merge(TX_EXECUTION_STATUS, txHash.toArray());
            byte[] value = indexSource.get(key);

            if (value == null) {
                return null;
            }

            return deserializeExecutionInfo(value);
        } catch (Exception e) {
            log.error("Failed to get execution info for transaction: {}", txHash.toHexString(), e);
            return null;
        }
    }

    // ========== Execution Info Serialization ==========

    /**
     * Serialize TransactionExecutionInfo to bytes.
     *
     * Format (49 bytes total):
     * - executingBlockHash: 32 bytes
     * - executingBlockHeight: 8 bytes (long)
     * - executionTimestamp: 8 bytes (long)
     * - isReversed: 1 byte (boolean)
     *
     * @param info execution info to serialize
     * @return serialized bytes
     */
    private byte[] serializeExecutionInfo(TransactionExecutionInfo info) {
        ByteBuffer buffer = ByteBuffer.allocate(49);
        buffer.put(info.getExecutingBlockHash().toArray());  // 32 bytes
        buffer.putLong(info.getExecutingBlockHeight());      // 8 bytes
        buffer.putLong(info.getExecutionTimestamp());        // 8 bytes
        buffer.put((byte) (info.isReversed() ? 1 : 0));      // 1 byte
        return buffer.array();
    }

    /**
     * Deserialize TransactionExecutionInfo from bytes.
     *
     * @param data serialized bytes
     * @return deserialized execution info
     */
    private TransactionExecutionInfo deserializeExecutionInfo(byte[] data) {
        if (data == null || data.length != 49) {
            log.warn("Invalid execution info data length: {} (expected 49)",
                    data != null ? data.length : 0);
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);

        // Read executingBlockHash (32 bytes)
        byte[] blockHashBytes = new byte[32];
        buffer.get(blockHashBytes);
        Bytes32 blockHash = Bytes32.wrap(blockHashBytes);

        // Read executingBlockHeight (8 bytes)
        long blockHeight = buffer.getLong();

        // Read executionTimestamp (8 bytes)
        long timestamp = buffer.getLong();

        // Read isReversed (1 byte)
        boolean isReversed = buffer.get() == 1;

        return TransactionExecutionInfo.builder()
                .executingBlockHash(blockHash)
                .executingBlockHeight(blockHeight)
                .executionTimestamp(timestamp)
                .isReversed(isReversed)
                .build();
    }
}
