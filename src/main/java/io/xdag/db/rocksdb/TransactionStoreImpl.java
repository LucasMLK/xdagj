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

import com.google.common.collect.Lists;
import io.xdag.core.Transaction;
import io.xdag.db.TransactionStore;
import io.xdag.utils.BytesUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RocksDB implementation of TransactionStore for XDAG v5.1
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
 * @since Phase 4 - v5.1 Architecture
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
            log.debug("Indexed transaction {} to block {}",
                     txHash.toHexString(), blockHash.toHexString());
        } catch (Exception e) {
            log.error("Failed to index transaction to block", e);
        }
    }

    @Override
    public List<Transaction> getTransactionsByAddress(Bytes32 address) {
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
    public void indexTransactionToAddress(Bytes32 address, Bytes32 txHash) {
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
}
