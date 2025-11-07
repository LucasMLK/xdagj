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

package io.xdag.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * GenesisConfig - Genesis Block Configuration (like Ethereum genesis.json)
 *
 * <p><strong>Purpose</strong>:
 * Provides configurable genesis block creation, supporting:
 * <ul>
 *   <li>Different networks (mainnet, testnet, devnet)</li>
 *   <li>Initial balance allocations</li>
 *   <li>Snapshot import from old XDAG versions</li>
 *   <li>Custom consensus parameters</li>
 * </ul>
 *
 * <p><strong>Example genesis.json</strong>:
 * <pre>
 * {
 *   "networkId": "mainnet",
 *   "chainId": 1,
 *   "timestamp": 1516406400,
 *   "initialDifficulty": "0x0000000000000001",
 *   "epochLength": 64,
 *   "alloc": {
 *     "0x1234...": "1000000000000000000000",
 *     "0x5678...": "500000000000000000000"
 *   },
 *   "snapshot": {
 *     "enabled": true,
 *     "height": 1234567,
 *     "hash": "0xabcd...",
 *     "timestamp": 1700000000,
 *     "dataFile": "./snapshot/mainnet-1234567.dat"
 *   }
 * }
 * </pre>
 *
 * <p><strong>Use Cases</strong>:
 * <ol>
 *   <li>Fresh start: Create genesis block with initial allocations</li>
 *   <li>Testnet: Use different genesis parameters than mainnet</li>
 *   <li>Migration: Import old XDAG chain via snapshot configuration</li>
 * </ol>
 *
 * @since v5.1 Phase 12
 */
@Data
public class GenesisConfig {

    // ========== Network Identity ==========

    /**
     * Network identifier: "mainnet", "testnet", "devnet"
     */
    @JsonProperty("networkId")
    private String networkId = "mainnet";

    /**
     * Chain ID for transaction replay protection (EIP-155)
     * - Mainnet: 1
     * - Testnet: 2
     * - Devnet: 3
     */
    @JsonProperty("chainId")
    private long chainId = 1;

    // ========== Timing Parameters ==========

    /**
     * Genesis block timestamp (Unix seconds)
     * Default: XDAG_ERA (2018-01-20 00:00:00 UTC = 1516406400)
     */
    @JsonProperty("timestamp")
    private long timestamp = 1516406400L;  // XDAG_ERA

    /**
     * Epoch length in seconds
     * Default: 64 seconds
     */
    @JsonProperty("epochLength")
    private int epochLength = 64;

    // ========== Consensus Parameters ==========

    /**
     * Initial difficulty (hex string)
     * Default: 1 (minimal difficulty for genesis)
     */
    @JsonProperty("initialDifficulty")
    private String initialDifficulty = "0x1";

    /**
     * Extra data (up to 32 bytes)
     * Can be used to embed version info, signatures, etc.
     */
    @JsonProperty("extraData")
    private String extraData = "XDAG v5.1 Genesis";

    // ========== Initial Allocations ==========

    /**
     * Pre-allocated balances (address -> amount in nanoxdag)
     * Address format: hex string (32 bytes)
     * Amount format: decimal string in nanoxdag (1 XDAG = 1e9 nanoxdag)
     *
     * Example:
     * "0x1234...": "1000000000000000000000"  // 1000 XDAG
     */
    @JsonProperty("alloc")
    private Map<String, String> alloc = new HashMap<>();

    // ========== Snapshot Configuration ==========

    /**
     * Snapshot configuration for importing old XDAG chain state
     */
    @JsonProperty("snapshot")
    private SnapshotConfig snapshot = new SnapshotConfig();

    // ========== Helper Methods ==========

    /**
     * Load genesis configuration from JSON file
     *
     * @param file genesis.json file
     * @return GenesisConfig instance
     * @throws IOException if file read fails
     */
    public static GenesisConfig load(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(file, GenesisConfig.class);
    }

    /**
     * Load genesis configuration from JSON string
     *
     * @param json JSON string
     * @return GenesisConfig instance
     * @throws IOException if parsing fails
     */
    public static GenesisConfig fromJson(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, GenesisConfig.class);
    }

    /**
     * Create default genesis configuration for a network
     *
     * @param networkId "mainnet", "testnet", or "devnet"
     * @return GenesisConfig instance
     */
    public static GenesisConfig createDefault(String networkId) {
        GenesisConfig config = new GenesisConfig();
        config.setNetworkId(networkId);

        switch (networkId.toLowerCase()) {
            case "mainnet":
                config.setChainId(1);
                config.setTimestamp(1516406400L);  // XDAG_ERA
                config.setInitialDifficulty("0x1");
                break;

            case "testnet":
                config.setChainId(2);
                config.setTimestamp(System.currentTimeMillis() / 1000);  // Current time
                config.setInitialDifficulty("0x1");
                break;

            case "devnet":
                config.setChainId(3);
                config.setTimestamp(System.currentTimeMillis() / 1000);
                config.setInitialDifficulty("0x1");
                // Add some test allocations for devnet
                config.getAlloc().put(
                        "0x0000000000000000000000000000000000000000000000000000000000000001",
                        "1000000000000000000000"  // 1000 XDAG
                );
                break;

            default:
                throw new IllegalArgumentException("Unknown network: " + networkId);
        }

        return config;
    }

    /**
     * Get initial difficulty as UInt256
     *
     * @return initial difficulty
     */
    public UInt256 getInitialDifficultyUInt256() {
        String hex = initialDifficulty.startsWith("0x")
                ? initialDifficulty.substring(2)
                : initialDifficulty;
        return UInt256.fromHexString(hex);
    }

    /**
     * Get pre-allocated balance for an address
     *
     * @param address address (hex string or Bytes32)
     * @return balance in nanoxdag, or UInt256.ZERO if not allocated
     */
    public UInt256 getAllocation(String address) {
        String amount = alloc.get(address);
        if (amount == null) {
            return UInt256.ZERO;
        }
        return UInt256.valueOf(new BigInteger(amount));
    }

    /**
     * Check if genesis has any pre-allocations
     *
     * @return true if alloc is not empty
     */
    public boolean hasAllocations() {
        return alloc != null && !alloc.isEmpty();
    }

    /**
     * Check if snapshot import is enabled
     *
     * @return true if snapshot is configured and enabled
     */
    public boolean hasSnapshot() {
        return snapshot != null && snapshot.isEnabled();
    }

    /**
     * Validate genesis configuration
     *
     * @throws IllegalArgumentException if configuration is invalid
     */
    public void validate() {
        if (timestamp <= 0) {
            throw new IllegalArgumentException("Invalid timestamp: " + timestamp);
        }

        if (epochLength <= 0) {
            throw new IllegalArgumentException("Invalid epoch length: " + epochLength);
        }

        if (chainId <= 0) {
            throw new IllegalArgumentException("Invalid chain ID: " + chainId);
        }

        // Validate difficulty format
        try {
            getInitialDifficultyUInt256();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid difficulty format: " + initialDifficulty, e);
        }

        // Validate allocations
        if (alloc != null) {
            for (Map.Entry<String, String> entry : alloc.entrySet()) {
                String address = entry.getKey();
                String amount = entry.getValue();

                // Check address format (should be 32 bytes hex)
                if (!address.matches("^0x[0-9a-fA-F]{64}$")) {
                    throw new IllegalArgumentException("Invalid address format: " + address);
                }

                // Check amount is valid number
                try {
                    new BigInteger(amount);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid amount for " + address + ": " + amount, e);
                }
            }
        }

        // Validate snapshot config if present
        if (hasSnapshot()) {
            snapshot.validate();
        }
    }

    /**
     * Save genesis configuration to JSON file
     *
     * @param file output file
     * @throws IOException if write fails
     */
    public void save(File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, this);
    }

    /**
     * Convert to JSON string
     *
     * @return JSON representation
     * @throws IOException if serialization fails
     */
    public String toJson() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }
}
