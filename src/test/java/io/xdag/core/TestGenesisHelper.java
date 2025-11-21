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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helper class for creating test genesis.json files
 *
 * @since XDAGJ 1.0
 */
public class TestGenesisHelper {

    /**
     * Create a minimal test genesis.json file in the specified directory
     *
     * @param directory Directory to create genesis.json in
     * @throws IOException if file creation fails
     */
    public static void createTestGenesisFile(Path directory) throws IOException {
        String genesisJson = """
            {
              "networkId": "test",
              "chainId": 999,
              "timestamp": 1516406400,
              "initialDifficulty": "0x1000",
              "genesisCoinbase": "0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
              "epochLength": 64,
              "extraData": "XDAGJ 1.0 Test Genesis",
              "alloc": {},
              "snapshot": {
                "enabled": false,
                "height": 0,
                "hash": "0x0000000000000000000000000000000000000000000000000000000000000000",
                "timestamp": 0,
                "dataFile": "",
                "verify": false,
                "format": "v1",
                "expectedAccounts": 0,
                "expectedBlocks": 0
              }
            }""";

        Path genesisFile = directory.resolve("genesis-devnet.json");
        Files.writeString(genesisFile, genesisJson);
    }
}
