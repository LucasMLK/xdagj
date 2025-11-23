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

package io.xdag.utils;

import static io.xdag.utils.BytesUtils.equalBytes;

import io.xdag.crypto.encoding.Base58;
import io.xdag.utils.exception.XdagOverFlowException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * Utility class containing basic operations for XDAG
 */
public class BasicUtils {

  /**
   * Convert hex string address to Bytes32 hash
   *
   * @param address Hex string address
   * @return Bytes32 hash or null if address is null
   */
  public static Bytes32 getHash(String address) {
    Bytes32 hash = null;
    if (address != null) {
      hash = Bytes32.fromHexString(address);
    }
    return hash;
  }

  /**
   * Convert 20-byte address to public address string
   *
   * @param address 20-byte address (hash160)
   * @return Base58 encoded public address
   */
  public static String address2PubAddress(Bytes address) {
    if (address.size() != 20) {
      throw new IllegalArgumentException(
          "Address must be exactly 20 bytes, got: " + address.size());
    }
    return Base58.encodeCheck(address.toArray());
  }

  /**
   * Convert hex public address to legacy 24-byte truncated hash format
   *
   * @param hexPubAddress Hex string public address (must not be null)
   * @return Legacy hash format as MutableBytes32
   * @throws IllegalArgumentException if hex string is invalid or null
   */
  public static MutableBytes32 hexPubAddress2Hash(String hexPubAddress) {
    // BUGFIX (BUG-034): Add input validation for defensive programming
    // Previously: Would throw obscure exception from Bytes.fromHexString()
    // Now: Validate input and provide clear error messages
    if (hexPubAddress == null) {
      throw new IllegalArgumentException("Hex public address cannot be null");
    }
    if (hexPubAddress.isEmpty()) {
      throw new IllegalArgumentException("Hex public address cannot be empty");
    }

    try {
      Bytes hash = Bytes.fromHexString(hexPubAddress);
      MutableBytes32 legacyHash = MutableBytes32.create();
      legacyHash.set(8, hash);
      return legacyHash;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Invalid hex public address: " + hexPubAddress, e);
    }
  }

  /**
   * Convert base64 address to hash
   *
   * @param address Base64 encoded address
   * @return Hash as Bytes32
   */
  public static Bytes32 address2Hash(String address) {
    Bytes ret = Bytes.fromBase64String(address);
    MutableBytes32 res = MutableBytes32.create();
    res.set(8, ret.reverse().slice(0, 24));
    return res;
  }

  /**
   * Convert XDAG amount to internal representation
   *
   * @param input XDAG amount as double
   * @return Internal amount as UInt64
   * @throws XdagOverFlowException if input is negative
   */
  public static UInt64 xdag2amount(double input) {
    if (input < 0) {
      throw new XdagOverFlowException();
    }
    long amount = (long) Math.floor(input);

    UInt64 res = UInt64.valueOf(amount).shiftLeft(32);
    input -= amount; // Decimal part
    input = input * Math.pow(2, 32);
    long tmp = (long) Math.ceil(input);
    return res.add(tmp);
  }

  /**
   * Convert internal amount to XDAG
   *
   * @param xdag Internal amount as long
   * @return XDAG amount as double
   * @throws XdagOverFlowException if input is negative
   */
  public static double amount2xdag(long xdag) {
    if (xdag < 0) {
      throw new XdagOverFlowException();
    }
    long first = xdag >>> 32;
    long temp = xdag - (first << 32);
    double tem = temp / Math.pow(2, 32);
    BigDecimal bigDecimal = new BigDecimal(first + tem);
    return bigDecimal.setScale(12, RoundingMode.HALF_UP).doubleValue();
  }

  /**
   * Verify CRC32 checksum for XDAG block (512 bytes)
   *
   * <p>XDAG blocks are fixed at 512 bytes. This method verifies the CRC32
   * checksum of the first 512 bytes of the input data.
   *
   * @param src Source data (must be at least 512 bytes)
   * @param crc Expected CRC value
   * @return true if checksum matches
   * @throws IllegalArgumentException if src is null or less than 512 bytes
   */
  public static boolean crc32Verify(byte[] src, int crc) {
    // BUGFIX (BUG-033): Add input validation for array bounds
    // Previously: Would throw ArrayIndexOutOfBoundsException if src.length < 512
    // Now: Validate input and provide clear error message
    if (src == null) {
      throw new IllegalArgumentException("Source data cannot be null");
    }
    if (src.length < 512) {
      throw new IllegalArgumentException(
          "Source data must be at least 512 bytes, got: " + src.length);
    }

    CRC32 crc32 = new CRC32();
    crc32.update(src, 0, 512);
    return (int) crc32.getValue() == crc;
  }

  /**
   * Convert internal amount to XDAG with BigDecimal precision
   *
   * @param xdag Internal amount
   * @return XDAG amount as BigDecimal
   * @throws XdagOverFlowException if input is negative
   */
  public static BigDecimal amount2xdagNew(long xdag) {
    if (xdag < 0) {
      throw new XdagOverFlowException();
    }
    long first = xdag >> 32;
    long temp = xdag - (first << 32);
    double tem = temp / Math.pow(2, 32);
    return new BigDecimal(first + tem);
  }

  /**
   * Extract IP address from address:port string
   *
   * @param ipAddressAndPort String in format "/ip:port"
   * @return Extracted IP address or null if not found
   */
  public static String extractIpAddress(String ipAddressAndPort) {
    String pattern = "/(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}):\\d+";
    Pattern r = Pattern.compile(pattern);
    Matcher m = r.matcher(ipAddressAndPort);
    if (m.find()) {
      return m.group(1);
    } else {
      return null;
    }
  }
}
