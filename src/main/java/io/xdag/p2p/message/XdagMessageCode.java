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
package io.xdag.p2p.message;

import lombok.Getter;

/**
 * Application layer message codes for XDAG protocol.
 *
 * <p>These codes are defined in the application layer (xdagj) and must not conflict
 * with P2P framework codes (0x00-0x1F). Application layer should use 0x20-0xFF range.
 *
 * <p><b>Reserved Ranges:</b>
 * <ul>
 *   <li>0x00-0x0F: KAD protocol (xdagj-p2p framework)</li>
 *   <li>0x10-0x1F: Node protocol (xdagj-p2p framework)</li>
 *   <li>0x20-0xFF: Application layer (XDAG protocol)</li>
 * </ul>
 *
 * <p><b>Note:</b> Renamed from MessageCode to XdagMessageCode to avoid package
 * namespace collision with xdagj-p2p's MessageCode class.
 *
 * @see IMessageCode
 */
@Getter
public enum XdagMessageCode implements IMessageCode {

  // =======================================
  // [0x30, 0x4F] FastDAG Sync Protocol (v3.0)
  // =======================================

  /**
   * [0x30] NEW_BLOCK_HASH - Broadcast new block hash (Inv)
   */
  NEW_BLOCK_HASH(0x30),

  /**
   * [0x31] GET_BLOCKS - Request block data by hash list
   */
  GET_BLOCKS(0x31),

  /**
   * [0x32] BLOCKS_REPLY - Reply with block data
   */
  BLOCKS_REPLY(0x32),

  /**
   * [0x33] GET_EPOCH_HASHES - Request block hashes in epoch range
   */
  GET_EPOCH_HASHES(0x33),

  /**
   * [0x34] EPOCH_HASHES_REPLY - Reply with epoch hashes
   */
  EPOCH_HASHES_REPLY(0x34),

  // =======================================
  // [0x27] Transaction Broadcast
  // =======================================

  /**
   * [0x27] NEW_TRANSACTION - Broadcast new transaction to peers (Phase 3) Used for real-time
   * transaction propagation through the P2P network
   *
   * @see NewTransactionMessage
   */
  NEW_TRANSACTION(0x27);


  private static final XdagMessageCode[] map = new XdagMessageCode[256];

  static {
    for (XdagMessageCode mc : XdagMessageCode.values()) {
      map[mc.code] = mc;
    }
  }

  /**
   * Get XdagMessageCode from byte value.
   *
   * @param code byte code value
   * @return XdagMessageCode or null if not found
   */
  public static XdagMessageCode of(int code) {
    return map[0xff & code];
  }

  private final int code;

  XdagMessageCode(int code) {
    // BUGFIX (BUG-045): Corrected Node protocol range check from 0x15 to 0x1F
    // Previously: Only checked 0x10-0x15, missing 0x16-0x1F
    // Now: Checks full Node protocol range 0x10-0x1F as documented
    //
    // Validate that application layer codes don't conflict with P2P framework
    // Note: Some legacy codes (0x00-0x1F) are kept for backward compatibility
    // but should be migrated to use P2P framework messages
    if (code >= 0x00 && code <= 0x0F) {
      // KAD protocol range - should use xdagj-p2p MessageCode instead
      System.err.println("WARNING: XdagMessageCode 0x" + Integer.toHexString(code) +
          " conflicts with P2P KAD protocol range (0x00-0x0F). " +
          "Consider using xdagj-p2p framework messages.");
    } else if (code >= 0x10 && code <= 0x1F) {
      // Node protocol range - should use xdagj-p2p MessageCode instead
      System.err.println("WARNING: XdagMessageCode 0x" + Integer.toHexString(code) +
          " conflicts with P2P Node protocol range (0x10-0x1F). " +
          "Consider using xdagj-p2p framework messages.");
    }
    this.code = code;
  }

  /**
   * Get the byte representation of this message code.
   *
   * @return message code as byte
   */
  @Override
  public byte toByte() {
    return (byte) code;
  }
}