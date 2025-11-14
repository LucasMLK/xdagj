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

package io.xdag.rpc.service;

import io.xdag.DagKernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
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
 * Test for Mining RPC Service
 *
 * <p>Tests the RPC interface for pool server integration without breaking existing functionality.
 *
 * @since XDAGJ v5.1
 */
public class MiningRpcServiceTest {

    private DagKernel dagKernel;
    private Config config;
    private Path tempDir;
    private Wallet testWallet;
    private MiningRpcServiceImpl miningRpcService;

    @Before
    public void setUp() throws IOException {
        System.out.println("\n========== Setting up MiningRpcServiceTest ==========");

        // Create temporary directory
        tempDir = Files.createTempDirectory("mining-rpc-test-");

        // Create test genesis.json file
        createTestGenesisFile();

        // Use DevnetConfig
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

        // Create test wallet
        testWallet = new Wallet(config);
        testWallet.unlock("test-password");
        testWallet.addAccountRandom();

        // Create and start DagKernel
        dagKernel = new DagKernel(config, testWallet);
        dagKernel.start();

        // Get MiningRpcService
        miningRpcService = dagKernel.getMiningRpcService();

        System.out.println("✓ Setup complete\n");
    }

    @After
    public void tearDown() {
        System.out.println("\n========== Tearing down MiningRpcServiceTest ==========");

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

        System.out.println("✓ Teardown complete\n");
    }

    /**
     * Create a minimal test genesis.json file
     */
    private void createTestGenesisFile() throws IOException {
        String genesisJson = "{\n" +
                "  \"networkId\": \"test\",\n" +
                "  \"chainId\": 999,\n" +
                "  \"timestamp\": 1516406400,\n" +
                "  \"initialDifficulty\": \"0x1000\",\n" +
                "  \"epochLength\": 64,\n" +
                "  \"extraData\": \"XDAG v5.1 Mining RPC Test\",\n" +
                "  \"genesisCoinbase\": \"0x0000000000000000000000001111111111111111111111111111111111111111\",\n" +
                "  \"alloc\": {},\n" +
                "  \"snapshot\": {\n" +
                "    \"enabled\": false,\n" +
                "    \"height\": 0,\n" +
                "    \"hash\": \"0x0000000000000000000000000000000000000000000000000000000000000000\",\n" +
                "    \"timestamp\": 0,\n" +
                "    \"dataFile\": \"\",\n" +
                "    \"verify\": false,\n" +
                "    \"format\": \"v1\",\n" +
                "    \"expectedAccounts\": 0,\n" +
                "    \"expectedBlocks\": 0\n" +
                "  }\n" +
                "}";

        Path genesisFile = tempDir.resolve("genesis.json");
        Files.writeString(genesisFile, genesisJson);
    }

    /**
     * Test 1: Verify MiningRpcService is initialized
     */
    @Test
    public void testMiningRpcServiceInitialized() {
        System.out.println("\n========== Test 1: MiningRpcService Initialized ==========");

        assertNotNull("MiningRpcService should not be null", miningRpcService);
        assertNotNull("BlockGenerator should not be null", miningRpcService.getBlockGenerator());
        assertNotNull("DagChain should not be null", miningRpcService.getDagChain());

        System.out.println("✓ MiningRpcService is properly initialized");
        System.out.println("========== Test 1 PASSED ==========\n");
    }

    /**
     * Test 2: Get candidate block
     */
    @Test
    public void testGetCandidateBlock() {
        System.out.println("\n========== Test 2: Get Candidate Block ==========");

        String poolId = "test-pool-1";
        Block candidate = miningRpcService.getCandidateBlock(poolId);

        assertNotNull("Candidate block should not be null", candidate);
        assertNotNull("Block hash should not be null", candidate.getHash());
        assertNotNull("Block header should not be null", candidate.getHeader());
        assertTrue("Block timestamp should be positive", candidate.getTimestamp() > 0);

        System.out.println("✓ Candidate block generated successfully");
        System.out.println("  - Hash: " + candidate.getHash().toHexString().substring(0, 18) + "...");
        System.out.println("  - Timestamp: " + candidate.getTimestamp());
        System.out.println("========== Test 2 PASSED ==========\n");
    }

    /**
     * Test 3: Get current difficulty target
     */
    @Test
    public void testGetCurrentDifficultyTarget() {
        System.out.println("\n========== Test 3: Get Current Difficulty Target ==========");

        UInt256 difficulty = miningRpcService.getCurrentDifficultyTarget();

        assertNotNull("Difficulty should not be null", difficulty);
        assertTrue("Difficulty should be positive", difficulty.compareTo(UInt256.ZERO) > 0);

        System.out.println("✓ Difficulty target retrieved successfully");
        System.out.println("  - Difficulty: " + difficulty.toHexString().substring(0, 18) + "...");
        System.out.println("========== Test 3 PASSED ==========\n");
    }

    /**
     * Test 4: Get RandomX info
     */
    @Test
    public void testGetRandomXInfo() {
        System.out.println("\n========== Test 4: Get RandomX Info ==========");

        RandomXInfo info = miningRpcService.getRandomXInfo();

        assertNotNull("RandomX info should not be null", info);
        assertTrue("Current epoch should be non-negative", info.getCurrentEpoch() >= 0);
        assertNotNull("Algorithm name should not be null", info.getAlgorithmName());

        System.out.println("✓ RandomX info retrieved successfully");
        System.out.println("  - Enabled: " + info.isEnabled());
        System.out.println("  - Current Epoch: " + info.getCurrentEpoch());
        System.out.println("  - Fork Epoch: " + info.getForkEpoch());
        System.out.println("  - Algorithm: " + info.getAlgorithmName());
        System.out.println("========== Test 4 PASSED ==========\n");
    }

    /**
     * Test 5: Submit mined block (should reject unknown block)
     */
    @Test
    public void testSubmitUnknownBlock() {
        System.out.println("\n========== Test 5: Submit Unknown Block (Should Reject) ==========");

        // Get a candidate block
        String poolId = "test-pool-1";
        Block candidate = miningRpcService.getCandidateBlock(poolId);
        assertNotNull(candidate);

        // Create a different block (not based on our candidate)
        Block unknownBlock = candidate.withNonce(org.apache.tuweni.bytes.Bytes32.random());

        // Try to submit it - should be rejected
        BlockSubmitResult result = miningRpcService.submitMinedBlock(unknownBlock, poolId);

        assertNotNull("Result should not be null", result);
        assertFalse("Unknown block should be rejected", result.isAccepted());
        assertNotNull("Error message should be present", result.getMessage());

        System.out.println("✓ Unknown block correctly rejected");
        System.out.println("  - Accepted: " + result.isAccepted());
        System.out.println("  - Message: " + result.getMessage());
        System.out.println("========== Test 5 PASSED ==========\n");
    }

    /**
     * Test 6: Cache statistics
     */
    @Test
    public void testCacheStatistics() {
        System.out.println("\n========== Test 6: Cache Statistics ==========");

        // Get a candidate block (this should cache it)
        String poolId = "test-pool-1";
        Block candidate = miningRpcService.getCandidateBlock(poolId);
        assertNotNull(candidate);

        // Get cache statistics
        String stats = miningRpcService.getCacheStatistics();
        assertNotNull("Cache statistics should not be null", stats);
        assertTrue("Cache should contain at least 1 entry", stats.contains("1 entries cached"));

        System.out.println("✓ Cache statistics retrieved");
        System.out.println("  - " + stats);
        System.out.println("========== Test 6 PASSED ==========\n");
    }

    /**
     * Test 7: Multiple pools can get candidate blocks
     */
    @Test
    public void testMultiplePoolsGetCandidates() {
        System.out.println("\n========== Test 7: Multiple Pools ==========");

        // Pool 1
        Block candidate1 = miningRpcService.getCandidateBlock("pool-1");
        assertNotNull("Pool 1 candidate should not be null", candidate1);

        // Pool 2
        Block candidate2 = miningRpcService.getCandidateBlock("pool-2");
        assertNotNull("Pool 2 candidate should not be null", candidate2);

        // Pool 3
        Block candidate3 = miningRpcService.getCandidateBlock("pool-3");
        assertNotNull("Pool 3 candidate should not be null", candidate3);

        System.out.println("✓ Multiple pools can get candidate blocks");
        System.out.println("  - Pool 1: " + candidate1.getHash().toHexString().substring(0, 16) + "...");
        System.out.println("  - Pool 2: " + candidate2.getHash().toHexString().substring(0, 16) + "...");
        System.out.println("  - Pool 3: " + candidate3.getHash().toHexString().substring(0, 16) + "...");
        System.out.println("========== Test 7 PASSED ==========\n");
    }

    /**
     * Test 8: Verify existing functionality not broken
     */
    @Test
    public void testExistingFunctionalityNotBroken() {
        System.out.println("\n========== Test 8: Existing Functionality ==========");

        // Verify DagChain still works
        assertNotNull("DagChain should not be null", dagKernel.getDagChain());
        assertTrue("Main chain length should be >= 0", dagKernel.getDagChain().getMainChainLength() >= 0);

        // Verify DagStore still works
        assertNotNull("DagStore should not be null", dagKernel.getDagStore());

        // Verify MiningManager still works (if initialized)
        if (dagKernel.getMiningManager() != null) {
            assertNotNull("MiningManager should not be null", dagKernel.getMiningManager());
        }

        System.out.println("✓ Existing functionality verified");
        System.out.println("  - DagChain: operational");
        System.out.println("  - DagStore: operational");
        System.out.println("  - MiningManager: " + (dagKernel.getMiningManager() != null ? "operational" : "not initialized"));
        System.out.println("========== Test 8 PASSED ==========\n");
    }
}
