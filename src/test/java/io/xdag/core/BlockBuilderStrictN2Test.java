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
import io.xdag.store.DagStore;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

// DagImportResult is returned by tryToConnect()

/**
 * Unit tests for BlockBuilder's Strict N-2 orphan referencing rule.
 *
 * <p>The Strict N-2 Rule ensures:
 * <ul>
 *   <li>Candidate blocks ONLY reference the main block from N-1 epoch</li>
 *   <li>Candidate blocks reference orphans from N-2 epoch (guaranteed propagation)</li>
 *   <li>Candidate blocks do NOT reference orphans from N-1 epoch (may not have arrived)</li>
 * </ul>
 *
 * <p>RATIONALE: When epoch N ends, all nodes broadcast blocks simultaneously.
 * Due to network latency, N-1's orphans may not have propagated to all nodes yet.
 * By only referencing N-2 orphans (which have had 64+ seconds to propagate),
 * we guarantee all nodes can mine with identical candidate blocks, preventing
 * hashpower waste from missing references.
 *
 * @since XDAGJ 5.1
 */
public class BlockBuilderStrictN2Test {

    private DagKernel dagKernel;
    private DagChainImpl dagChain;
    private DagStore dagStore;
    private Config config;
    private Path tempDir;
    private Wallet testWallet;

    // Epoch used for testing (arbitrary but consistent)
    private static final long BASE_EPOCH = 23694000;

    @Before
    public void setUp() throws IOException {
        // Create unique temporary directory
        tempDir = Files.createTempDirectory("block-builder-n2-test-");

        // Create test genesis.json file
        createTestGenesisFile();

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

        // Create test wallet
        testWallet = new Wallet(config);
        testWallet.unlock("test-password");
        testWallet.addAccountRandom();

        // Create and start DagKernel
        dagKernel = new DagKernel(config, testWallet);
        dagKernel.start();

        // Get instances
        dagChain = (DagChainImpl) dagKernel.getDagChain();
        dagStore = dagKernel.getDagStore();
    }

    private void createTestGenesisFile() throws IOException {
        String genesisJson = "{\n" +
                "  \"networkId\": \"test\",\n" +
                "  \"chainId\": 999,\n" +
                "  \"epoch\": " + BASE_EPOCH + ",\n" +
                "  \"difficulty\": \"0x1\",\n" +
                "  \"randomXSeed\": \"0x0000000000000000000000000000000000000000000000000000000000000001\",\n" +
                "  \"alloc\": {}\n" +
                "}";

        // Create genesis-devnet.json file (DevnetConfig expects this filename)
        Path genesisFile = tempDir.resolve("genesis-devnet.json");
        Files.writeString(genesisFile, genesisJson);
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
     * Test 1: Verify that candidate blocks reference main block from N-1 epoch.
     *
     * <p>When creating a candidate for height H, it should include the main block
     * from height H-1 as a parent reference.
     */
    @Test
    public void testStrictN2_ReferencesMainBlockFromN1() {
        System.out.println("\n========== Test 1: References Main Block from N-1 ==========");

        // Genesis block is at height 1
        ChainStats stats = dagChain.getChainStats();
        Block genesisBlock = dagStore.getMainBlockByHeight(1);
        assertNotNull("Genesis block should exist", genesisBlock);

        System.out.println("Genesis block: epoch=" + genesisBlock.getEpoch() +
                ", hash=" + genesisBlock.getHash().toHexString().substring(0, 16));

        // Create a candidate block
        Block candidate = dagChain.createCandidateBlock();
        assertNotNull("Candidate block should be created", candidate);

        // Verify that candidate references the genesis block (N-1's main block)
        List<Link> links = candidate.getLinks();
        assertFalse("Candidate should have at least one link", links.isEmpty());

        boolean referencesGenesis = links.stream()
                .filter(Link::isBlock)
                .anyMatch(link -> link.getTargetHash().equals(genesisBlock.getHash()));

        assertTrue("Candidate should reference the N-1 main block (genesis)",
                referencesGenesis);

        System.out.println("Candidate references genesis: " + referencesGenesis);
        System.out.println("Candidate links: " + links.size());

        System.out.println("========== Test 1 PASSED ==========\n");
    }

    /**
     * Test 2: Verify that candidate blocks do NOT reference orphans from N-1 epoch.
     *
     * <p>This is the key test for the Strict N-2 rule. Even if orphans exist in
     * the N-1 epoch, they should NOT be included in the candidate block because
     * they may not have propagated to all nodes yet.
     */
    @Test
    public void testStrictN2_DoesNotReferenceOrphansFromN1() throws Exception {
        System.out.println("\n========== Test 2: Does NOT Reference Orphans from N-1 ==========");

        // Get genesis
        Block genesisBlock = dagStore.getMainBlockByHeight(1);
        long genesisEpoch = genesisBlock.getEpoch();

        // Create and import a block for epoch N-1 (will become main)
        long epochN1 = genesisEpoch + 1;
        Block mainBlockN1 = createTestBlock(epochN1, genesisBlock.getHash(), Bytes.wrap(new byte[20]));
        DagImportResult result1 = dagChain.tryToConnect(mainBlockN1);
        System.out.println("Main block N-1 import: " + result1);

        // Create and import an orphan block for epoch N-1 (different coinbase = different hash)
        Block orphanBlockN1 = createTestBlock(epochN1, genesisBlock.getHash(),
                Bytes.fromHexString("0x1111111111111111111111111111111111111111"));
        DagImportResult result2 = dagChain.tryToConnect(orphanBlockN1);
        System.out.println("Orphan block N-1 import: " + result2);

        // Verify both blocks are in the same epoch
        assertEquals("Both blocks should be in epoch N-1", epochN1, mainBlockN1.getEpoch());
        assertEquals("Both blocks should be in epoch N-1", epochN1, orphanBlockN1.getEpoch());

        // Create candidate block
        ChainStats stats = dagChain.getChainStats();
        Block candidate = dagChain.createCandidateBlock();
        assertNotNull("Candidate should be created", candidate);

        // Collect all referenced block hashes
        Set<Bytes32> referencedHashes = new HashSet<>();
        for (Link link : candidate.getLinks()) {
            if (link.isBlock()) {
                referencedHashes.add(link.getTargetHash());
            }
        }

        System.out.println("Main block N-1: " + mainBlockN1.getHash().toHexString().substring(0, 16));
        System.out.println("Orphan block N-1: " + orphanBlockN1.getHash().toHexString().substring(0, 16));
        System.out.println("Referenced hashes: " + referencedHashes.size());

        // The main block from N-1 should be referenced (as parent)
        // But we need to check the height to see which one became main
        Block currentMain = dagStore.getMainBlockByHeight(2);
        if (currentMain != null) {
            System.out.println("Current main at height 2: " + currentMain.getHash().toHexString().substring(0, 16));

            // The main block should be referenced
            assertTrue("Main block should be referenced",
                    referencedHashes.contains(currentMain.getHash()));

            // Determine which block is orphan (the one that's NOT main)
            Bytes32 orphanHash;
            if (currentMain.getHash().equals(mainBlockN1.getHash())) {
                orphanHash = orphanBlockN1.getHash();
            } else {
                orphanHash = mainBlockN1.getHash();
            }

            // The orphan from N-1 should NOT be referenced (Strict N-2 rule!)
            assertFalse("N-1 orphan should NOT be referenced (Strict N-2 rule)",
                    referencedHashes.contains(orphanHash));

            System.out.println("Orphan hash: " + orphanHash.toHexString().substring(0, 16));
            System.out.println("Orphan referenced: " + referencedHashes.contains(orphanHash));
        }

        System.out.println("========== Test 2 PASSED ==========\n");
    }

    /**
     * Test 3: Verify that candidate blocks DO reference orphans from N-2 epoch.
     *
     * <p>Orphans from N-2 epoch have had at least 64 seconds to propagate,
     * so they are guaranteed to be available on all nodes.
     */
    @Test
    public void testStrictN2_ReferencesOrphansFromN2() throws Exception {
        System.out.println("\n========== Test 3: References Orphans from N-2 ==========");

        // Get genesis
        Block genesisBlock = dagStore.getMainBlockByHeight(1);
        long genesisEpoch = genesisBlock.getEpoch();

        // Create blocks for 3 epochs to have N-2 orphans
        // Epoch N-2 (relative to final candidate)
        long epochN2 = genesisEpoch + 1;
        Block mainBlockN2 = createTestBlock(epochN2, genesisBlock.getHash(), Bytes.wrap(new byte[20]));
        dagChain.tryToConnect(mainBlockN2);

        Block orphanBlockN2 = createTestBlock(epochN2, genesisBlock.getHash(),
                Bytes.fromHexString("0x2222222222222222222222222222222222222222"));
        dagChain.tryToConnect(orphanBlockN2);

        // Determine which became the main block at height 2
        Block mainAtHeight2 = dagStore.getMainBlockByHeight(2);
        assertNotNull("Main block at height 2 should exist", mainAtHeight2);

        Bytes32 n2OrphanHash;
        if (mainAtHeight2.getHash().equals(mainBlockN2.getHash())) {
            n2OrphanHash = orphanBlockN2.getHash();
        } else {
            n2OrphanHash = mainBlockN2.getHash();
        }

        System.out.println("N-2 main: " + mainAtHeight2.getHash().toHexString().substring(0, 16));
        System.out.println("N-2 orphan: " + n2OrphanHash.toHexString().substring(0, 16));

        // Epoch N-1 (relative to final candidate)
        long epochN1 = genesisEpoch + 2;
        Block mainBlockN1 = createTestBlock(epochN1, mainAtHeight2.getHash(), Bytes.wrap(new byte[20]));
        dagChain.tryToConnect(mainBlockN1);

        Block orphanBlockN1 = createTestBlock(epochN1, mainAtHeight2.getHash(),
                Bytes.fromHexString("0x3333333333333333333333333333333333333333"));
        dagChain.tryToConnect(orphanBlockN1);

        // Now create candidate block (for epoch N)
        ChainStats stats = dagChain.getChainStats();
        Block candidate = dagChain.createCandidateBlock();
        assertNotNull("Candidate should be created", candidate);

        // Collect all referenced block hashes
        Set<Bytes32> referencedHashes = new HashSet<>();
        for (Link link : candidate.getLinks()) {
            if (link.isBlock()) {
                referencedHashes.add(link.getTargetHash());
            }
        }

        System.out.println("Candidate links count: " + referencedHashes.size());
        System.out.println("Referenced hashes:");
        for (Bytes32 hash : referencedHashes) {
            System.out.println("  - " + hash.toHexString().substring(0, 16));
        }

        // The N-2 orphan SHOULD be referenced (Strict N-2 rule)
        assertTrue("N-2 orphan SHOULD be referenced (guaranteed propagation)",
                referencedHashes.contains(n2OrphanHash));

        System.out.println("N-2 orphan referenced: " + referencedHashes.contains(n2OrphanHash));

        System.out.println("========== Test 3 PASSED ==========\n");
    }

    /**
     * Test 4: Verify the exact link composition follows Strict N-2 rule.
     *
     * <p>Expected composition:
     * <ul>
     *   <li>1 link to N-1 main block (parent)</li>
     *   <li>0 links to N-1 orphans (not yet propagated)</li>
     *   <li>N links to N-2 orphans (guaranteed propagation)</li>
     * </ul>
     */
    @Test
    public void testStrictN2_ExactLinkComposition() throws Exception {
        System.out.println("\n========== Test 4: Exact Link Composition ==========");

        // Build a chain with known structure
        Block genesisBlock = dagStore.getMainBlockByHeight(1);
        long genesisEpoch = genesisBlock.getEpoch();

        // Epoch E (N-2): Create main + 2 orphans
        long epochN2 = genesisEpoch + 1;
        Block mainN2 = createTestBlock(epochN2, genesisBlock.getHash(),
                Bytes.fromHexString("0x0000000000000000000000000000000000000001"));
        dagChain.tryToConnect(mainN2);

        Block orphan1N2 = createTestBlock(epochN2, genesisBlock.getHash(),
                Bytes.fromHexString("0x0000000000000000000000000000000000000002"));
        dagChain.tryToConnect(orphan1N2);

        Block orphan2N2 = createTestBlock(epochN2, genesisBlock.getHash(),
                Bytes.fromHexString("0x0000000000000000000000000000000000000003"));
        dagChain.tryToConnect(orphan2N2);

        // Get main at height 2 and identify N-2 orphans
        Block mainAtHeight2 = dagStore.getMainBlockByHeight(2);
        assertNotNull("Main at height 2", mainAtHeight2);

        Set<Bytes32> n2Orphans = new HashSet<>();
        for (Block b : List.of(mainN2, orphan1N2, orphan2N2)) {
            if (!b.getHash().equals(mainAtHeight2.getHash())) {
                n2Orphans.add(b.getHash());
            }
        }
        System.out.println("N-2 orphan count: " + n2Orphans.size());

        // Epoch E+1 (N-1): Create main + 1 orphan
        long epochN1 = genesisEpoch + 2;
        Block mainN1 = createTestBlock(epochN1, mainAtHeight2.getHash(),
                Bytes.fromHexString("0x0000000000000000000000000000000000000011"));
        dagChain.tryToConnect(mainN1);

        Block orphanN1 = createTestBlock(epochN1, mainAtHeight2.getHash(),
                Bytes.fromHexString("0x0000000000000000000000000000000000000012"));
        dagChain.tryToConnect(orphanN1);

        // Get main at height 3 and identify N-1 orphan
        Block mainAtHeight3 = dagStore.getMainBlockByHeight(3);
        assertNotNull("Main at height 3", mainAtHeight3);

        Bytes32 n1OrphanHash = mainAtHeight3.getHash().equals(mainN1.getHash())
                ? orphanN1.getHash() : mainN1.getHash();
        System.out.println("N-1 orphan: " + n1OrphanHash.toHexString().substring(0, 16));

        // Create candidate for epoch E+2 (N)
        ChainStats stats = dagChain.getChainStats();
        Block candidate = dagChain.createCandidateBlock();

        // Analyze links
        int n1MainLinks = 0;
        int n1OrphanLinks = 0;
        int n2OrphanLinks = 0;

        for (Link link : candidate.getLinks()) {
            if (!link.isBlock()) continue;

            Bytes32 targetHash = link.getTargetHash();
            if (targetHash.equals(mainAtHeight3.getHash())) {
                n1MainLinks++;
            } else if (targetHash.equals(n1OrphanHash)) {
                n1OrphanLinks++;
            } else if (n2Orphans.contains(targetHash)) {
                n2OrphanLinks++;
            }
        }

        System.out.println("Link composition:");
        System.out.println("  N-1 main links: " + n1MainLinks + " (expected: 1)");
        System.out.println("  N-1 orphan links: " + n1OrphanLinks + " (expected: 0)");
        System.out.println("  N-2 orphan links: " + n2OrphanLinks + " (expected: " + n2Orphans.size() + ")");

        // Verify Strict N-2 rule
        assertEquals("Should have exactly 1 link to N-1 main block", 1, n1MainLinks);
        assertEquals("Should have 0 links to N-1 orphans (Strict N-2 rule)", 0, n1OrphanLinks);
        assertEquals("Should have links to all N-2 orphans", n2Orphans.size(), n2OrphanLinks);

        System.out.println("========== Test 4 PASSED ==========\n");
    }

    // ==================== Helper Methods ====================

    /**
     * Create a test block with specified parameters.
     *
     * @param epoch    block epoch
     * @param parent   parent block hash
     * @param coinbase coinbase address (different coinbase = different hash)
     * @return created block
     */
    private Block createTestBlock(long epoch, Bytes32 parent, Bytes coinbase) {
        List<Link> links = List.of(Link.toBlock(parent));

        return Block.createWithNonce(
                epoch,
                UInt256.MAX_VALUE, // DEVNET difficulty
                Bytes32.random(),  // Random nonce for unique hash
                coinbase,
                links
        );
    }
}
