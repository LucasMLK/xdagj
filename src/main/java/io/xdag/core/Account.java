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

import lombok.Builder;
import lombok.Value;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;

import java.nio.ByteBuffer;

/**
 * Account - Account model for XDAG
 *
 * <p>This is the core data structure for storing account state in the AccountStore.
 * It follows the account model with address (20 bytes), balance, and nonce.
 *
 * <h2>Account Model</h2>
 * <pre>
 * Account {
 *   address: Bytes (20 bytes, SHA256+RIPEMD160 hash)
 *   balance: UInt256 (account balance in smallest unit)
 *   nonce: UInt64 (transaction counter for replay protection)
 *   codeHash: Bytes32 (hash of contract code, optional)
 *   storageRoot: Bytes32 (root of contract storage trie, optional)
 * }
 * </pre>
 *
 * <h2>Account Types</h2>
 * <ul>
 *   <li><b>EOA (Externally Owned Account)</b>: codeHash = null, storageRoot = null</li>
 *   <li><b>Contract Account</b>: codeHash != null, storageRoot != null</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * // Create EOA account
 * Account eoa = Account.builder()
 *     .address(userAddress)
 *     .balance(UInt256.valueOf(1000))
 *     .nonce(UInt64.valueOf(5))
 *     .build();
 *
 * // Create contract account
 * Account contract = Account.builder()
 *     .address(contractAddress)
 *     .balance(UInt256.ZERO)
 *     .nonce(UInt64.ONE)
 *     .codeHash(codeHash)
 *     .storageRoot(storageRoot)
 *     .build();
 * </pre>
 *
 * @since AccountStore
 */
@Value
@Builder(toBuilder = true)
public class Account {

  /**
   * Account address (20 bytes, SHA256+RIPEMD160)
   */
  Bytes address;

  /**
   * Account balance (256-bit unsigned integer)
   */
  @Builder.Default
  UInt256 balance = UInt256.ZERO;

  /**
   * Transaction nonce (64-bit unsigned integer)
   * <p>Used for replay protection and ordering transactions
   */
  @Builder.Default
  UInt64 nonce = UInt64.ZERO;

  /**
   * Code hash for contract accounts (optional)
   * <p>If null, this is an EOA account. If non-null, this is a contract account.
   */
  Bytes32 codeHash;

  /**
   * Storage root hash for contract accounts (optional)
   * <p>Root of the Merkle Patricia Trie for contract storage
   */
  Bytes32 storageRoot;

  /**
   * Check if this is a contract account
   *
   * @return true if contract, false if EOA
   */
  public boolean isContract() {
    return codeHash != null;
  }

  /**
   * Check if account is empty (zero balance, zero nonce, no code)
   *
   * @return true if empty according to EIP-161
   */
  public boolean isEmpty() {
    return balance.equals(UInt256.ZERO)
        && nonce.equals(UInt64.ZERO)
        && codeHash == null;
  }

  /**
   * Create a new account with updated balance
   *
   * @param newBalance new balance
   * @return new Account instance with updated balance
   */
  public Account withBalance(UInt256 newBalance) {
    return toBuilder().balance(newBalance).build();
  }

  /**
   * Create a new account with incremented nonce
   *
   * @return new Account instance with nonce + 1
   */
  public Account withIncrementedNonce() {
    return toBuilder().nonce(nonce.add(UInt64.ONE)).build();
  }

  /**
   * Create a new account with updated nonce
   *
   * @param newNonce new nonce value
   * @return new Account instance with updated nonce
   */
  public Account withNonce(UInt64 newNonce) {
    return toBuilder().nonce(newNonce).build();
  }

  // ==================== Serialization ====================

  /**
   * Serialize account to bytes for RocksDB storage
   *
   * <p>Format (variable size):
   * - address: 20 bytes (Bytes - hash160) - balance: 32 bytes (UInt256) - nonce: 8 bytes (UInt64) -
   * has_code: 1 byte (0 = EOA, 1 = contract) - [optional] codeHash: 32 bytes (if has_code = 1) -
   * [optional] storageRoot: 32 bytes (if has_code = 1)
   *
   * <p>Total size:
   * - EOA: 61 bytes (20 + 32 + 8 + 1) - Contract: 125 bytes (61 + 64)
   *
   * @return serialized bytes
   */
  public byte[] toBytes() {
    int size = 20 + 32 + 8 + 1;
    if (isContract()) {
      size += 64;  // codeHash + storageRoot
    }

    ByteBuffer buffer = ByteBuffer.allocate(size);

    // Address (20 bytes)
    if (address.size() != 20) {
      throw new IllegalStateException("Address must be exactly 20 bytes, got: " + address.size());
    }
    buffer.put(address.toArray());

    // Balance (32 bytes)
    buffer.put(balance.toBytes().toArray());

    // Nonce (8 bytes)
    buffer.put(nonce.toBytes().toArray());

    // Contract flag
    buffer.put((byte) (isContract() ? 1 : 0));

    // Contract data (if present)
    if (isContract()) {
      buffer.put(codeHash.toArray());
      buffer.put(storageRoot.toArray());
    }

    return buffer.array();
  }

  /**
   * Deserialize account from bytes
   *
   * @param data serialized account data
   * @return Account instance
   * @throws IllegalArgumentException if data is invalid
   */
  public static Account fromBytes(byte[] data) {
    if (data == null || data.length < 61) {
      throw new IllegalArgumentException(
          "Invalid account data: too short (minimum 61 bytes for EOA)");
    }

    ByteBuffer buffer = ByteBuffer.wrap(data);

    // Read address (20 bytes)
    byte[] addressBytes = new byte[20];
    buffer.get(addressBytes);
    Bytes address = Bytes.wrap(addressBytes);

    // Read balance
    byte[] balanceBytes = new byte[32];
    buffer.get(balanceBytes);
    UInt256 balance = UInt256.fromBytes(Bytes.wrap(balanceBytes));

    // Read nonce
    byte[] nonceBytes = new byte[8];
    buffer.get(nonceBytes);
    UInt64 nonce = UInt64.fromBytes(Bytes.wrap(nonceBytes));

    // Read contract flag
    boolean isContract = buffer.get() == 1;

    // Build account
    AccountBuilder builder = Account.builder()
        .address(address)
        .balance(balance)
        .nonce(nonce);

    // Read contract data if present
    if (isContract) {
      byte[] codeHashBytes = new byte[32];
      buffer.get(codeHashBytes);
      Bytes32 codeHash = Bytes32.wrap(codeHashBytes);

      byte[] storageRootBytes = new byte[32];
      buffer.get(storageRootBytes);
      Bytes32 storageRoot = Bytes32.wrap(storageRootBytes);

      builder.codeHash(codeHash).storageRoot(storageRoot);
    }

    return builder.build();
  }

  // ==================== Factory Methods ====================

  /**
   * Create a new empty EOA account
   *
   * @param address account address (20 bytes, hash160)
   * @return new Account with zero balance and nonce
   * @throws IllegalArgumentException if address is not exactly 20 bytes
   */
  public static Account createEOA(Bytes address) {
    if (address.size() != 20) {
      throw new IllegalArgumentException(
          "Address must be exactly 20 bytes, got: " + address.size());
    }
    return Account.builder()
        .address(address)
        .balance(UInt256.ZERO)
        .nonce(UInt64.ZERO)
        .build();
  }

  /**
   * Create a new contract account
   *
   * @param address     account address (20 bytes, hash160)
   * @param codeHash    hash of contract code
   * @param storageRoot root of contract storage
   * @return new contract Account
   * @throws IllegalArgumentException if address is not exactly 20 bytes
   */
  public static Account createContract(Bytes address, Bytes32 codeHash, Bytes32 storageRoot) {
    if (address.size() != 20) {
      throw new IllegalArgumentException(
          "Address must be exactly 20 bytes, got: " + address.size());
    }
    return Account.builder()
        .address(address)
        .balance(UInt256.ZERO)
        .nonce(UInt64.ONE)  // Contract nonce starts at 1
        .codeHash(codeHash)
        .storageRoot(storageRoot)
        .build();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Account{");
    sb.append("address=").append(address.toHexString());
    sb.append(", balance=").append(balance.toDecimalString());
    sb.append(", nonce=").append(nonce.toLong());
    if (isContract()) {
      sb.append(", codeHash=").append(codeHash.toHexString());
      sb.append(", storageRoot=").append(storageRoot.toHexString());
    }
    sb.append(", type=").append(isContract() ? "CONTRACT" : "EOA");
    sb.append("}");
    return sb.toString();
  }
}
