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

package io.xdag.core.consensus;

import io.xdag.core.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Consensus algorithm test suite - Priority P1 (High Priority)
 *
 * <p>Tests advanced consensus scenarios:
 * <ul>
 *   <li>2.1 Simple fork resolution</li>
 *   <li>5.1 Network partition and recovery</li>
 * </ul>
 *
 * @since Phase 12.5+ Consensus Testing
 */
@Slf4j
public class ConsensusTestP1 {

    private MultiNodeTestEnvironment env;

    @Before
    public void setUp() throws Exception {
        env = new MultiNodeTestEnvironment();
        log.info("\n\n========== Starting P1 Consensus Test ==========\n");
    }

    @After
    public void tearDown() {
        if (env != null) {
            env.close();
        }
        log.info("\n========== Test Complete ==========\n\n");
    }

    // ==================== Test 2.1: Simple Fork Resolution ====================

    /**
     * Test 2.1: Simple Fork Resolution - Distinct Chain Selection
     *
     * <p>Scenario: Node A has shorter distinct chain, receives longer distinct chain from Node B
     * <p>Expected: Chain reorganization - adopts chain with higher cumulative difficulty
     *
     * <p>XDAG Consensus Rule (from XDAG_ORIGINAL_DESIGN_ANALYSIS.md):
     * <ul>
     *   <li><b>Main chain = distinct chain with maximum difficulty</b></li>
     *   <li><b>Distinct chain</b>: every block belongs to separate 64-second epoch</li>
     *   <li>Difficulty of chain = sum of difficulties of blocks</li>
     *   <li>Difficulty of block = 1/hash (smaller hash = higher difficulty)</li>
     * </ul>
     *
     * <p>This test verifies:
     * <ul>
     *   <li>Node A builds short distinct chain (2 blocks in epochs 1-2)</li>
     *   <li>Node B builds longer distinct chain (3 blocks in epochs 1-2-3)</li>
     *   <li>Both chains compete in epochs 1-2 (same epochs)</li>
     *   <li>Node B's blocks have smaller hashes → win epoch competition → higher cumulative difficulty</li>
     *   <li>When Node A receives B's chain, it reorganizes to the longer/higher-difficulty chain</li>
     * </ul>
     *
     * <p>Priority: P1 (High Priority)
     */
    @Test
    public void testSimpleForkResolution() throws Exception {
        log.info("\n========== Test 2.1: Simple Fork Resolution (Distinct Chain Selection) ==========\n");

        // Step 1: Create 2 nodes
        MultiNodeTestEnvironment.TestNode nodeA = env.createNode("NodeA");
        MultiNodeTestEnvironment.TestNode nodeB = env.createNode("NodeB");

        log.info("Step 1: Created 2 nodes (A, B)");

        // Step 2: Both nodes start from same genesis
        Block genesis = nodeA.getDagChain().getMainBlockByHeight(1);
        long genesisTimestamp = nodeA.getConfig().getXdagEra();

        assertNotNull("NodeA should have genesis block", genesis);
        log.info("\nStep 2: Both nodes have same genesis");
        log.info("  Genesis hash: {}", genesis.getHash().toHexString());

        // Step 3: Node A builds short distinct chain (2 blocks in epochs 1-2)
        log.info("\nStep 3: Node A builds short distinct chain (2 blocks in epochs 1-2)");

        List<Block> chainA = new ArrayList<>();
        chainA.add(genesis);

        // Node A's blocks have LARGE hashes (will lose epoch competition)
        for (int i = 1; i <= 2; i++) {
            long timestamp = genesisTimestamp + (i * 64);  // Epochs 1, 2

            Block block = generateBlockWithLargeHash(timestamp, chainA.get(i - 1).getHash());

            DagImportResult result = nodeA.getDagChain().tryToConnect(block);
            assertTrue("Node A should import its own block", result.isMainBlock());

            chainA.add(block);
            log.info("  Node A block {} (epoch={}): {} (LARGE hash, low difficulty)",
                    i, timestamp / 64, block.getHash().toHexString().substring(0, 16));
        }

        // Step 4: Node B builds longer distinct chain (3 blocks in epochs 1, 2, 3)
        log.info("\nStep 4: Node B builds longer distinct chain (3 blocks in epochs 1, 2, 3)");

        List<Block> chainB = new ArrayList<>();
        chainB.add(genesis);

        // Node B's blocks have SMALL hashes (will win epoch competition AND have higher cumulative difficulty)
        for (int i = 1; i <= 3; i++) {
            long timestamp = genesisTimestamp + (i * 64);  // Epochs 1, 2, 3

            Block block = generateBlockWithSmallHash(timestamp, chainB.get(i - 1).getHash());

            DagImportResult result = nodeB.getDagChain().tryToConnect(block);
            assertTrue("Node B should import its own block", result.isMainBlock());

            chainB.add(block);
            log.info("  Node B block {} (epoch={}): {} (SMALL hash, high difficulty)",
                    i, timestamp / 64, block.getHash().toHexString().substring(0, 16));
        }

        log.info("\n  Verification: Both chains are distinct");
        log.info("    Chain A: epochs 0, 1, 2 (3 blocks total)");
        log.info("    Chain B: epochs 0, 1, 2, 3 (4 blocks total)");
        log.info("    B is longer AND has higher cumulative difficulty (smaller hashes)");

        // Step 5: Record Node A's initial state
        ChainStats statsA_before = nodeA.getDagChain().getChainStats();
        log.info("\nStep 5: Node A state before receiving chain B");
        log.info("  Main block count: {}", statsA_before.getMainBlockCount());
        log.info("  Max difficulty: {}", statsA_before.getMaxDifficulty().toDecimalString());

        // Step 6: Node A receives all blocks from chain B
        log.info("\nStep 6: Node A receives chain B blocks (triggers epoch competition and reorganization)");

        for (int i = 1; i < chainB.size(); i++) {
            DagImportResult result = nodeA.getDagChain().tryToConnect(chainB.get(i));
            log.info("  Received B{} (epoch={}): {} -> {}",
                    i, chainB.get(i).getEpoch(),
                    chainB.get(i).getHash().toHexString().substring(0, 16),
                    result.getStatus());
        }

        // Step 7: Verification - Chain reorganization
        log.info("\nStep 7: Verification - Node A reorganized to B's longer/higher-difficulty chain");

        ChainStats statsA_after = nodeA.getDagChain().getChainStats();
        ChainStats statsB = nodeB.getDagChain().getChainStats();

        log.info("\n  Node A state after reorganization:");
        log.info("    Main block count: {}", statsA_after.getMainBlockCount());
        log.info("    Max difficulty: {}", statsA_after.getMaxDifficulty().toDecimalString());

        log.info("\n  Node B state:");
        log.info("    Main block count: {}", statsB.getMainBlockCount());
        log.info("    Max difficulty: {}", statsB.getMaxDifficulty().toDecimalString());

        // 7.1 Node A should have switched to chain B (longer chain)
        assertEquals("Node A should have same main block count as Node B",
                statsB.getMainBlockCount(), statsA_after.getMainBlockCount());

        assertEquals("Node A should have same max difficulty as Node B",
                statsB.getMaxDifficulty(), statsA_after.getMaxDifficulty());

        // 7.2 Verify chain A blocks became orphans (height=0)
        log.info("\n  Verifying chain A blocks are orphans:");

        for (int i = 1; i < chainA.size(); i++) {
            Block blockA = nodeA.getDagKernel().getDagStore().getBlockByHash(chainA.get(i).getHash(), false);
            assertNotNull("Chain A block " + i + " should exist", blockA);

            long height = blockA.getInfo().getHeight();
            log.info("    Chain A block {} (epoch={}): height={}", i, blockA.getEpoch(), height);

            assertEquals("Chain A block " + i + " should be orphan (lost epoch competition)",
                    0L, height);
        }

        // 7.3 Verify chain B blocks are on main chain
        log.info("\n  Verifying chain B blocks are on main chain:");

        for (int i = 1; i < chainB.size(); i++) {
            Block blockB = nodeA.getDagKernel().getDagStore().getBlockByHash(chainB.get(i).getHash(), false);
            assertNotNull("Chain B block " + i + " should exist", blockB);

            long height = blockB.getInfo().getHeight();
            log.info("    Chain B block {} (epoch={}): height={}", i, blockB.getEpoch(), height);

            assertTrue("Chain B block " + i + " should be on main chain (won epoch competition)",
                    height > 0);
        }

        // 7.4 Verify main chain path matches chain B (distinct chain rule)
        log.info("\n  Verifying main chain is a distinct chain matching chain B:");

        for (long pos = 1; pos <= statsA_after.getMainBlockCount(); pos++) {
            Block blockOnA = nodeA.getDagChain().getMainBlockByHeight(pos);
            Block blockOnB = nodeB.getDagChain().getMainBlockByHeight(pos);

            assertNotNull("Position " + pos + " should have block on Node A", blockOnA);
            assertNotNull("Position " + pos + " should have block on Node B", blockOnB);

            assertEquals("Block at height " + pos + " should match on both nodes",
                    blockOnB.getHash(), blockOnA.getHash());

            log.info("    Position {} (epoch={}): {}... ✓", pos, blockOnA.getEpoch(),
                    blockOnA.getHash().toHexString().substring(0, 16));
        }

        // 7.5 Verify distinct chain property
        log.info("\n  Verifying distinct chain property (each block in separate 64s epoch):");
        Set<Long> epochs = new HashSet<>();
        for (long pos = 1; pos <= statsA_after.getMainBlockCount(); pos++) {
            Block block = nodeA.getDagChain().getMainBlockByHeight(pos);
            long epoch = block.getEpoch();

            assertFalse("Epoch " + epoch + " should appear only once in main chain (distinct chain rule)",
                    epochs.contains(epoch));
            epochs.add(epoch);

            log.info("    Position {}: epoch={} ✓", pos, epoch);
        }

        log.info("\n✓ Chain reorganization successful");
        log.info("✓ Node A adopted B's longer distinct chain with higher cumulative difficulty");
        log.info("✓ All blocks in main chain belong to separate epochs (distinct chain)");
        log.info("\n========== Test 2.1 PASSED ==========\n");
    }

    // ==================== Test 5.1: Network Partition and Recovery ====================

    /**
     * Test 5.1: Network Partition and Recovery
     *
     * <p>Scenario: Network splits into 2 partitions, grows independently in SAME epochs, then reconnects
     * <p>Expected: After recovery, all nodes converge on the partition with higher cumulative difficulty
     *
     * <p>XDAG Consensus Rule:
     * <ul>
     *   <li>Both partitions build distinct chains competing in SAME epochs (3, 4, 5)</li>
     *   <li>Partition 1 has LARGE hashes → low cumulative difficulty</li>
     *   <li>Partition 2 has SMALL hashes → high cumulative difficulty</li>
     *   <li>After recovery, Partition 2 wins epoch competition → all nodes converge</li>
     * </ul>
     *
     * <p>Priority: P1 (High Priority)
     */
    @Test
    public void testNetworkPartitionAndRecovery() throws Exception {
        log.info("\n========== Test 5.1: Network Partition and Recovery ==========\n");

        // Step 1: Create 4 nodes (2 in each partition)
        MultiNodeTestEnvironment.TestNode nodeA = env.createNode("NodeA");
        MultiNodeTestEnvironment.TestNode nodeB = env.createNode("NodeB");
        MultiNodeTestEnvironment.TestNode nodeC = env.createNode("NodeC");
        MultiNodeTestEnvironment.TestNode nodeD = env.createNode("NodeD");

        log.info("Step 1: Created 4 nodes");
        log.info("  Partition 1: Node A, Node B (will have LARGE hashes)");
        log.info("  Partition 2: Node C, Node D (will have SMALL hashes)");

        // Step 2: All nodes start from same genesis
        Block genesis = nodeA.getDagChain().getMainBlockByHeight(1);
        long genesisTimestamp = nodeA.getConfig().getXdagEra();

        log.info("\nStep 2: All nodes share same genesis");
        log.info("  Genesis hash: {}", genesis.getHash().toHexString());

        // Step 3: Build common chain (2 blocks in epochs 1, 2)
        log.info("\nStep 3: Building common chain (2 blocks in epochs 1, 2)");

        List<Block> commonChain = new ArrayList<>();
        commonChain.add(genesis);

        for (int i = 1; i <= 2; i++) {
            long timestamp = genesisTimestamp + (i * 64);
            Bytes coinbase = Bytes.random(20);  // 20-byte Ethereum-style address
            List<Link> links = List.of(Link.toBlock(commonChain.get(i - 1).getHash()));

            Block block = Block.createWithNonce(
                    timestamp,
                    UInt256.MAX_VALUE,
                    Bytes32.ZERO,
                    coinbase,
                    links
            );

            // All nodes import common blocks
            nodeA.getDagChain().tryToConnect(block);
            nodeB.getDagChain().tryToConnect(block);
            nodeC.getDagChain().tryToConnect(block);
            nodeD.getDagChain().tryToConnect(block);

            commonChain.add(block);
            log.info("  Common block {} (epoch={}): {}...", i, timestamp / 64,
                    block.getHash().toHexString().substring(0, 16));
        }

        log.info("  All nodes at mainBlockCount: {}", commonChain.size() - 1);

        // Step 4: Network partition - Partition 1 builds 3 blocks in epochs 3, 4, 5 with LARGE hashes
        log.info("\nStep 4: Network partition - Partition 1 builds 3 blocks (epochs 3-5, LARGE hashes)");

        List<Block> partition1Chain = new ArrayList<>(commonChain);

        for (int i = 1; i <= 3; i++) {
            long timestamp = genesisTimestamp + ((2 + i) * 64);  // Epochs 3, 4, 5

            // Generate block with LARGE hash (low difficulty)
            Block block = generateBlockWithLargeHash(timestamp,
                    partition1Chain.get(partition1Chain.size() - 1).getHash());

            // Only partition 1 receives these blocks
            nodeA.getDagChain().tryToConnect(block);
            nodeB.getDagChain().tryToConnect(block);

            partition1Chain.add(block);
            log.info("  Partition 1 block {} (epoch={}): {}... (LARGE hash, low difficulty)",
                    i, timestamp / 64, block.getHash().toHexString().substring(0, 16));
        }

        // Step 5: Partition 2 builds 3 blocks in SAME epochs (3, 4, 5) with SMALL hashes
        log.info("\nStep 5: Partition 2 builds 3 blocks (epochs 3-5, SMALL hashes - COMPETING)");

        List<Block> partition2Chain = new ArrayList<>(commonChain);

        for (int i = 1; i <= 3; i++) {
            long timestamp = genesisTimestamp + ((2 + i) * 64);  // Epochs 3, 4, 5 (SAME as Partition 1)

            // Generate block with SMALL hash (high difficulty)
            Block block = generateBlockWithSmallHash(timestamp,
                    partition2Chain.get(partition2Chain.size() - 1).getHash());

            // Only partition 2 receives these blocks
            nodeC.getDagChain().tryToConnect(block);
            nodeD.getDagChain().tryToConnect(block);

            partition2Chain.add(block);
            log.info("  Partition 2 block {} (epoch={}): {}... (SMALL hash, high difficulty)",
                    i, timestamp / 64, block.getHash().toHexString().substring(0, 16));
        }

        log.info("\n  Verification: Both partitions built distinct chains competing in SAME epochs");
        log.info("    Partition 1: epochs 0, 1, 2, 3, 4, 5 (6 blocks, LARGE hashes)");
        log.info("    Partition 2: epochs 0, 1, 2, 3, 4, 5 (6 blocks, SMALL hashes)");
        log.info("    Partition 2 will win epoch competition (smaller hashes)");

        // Step 6: Verify partition states before recovery
        log.info("\nStep 6: Partition states before recovery");

        ChainStats statsA = nodeA.getDagChain().getChainStats();
        ChainStats statsC = nodeC.getDagChain().getChainStats();

        log.info("  Partition 1 (A,B): mainBlockCount={}, maxDiff={}",
                statsA.getMainBlockCount(), statsA.getMaxDifficulty().toDecimalString());
        log.info("  Partition 2 (C,D): mainBlockCount={}, maxDiff={}",
                statsC.getMainBlockCount(), statsC.getMaxDifficulty().toDecimalString());

        // Both partitions should have same chain length (same number of epochs)
        // genesis(1) + 2 common blocks(2,3) + 3 partition blocks(4,5,6) = 6 total
        assertEquals("Both partitions should have same main block count before recovery",
                6L, statsA.getMainBlockCount());
        assertEquals("Both partitions should have same main block count before recovery",
                6L, statsC.getMainBlockCount());

        // Step 7: Network recovery - partitions exchange blocks
        log.info("\nStep 7: Network recovery - partitions reconnect and exchange blocks");

        // Partition 1 (A, B) receives Partition 2's blocks (epochs 3, 4, 5)
        log.info("  Partition 1 receives Partition 2 blocks...");
        for (int i = 3; i < partition2Chain.size(); i++) {
            DagImportResult resultA = nodeA.getDagChain().tryToConnect(partition2Chain.get(i));
            DagImportResult resultB = nodeB.getDagChain().tryToConnect(partition2Chain.get(i));
            log.debug("    P2 block {} → A:{}, B:{}",
                    i - 2, resultA.getStatus(), resultB.getStatus());
        }

        // Partition 2 (C, D) receives Partition 1's blocks (epochs 3, 4, 5)
        log.info("  Partition 2 receives Partition 1 blocks...");
        for (int i = 3; i < partition1Chain.size(); i++) {
            DagImportResult resultC = nodeC.getDagChain().tryToConnect(partition1Chain.get(i));
            DagImportResult resultD = nodeD.getDagChain().tryToConnect(partition1Chain.get(i));
            log.debug("    P1 block {} → C:{}, D:{}",
                    i - 2, resultC.getStatus(), resultD.getStatus());
        }

        // Step 8: Verification - All nodes converge to Partition 2's chain
        log.info("\nStep 8: Verification after recovery");

        ChainStats statsA_after = nodeA.getDagChain().getChainStats();
        ChainStats statsB_after = nodeB.getDagChain().getChainStats();
        ChainStats statsC_after = nodeC.getDagChain().getChainStats();
        ChainStats statsD_after = nodeD.getDagChain().getChainStats();

        log.info("\n  Final states:");
        log.info("    Node A: mainBlockCount={}, maxDiff={}",
                statsA_after.getMainBlockCount(), statsA_after.getMaxDifficulty().toDecimalString());
        log.info("    Node B: mainBlockCount={}, maxDiff={}",
                statsB_after.getMainBlockCount(), statsB_after.getMaxDifficulty().toDecimalString());
        log.info("    Node C: mainBlockCount={}, maxDiff={}",
                statsC_after.getMainBlockCount(), statsC_after.getMaxDifficulty().toDecimalString());
        log.info("    Node D: mainBlockCount={}, maxDiff={}",
                statsD_after.getMainBlockCount(), statsD_after.getMaxDifficulty().toDecimalString());

        // 8.1 All nodes should have same main block count (genesis + 2 common + 3 partition2 blocks)
        assertEquals("All nodes should converge on same main block count",
                6L, statsA_after.getMainBlockCount());
        assertEquals("All nodes should converge on same main block count",
                6L, statsB_after.getMainBlockCount());
        assertEquals("All nodes should converge on same main block count",
                6L, statsC_after.getMainBlockCount());
        assertEquals("All nodes should converge on same main block count",
                6L, statsD_after.getMainBlockCount());

        // 8.2 All nodes should have same max difficulty
        assertEquals("All nodes should have same max difficulty",
                statsC_after.getMaxDifficulty(), statsA_after.getMaxDifficulty());
        assertEquals("All nodes should have same max difficulty",
                statsC_after.getMaxDifficulty(), statsB_after.getMaxDifficulty());
        assertEquals("All nodes should have same max difficulty",
                statsC_after.getMaxDifficulty(), statsD_after.getMaxDifficulty());

        // 8.3 Verify all nodes chose partition 2's blocks (smaller hashes win epoch competition)
        log.info("\n  Verifying all nodes adopted Partition 2's blocks:");

        for (long pos = 1; pos <= statsA_after.getMainBlockCount(); pos++) {
            Block blockA = nodeA.getDagChain().getMainBlockByHeight(pos);
            Block blockB = nodeB.getDagChain().getMainBlockByHeight(pos);
            Block blockC = nodeC.getDagChain().getMainBlockByHeight(pos);
            Block blockD = nodeD.getDagChain().getMainBlockByHeight(pos);

            assertNotNull("Position " + pos + " should exist on all nodes", blockA);
            assertNotNull("Position " + pos + " should exist on all nodes", blockB);
            assertNotNull("Position " + pos + " should exist on all nodes", blockC);
            assertNotNull("Position " + pos + " should exist on all nodes", blockD);

            assertEquals("Position " + pos + " should match across all nodes",
                    blockC.getHash(), blockA.getHash());
            assertEquals("Position " + pos + " should match across all nodes",
                    blockC.getHash(), blockB.getHash());
            assertEquals("Position " + pos + " should match across all nodes",
                    blockC.getHash(), blockD.getHash());

            log.info("    Position {} (epoch={}): {}... ✓", pos, blockA.getEpoch(),
                    blockA.getHash().toHexString().substring(0, 16));
        }

        // 8.4 Verify partition 1's blocks in epochs 3-5 became orphans (lost epoch competition)
        log.info("\n  Verifying Partition 1 blocks became orphans:");

        for (int i = 3; i < partition1Chain.size(); i++) {
            Block blockOnA = nodeA.getDagKernel().getDagStore().getBlockByHash(
                    partition1Chain.get(i).getHash(), false);

            assertNotNull("Partition 1 block should exist on Node A", blockOnA);
            assertEquals("Partition 1 block should be orphan (lost epoch competition)",
                    0L, blockOnA.getInfo().getHeight());

            log.info("    P1 block {} (epoch={}): height=0 (orphan) ✓",
                    i - 2, partition1Chain.get(i).getEpoch());
        }

        // 8.5 Verify partition 2's blocks in epochs 3-5 are on main chain
        log.info("\n  Verifying Partition 2 blocks are on main chain:");

        for (int i = 3; i < partition2Chain.size(); i++) {
            Block blockOnA = nodeA.getDagKernel().getDagStore().getBlockByHash(
                    partition2Chain.get(i).getHash(), false);

            assertNotNull("Partition 2 block should exist on Node A", blockOnA);
            assertTrue("Partition 2 block should be on main chain (won epoch competition)",
                    blockOnA.getInfo().getHeight() > 0);

            log.info("    P2 block {} (epoch={}): height={} (main chain) ✓",
                    i - 2, partition2Chain.get(i).getEpoch(), blockOnA.getInfo().getHeight());
        }

        log.info("\n✓ Network partition recovery successful");
        log.info("✓ All nodes converged on Partition 2's higher-difficulty chain");
        log.info("✓ Epoch competition correctly resolved (smaller hashes won)");
        log.info("\n========== Test 5.1 PASSED ==========\n");
    }

    // ==================== Helper Methods ====================

    /**
     * Generate a block with a "small" hash (starts with 0x0...)
     *
     * <p>Strategy: Try different random coinbases until finding one that produces
     * a hash starting with 0x00 or 0x01 (statistically small hash)
     *
     * @param timestamp block timestamp
     * @param parentHash parent block hash
     * @return block with small hash
     */
    private Block generateBlockWithSmallHash(long timestamp, Bytes32 parentHash) {
        List<Link> links = List.of(Link.toBlock(parentHash));

        // Try up to 1000 random coinbases
        Block bestBlock = null;
        Bytes32 smallestHash = null;

        for (int attempt = 0; attempt < 1000; attempt++) {
            Bytes coinbase = Bytes.random(20);  // 20-byte Ethereum-style address

            Block block = Block.createWithNonce(
                    timestamp,
                    UInt256.MAX_VALUE,
                    Bytes32.ZERO,
                    coinbase,
                    links
            );

            Bytes32 hash = block.getHash();

            // Check if hash starts with 0x00 or 0x01 (first byte < 0x02)
            if ((hash.get(0) & 0xFF) < 0x02) {
                log.debug("Found small hash on attempt {}: {}", attempt,
                        hash.toHexString().substring(0, 16));
                return block;
            }

            // Keep track of smallest hash found so far
            if (smallestHash == null || hash.compareTo(smallestHash) < 0) {
                smallestHash = hash;
                bestBlock = block;
            }
        }

        // If we didn't find a hash starting with 0x00/0x01, return the smallest one found
        log.warn("Could not find hash starting with 0x00/0x01 after 1000 attempts, using smallest found: {}",
                smallestHash.toHexString().substring(0, 16));
        return bestBlock;
    }

    /**
     * Generate a block with a "large" hash (starts with 0xF...)
     *
     * <p>Strategy: Try different random coinbases until finding one that produces
     * a hash starting with 0xFE or 0xFF (statistically large hash)
     *
     * @param timestamp block timestamp
     * @param parentHash parent block hash
     * @return block with large hash
     */
    private Block generateBlockWithLargeHash(long timestamp, Bytes32 parentHash) {
        List<Link> links = List.of(Link.toBlock(parentHash));

        // Try up to 1000 random coinbases
        Block bestBlock = null;
        Bytes32 largestHash = null;

        for (int attempt = 0; attempt < 1000; attempt++) {
            Bytes coinbase = Bytes.random(20);  // 20-byte Ethereum-style address

            Block block = Block.createWithNonce(
                    timestamp,
                    UInt256.MAX_VALUE,
                    Bytes32.ZERO,
                    coinbase,
                    links
            );

            Bytes32 hash = block.getHash();

            // Check if hash starts with 0xFE or 0xFF (first byte >= 0xFE)
            if ((hash.get(0) & 0xFF) >= 0xFE) {
                log.debug("Found large hash on attempt {}: {}", attempt,
                        hash.toHexString().substring(0, 16));
                return block;
            }

            // Keep track of largest hash found so far
            if (largestHash == null || hash.compareTo(largestHash) > 0) {
                largestHash = hash;
                bestBlock = block;
            }
        }

        // If we didn't find a hash starting with 0xFE/0xFF, return the largest one found
        log.warn("Could not find hash starting with 0xFE/0xFF after 1000 attempts, using largest found: {}",
                largestHash.toHexString().substring(0, 16));
        return bestBlock;
    }
}
