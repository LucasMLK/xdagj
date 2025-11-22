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

import io.xdag.p2p.utils.SimpleDecoder;
import io.xdag.p2p.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;

/**
 * SyncMainBlocksRequestMessage - Batch request main blocks by height range
 *
 * <p>Hybrid Sync Protocol - Main Blocks Request (0x1F)
 *
 * <p><strong>Purpose</strong>:
 * Request a batch of main chain blocks in a specified height range. Used for Phase 1 (Linear Main
 * Chain Synchronization) of hybrid sync.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [8 bytes] fromHeight    - Start height (inclusive)
 * [8 bytes] toHeight      - End height (inclusive)
 * [4 bytes] maxBlocks     - Maximum blocks to return (recommended 1000)
 * [1 byte]  isRaw         - Whether to return full block data (true=1, false=0)
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code fromHeight}: Start height (inclusive), must be >= 0</li>
 *   <li>{@code toHeight}: End height (inclusive), must be >= fromHeight</li>
 *   <li>{@code maxBlocks}: Maximum blocks to return (default 1000, hard limit 10000)</li>
 *   <li>{@code isRaw}: true = return full block data, false = return BlockInfo only</li>
 * </ul>
 *
 * <p><strong>Validation Rules</strong>:
 * <pre>{@code
 * // 1. Height range validation
 * if (fromHeight < 0 || toHeight < fromHeight) {
 *     throw new IllegalArgumentException("Invalid height range");
 * }
 *
 * // 2. Batch size limiting
 * long requestSize = toHeight - fromHeight + 1;
 * if (requestSize > maxBlocks) {
 *     toHeight = fromHeight + maxBlocks - 1;  // Auto-truncate
 * }
 *
 * // 3. Hard limit
 * if (maxBlocks > 10000) {
 *     maxBlocks = 10000;
 * }
 * }</pre>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Request main blocks [1000, 2000)
 * SyncMainBlocksRequestMessage request = new SyncMainBlocksRequestMessage(
 *     1000,    // fromHeight
 *     1999,    // toHeight
 *     1000,    // maxBlocks
 *     false    // BlockInfo only
 * );
 * channel.sendMessage(request);
 *
 * // Wait for reply
 * SyncMainBlocksReplyMessage reply =
 *     channel.waitForResponse(SyncMainBlocksReplyMessage.class);
 * List<Block> blocks = reply.getBlocks();
 * }</pre>
 *
 * @see SyncMainBlocksReplyMessage for the response message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since XDAGJ
 */
@Getter
@Setter
public class SyncMainBlocksRequestMessage extends Message {

  /**
   * Start height (inclusive)
   */
  private long fromHeight;

  /**
   * End height (inclusive)
   */
  private long toHeight;

  /**
   * Maximum blocks to return (default 1000, hard limit 10000)
   */
  private int maxBlocks;

  /**
   * Whether to return full block data (true) or BlockInfo only (false)
   */
  private boolean isRaw;

  /**
   * Constructor for receiving message from network
   *
   * <p>Deserializes message body:
   * <ol>
   *   <li>Read fromHeight (long, 8 bytes)</li>
   *   <li>Read toHeight (long, 8 bytes)</li>
   *   <li>Read maxBlocks (int, 4 bytes)</li>
   *   <li>Read isRaw (boolean, 1 byte)</li>
   * </ol>
   *
   * @param body serialized message body (must be at least 21 bytes)
   * @throws IllegalArgumentException if deserialization fails or body is too short
   */
  public SyncMainBlocksRequestMessage(byte[] body) {
    super(XdagMessageCode.SYNC_MAIN_BLOCKS_REQUEST, SyncMainBlocksReplyMessage.class);

    // BUGFIX (BUG-051): Add message length validation
    // Previously: Would throw unclear exception from SimpleDecoder
    // Now: Validate input and provide clear error message
    if (body == null || body.length < 21) {
      throw new IllegalArgumentException(
          "Message body must be at least 21 bytes (8+8+4+1), got: " +
          (body == null ? "null" : body.length));
    }

    SimpleDecoder dec = new SimpleDecoder(body);

    // Deserialize fields
    this.fromHeight = dec.readLong();
    this.toHeight = dec.readLong();
    this.maxBlocks = dec.readInt();
    this.isRaw = dec.readBoolean();

    // Set body for reference
    this.body = body;
  }

  /**
   * Constructor for sending message to network
   *
   * <p>Serializes message:
   * <ol>
   *   <li>Write fromHeight (long, 8 bytes)</li>
   *   <li>Write toHeight (long, 8 bytes)</li>
   *   <li>Write maxBlocks (int, 4 bytes)</li>
   *   <li>Write isRaw (boolean, 1 byte)</li>
   * </ol>
   *
   * @param fromHeight start height (inclusive, must be >= 0)
   * @param toHeight   end height (inclusive, must be >= fromHeight)
   * @param maxBlocks  maximum blocks to return (capped at 10000)
   * @param isRaw      true = full block data, false = BlockInfo only
   * @throws IllegalArgumentException if parameters are invalid
   */
  public SyncMainBlocksRequestMessage(long fromHeight, long toHeight, int maxBlocks,
      boolean isRaw) {
    super(XdagMessageCode.SYNC_MAIN_BLOCKS_REQUEST, SyncMainBlocksReplyMessage.class);

    // BUGFIX (BUG-052): Add parameter validation as documented in JavaDoc
    // Previously: No validation, could send invalid requests
    // Now: Validate parameters according to documented rules (line 56-73)
    if (fromHeight < 0) {
      throw new IllegalArgumentException("fromHeight must be >= 0, got: " + fromHeight);
    }
    if (toHeight < fromHeight) {
      throw new IllegalArgumentException(
          String.format("toHeight (%d) must be >= fromHeight (%d)", toHeight, fromHeight));
    }
    if (maxBlocks <= 0) {
      throw new IllegalArgumentException("maxBlocks must be > 0, got: " + maxBlocks);
    }

    this.fromHeight = fromHeight;
    this.toHeight = toHeight;
    // Apply hard limit of 10000 blocks as documented
    this.maxBlocks = Math.min(maxBlocks, 10000);
    this.isRaw = isRaw;

    // Serialize message body
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    // Serialize fields
    enc.writeLong(fromHeight);
    enc.writeLong(toHeight);
    enc.writeInt(maxBlocks);
    enc.writeBoolean(isRaw);
  }

  @Override
  public String toString() {
    return String.format(
        "SyncMainBlocksRequestMessage[from=%d, to=%d, max=%d, raw=%b, size=%d bytes]",
        fromHeight,
        toHeight,
        maxBlocks,
        isRaw,
        body != null ? body.length : 0
    );
  }
}
