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

package io.xdag.api;

import io.xdag.DagKernel;
import io.xdag.Wallet;
import io.xdag.api.dto.NodeStatusInfo;
import io.xdag.api.service.AccountApiService;
import io.xdag.api.service.ChainApiService;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.*;

/**
 * Tests for Node Status API
 *
 * @since XDAGJ v5.1
 */
public class NodeStatusApiTest {

    private Path tempDir;
    private DagKernel dagKernel;
    private ChainApiService chainApiService;
    private Wallet testWallet;

    @Before
    public void setUp() throws IOException {
        // Create temporary directory
        tempDir = Files.createTempDirectory("node-status-api-test-");

        // Create test genesis file
        createTestGenesisFile();

        // Configure kernel
        Config config = new DevnetConfig() {
            @Override
            public String getStoreDir() {
                return tempDir.toString();
            }

            @Override
            public String getRootDir() {
                return tempDir.toString();
            }
        };

        // Create and start kernel
        testWallet = new Wallet(config);
        testWallet.unlock("test-password");
        testWallet.addAccountRandom();

        dagKernel = new DagKernel(config, testWallet);
        dagKernel.start();

        // Create API services
        AccountApiService accountApiService = new AccountApiService(dagKernel);
        chainApiService = new ChainApiService(dagKernel, accountApiService);
    }

    private void createTestGenesisFile() throws IOException {
        String genesisJson = "{\n" +
                "  \"networkId\": \"test\",\n" +
                "  \"chainId\": 999,\n" +
                "  \"timestamp\": 1516406400,\n" +
                "  \"initialDifficulty\": \"0x1000\",\n" +
                "  \"epochLength\": 64,\n" +
                "  \"extraData\": \"XDAG Node Status API Test\",\n" +
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

        Path genesisFile = tempDir.resolve("genesis-devnet.json");
        Files.writeString(genesisFile, genesisJson);
    }

    @After
    public void tearDown() {
        // Stop kernel
        if (dagKernel != null) {
            try {
                dagKernel.stop();
            } catch (Exception e) {
                System.err.println("Error stopping kernel: " + e.getMessage());
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
     * Test 1: Verify getNodeStatus() returns valid information
     */
    @Test
    public void testGetNodeStatus_ReturnsValidInfo() {
        System.out.println("\n========== Test 1: Get Node Status API ==========");

        NodeStatusInfo status = chainApiService.getNodeStatus();

        assertNotNull("Node status should not be null", status);
        assertNotNull("Sync state should not be null", status.getSyncState());
        assertNotNull("Mining status should not be null", status.getMiningStatus());
        assertNotNull("Warning level should not be null", status.getWarningLevel());
        assertNotNull("Message should not be null", status.getMessage());

        System.out.println("Node Status API Response:");
        System.out.println("  Sync State: " + status.getSyncState());
        System.out.println("  Is Behind: " + status.isBehind());
        System.out.println("  Current Epoch: " + status.getCurrentEpoch());
        System.out.println("  Local Latest Epoch: " + status.getLocalLatestEpoch());
        System.out.println("  Epoch Gap: " + status.getEpochGap());
        System.out.println("  Time Lag: " + status.getTimeLagMinutes() + " minutes");
        System.out.println("  Mining Status: " + status.getMiningStatus());
        System.out.println("  Can Mine: " + status.isCanMine());
        System.out.println("  Main Chain Length: " + status.getMainChainLength());
        System.out.println("  Warning Level: " + status.getWarningLevel());
        System.out.println("  Message: " + status.getMessage());

        System.out.println("========== Test 1 PASSED ==========\n");
    }

    /**
     * Test 2: Verify status fields have correct values
     */
    @Test
    public void testGetNodeStatus_CorrectValues() {
        System.out.println("\n========== Test 2: Verify Status Values ==========");

        NodeStatusInfo status = chainApiService.getNodeStatus();
        assertNotNull("Node status should not be null", status);

        // Verify epoch gap calculation
        long expectedGap = status.getCurrentEpoch() - status.getLocalLatestEpoch();
        assertEquals("Epoch gap should be calculated correctly", expectedGap, status.getEpochGap());

        // Verify time lag calculation (epoch gap * 64 seconds / 60)
        long expectedTimeLag = expectedGap * 64 / 60;
        assertEquals("Time lag should be calculated correctly", expectedTimeLag, status.getTimeLagMinutes());

        // Verify sync state consistency
        if (status.getEpochGap() > 100) {
            assertTrue("Node should be marked as behind when gap > 100", status.isBehind());
            assertEquals("Sync state should be 'behind'", "behind", status.getSyncState());
        } else {
            assertFalse("Node should not be behind when gap <= 100", status.isBehind());
            assertEquals("Sync state should be 'up-to-date'", "up-to-date", status.getSyncState());
        }

        // Verify mining status consistency
        if (status.getEpochGap() > 16) {
            assertFalse("Mining should be blocked when gap > 16", status.isCanMine());
            assertEquals("Mining status should be 'blocked'", "blocked", status.getMiningStatus());
        } else {
            assertTrue("Mining should be allowed when gap <= 16", status.isCanMine());
            assertEquals("Mining status should be 'allowed'", "allowed", status.getMiningStatus());
        }

        System.out.println("All status values verified correctly!");
        System.out.println("========== Test 2 PASSED ==========\n");
    }

    /**
     * Test 3: Verify warning levels based on epoch gap
     */
    @Test
    public void testGetNodeStatus_WarningLevels() {
        System.out.println("\n========== Test 3: Verify Warning Levels ==========");

        NodeStatusInfo status = chainApiService.getNodeStatus();
        assertNotNull("Node status should not be null", status);

        long epochGap = status.getEpochGap();
        String warningLevel = status.getWarningLevel();

        System.out.println("Epoch Gap: " + epochGap);
        System.out.println("Warning Level: " + warningLevel);

        // Verify warning level logic
        if (epochGap <= 100) {
            assertEquals("Warning level should be 'none' for gap <= 100", "none", warningLevel);
        } else if (epochGap <= 1000) {
            assertEquals("Warning level should be 'info' for 100 < gap <= 1000", "info", warningLevel);
        } else if (epochGap <= 16384) {
            assertEquals("Warning level should be 'warning' for 1000 < gap <= 16384", "warning", warningLevel);
        } else {
            assertEquals("Warning level should be 'critical' for gap > 16384", "critical", warningLevel);
        }

        System.out.println("Warning level is correct for epoch gap " + epochGap);
        System.out.println("========== Test 3 PASSED ==========\n");
    }
}
