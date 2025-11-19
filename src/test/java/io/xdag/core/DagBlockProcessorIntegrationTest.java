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
import io.xdag.utils.XdagTime;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.*;

/**
 * Integration test for DagBlockProcessor v5.1
 *
 * <p>Tests block processing functionality with REAL components (not mocks).
 *
 * <p>Phase 10 Integration Test - Block Processing Layer
 *
 * @since @since XDAGJ
 */
public class DagBlockProcessorIntegrationTest {

    private DagKernel dagKernel;
    private Config config;
    private Path tempDir;
    private Wallet testWallet;
    private DagBlockProcessor blockProcessor;
    private DagAccountManager accountManager;

    @Before
    public void setUp() throws IOException {
        // Create unique temporary directory for each test
        tempDir = Files.createTempDirectory("dagblockprocessor-test-");

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

        // Create real DagKernel with wallet (not mocked)
        dagKernel = new DagKernel(config, testWallet);
        dagKernel.start();

        // Get components
        blockProcessor = dagKernel.getDagBlockProcessor();
        accountManager = dagKernel.getDagAccountManager();
    }

    @After
    public void tearDown() {
        // Cleanup: stop DagKernel
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
     * Test 1: DagBlockProcessor availability
     *
     * <p>Verify that DagBlockProcessor is properly initialized.
     */
    @Test
    public void testDagBlockProcessorAvailability() {
        assertNotNull("DagBlockProcessor should be available", blockProcessor);
    }

    /**
     * Test 2: Block existence check
     *
     * <p>Verify hasBlock() works correctly.
     */
    @Test
    public void testBlockExistenceCheck() {
        // Random block hash should not exist
        org.apache.tuweni.bytes.Bytes32 randomHash = Bytes32.random();
        assertFalse("Random block should not exist", blockProcessor.hasBlock(randomHash));
    }

    /**
     * Test 3: Get non-existent block
     *
     * <p>Verify getBlock() returns null for non-existent blocks.
     */
    @Test
    public void testGetNonExistentBlock() {
        Bytes32 randomHash = Bytes32.random();
        Block block = blockProcessor.getBlock(randomHash, false);
        assertNull("Non-existent block should return null", block);
    }

    /**
     * Test 4: Process null block
     *
     * <p>Verify that processing null block fails gracefully.
     */
    @Test
    public void testProcessNullBlock() {
        DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(null);

        assertFalse("Processing null block should fail", result.isSuccess());
        assertTrue("Error should be returned", result.isError());
        assertNotNull("Error message should not be null", result.getError());
        assertTrue("Error should mention invalid structure",
                result.getError().contains("Invalid block structure"));
    }

    /**
     * Test 5: Process block with invalid header (null timestamp)
     *
     * <p>Verify that processing block with invalid header fails.
     */
    @Test
    public void testProcessBlockWithInvalidHeader() {
        // Create block with invalid timestamp (0)
        BlockHeader invalidHeader = BlockHeader.builder()
                .epoch(0)  // Invalid: must be > 0
                .difficulty(org.apache.tuweni.units.bigints.UInt256.ONE)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.random(20))
                .build();

        Block block = Block.builder()
                .header(invalidHeader)
                .info(BlockInfo.builder()
                        .height(1L)
                        .difficulty(org.apache.tuweni.units.bigints.UInt256.ONE)
                        .build())
                .build();

        DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);

        assertFalse("Processing block with invalid header should fail", result.isSuccess());
        assertTrue("Error should mention invalid structure",
                result.getError().contains("Invalid block structure"));
    }

    /**
     * Test 6: Process block with null info
     *
     * <p>Verify that processing block with null info fails.
     */
    @Test
    public void testProcessBlockWithNullInfo() {
        // Create valid header
        BlockHeader header = BlockHeader.builder()
                .epoch(System.currentTimeMillis())
                .difficulty(org.apache.tuweni.units.bigints.UInt256.ONE)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.random(20))
                .build();

        // Create block with null info
        Block block = Block.builder()
                .header(header)
                .info(null)
                .build();

        DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);

        assertFalse("Processing block with null info should fail", result.isSuccess());
        assertTrue("Error should mention invalid structure",
                result.getError().contains("Invalid block structure"));
    }

    /**
     * Test 7: Process block with invalid timestamp
     *
     * <p>Verify that processing block with invalid timestamp fails.
     */
    @Test
    public void testProcessBlockWithInvalidTimestamp() {
        // Create block with invalid timestamp (0)
        BlockHeader header = BlockHeader.builder()
                .epoch(0L)  // Invalid timestamp
                .difficulty(org.apache.tuweni.units.bigints.UInt256.ONE)
                .nonce(Bytes32.ZERO)
                .coinbase(Bytes.random(20))
                .build();

        // Create block info with valid fields
        BlockInfo info = BlockInfo.builder()
                .hash(Bytes32.random())
                .height(1L)
                .difficulty(UInt256.ONE)
                .epoch(XdagTime.getCurrentEpoch())
                .build();

        Block block = Block.builder()
                .header(header)
                .info(info)
                .build();

        DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);

        assertFalse("Processing block with invalid timestamp should fail", result.isSuccess());
        assertTrue("Error should mention invalid structure",
                result.getError().contains("Invalid block structure"));
    }

    /**
     * Test 8: Process valid block (no transactions)
     *
     * <p>Verify that processing a valid block with no transactions succeeds.
     *
     * <p>Note: extractTransactions() currently returns empty list,
     * so this tests the basic block processing flow.
     */
    @Test
    public void testProcessValidBlockNoTransactions() {
        // Create valid block using factory method
        long timestamp = XdagTime.getMainTime(); // Use XDAG main block time
        Block block = Block.createWithNonce(
                timestamp,
                org.apache.tuweni.units.bigints.UInt256.ONE,
                Bytes32.ZERO,
                Bytes.random(20),  // coinbase
                java.util.List.of()  // Empty links
        );

        // Create valid block info with all required fields
        BlockInfo info = BlockInfo.builder()
                .hash(block.getHash())  // Use actual block hash
                .height(1L)
                .difficulty(UInt256.ONE)
                .epoch(timestamp)
                .build();

        // Attach info to block
        block = block.withInfo(info);

        // Process block
        DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);

        // Verify success
        assertTrue("Processing valid block should succeed: " + result.getError(), result.isSuccess());

        // Verify block was saved
        assertTrue("Block should exist after processing", blockProcessor.hasBlock(block.getHash()));

        // Verify block can be retrieved
        Block retrievedBlock = blockProcessor.getBlock(block.getHash(), false);
        assertNotNull("Retrieved block should not be null", retrievedBlock);
        assertEquals("Retrieved block hash should match", block.getHash(), retrievedBlock.getHash());
    }

    /**
     * Test 9: Process multiple blocks sequentially
     *
     * <p>Verify that multiple blocks can be processed one after another.
     */
    @Test
    public void testProcessMultipleBlocksSequentially() {
        int blockCount = 5;

        for (int i = 0; i < blockCount; i++) {
            // Create block - each in a different epoch to avoid timestamp collision
            long timestamp = XdagTime.getMainTime() + (i * 0x10000L); // Offset by epochs
            Block block = Block.createWithNonce(
                    timestamp,
                    UInt256.ONE,
                    Bytes32.ZERO,
                    Bytes.random(20),
                    java.util.List.of()
            );

            // Create block info with all required fields
            BlockInfo info = BlockInfo.builder()
                    .hash(block.getHash())
                    .height((long) (i + 1))
                    .difficulty(UInt256.ONE)
                    .epoch(timestamp)
                    .build();

            block = block.withInfo(info);

            // Process block
            DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);

            assertTrue("Block " + i + " should be processed successfully", result.isSuccess());
            assertTrue("Block " + i + " should exist after processing",
                    blockProcessor.hasBlock(block.getHash()));
        }

        // Verify all blocks were processed
        // (We don't have a getBlockCount() method, but we verified each individually)
    }

    /**
     * Test 10: Retrieve block with and without links
     *
     * <p>Verify getBlock() works with both withLinks flags.
     */
    @Test
    public void testGetBlockWithAndWithoutLinks() {
        // Create and process a valid block
        long timestamp = XdagTime.getMainTime(); // Use XDAG main block time
        Block block = Block.createWithNonce(
                timestamp,
                UInt256.ONE,
                Bytes32.ZERO,
                Bytes.random(20),
                java.util.List.of()
        );

        BlockInfo info = BlockInfo.builder()
                .hash(block.getHash())
                .height(1L)
                .difficulty(UInt256.ONE)
                .epoch(timestamp)
                .build();

        block = block.withInfo(info);

        // Process block
        DagBlockProcessor.ProcessingResult result = blockProcessor.processBlock(block);
        assertTrue("Block should be processed successfully", result.isSuccess());

        Bytes32 blockHash = block.getHash();

        // Retrieve without links
        Block retrievedWithoutLinks = blockProcessor.getBlock(blockHash, false);
        assertNotNull("Block should be retrievable without links", retrievedWithoutLinks);
        assertEquals("Hash should match", blockHash, retrievedWithoutLinks.getHash());

        // Retrieve with links
        Block retrievedWithLinks = blockProcessor.getBlock(blockHash, true);
        assertNotNull("Block should be retrievable with links", retrievedWithLinks);
        assertEquals("Hash should match", blockHash, retrievedWithLinks.getHash());
    }

    /**
     * Test 11: Process duplicate block
     *
     * <p>Verify that processing the same block twice doesn't cause errors.
     * (DagStore should handle duplicates gracefully)
     */
    @Test
    public void testProcessDuplicateBlock() {
        // Create block
        long timestamp = XdagTime.getMainTime(); // Use XDAG main block time
        Block block = Block.createWithNonce(
                timestamp,
                UInt256.ONE,
                Bytes32.ZERO,
                Bytes.random(20),
                List.of()
        );

        BlockInfo info = BlockInfo.builder()
                .hash(block.getHash())
                .height(1L)
                .difficulty(UInt256.ONE)
                .epoch(timestamp)
                .build();

        block = block.withInfo(info);

        // Process block first time
        DagBlockProcessor.ProcessingResult result1 = blockProcessor.processBlock(block);
        assertTrue("First processing should succeed", result1.isSuccess());

        // Process same block again
        DagBlockProcessor.ProcessingResult result2 = blockProcessor.processBlock(block);

        // Should still succeed (DagStore handles duplicates)
        assertTrue("Second processing should succeed", result2.isSuccess());

        // Block should still exist
        assertTrue("Block should still exist", blockProcessor.hasBlock(block.getHash()));
    }

    /**
     * Test 12: ProcessingResult toString
     *
     * <p>Verify ProcessingResult toString() works correctly.
     */
    @Test
    public void testProcessingResultToString() {
        DagBlockProcessor.ProcessingResult success = DagBlockProcessor.ProcessingResult.success();
        assertTrue("Success toString should contain 'success'",
                success.toString().contains("success"));

        DagBlockProcessor.ProcessingResult error = DagBlockProcessor.ProcessingResult.error("Test error");
        assertTrue("Error toString should contain error message",
                error.toString().contains("Test error"));
    }
}
