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
import java.io.File;
import lombok.Data;
import org.apache.tuweni.bytes.Bytes32;

/**
 * SnapshotConfig - Configuration for importing old XDAG chain snapshots
 *
 * <p><strong>Purpose</strong>:
 * Enables migration from old XDAG versions to by:
 * <ul>
 *   <li>Loading a chain snapshot at specific height</li>
 *   <li>Importing account balances and state</li>
 *   <li>Resuming chain from snapshot point</li>
 * </ul>
 *
 * <p><strong>Snapshot Format</strong>:
 * The snapshot data file should contain:
 * <ul>
 *   <li>Block headers and data up to snapshot height</li>
 *   <li>Account balances at snapshot time</li>
 *   <li>Main chain state</li>
 *   <li>Orphan blocks (optional)</li>
 * </ul>
 *
 * <p><strong>Migration Workflow</strong>:
 * <pre>
 * 1. Export snapshot from old XDAG node:
 *    - Stop old node at specific height
 *    - Export blocks, accounts, main chain
 *    - Calculate final hash and timestamp
 *
 * 2. Configure genesis.json:
 *    {
 *      "snapshot": {
 *        "enabled": true,
 *        "height": 1234567,
 *        "hash": "0xabcd...",
 *        "timestamp": 1700000000,
 *        "dataFile": "./snapshot/mainnet-1234567.dat"
 *      }
 *    }
 *
 * 3. Start node:
 *    - DagKernel detects snapshot config
 *    - Imports snapshot data
 *    - Creates genesis block referencing snapshot state
 *    - Resumes normal operation
 * </pre>
 *
 * <p><strong>Security Considerations</strong>:
 * - Hash must match snapshot data (integrity check)
 * - Timestamp must be valid (not in future)
 * - Height must be positive
 * - Data file must exist and be readable
 *
 * @since XDAGJ
 */
@Data
public class SnapshotConfig {

  /**
   * Enable snapshot import Default: false (normal genesis block creation)
   */
  @JsonProperty("enabled")
  private boolean enabled = false;

  /**
   * Snapshot block height The main chain height at which the snapshot was taken
   */
  @JsonProperty("height")
  private long height = 0;

  /**
   * Snapshot last block hash (hex string with 0x prefix) This is the hash of the last main block in
   * the snapshot Used for integrity verification
   */
  @JsonProperty("hash")
  private String hash = "0x0000000000000000000000000000000000000000000000000000000000000000";

  /**
   * Snapshot timestamp (Unix seconds) The timestamp of the last block in the snapshot
   */
  @JsonProperty("timestamp")
  private long timestamp = 0;

  /**
   * Snapshot data file path Can be absolute or relative to config directory
   * <p>
   * Expected format: - Binary format with magic header - Version info - Block data - Account
   * balances - Main chain indexes
   */
  @JsonProperty("dataFile")
  private String dataFile = "";

  /**
   * Verify snapshot data before import If enabled, will check hash matches after loading Default:
   * true (recommended)
   */
  @JsonProperty("verify")
  private boolean verify = true;

  /**
   * Snapshot format version - v1: Old XDAG binary format - v2: optimized format
   */
  @JsonProperty("format")
  private String format = "v1";

  /**
   * Expected number of accounts in snapshot Used for progress reporting and validation Optional (0
   * = unknown)
   */
  @JsonProperty("expectedAccounts")
  private long expectedAccounts = 0;

  /**
   * Expected number of blocks in snapshot Used for progress reporting and validation Optional (0 =
   * unknown)
   */
  @JsonProperty("expectedBlocks")
  private long expectedBlocks = 0;

  // ========== Helper Methods ==========

  /**
   * Get snapshot hash as Bytes32
   *
   * @return snapshot hash
   */
  public Bytes32 getHashBytes32() {
    String hex = hash.startsWith("0x") ? hash.substring(2) : hash;
    return Bytes32.fromHexString(hex);
  }

  /**
   * Get snapshot data file as File object
   *
   * @return data file
   */
  public File getDataFileObject() {
    return new File(dataFile);
  }

  /**
   * Check if data file exists
   *
   * @return true if file exists and is readable
   */
  public boolean dataFileExists() {
    if (dataFile == null || dataFile.isEmpty()) {
      return false;
    }
    File file = getDataFileObject();
    return file.exists() && file.isFile() && file.canRead();
  }

  /**
   * Validate snapshot configuration
   *
   * @throws IllegalArgumentException if configuration is invalid
   */
  public void validate() {
    if (!enabled) {
      return;  // Skip validation if snapshot is disabled
    }

    if (height <= 0) {
      throw new IllegalArgumentException("Invalid snapshot height: " + height);
    }

    if (timestamp <= 0) {
      throw new IllegalArgumentException("Invalid snapshot timestamp: " + timestamp);
    }

    // Check timestamp is not in future
    long now = System.currentTimeMillis() / 1000;
    if (timestamp > now + 86400) {  // Allow 1 day tolerance for clock skew
      throw new IllegalArgumentException("Snapshot timestamp is in future: " + timestamp);
    }

    // Validate hash format
    if (!hash.matches("^0x[0-9a-fA-F]{64}$")) {
      throw new IllegalArgumentException("Invalid snapshot hash format: " + hash);
    }

    // Check data file
    if (dataFile == null || dataFile.isEmpty()) {
      throw new IllegalArgumentException("Snapshot data file not specified");
    }

    if (!dataFileExists()) {
      throw new IllegalArgumentException(
          "Snapshot data file not found or not readable: " + dataFile);
    }

    // Validate format
    if (!format.equals("v1") && !format.equals("v2")) {
      throw new IllegalArgumentException("Unknown snapshot format: " + format);
    }
  }

  /**
   * Create a snapshot config for testing (minimal valid config)
   *
   * @return test snapshot config
   */
  public static SnapshotConfig createTestConfig() {
    SnapshotConfig config = new SnapshotConfig();
    config.setEnabled(false);  // Disabled by default for tests
    config.setHeight(0);
    config.setHash("0x0000000000000000000000000000000000000000000000000000000000000000");
    config.setTimestamp(1516406400L);  // XDAG_ERA
    config.setDataFile("");
    config.setVerify(true);
    config.setFormat("v1");
    return config;
  }

  /**
   * Get progress percentage for snapshot import
   *
   * @param blocksLoaded   number of blocks loaded so far
   * @param accountsLoaded number of accounts loaded so far
   * @return progress percentage (0-100), or -1 if unknown
   */
  public int getProgress(long blocksLoaded, long accountsLoaded) {
    if (!enabled) {
      return 100;
    }

    // If we have expected counts, calculate weighted progress
    if (expectedBlocks > 0 && expectedAccounts > 0) {
      double blockProgress = (double) blocksLoaded / expectedBlocks;
      double accountProgress = (double) accountsLoaded / expectedAccounts;
      // Weight: 70% blocks, 30% accounts
      double weighted = (blockProgress * 0.7) + (accountProgress * 0.3);
      return (int) Math.min(100, weighted * 100);
    }

    // If only blocks count is known
    if (expectedBlocks > 0) {
      return (int) Math.min(100, (blocksLoaded * 100) / expectedBlocks);
    }

    // Unknown progress
    return -1;
  }

  /**
   * Get human-readable description
   *
   * @return description string
   */
  public String getDescription() {
    if (!enabled) {
      return "Snapshot import: disabled";
    }

    return String.format(
        "Snapshot import: height=%d, hash=%s, timestamp=%d, file=%s",
        height,
        hash.substring(0, Math.min(10, hash.length())) + "...",
        timestamp,
        dataFile
    );
  }
}
