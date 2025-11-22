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

import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.PublicKey;
import io.xdag.crypto.keys.Signature;
import io.xdag.crypto.keys.Signer;
import java.io.Serializable;
import java.nio.ByteBuffer;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Independent Transaction class for XDAG
 * <p>
 * Design principles: 1. Independent existence: separate broadcast and storage from Block 2. Account
 * model: similar to Ethereum with from/to/nonce 3. EVM compatible: ECDSA signature (secp256k1) with
 * v/r/s format 4. No timestamp: nonce is sufficient for ordering (like Ethereum) 5. No PoW
 * required: validated through signature 6. Hash caching: lazy computation, calculated on first use
 *
 * @see <a href="docs/refactor-design/CORE_DATA_STRUCTURES.md">Design</a>
 */
@Value
@Builder(toBuilder = true)
public class Transaction implements Serializable {

  /**
   * Source account address (20 bytes, hash160)
   */
  Bytes from;

  /**
   * Target account address (20 bytes, hash160)
   */
  Bytes to;

  /**
   * Transfer amount
   */
  XAmount amount;

  /**
   * Account nonce (prevents replay attacks, ensures ordering) Incrementing nonce similar to
   * Ethereum
   */
  long nonce;

  /**
   * Transaction fee Fee increases with data size: base_fee * (1 + data_length/256)
   */
  XAmount fee;

  /**
   * Transaction data (max 1KB) Used for smart contract calls, similar to Ethereum's data field
   */
  @Builder.Default
  Bytes data = Bytes.EMPTY;

  // ========== Signature (does not participate in hash calculation, EVM compatible) ==========

  /**
   * Recovery ID (for public key recovery from signature) Combined with r/s forms complete ECDSA
   * signature
   */
  int v;

  /**
   * Signature r value (32 bytes) Part of ECDSA signature
   */
  Bytes32 r;

  /**
   * Signature s value (32 bytes) Part of ECDSA signature
   */
  Bytes32 s;

  // ========== Hash Cache (does not participate in serialization) ==========

  /**
   * Transaction hash cache (lazy computation) Calculated on first call to getHash() Not serialized,
   * recalculated when needed
   */
  @Getter(lazy = true)
  Bytes32 hash = calculateHash();

  // ========== Constants ==========

  /**
   * Maximum data length: 1KB (1024 bytes) Sufficient for most DeFi operations (reference: ETH
   * average 200-500 bytes)
   */
  public static final int MAX_DATA_LENGTH = 1024;

  /**
   * Base data length for fee calculation: 256 bytes
   */
  public static final int BASE_DATA_LENGTH = 256;

  // ========== Core Methods ==========

  /**
   * Calculate transaction hash
   * <p>
   * Hash calculation: tx_hash = Keccak256(from + to + amount + nonce + fee + data) Signature
   * (v/r/s) does NOT participate in hash calculation
   *
   * @return transaction hash (32 bytes)
   */
  private Bytes32 calculateHash() {
    // Validate address sizes
    if (from.size() != 20) {
      throw new IllegalStateException("from address must be exactly 20 bytes, got: " + from.size());
    }
    if (to.size() != 20) {
      throw new IllegalStateException("to address must be exactly 20 bytes, got: " + to.size());
    }

    // Serialize transaction data for hashing
    ByteBuffer buffer = ByteBuffer.allocate(
        20 +  // from (hash160)
            20 +  // to (hash160)
            8 +   // amount
            8 +   // nonce
            8 +   // fee
            2 +   // data length
            data.size()  // data
    );

    buffer.put(from.toArray());
    buffer.put(to.toArray());
    buffer.putLong(amount.toXAmount().toLong());
    buffer.putLong(nonce);
    buffer.putLong(fee.toXAmount().toLong());
    buffer.putShort((short) data.size());
    buffer.put(data.toArray());

    return HashUtils.keccak256(Bytes.wrap(buffer.array()));
  }

  /**
   * Sign this transaction with private key
   * <p>
   * Signature process: 1. Calculate transaction hash 2. Sign hash with private key: (v, r, s) =
   * ECDSA_Sign(hash, private_key) 3. Extract v, r, s components from Signature object 4. Return new
   * Transaction with signature
   *
   * @param keyPair key pair containing private key
   * @return new Transaction instance with signature
   */
  public Transaction sign(ECKeyPair keyPair) {
    Bytes32 hash = getHash();
    Signature signature = Signer.sign(hash, keyPair);

    // Extract v, r, s components from Signature object
    // Signature class provides getters: getRBytes(), getSBytes(), getRecId()
    Bytes32 rValue = signature.getRBytes();
    Bytes32 sValue = signature.getSBytes();
    int vValue = signature.getRecId() & 0xFF;  // Convert byte to int (0-255)

    return this.toBuilder()
        .v(vValue)
        .r(rValue)
        .s(sValue)
        .build();
  }

  /**
   * Verify transaction signature
   * <p>
   * Verification process: 1. Recover public key from signature: recovered = ECRecover(hash, v, r,
   * s) 2. Derive address from recovered public key: SHA256 → RIPEMD160 (20 bytes) 3. Check if
   * recovered address matches from address
   *
   * @return true if signature is valid
   */
  public boolean verifySignature() {
    try {
      Bytes32 hash = getHash();
      Signature signature = Signature.create(
          r.toUnsignedBigInteger(),
          s.toUnsignedBigInteger(),
          (byte) v
      );

      PublicKey recoveredKey = Signer.recoverPublicKey(hash, signature);
      if (recoveredKey == null) {
        return false;
      }

      // Derive address from public key: SHA256 → RIPEMD160 (hash160)
      Bytes recoveredAddress = HashUtils.sha256hash160(recoveredKey.toBytes());
      return recoveredAddress.equals(from);

    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Calculate total fee including data fee
   * <p>
   * Fee structure:
   * <ul>
   *   <li>data <= 256 bytes: total_fee = fee (no extra charge)</li>
   *   <li>data > 256 bytes: total_fee = fee + base_fee * ((data_length - 256) / 256)</li>
   * </ul>
   * <p>
   * Example: 512 bytes of data → total_fee = fee + base_fee * 1.0 = fee + base_fee
   *
   * @param baseFee base transaction fee
   * @return total fee
   */
  public XAmount calculateTotalFee(XAmount baseFee) {
    if (data.size() <= BASE_DATA_LENGTH) {
      return fee;
    }

    double multiplier = 1.0 + ((double) (data.size() - BASE_DATA_LENGTH) / BASE_DATA_LENGTH);
    return fee.add(baseFee.multiply(multiplier - 1.0));
  }

  /**
   * Check if transaction has data
   *
   * @return true if data is not empty
   */
  public boolean hasData() {
    return !data.isEmpty();
  }

  /**
   * Validate transaction basic rules
   * <p>
   * Checks: 1. data size <= MAX_DATA_LENGTH 2. amount >= 0 3. fee >= 0 4. nonce >= 0 5. from and to
   * are not null
   *
   * @return true if valid
   */
  public boolean isValid() {
    if (data.size() > MAX_DATA_LENGTH) {
      return false;
    }
    if (amount.compareTo(XAmount.ZERO) < 0) {
      return false;
    }
    if (fee.compareTo(XAmount.ZERO) < 0) {
      return false;
    }
    if (nonce < 0) {
      return false;
    }
    if (from == null || to == null) {
      return false;
    }
    return true;
  }

  /**
   * Get transaction size in bytes (for storage and transmission)
   *
   * @return size in bytes
   */
  public int getSize() {
    return 20 +  // from (hash160)
        20 +  // to (hash160)
        8 +   // amount
        8 +   // nonce
        8 +   // fee
        2 +   // data length
        data.size() +  // data
        1 +   // v
        32 +  // r
        32;   // s
  }

  @Override
  public String toString() {
    return String.format(
        "Transaction[hash=%s, from=%s, to=%s, amount=%s, nonce=%d, fee=%s, dataSize=%d]",
        getHash().toHexString().substring(0, 16) + "...",
        from.toHexString().substring(0, 16) + "...",
        to.toHexString().substring(0, 16) + "...",
        amount.toDecimal(9, XUnit.XDAG).toPlainString(),
        nonce,
        fee.toDecimal(9, XUnit.XDAG).toPlainString(),
        data.size()
    );
  }

  // ========== Factory Methods ==========

  /**
   * Create a simple transfer transaction (no data)
   *
   * @param from   source address (20 bytes, hash160)
   * @param to     target address (20 bytes, hash160)
   * @param amount transfer amount
   * @param nonce  account nonce
   * @param fee    transaction fee
   * @return unsigned transaction
   * @throws IllegalArgumentException if addresses are not exactly 20 bytes
   */
  public static Transaction createTransfer(Bytes from, Bytes to, XAmount amount, long nonce,
      XAmount fee) {
    if (from.size() != 20) {
      throw new IllegalArgumentException(
          "from address must be exactly 20 bytes, got: " + from.size());
    }
    if (to.size() != 20) {
      throw new IllegalArgumentException("to address must be exactly 20 bytes, got: " + to.size());
    }

    return Transaction.builder()
        .from(from)
        .to(to)
        .amount(amount)
        .nonce(nonce)
        .fee(fee)
        .data(Bytes.EMPTY)
        .build();
  }

  /**
   * Create a transaction with data (for smart contract calls)
   *
   * @param from   source address (20 bytes, hash160)
   * @param to     target address (20 bytes, hash160)
   * @param amount transfer amount
   * @param nonce  account nonce
   * @param fee    transaction fee
   * @param data   transaction data (max 1KB)
   * @return unsigned transaction
   * @throws IllegalArgumentException if data size exceeds MAX_DATA_LENGTH or addresses are not 20
   *                                  bytes
   */
  public static Transaction createWithData(Bytes from, Bytes to, XAmount amount, long nonce,
      XAmount fee, Bytes data) {
    if (from.size() != 20) {
      throw new IllegalArgumentException(
          "from address must be exactly 20 bytes, got: " + from.size());
    }
    if (to.size() != 20) {
      throw new IllegalArgumentException("to address must be exactly 20 bytes, got: " + to.size());
    }
    if (data.size() > MAX_DATA_LENGTH) {
      throw new IllegalArgumentException(
          "Data size exceeds maximum: " + data.size() + " > " + MAX_DATA_LENGTH);
    }

    return Transaction.builder()
        .from(from)
        .to(to)
        .amount(amount)
        .nonce(nonce)
        .fee(fee)
        .data(data)
        .build();
  }

  // ========== Serialization ==========

  /**
   * Serialize transaction to bytes (for storage or network transmission)
   * <p>
   * Format (minimum 132 bytes + data length): [from - 20 bytes, hash160] [to - 20 bytes, hash160]
   * [amount - 8 bytes] [nonce - 8 bytes] [fee - 8 bytes] [data_length - 2 bytes] [data - variable,
   * max 1024 bytes] [v - 1 byte] [r - 32 bytes] [s - 32 bytes]
   *
   * @return serialized transaction bytes
   */
  public byte[] toBytes() {
    int size = getSize();
    ByteBuffer buffer = ByteBuffer.allocate(size);

    // Serialize transaction data
    buffer.put(from.toArray());
    buffer.put(to.toArray());
    buffer.putLong(amount.toXAmount().toLong());
    buffer.putLong(nonce);
    buffer.putLong(fee.toXAmount().toLong());
    buffer.putShort((short) data.size());
    buffer.put(data.toArray());

    // Serialize signature
    buffer.put((byte) v);
    buffer.put(r != null ? r.toArray() : new byte[32]);
    buffer.put(s != null ? s.toArray() : new byte[32]);

    return buffer.array();
  }

  /**
   * Deserialize transaction from bytes
   *
   * @param bytes serialized transaction data
   * @return Transaction instance
   * @throws IllegalArgumentException if data is invalid
   */
  public static Transaction fromBytes(byte[] bytes) {
    if (bytes.length < 131) {  // Minimum size without data (20+20+8+8+8+2+1+32+32 = 131 bytes)
      throw new IllegalArgumentException(
          "Invalid transaction data: too small (" + bytes.length + " bytes, minimum 131)"
      );
    }

    ByteBuffer buffer = ByteBuffer.wrap(bytes);

    // Deserialize transaction data - read 20-byte addresses
    byte[] fromBytes = new byte[20];
    buffer.get(fromBytes);
    Bytes from = Bytes.wrap(fromBytes);

    byte[] toBytes = new byte[20];
    buffer.get(toBytes);
    Bytes to = Bytes.wrap(toBytes);

    long amountValue = buffer.getLong();
    XAmount amount = XAmount.ofXAmount(amountValue);

    long nonce = buffer.getLong();

    long feeValue = buffer.getLong();
    XAmount fee = XAmount.ofXAmount(feeValue);

    short dataLength = buffer.getShort();
    if (dataLength < 0 || dataLength > MAX_DATA_LENGTH) {
      throw new IllegalArgumentException(
          "Invalid data length: " + dataLength
      );
    }

    Bytes data;
    if (dataLength > 0) {
      byte[] dataBytes = new byte[dataLength];
      buffer.get(dataBytes);
      data = Bytes.wrap(dataBytes);
    } else {
      data = Bytes.EMPTY;
    }

    // Deserialize signature
    int v = buffer.get() & 0xFF;

    byte[] rBytes = new byte[32];
    buffer.get(rBytes);
    Bytes32 r = Bytes32.wrap(rBytes);

    byte[] sBytes = new byte[32];
    buffer.get(sBytes);
    Bytes32 s = Bytes32.wrap(sBytes);

    // Build transaction
    return Transaction.builder()
        .from(from)
        .to(to)
        .amount(amount)
        .nonce(nonce)
        .fee(fee)
        .data(data)
        .v(v)
        .r(r)
        .s(s)
        .build();
  }
}
