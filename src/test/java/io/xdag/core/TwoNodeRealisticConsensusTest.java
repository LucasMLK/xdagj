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
import io.xdag.utils.TimeUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
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
 * Realistic two-node consensus integration test using actual DagKernel and DagChainImpl.
 *
 * <p>This test validates consensus consistency between two nodes by:
 * <ul>
 *   <li>Using real {@link DagKernel} instances with separate RocksDB stores</li>
 *   <li>Using real {@link DagChainImpl} for consensus logic</li>
 *   <li>Using real {@link BlockImporter} for block validation</li>
 *   <li>Simulating P2P sync by directly exchanging blocks</li>
 * </ul>
 *
 * <p>Unlike {@link TwoNodeConsensusSimulationTest} which uses simplified in-memory structures,
 * this test exercises the actual production code paths.
 *
 * <p><b>Test Scenarios:</b>
 * <ol>
 *   <li>Both nodes produce blocks for the same epochs</li>
 *   <li>Blocks are exchanged (simulating P2P sync)</li>
 *   <li>Both nodes must agree on epoch winners (smallest hash wins)</li>
 *   <li>Both nodes must have identical main chains</li>
 * </ol>
 *
 * @since XDAGJ 5.1
 */
public class TwoNodeRealisticConsensusTest {

    private static final int NUM_EPOCHS = 100;

    private DagKernel node1Kernel;
    private DagKernel node2Kernel;
    private DagChainImpl node1Chain;
    private DagChainImpl node2Chain;
    private Path tempDir1;
    private Path tempDir2;
    private long genesisEpoch;

    @Before
    public void setUp() throws IOException {
        // Use epoch from 150 epochs ago to allow generating blocks without hitting future
        genesisEpoch = TimeUtils.getCurrentEpochNumber() - 150;

        // Create temporary directories for both nodes
        tempDir1 = Files.createTempDirectory("xdagj-realistic-node1-");
        tempDir2 = Files.createTempDirectory("xdagj-realistic-node2-");

        // Create genesis files with same epoch for both nodes
        createGenesisFile(tempDir1, genesisEpoch);
        createGenesisFile(tempDir2, genesisEpoch);

        // Initialize Node1
        Config config1 = createConfig(tempDir1);
        Wallet wallet1 = new Wallet(config1);
        wallet1.unlock("test-password");
        wallet1.addAccountRandom();
        node1Kernel = new DagKernel(config1, wallet1);
        node1Kernel.setP2pEnabled(false);
        node1Kernel.start();
        node1Chain = (DagChainImpl) node1Kernel.getDagChain();

        // Initialize Node2
        Config config2 = createConfig(tempDir2);
        Wallet wallet2 = new Wallet(config2);
        wallet2.unlock("test-password");
        wallet2.addAccountRandom();
        node2Kernel = new DagKernel(config2, wallet2);
        node2Kernel.setP2pEnabled(false);
        node2Kernel.start();
        node2Chain = (DagChainImpl) node2Kernel.getDagChain();

        // Set mining coinbase for both nodes
        node1Chain.setMiningCoinbase(Bytes.wrap(new byte[20]));
        node2Chain.setMiningCoinbase(Bytes.wrap(new byte[20]));
    }

    @After
    public void tearDown() {
        stopKernel(node1Kernel);
        stopKernel(node2Kernel);
        deleteDirectory(tempDir1);
        deleteDirectory(tempDir2);
    }

    /**
     * Test that two nodes reach consensus over multiple epochs.
     *
     * <p>Both nodes produce blocks for each epoch, exchange them, and should
     * agree on the epoch winner (smallest hash) for every epoch.
     */
    @Test
    public void testTwoNodesReachConsensus() {
        System.out.println("\n========== Realistic Two-Node Consensus Test ==========");
        System.out.println("Testing " + NUM_EPOCHS + " epochs with real DagKernel/DagChainImpl");

        // Both nodes initialized from same genesis file, so they share the same genesis

        long startHeight1 = node1Chain.getMainChainLength();
        long startHeight2 = node2Chain.getMainChainLength();
        System.out.println("Node1 starting height: " + startHeight1);
        System.out.println("Node2 starting height: " + startHeight2);

        int consensusMatches = 0;
        int consensusMismatches = 0;

        // Run simulation for NUM_EPOCHS epochs
        for (int i = 1; i <= NUM_EPOCHS; i++) {
            long epoch = genesisEpoch + i;

            // Both nodes produce blocks for this epoch
            Block block1 = createBlockForEpoch(node1Chain, epoch);
            Block block2 = createBlockForEpoch(node2Chain, epoch);

            // Import own blocks first
            DagImportResult result1Self = node1Chain.tryToConnect(block1);
            DagImportResult result2Self = node2Chain.tryToConnect(block2);

            // Exchange blocks (simulate P2P sync)
            DagImportResult result1Remote = node1Chain.tryToConnect(block2);
            DagImportResult result2Remote = node2Chain.tryToConnect(block1);

            // Log exchange failures
            if (!result1Remote.isSuccess()) {
                System.out.println("Epoch " + epoch + ": Node1 failed to import Node2's block: " +
                    result1Remote.getStatus() + " - " + result1Remote.getErrorMessage());
            }
            if (!result2Remote.isSuccess()) {
                System.out.println("Epoch " + epoch + ": Node2 failed to import Node1's block: " +
                    result2Remote.getStatus() + " - " + result2Remote.getErrorMessage());
            }

            // Get the winner for this epoch on both nodes
            Block winner1 = getEpochWinner(node1Chain, epoch);
            Block winner2 = getEpochWinner(node2Chain, epoch);

            if (winner1 != null && winner2 != null) {
                if (winner1.getHash().equals(winner2.getHash())) {
                    consensusMatches++;
                } else {
                    consensusMismatches++;
                    System.out.println("MISMATCH at epoch " + epoch + ":");
                    System.out.println("  Node1 winner: " + winner1.getHash().toHexString().substring(0, 16));
                    System.out.println("  Node2 winner: " + winner2.getHash().toHexString().substring(0, 16));
                }
            }

            // Progress report every 20 epochs
            if (i % 20 == 0) {
                System.out.println("Progress: " + i + "/" + NUM_EPOCHS + " epochs processed, " +
                        consensusMatches + " matches, " + consensusMismatches + " mismatches");
            }
        }

        // Final verification
        System.out.println("\n========== Final Verification ==========");

        long finalHeight1 = node1Chain.getMainChainLength();
        long finalHeight2 = node2Chain.getMainChainLength();

        System.out.println("Node1 final height: " + finalHeight1);
        System.out.println("Node2 final height: " + finalHeight2);
        System.out.println("Consensus matches: " + consensusMatches);
        System.out.println("Consensus mismatches: " + consensusMismatches);

        // Verify heights match (only check the epochs we tested)
        // Note: EpochConsensusManager may create additional blocks for current epoch
        long expectedHeight = NUM_EPOCHS + 1;  // genesis + NUM_EPOCHS
        assertTrue("Node1 should have at least expected height",
                finalHeight1 >= expectedHeight);
        assertTrue("Node2 should have at least expected height",
                finalHeight2 >= expectedHeight);

        // Verify all main blocks match (only up to the epochs we tested)
        int blockMatches = 0;
        int blockMismatches = 0;
        for (long h = 1; h <= expectedHeight; h++) {
            Block b1 = node1Chain.getMainBlockByHeight(h);
            Block b2 = node2Chain.getMainBlockByHeight(h);

            if (b1 != null && b2 != null && b1.getHash().equals(b2.getHash())) {
                blockMatches++;
            } else {
                blockMismatches++;
                System.out.println("Block mismatch at height " + h + ":");
                if (b1 != null) {
                    System.out.println("  Node1: epoch=" + b1.getEpoch() + ", hash=" + b1.getHash().toHexString().substring(0, 16));
                    // Check all blocks in this epoch on Node1
                    List<Bytes32> node1EpochHashes = node1Chain.getBlockHashesByEpoch(b1.getEpoch());
                    System.out.println("    Node1 has " + node1EpochHashes.size() + " blocks in epoch " + b1.getEpoch());
                } else {
                    System.out.println("  Node1: NULL");
                }
                if (b2 != null) {
                    System.out.println("  Node2: epoch=" + b2.getEpoch() + ", hash=" + b2.getHash().toHexString().substring(0, 16));
                    // Check all blocks in this epoch on Node2
                    List<Bytes32> node2EpochHashes = node2Chain.getBlockHashesByEpoch(b2.getEpoch());
                    System.out.println("    Node2 has " + node2EpochHashes.size() + " blocks in epoch " + b2.getEpoch());
                } else {
                    System.out.println("  Node2: NULL");
                }
                // Print detailed comparison
                if (b1 != null && b2 != null && b1.getEpoch() == b2.getEpoch()) {
                    // Same epoch but different winner - check smallest hash rule
                    System.out.println("  Same epoch different winner! Checking smallest hash rule:");
                    int cmp = b1.getHash().compareTo(b2.getHash());
                    if (cmp < 0) {
                        System.out.println("    Node1 hash is SMALLER (should win)");
                    } else if (cmp > 0) {
                        System.out.println("    Node2 hash is SMALLER (should win)");
                    }
                }
            }
        }

        System.out.println("Block matches: " + blockMatches + "/" + expectedHeight);
        System.out.println("Block mismatches: " + blockMismatches);

        assertEquals("All main blocks should match between nodes", 0, blockMismatches);
        assertEquals("All epochs should reach consensus", 0, consensusMismatches);

        System.out.println("\n========== SUCCESS: Both nodes reached consensus ==========\n");
    }

    /**
     * Test network partition and recovery scenario.
     *
     * <p>Scenario:
     * <ol>
     *   <li>Both nodes sync normally for 30 epochs</li>
     *   <li>Network partition: nodes produce independently for 20 epochs</li>
     *   <li>Network heals: nodes exchange all blocks</li>
     *   <li>Both nodes should converge to the same chain</li>
     * </ol>
     */
    @Test
    public void testNetworkPartitionAndRecovery() {
        System.out.println("\n========== Network Partition Recovery Test ==========");

        // Phase 1: Normal sync for 30 epochs
        System.out.println("Phase 1: Normal sync for 30 epochs...");
        for (int i = 1; i <= 30; i++) {
            long epoch = genesisEpoch + i;
            Block block1 = createBlockForEpoch(node1Chain, epoch);
            Block block2 = createBlockForEpoch(node2Chain, epoch);

            node1Chain.tryToConnect(block1);
            node2Chain.tryToConnect(block2);
            node1Chain.tryToConnect(block2);
            node2Chain.tryToConnect(block1);
        }

        long heightAfterPhase1_node1 = node1Chain.getMainChainLength();
        long heightAfterPhase1_node2 = node2Chain.getMainChainLength();
        System.out.println("After Phase 1 - Node1: " + heightAfterPhase1_node1 + ", Node2: " + heightAfterPhase1_node2);

        assertEquals("Nodes should be in sync after Phase 1",
                heightAfterPhase1_node1, heightAfterPhase1_node2);

        // Phase 2: Network partition - nodes produce independently
        System.out.println("Phase 2: Network partition - 20 epochs of independent production...");
        List<Block> node1Blocks = new ArrayList<>();
        List<Block> node2Blocks = new ArrayList<>();

        for (int i = 31; i <= 50; i++) {
            long epoch = genesisEpoch + i;
            Block block1 = createBlockForEpoch(node1Chain, epoch);
            Block block2 = createBlockForEpoch(node2Chain, epoch);

            node1Chain.tryToConnect(block1);
            node2Chain.tryToConnect(block2);

            node1Blocks.add(block1);
            node2Blocks.add(block2);
        }

        long heightAfterPhase2_node1 = node1Chain.getMainChainLength();
        long heightAfterPhase2_node2 = node2Chain.getMainChainLength();
        System.out.println("After Phase 2 (partition) - Node1: " + heightAfterPhase2_node1 + ", Node2: " + heightAfterPhase2_node2);

        // Phase 3: Network heals - exchange all blocks
        System.out.println("Phase 3: Network heals - exchanging " + node1Blocks.size() + " blocks...");
        for (Block block : node1Blocks) {
            node2Chain.tryToConnect(block);
        }
        for (Block block : node2Blocks) {
            node1Chain.tryToConnect(block);
        }

        // Final verification
        long finalHeight1 = node1Chain.getMainChainLength();
        long finalHeight2 = node2Chain.getMainChainLength();
        System.out.println("After Phase 3 (recovery) - Node1: " + finalHeight1 + ", Node2: " + finalHeight2);

        // Only check up to the epochs we tested (50 + genesis = 51)
        long expectedHeight = 51;
        assertTrue("Node1 should have at least expected height",
                finalHeight1 >= expectedHeight);
        assertTrue("Node2 should have at least expected height",
                finalHeight2 >= expectedHeight);

        // Verify block-by-block consistency (only up to epochs we tested)
        int mismatches = 0;
        for (long h = 1; h <= expectedHeight; h++) {
            Block b1 = node1Chain.getMainBlockByHeight(h);
            Block b2 = node2Chain.getMainBlockByHeight(h);

            if (b1 == null || b2 == null || !b1.getHash().equals(b2.getHash())) {
                mismatches++;
                if (b1 != null && b2 != null) {
                    System.out.println("Mismatch at height " + h + ": Node1=" + b1.getHash().toHexString().substring(0, 16) +
                            ", Node2=" + b2.getHash().toHexString().substring(0, 16));
                }
            }
        }

        assertEquals("All blocks should match after partition recovery", 0, mismatches);
        System.out.println("\n========== SUCCESS: Nodes recovered from partition ==========\n");
    }

    /**
     * Test that smallest hash always wins epoch competition.
     *
     * <p>This test specifically verifies the core consensus rule:
     * when two blocks compete for the same epoch, the one with
     * the smaller hash wins.
     */
    @Test
    public void testSmallestHashWinsEpochCompetition() {
        System.out.println("\n========== Smallest Hash Wins Test ==========");

        long testEpoch = genesisEpoch + 1;

        // Create multiple blocks for the same epoch
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Block block = createBlockForEpoch(node1Chain, testEpoch);
            blocks.add(block);
            node1Chain.tryToConnect(block);
            node2Chain.tryToConnect(block);
        }

        // Find the block with smallest hash
        Block expectedWinner = blocks.stream()
                .min(Comparator.comparing(Block::getHash))
                .orElse(null);

        assertNotNull("Should have at least one block", expectedWinner);

        // Get the actual winner from both nodes
        Block winner1 = getEpochWinner(node1Chain, testEpoch);
        Block winner2 = getEpochWinner(node2Chain, testEpoch);

        System.out.println("Expected winner (smallest hash): " + expectedWinner.getHash().toHexString().substring(0, 16));
        System.out.println("Node1 winner: " + (winner1 != null ? winner1.getHash().toHexString().substring(0, 16) : "null"));
        System.out.println("Node2 winner: " + (winner2 != null ? winner2.getHash().toHexString().substring(0, 16) : "null"));

        // Both nodes should agree
        assertNotNull("Node1 should have a winner", winner1);
        assertNotNull("Node2 should have a winner", winner2);
        assertEquals("Both nodes should agree on winner", winner1.getHash(), winner2.getHash());

        // The winner should have the smallest hash
        assertEquals("Winner should have smallest hash",
                expectedWinner.getHash(), winner1.getHash());

        System.out.println("\n========== SUCCESS: Smallest hash wins ==========\n");
    }

    /**
     * Test storage consistency after block import.
     *
     * <p>Verifies that RocksDB storage is correctly updated:
     * <ul>
     *   <li>Block can be retrieved by hash</li>
     *   <li>Block can be retrieved by height</li>
     *   <li>Block can be retrieved by epoch</li>
     *   <li>Chain stats are updated correctly</li>
     * </ul>
     */
    @Test
    public void testStorageConsistency() {
        System.out.println("\n========== Storage Consistency Test ==========");

        long testEpoch = genesisEpoch + 1;
        Block block = createBlockForEpoch(node1Chain, testEpoch);

        // Import block
        DagImportResult result = node1Chain.tryToConnect(block);
        System.out.println("Block import result: " + result.getStatus());

        if (result.isMainBlock()) {
            Bytes32 blockHash = block.getHash();

            // Test retrieval by hash
            Block byHash = node1Chain.getBlockByHash(blockHash);
            assertNotNull("Block should be retrievable by hash", byHash);
            assertEquals("Retrieved block should match original", blockHash, byHash.getHash());

            // Test retrieval by height
            long height = byHash.getInfo().getHeight();
            if (height > 0) {
                Block byHeight = node1Chain.getMainBlockByHeight(height);
                assertNotNull("Block should be retrievable by height", byHeight);
                assertEquals("Retrieved block should match", blockHash, byHeight.getHash());
            }

            // Test retrieval by epoch
            List<Bytes32> epochHashes = node1Chain.getBlockHashesByEpoch(testEpoch);
            assertTrue("Epoch should contain the block",
                    epochHashes.stream().anyMatch(h -> h.equals(blockHash)));

            // Test chain stats
            ChainStats stats = node1Chain.getChainStats();
            assertTrue("Main block count should be positive", stats.getMainBlockCount() > 0);

            System.out.println("Storage consistency verified:");
            System.out.println("  Block hash: " + blockHash.toHexString().substring(0, 16));
            System.out.println("  Block height: " + height);
            System.out.println("  Block epoch: " + testEpoch);
            System.out.println("  Chain length: " + stats.getMainBlockCount());
        }

        System.out.println("\n========== SUCCESS: Storage is consistent ==========\n");
    }

    // ==================== Helper Methods ====================

    /**
     * Create a block for a specific epoch that links to genesis only.
     *
     * <p>This simplified approach ensures both nodes can import each other's blocks
     * without needing full chain synchronization. In production, blocks would
     * link to recent main blocks, requiring proper sync.
     */
    private Block createBlockForEpoch(DagChain chain, long epoch) {
        // Link to genesis block only (shared by both nodes)
        // This allows cross-node import without missing dependencies
        Block genesis = chain.getMainBlockByHeight(1);
        List<Link> links = new ArrayList<>();
        if (genesis != null) {
            links.add(Link.toBlock(genesis.getHash()));
        }

        // Get difficulty from chain stats
        UInt256 difficulty = chain.getChainStats().getBaseDifficultyTarget();

        // Create block
        return Block.createWithNonce(
                epoch,
                difficulty,
                Bytes32.random(),  // random nonce (devnet accepts any PoW)
                Bytes.wrap(new byte[20]),  // coinbase
                links);
    }

    private Block getEpochWinner(DagChain chain, long epoch) {
        List<Bytes32> hashes = chain.getBlockHashesByEpoch(epoch);
        if (hashes.isEmpty()) {
            return null;
        }

        // Find the block with height > 0 (the main block)
        for (Bytes32 hash : hashes) {
            Block block = chain.getBlockByHash(hash);
            if (block != null && block.getInfo().getHeight() > 0) {
                return block;
            }
        }

        return null;
    }

    private Config createConfig(Path dir) {
        return new DevnetConfig() {
            @Override
            public String getStoreDir() {
                return dir.toString();
            }

            @Override
            public String getRootDir() {
                return dir.toString();
            }
        };
    }

    private void createGenesisFile(Path dir, long epoch) throws IOException {
        String genesisJson = "{\n" +
                "  \"networkId\": \"test\",\n" +
                "  \"chainId\": 999,\n" +
                "  \"epoch\": " + epoch + ",\n" +
                "  \"difficulty\": \"0x1\",\n" +
                "  \"randomXSeed\": \"0x0000000000000000000000000000000000000000000000000000000000000001\",\n" +
                "  \"alloc\": {}\n" +
                "}";

        Path genesisFile = dir.resolve("genesis-devnet.json");
        Files.writeString(genesisFile, genesisJson);
    }

    private void stopKernel(DagKernel kernel) {
        if (kernel != null) {
            try {
                kernel.stop();
            } catch (Exception e) {
                System.err.println("Error stopping kernel: " + e.getMessage());
            }
        }
    }

    private void deleteDirectory(Path dir) {
        if (dir != null && Files.exists(dir)) {
            try {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(file -> {
                                if (!file.delete()) {
                                    System.err.println("Failed to delete: " + file);
                                }
                            });
                }
            } catch (Exception e) {
                System.err.println("Error deleting directory: " + e.getMessage());
            }
        }
    }
}
