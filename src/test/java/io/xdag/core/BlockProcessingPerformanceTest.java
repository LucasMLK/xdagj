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
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.db.TransactionStore;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Performance benchmarking test for block processing
 *
 * <p>Measures throughput and latency for block and transaction processing
 * under various load conditions.
 *
 * <p>Performance targets (from DAGSTORE_CAPACITY_AND_PERFORMANCE.md):
 * - Block import: < 50ms (P99)
 * - Transaction validation: < 10ms (P99)
 * - Account balance read: < 5ms (P99)
 *
 * @since @since XDAGJ
 */
public class BlockProcessingPerformanceTest {

    private DagKernel dagKernel;
    private Config config;
    private Path tempDir;
    private Wallet testWallet;

    // Components
    private DagBlockProcessor blockProcessor;
    private DagAccountManager accountManager;
    private DagTransactionProcessor txProcessor;
    private TransactionStore transactionStore;

    // Test accounts
    private Bytes senderAddress;
    private ECKeyPair senderKey;

    @Before
    public void setUp() throws IOException {
        // Create unique temporary directory
        tempDir = Files.createTempDirectory("perf-test-");

        // Create test genesis.json file
        TestGenesisHelper.createTestGenesisFile(tempDir);

        // Use DevnetConfig with custom database directory
        config = new DevnetConfig() {
            @Override
            public String getStoreDir() {
                return tempDir.toString();
            }

            @Override
            public String getRootDir() {
                return tempDir.toString();
            }
        };

        // Create test wallet with random account
        testWallet = new Wallet(config);
        testWallet.unlock("test-password");
        testWallet.addAccountRandom();

        // Create and start DagKernel with wallet
        dagKernel = new DagKernel(config, testWallet);
        dagKernel.start();

        // Get components
        blockProcessor = dagKernel.getDagBlockProcessor();
        accountManager = dagKernel.getDagAccountManager();
        txProcessor = dagKernel.getDagTransactionProcessor();
        transactionStore = dagKernel.getTransactionStore();

        // Create test account with large balance
        senderKey = ECKeyPair.generate();
        senderAddress = Bytes.wrap(AddressUtils.toBytesAddress(senderKey));
        accountManager.ensureAccountExists(senderAddress);
        accountManager.setBalance(senderAddress, UInt256.valueOf(10_000_000_000_000L)); // 10000 XDAG (enough for all tests)
    }

    @After
    public void tearDown() {
        // Stop DagKernel
        if (dagKernel != null) {
            try {
                dagKernel.stop();
            } catch (Exception e) {
                System.err.println("Error stopping DagKernel: " + e.getMessage());
            }
        }

        // Delete temporary directory
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                try (var walk = Files.walk(tempDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(file -> {
                                if (!file.delete()) {
                                    System.err.println("Failed to delete: " + file);
                                }
                            });
                }
            } catch (Exception e) {
                System.err.println("Error deleting temp directory: " + e.getMessage());
            }
        }
    }

    /**
     * Test 1: Single block processing latency
     *
     * <p>Measures P50, P95, P99 latency for processing single blocks
     * with varying numbers of transactions.
     */
    @Test
    public void testSingleBlockLatency() {
        System.out.println("\n========== Test 1: Single Block Processing Latency ==========");

        int[] txCounts = {1, 5, 10, 20};
        int iterations = 100;

        for (int txCount : txCounts) {
            List<Long> latencies = new ArrayList<>();

            for (int i = 0; i < iterations; i++) {
                // Create block with N transactions
                Block block = createBlockWithTransactions(txCount, i * 100);

                // Measure processing time
                long startTime = System.nanoTime();
                DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);
                long endTime = System.nanoTime();

                if (!result.isSuccess()) {
                    System.err.println("Block processing failed: " + result);
                }
                assertTrue("Block should process successfully. Result: " + result, result.isSuccess());

                long latencyMs = (endTime - startTime) / 1_000_000;
                latencies.add(latencyMs);
            }

            // Calculate statistics
            latencies.sort(Long::compareTo);
            long p50 = latencies.get(iterations / 2);
            long p95 = latencies.get((int) (iterations * 0.95));
            long p99 = latencies.get((int) (iterations * 0.99));
            long avg = latencies.stream().mapToLong(Long::longValue).sum() / iterations;

            System.out.println(String.format("\n%d transactions per block:", txCount));
            System.out.println(String.format("  P50: %d ms", p50));
            System.out.println(String.format("  P95: %d ms", p95));
            System.out.println(String.format("  P99: %d ms", p99));
            System.out.println(String.format("  AVG: %d ms", avg));

            // Performance target: < 50ms for P99
            assertTrue(String.format("P99 latency (%d ms) should be < 50ms for %d tx/block", p99, txCount),
                    p99 < 50);
        }

        System.out.println("\n========== Test 1 PASSED ==========\n");
    }

    /**
     * Test 2: Sequential block processing throughput
     *
     * <p>Measures how many blocks can be processed per second
     * in sequential mode (no parallelism).
     */
    @Test
    public void testSequentialThroughput() {
        System.out.println("\n========== Test 2: Sequential Block Processing Throughput ==========");

        int blockCount = 100;
        int txPerBlock = 5;

        // Warmup
        for (int i = 0; i < 10; i++) {
            Block warmupBlock = createBlockWithTransactions(txPerBlock, i * 100);
            blockProcessor.processBlock(warmupBlock);
        }

        // Measure throughput
        long startTime = System.nanoTime();
        int successCount = 0;

        for (int i = 0; i < blockCount; i++) {
            Block block = createBlockWithTransactions(txPerBlock, i * 100);
            DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);

            if (result.isSuccess()) {
                successCount++;
            }
        }

        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;
        double durationSec = durationMs / 1000.0;

        double blocksPerSec = successCount / durationSec;
        double txPerSec = (successCount * txPerBlock) / durationSec;

        System.out.println("\nResults:");
        System.out.println(String.format("  Blocks processed: %d", successCount));
        System.out.println(String.format("  Total transactions: %d", successCount * txPerBlock));
        System.out.println(String.format("  Duration: %.2f sec", durationSec));
        System.out.println(String.format("  Throughput: %.2f blocks/sec", blocksPerSec));
        System.out.println(String.format("  Throughput: %.2f tx/sec", txPerSec));

        // Performance expectation: > 10 blocks/sec (conservative)
        assertTrue(String.format("Should process > 10 blocks/sec (actual: %.2f)", blocksPerSec),
                blocksPerSec > 10);

        System.out.println("\n========== Test 2 PASSED ==========\n");
    }

    /**
     * Test 3: Account read performance
     *
     * <p>Measures latency for account balance reads
     * (simulating RPC queries).
     */
    @Test
    public void testAccountReadPerformance() {
        System.out.println("\n========== Test 3: Account Read Performance ==========");

        // Create 100 accounts
        int accountCount = 100;
        List<Bytes> addresses = new ArrayList<>();

        for (int i = 0; i < accountCount; i++) {
            Bytes address = Bytes.random(20);
            addresses.add(address);
            accountManager.ensureAccountExists(address);
            accountManager.setBalance(address, UInt256.valueOf(1_000_000_000L));
        }

        // Warmup
        for (int i = 0; i < 100; i++) {
            accountManager.getBalance(addresses.get(i % accountCount));
        }

        // Measure read latency
        int iterations = 1000;
        List<Long> latencies = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            Bytes address = addresses.get(i % accountCount);

            long startTime = System.nanoTime();
            UInt256 balance = accountManager.getBalance(address);
            long endTime = System.nanoTime();

            assertNotNull("Balance should be returned", balance);

            long latencyNs = endTime - startTime;
            latencies.add(latencyNs);
        }

        // Calculate statistics (in microseconds)
        latencies.sort(Long::compareTo);
        long p50Us = latencies.get(iterations / 2) / 1000;
        long p95Us = latencies.get((int) (iterations * 0.95)) / 1000;
        long p99Us = latencies.get((int) (iterations * 0.99)) / 1000;
        long avgUs = latencies.stream().mapToLong(Long::longValue).sum() / iterations / 1000;

        System.out.println("\nAccount balance read latency:");
        System.out.println(String.format("  P50: %d μs", p50Us));
        System.out.println(String.format("  P95: %d μs", p95Us));
        System.out.println(String.format("  P99: %d μs", p99Us));
        System.out.println(String.format("  AVG: %d μs", avgUs));

        // Performance target: < 5ms = 5000μs for P99
        assertTrue(String.format("P99 latency (%d μs) should be < 5000 μs", p99Us),
                p99Us < 5000);

        System.out.println("\n========== Test 3 PASSED ==========\n");
    }

    /**
     * Test 4: Transaction processing performance
     *
     * <p>Measures latency for transaction processing
     * (balance updates, nonce increment).
     */
    @Test
    public void testTransactionProcessingPerformance() {
        System.out.println("\n========== Test 4: Transaction Processing Performance ==========");

        int iterations = 100;
        List<Long> latencies = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            // Create transaction
            Bytes receiver = Bytes.random(20);
            accountManager.ensureAccountExists(receiver);

            XAmount amount = XAmount.of(1, XUnit.XDAG);
            XAmount fee = XAmount.of(100, XUnit.MILLI_XDAG);

            Transaction tx = Transaction.builder()
                    .from(senderAddress)
                    .to(receiver)
                    .amount(amount)
                    .nonce(i)
                    .fee(fee)
                    .build();

            Transaction signedTx = tx.sign(senderKey);

            // Measure processing time
            long startTime = System.nanoTime();
            DagTransactionProcessor.ProcessingResult result =
                    txProcessor.processTransaction(signedTx);
            long endTime = System.nanoTime();

            assertTrue("Transaction should process successfully", result.isSuccess());

            long latencyUs = (endTime - startTime) / 1000;
            latencies.add(latencyUs);
        }

        // Calculate statistics
        latencies.sort(Long::compareTo);
        long p50 = latencies.get(iterations / 2);
        long p95 = latencies.get((int) (iterations * 0.95));
        long p99 = latencies.get((int) (iterations * 0.99));
        long avg = latencies.stream().mapToLong(Long::longValue).sum() / iterations;

        System.out.println("\nTransaction processing latency:");
        System.out.println(String.format("  P50: %d μs", p50));
        System.out.println(String.format("  P95: %d μs", p95));
        System.out.println(String.format("  P99: %d μs", p99));
        System.out.println(String.format("  AVG: %d μs", avg));

        // Performance target: < 10ms = 10000μs for P99
        assertTrue(String.format("P99 latency (%d μs) should be < 10000 μs", p99),
                p99 < 10000);

        System.out.println("\n========== Test 4 PASSED ==========\n");
    }

    // ==================== Helper Methods ====================

    /**
     * Create a block with specified number of transactions
     * Uses current nonce from AccountStore - nonce will be incremented by block processor
     */
    private Block createBlockWithTransactions(int txCount, long unusedNonceOffset) {
        List<Link> links = new ArrayList<>();

        // BUGFIX: Add at least one Block reference (required by Block.isValid())
        // Use a dummy block hash as reference (simulates prevMainBlock)
        links.add(Link.toBlock(Bytes32.random()));

        // Get current sender nonce (will be incremented by block processor after processing)
        UInt64 currentNonce = accountManager.getNonce(senderAddress);
        long baseNonce = currentNonce.toLong();

        // Create and save transactions with sequential nonces
        for (int i = 0; i < txCount; i++) {
            Bytes receiver = Bytes.random(20);
            accountManager.ensureAccountExists(receiver);

            XAmount amount = XAmount.of(1, XUnit.XDAG);
            XAmount fee = XAmount.of(100, XUnit.MILLI_XDAG);

            Transaction tx = Transaction.builder()
                    .from(senderAddress)
                    .to(receiver)
                    .amount(amount)
                    .nonce(baseNonce + i)
                    .fee(fee)
                    .build();

            Transaction signedTx = tx.sign(senderKey);
            transactionStore.saveTransaction(signedTx);

            links.add(Link.toTransaction(signedTx.getHash()));
        }

        // Create block
        // BUGFIX: Use XDAG main block timestamp (lower 16 bits must be 0xffff)
        // This matches C code validation: (time & 0xffff) == 0xffff
        long currentTime = System.currentTimeMillis();
        long timestamp = (currentTime & ~0xffffL) | 0xffff;  // Set lower 16 bits to 0xffff
        Block block = Block.createWithNonce(
                timestamp,
                UInt256.ONE,
                Bytes32.ZERO,
                senderAddress,  // Use 20-byte address for coinbase
                links
        );

        BlockInfo blockInfo = BlockInfo.builder()
                .hash(block.getHash())
                .height(1L)
                .difficulty(UInt256.ONE)
                .epoch(timestamp)
                .build();

        return block.withInfo(blockInfo);
    }
}
