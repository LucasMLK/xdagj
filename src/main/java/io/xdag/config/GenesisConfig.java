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
import io.xdag.crypto.exception.AddressFormatException;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.utils.TimeUtils;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * GenesisConfig - Genesis Block Configuration (like Ethereum genesis.json)
 *
 * <p><strong>Purpose</strong>:
 * Provides configurable genesis block creation, supporting:
 * <ul>
 *   <li>Different networks (mainnet, testnet, devnet)</li>
 *   <li>Initial balance allocations</li>
 *   <li>Custom consensus parameters</li>
 * </ul>
 *
 * <p><strong>Example genesis.json</strong>:
 * <pre>
 * {
 *   "networkId": "mainnet",
 *   "chainId": 1,
 *   "epoch": 23694000,
 *   "initialDifficulty": "0x1",
 *   "genesisCoinbase": "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi",
 *   "alloc": {
 *     "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi": "1000000000000000000000",
 *     "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2": "500000000000000000000"
 *   }
 * }
 * </pre>
 *
 * <p><strong>Use Cases</strong>:
 * <ol>
 *   <li>Fresh start: Create genesis block with initial allocations</li>
 *   <li>Testnet: Use different genesis parameters than mainnet</li>
 *   <li>Devnet: Development network with test allocations</li>
 * </ol>
 *
 * @since XDAGJ
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
   * Chain ID for transaction replay protection (EIP-155) - Mainnet: 1 - Testnet: 2 - Devnet: 3
   */
  @JsonProperty("chainId")
  private long chainId = 1;

  // ========== Timing Parameters ==========

  /**
   * Genesis block epoch number (XDAG epoch) Default: 23694000 (XDAG_ERA: 2018-01-20 00:00:00 UTC)
   *
   * <p>Conversion from Unix timestamp to epoch using TimeUtils:
   * <pre>
   * long ms = unixSeconds * 1000;
   * long xdagTimestamp = TimeUtils.msToXdagtimestamp(ms);
   * long epoch = TimeUtils.getEpoch(xdagTimestamp);
   * </pre>
   */
  @JsonProperty("epoch")
  private long epoch = 23694000L;

  // ========== Consensus Parameters ==========

  /**
   * Initial difficulty (hex string) Default: 1 (minimal difficulty for genesis)
   */
  @JsonProperty("initialDifficulty")
  private String initialDifficulty = "0x1";

  /**
   * RandomX initial seed (32-byte hex string)
   *
   * <p>IMPORTANT: This seed is used to initialize RandomX from genesis block.
   * All nodes must use the same randomXSeed to ensure deterministic mining.
   *
   * <p>Format: 0x-prefixed 64-character hex string (32 bytes)
   *
   * <p>Examples:
   * <ul>
   *   <li>Mainnet: "0x0000000000000000000000000000000000000000000000000000000000000001"</li>
   *   <li>Testnet: "0x0000000000000000000000000000000000000000000000000000000000000002"</li>
   *   <li>Devnet:  "0x0000000000000000000000000000000000000000000000000000000000000003"</li>
   * </ul>
   */
  @JsonProperty("randomXSeed")
  private String randomXSeed = null;

  // ========== Initial Allocations ==========

  /**
   * Pre-allocated balances (address -> amount in nanoxdag)
   *
   * <p><strong>Address format</strong>: base58check encoded XDAG address (standard format)
   * <p><strong>Amount format</strong>: decimal string in nanoxdag (1 XDAG = 1e9 nanoxdag)
   *
   * <p><strong>Example</strong>:
   * <pre>
   * "alloc": {
   *   "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi": "1000000000000000000000",  // 1000 XDAG
   *   "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2": "500000000000000000000"     // 500 XDAG
   * }
   * </pre>
   *
   * <p><strong>Important</strong>: Use base58check addresses (like Bitcoin/XDAG addresses).
   * Hex format (0x...) is discouraged and may be removed in future versions.
   */
  @JsonProperty("alloc")
  private Map<String, String> alloc = new HashMap<>();

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
        // XDAG_ERA: 2018-01-20 00:00:00 UTC (Unix: 1516406400)
        long mainnetMs = 1516406400L * 1000;
        long mainnetTimestamp = TimeUtils.timeMillisToEpoch(mainnetMs);
        long mainnetEpoch = TimeUtils.getEpoch(mainnetTimestamp);
        config.setEpoch(mainnetEpoch);
        config.setInitialDifficulty("0x1");
        break;

      case "testnet":
        config.setChainId(2);
        // Current time to epoch using TimeUtils
        long testnetTimestamp = TimeUtils.getCurrentEpoch();
        long testnetEpoch = TimeUtils.getEpoch(testnetTimestamp);
        config.setEpoch(testnetEpoch);
        config.setInitialDifficulty("0x1");
        break;

      case "devnet":
        config.setChainId(3);
        long devnetTimestamp = TimeUtils.getCurrentEpoch();
        long devnetEpoch = TimeUtils.getEpoch(devnetTimestamp);
        config.setEpoch(devnetEpoch);
        config.setInitialDifficulty("0x1");
        // Add some test allocations for devnet (using base58check addresses)
        config.getAlloc().put(
            "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi",
            "1000000000000000000000"  // 1000 XDAG
        );
        config.getAlloc().put(
            "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2",
            "500000000000000000000"   // 500 XDAG
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
   * Parse address string to 20-byte Bytes
   *
   * <p>Supports multiple formats:
   * <ul>
   *   <li>base58check encoded (recommended): "4dutRdv..." → 20 bytes</li>
   *   <li>Legacy 32-byte hex: "0x0000...0000" → extract bytes 12-31 (20 bytes)</li>
   *   <li>Legacy 20-byte hex: "0x1234...abcd" (40 hex chars) → 20 bytes</li>
   * </ul>
   *
   * @param address address string
   * @return 20-byte address
   * @throws AddressFormatException if format is invalid
   */
  public static Bytes parseAddress(String address) throws AddressFormatException {
    if (address == null || address.trim().isEmpty()) {
      throw new AddressFormatException("Address is null or empty");
    }

    address = address.trim();

    // Check if it's hex format (legacy)
    if (address.startsWith("0x")) {
      String hex = address.substring(2);

      if (hex.length() == 64) {
        // Legacy 32-byte hex format - extract bytes 12-31 (20 bytes)
        Bytes32 bytes32 = Bytes32.fromHexString(hex);
        return bytes32.slice(12, 20);
      } else if (hex.length() == 40) {
        // 20-byte hex format (direct)
        return Bytes.fromHexString(hex);
      } else {
        throw new AddressFormatException(
            "Invalid hex address length: " + hex.length() +
                " (expected 40 for 20-byte or 64 for 32-byte)"
        );
      }
    }

    // Use AddressUtils for base58check format (standard XDAG address)
    return AddressUtils.fromBase58Address(address);
  }

  /**
   * Check if RandomX seed is configured
   *
   * @return true if randomXSeed is set
   */
  public boolean hasRandomXSeed() {
    return randomXSeed != null && !randomXSeed.trim().isEmpty();
  }

  /**
   * Get RandomX seed as bytes
   *
   * @return 32-byte seed, or null if not configured
   * @throws IllegalArgumentException if seed format is invalid
   */
  public byte[] getRandomXSeedBytes() {
    if (randomXSeed == null || randomXSeed.trim().isEmpty()) {
      return null;
    }

    try {
      String hex = randomXSeed.startsWith("0x") ? randomXSeed.substring(2) : randomXSeed;
      if (hex.length() != 64) {
        throw new IllegalArgumentException(
            "RandomX seed must be 32 bytes (64 hex characters), got: " + hex.length()
        );
      }
      return Bytes32.fromHexString(hex).toArray();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid randomXSeed format: " + randomXSeed, e);
    }
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
   * Validate genesis configuration
   *
   * @throws IllegalArgumentException if configuration is invalid
   */
  public void validate() {
    if (epoch <= 0) {
      throw new IllegalArgumentException("Invalid epoch: " + epoch);
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
        String addressStr = entry.getKey();
        String amount = entry.getValue();

        // Validate address format (base58check or hex)
        try {
          Bytes address = parseAddress(addressStr);
          if (address.size() != 20) {
            throw new AddressFormatException(
                "Address must be exactly 20 bytes, got: " + address.size()
            );
          }
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "Invalid address format: " + addressStr +
                  " (expected base58check address or 0x-prefixed hex)", e
          );
        }

        // Check amount is valid number
        try {
          new BigInteger(amount);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Invalid amount for " + addressStr + ": " + amount, e);
        }
      }
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
