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
import io.xdag.SampleKeys;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.TimeUtils;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Base class for integration tests requiring DagKernel and temporary storage.
 * Handles setup/teardown of temporary directories, genesis files, and DagKernel.
 */
public abstract class BaseIntegrationTest {

    protected DagKernel dagKernel;
    protected Config config;
    protected Path tempDir;
    protected Wallet testWallet;
    protected ECKeyPair testKeyPair;

    @Before
    public void setUp() throws IOException {
        // Create unique temporary directory for each test
        tempDir = Files.createTempDirectory("xdagj-test-");

        // Create test genesis.json
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

        // Initialize test key pair
        testKeyPair = SampleKeys.KEY_PAIR;

        // Create test wallet
        testWallet = new Wallet(config);
        testWallet.unlock("test-password");
        testWallet.addAccountRandom();

        // Create DagKernel (subclasses can start it when ready)
        dagKernel = new DagKernel(config, testWallet);
    }

    @After
    public void tearDown() {
        if (dagKernel != null) {
            try {
                dagKernel.stop();
            } catch (Exception e) {
                System.err.println("Error stopping DagKernel: " + e.getMessage());
            }
        }

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

    protected void createTestGenesisFile() throws IOException {
        createTestGenesisFile(this.tempDir);
    }

    public static void createTestGenesisFile(Path directory) throws IOException {
        long epochNumber = 1;

        String genesisJson = "{\n" +
                "  \"networkId\": \"test\",\n" +
                "  \"chainId\": 999,\n" +
                "  \"epoch\": " + epochNumber + ",\n" +
                "  \"initialDifficulty\": \"0x1000\",\n" +
                "  \"epochLength\": 64,\n" +
                "  \"extraData\": \"XDAGJ Test Genesis\",\n" +
                "  \"genesisCoinbase\": \"0x0000000000000000000000001111111111111111111111111111111111111111\",\n" +
                "  \"randomXSeed\": \"0x0000000000000000000000000000000000000000000000000000000000000001\",\n" +
                "  \"alloc\": {}\n" +
                "}";

        Path genesisFile = directory.resolve("genesis-devnet.json");
        Files.writeString(genesisFile, genesisJson);
    }
}
