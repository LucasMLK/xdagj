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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.xdag.config.spec.TransactionPoolSpec;
import io.xdag.store.TransactionStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;

import static io.xdag.core.XUnit.NANO_XDAG;

/**
 * Caffeine-based implementation of TransactionPool.
 *
 * <p>This implementation provides:
 * <ul>
 *   <li>High-performance caching with Caffeine</li>
 *   <li>Thread-safe concurrent access with ReadWriteLock</li>
 *   <li>Nonce ordering per account with TreeMap indexing</li>
 *   <li>Automatic expiration and cleanup</li>
 *   <li>Detailed statistics tracking</li>
 * </ul>
 *
 * <h2>Concurrency Model</h2>
 * <ul>
 *   <li>Read operations (get, contains, select): ReadLock - allows concurrent reads</li>
 *   <li>Write operations (add, remove): WriteLock - exclusive access</li>
 * </ul>
 *
 * @since Phase 1 - TransactionPool Implementation
 */
@Slf4j
public class TransactionPoolImpl implements TransactionPool {

    // ========== Core Data Structures ==========

    /**
     * Main cache: txHash -> PendingTransaction
     * Caffeine provides thread-safe operations and automatic eviction
     */
    private final Cache<Bytes32, PendingTransaction> txCache;

    /**
     * Nonce index: address -> TreeMap(nonce -> txHash)
     * TreeMap maintains nonce ordering
     */
    private final ConcurrentHashMap<Bytes, TreeMap<Long, Bytes32>> accountNonceMap;

    /**
     * Read-write lock for pool operations
     * Fair lock to prevent starvation
     */
    private final ReadWriteLock poolLock = new ReentrantReadWriteLock(true);

    // ========== Dependencies ==========

    private final TransactionPoolSpec config;
    private final DagAccountManager accountManager;
    private final TransactionStore transactionStore;

    // ========== Statistics ==========

    private final AtomicLong totalAdded = new AtomicLong(0);
    private final AtomicLong totalRemoved = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);

    /**
     * Create a new TransactionPoolImpl.
     *
     * @param config pool configuration
     * @param accountManager account manager for balance/nonce queries
     * @param transactionStore transaction store for execution status
     */
    public TransactionPoolImpl(
            TransactionPoolSpec config,
            DagAccountManager accountManager,
            TransactionStore transactionStore) {
        this.config = config;
        this.accountManager = accountManager;
        this.transactionStore = transactionStore;
        this.accountNonceMap = new ConcurrentHashMap<>();

        // ========== Configure Caffeine Cache ==========
        this.txCache = Caffeine.newBuilder()
                // Maximum capacity
                .maximumSize(config.getMaxPoolSize())

                // Expire after access (idle timeout)
                .expireAfterAccess(config.getMaxTxAgeMillis(), TimeUnit.MILLISECONDS)

                // Removal listener - cleanup nonce index
                .removalListener(this::onTransactionRemoved)

                // Enable statistics
                .recordStats()

                .build();

        log.info("TransactionPool initialized: {}", config);
    }

    /**
     * Removal listener callback - cleanup nonce index when transaction is evicted.
     */
    private void onTransactionRemoved(Bytes32 key, PendingTransaction value, RemovalCause cause) {
        if (value != null) {
            Transaction tx = value.getTransaction();

            // Cleanup nonce index
            TreeMap<Long, Bytes32> accountTxs = accountNonceMap.get(tx.getFrom());
            if (accountTxs != null) {
                accountTxs.remove(tx.getNonce());
                if (accountTxs.isEmpty()) {
                    accountNonceMap.remove(tx.getFrom());
                }
            }

            log.debug("Transaction {} removed from pool (cause: {}, age: {}ms)",
                    key.toHexString().substring(0, 16),
                    cause,
                    value.getAge());

            totalRemoved.incrementAndGet();
        }
    }

    // ========== Core Operations ==========

    @Override
    public boolean addTransaction(Transaction tx) {
        Bytes32 txHash = tx.getHash();

        poolLock.writeLock().lock();
        try {
            // 1. Check if already in pool
            if (txCache.getIfPresent(txHash) != null) {
                log.debug("Transaction {} already in pool", txHash.toHexString().substring(0, 16));
                return false;
            }

            // 2. Basic validation
            if (!validateBasic(tx)) {
                totalRejected.incrementAndGet();
                return false;
            }

            // 3. Check if already executed
            if (transactionStore.isTransactionExecuted(txHash)) {
                log.debug("Transaction {} already executed", txHash.toHexString().substring(0, 16));
                totalRejected.incrementAndGet();
                return false;
            }

            // 4. Validate nonce continuity
            if (!canAcceptNonce(tx.getFrom(), tx.getNonce())) {
                log.debug("Transaction {} rejected: nonce gap (from={}, nonce={}, expected={})",
                        txHash.toHexString().substring(0, 16),
                        tx.getFrom().toHexString().substring(0, 8),
                        tx.getNonce(),
                        getExpectedNonce(tx.getFrom()));
                totalRejected.incrementAndGet();
                return false;
            }

            // 5. Validate balance
            if (!hasSufficientBalance(tx)) {
                log.debug("Transaction {} rejected: insufficient balance",
                        txHash.toHexString().substring(0, 16));
                totalRejected.incrementAndGet();
                return false;
            }

            // 6. Add to cache
            PendingTransaction pendingTx = new PendingTransaction(tx);
            txCache.put(txHash, pendingTx);

            // 7. Update nonce index
            accountNonceMap.computeIfAbsent(tx.getFrom(), k -> new TreeMap<>())
                    .put(tx.getNonce(), txHash);

            totalAdded.incrementAndGet();

            log.info("Added transaction {} to pool (from={}, nonce={}, amount={}, fee={})",
                    txHash.toHexString().substring(0, 16),
                    tx.getFrom().toHexString().substring(0, 8),
                    tx.getNonce(),
                    tx.getAmount().toDecimal(9, XUnit.XDAG),
                    tx.getFee().toDecimal(9, XUnit.XDAG));

            return true;

        } finally {
            poolLock.writeLock().unlock();
        }
    }

    @Override
    public boolean removeTransaction(Bytes32 txHash) {
        poolLock.writeLock().lock();
        try {
            PendingTransaction pendingTx = txCache.getIfPresent(txHash);
            if (pendingTx == null) {
                return false;
            }

            Transaction tx = pendingTx.getTransaction();

            // Remove from cache
            txCache.invalidate(txHash);

            // Remove from nonce index
            TreeMap<Long, Bytes32> accountTxs = accountNonceMap.get(tx.getFrom());
            if (accountTxs != null) {
                accountTxs.remove(tx.getNonce());
                if (accountTxs.isEmpty()) {
                    accountNonceMap.remove(tx.getFrom());
                }
            }

            log.debug("Removed transaction {} from pool (from={}, nonce={})",
                    txHash.toHexString().substring(0, 16),
                    tx.getFrom().toHexString().substring(0, 8),
                    tx.getNonce());

            return true;

        } finally {
            poolLock.writeLock().unlock();
        }
    }

    @Override
    public int removeTransactions(List<Bytes32> txHashes) {
        int removed = 0;
        for (Bytes32 txHash : txHashes) {
            if (removeTransaction(txHash)) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int removeTransactionsByAccount(Bytes address) {
        poolLock.writeLock().lock();
        try {
            TreeMap<Long, Bytes32> accountTxs = accountNonceMap.remove(address);
            if (accountTxs == null) {
                return 0;
            }

            int removed = 0;
            for (Bytes32 txHash : accountTxs.values()) {
                txCache.invalidate(txHash);
                removed++;
            }

            log.info("Removed {} transactions from account {}",
                    removed, address.toHexString().substring(0, 16));

            return removed;

        } finally {
            poolLock.writeLock().unlock();
        }
    }

    @Override
    public boolean contains(Bytes32 txHash) {
        poolLock.readLock().lock();
        try {
            return txCache.getIfPresent(txHash) != null;
        } finally {
            poolLock.readLock().unlock();
        }
    }

    @Override
    public Transaction getTransaction(Bytes32 txHash) {
        poolLock.readLock().lock();
        try {
            PendingTransaction pendingTx = txCache.getIfPresent(txHash);
            return pendingTx != null ? pendingTx.getTransaction() : null;
        } finally {
            poolLock.readLock().unlock();
        }
    }

    @Override
    public List<Transaction> selectTransactions(int maxCount) {
        poolLock.readLock().lock();
        try {
            // Get snapshot of all pending transactions
            Map<Bytes32, PendingTransaction> snapshot = txCache.asMap();

            // Sort by: 1. Fee (desc), 2. Entry time (asc)
            return snapshot.values().stream()
                    .sorted(new TransactionComparator())
                    .limit(maxCount)
                    .map(PendingTransaction::getTransaction)
                    .collect(Collectors.toList());

        } finally {
            poolLock.readLock().unlock();
        }
    }

    @Override
    public List<Transaction> getTransactionsByAccount(Bytes address) {
        poolLock.readLock().lock();
        try {
            TreeMap<Long, Bytes32> accountTxs = accountNonceMap.get(address);
            if (accountTxs == null) {
                return new ArrayList<>();
            }

            List<Transaction> result = new ArrayList<>();
            for (Bytes32 txHash : accountTxs.values()) {
                PendingTransaction pendingTx = txCache.getIfPresent(txHash);
                if (pendingTx != null) {
                    result.add(pendingTx.getTransaction());
                }
            }
            return result;

        } finally {
            poolLock.readLock().unlock();
        }
    }

  @Override
    public int size() {
        return (int) txCache.estimatedSize();
    }

    @Override
    public void clear() {
        poolLock.writeLock().lock();
        try {
            txCache.invalidateAll();
            accountNonceMap.clear();
            log.warn("Transaction pool cleared");
        } finally {
            poolLock.writeLock().unlock();
        }
    }

    @Override
    public PoolStatistics getStatistics() {
        var stats = txCache.stats();
        return new PoolStatistics(
                size(),
                totalAdded.get(),
                totalRemoved.get(),
                totalRejected.get(),
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate());
    }

    // ========== Validation Methods ==========

    /**
     * Basic validation - signature, format, fee.
     */
    private boolean validateBasic(Transaction tx) {
        // Check signature validity
        // Note: Full signature validation is expensive, we do basic checks here
        if (tx.getV() < 0) {
            log.debug("Transaction {} rejected: invalid signature v value",
                    tx.getHash().toHexString().substring(0, 16));
            return false;
        }

        // Check minimum fee
        if (tx.getFee().compareTo(config.getMinFee()) < 0) {
            log.debug("Transaction {} rejected: fee too low (have: {}, min: {})",
                    tx.getHash().toHexString().substring(0, 16),
                    tx.getFee().toDecimal(9, XUnit.XDAG),
                    config.getMinFee().toDecimal(9, XUnit.XDAG));
            return false;
        }

        // Check amounts are positive
        if (tx.getAmount().isNegative() || tx.getFee().isNegative()) {
            log.debug("Transaction {} rejected: negative amount or fee",
                    tx.getHash().toHexString().substring(0, 16));
            return false;
        }

        return true;
    }

    /**
     * Check if nonce can be accepted (strict continuity).
     */
    private boolean canAcceptNonce(Bytes from, long nonce) {
        long expectedNonce = getExpectedNonce(from);
        return nonce == expectedNonce;
    }

    /**
     * Get expected next nonce for an account.
     */
    private long getExpectedNonce(Bytes from) {
        UInt64 accountNonce = accountManager.getNonce(from);
        TreeMap<Long, Bytes32> accountTxs = accountNonceMap.get(from);

        if (accountTxs == null || accountTxs.isEmpty()) {
            // No pending transactions, expect account nonce + 1
            return accountNonce.toLong() + 1;
        }

        // Have pending transactions, expect max pool nonce + 1
        return accountTxs.lastKey() + 1;
    }

    /**
     * Check if account has sufficient balance for transaction.
     */
    private boolean hasSufficientBalance(Transaction tx) {
        UInt256 balance = accountManager.getBalance(tx.getFrom());
        XAmount required = tx.getAmount().add(tx.getFee());

        // Convert XAmount to UInt256 (nano units)
        UInt256 requiredUInt256 = UInt256.valueOf(required.toDecimal(0, NANO_XDAG).longValue());

        return balance.compareTo(requiredUInt256) >= 0;
    }

    // ========== Transaction Comparator ==========

    /**
     * Comparator for transaction selection priority.
     * Order: 1. Fee (desc), 2. Entry time (asc)
     */
    private static class TransactionComparator implements Comparator<PendingTransaction> {
        @Override
        public int compare(PendingTransaction pt1, PendingTransaction pt2) {
            Transaction tx1 = pt1.getTransaction();
            Transaction tx2 = pt2.getTransaction();

            // 1. Compare fee (higher fee first)
            int feeComparison = tx2.getFee().compareTo(tx1.getFee());
            if (feeComparison != 0) {
                return feeComparison;
            }

            // 2. Compare entry time (older first)
            return Long.compare(pt1.getEntryTime(), pt2.getEntryTime());
        }
    }
}
