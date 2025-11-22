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

import io.xdag.core.Block;
import io.xdag.p2p.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;

/**
 * NewBlockMessage - new block broadcast message with TTL hop limit
 *
 * <p>This message is used for real-time block propagation through the P2P network.
 * When a node mines or receives a new block, it broadcasts this message to all connected peers to
 * spread the block quickly across the network.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [1 byte]   ttl     - Time-To-Live (hop count remaining)
 * [variable] block   - Serialized Block object
 * </pre>
 *
 * <p><strong>TTL (Time-To-Live) Mechanism</strong>:
 * Each message has a TTL value (default 5) that decrements with each hop:
 * <ul>
 *   <li>Initial broadcast: TTL = 5 (max 5 hops)</li>
 *   <li>Each forward: TTL decrements by 1</li>
 *   <li>TTL = 0: Message is dropped, no further forwarding</li>
 *   <li>Prevents infinite propagation in very large networks</li>
 * </ul>
 *
 * <p><strong>Design Notes</strong>:
 * <ul>
 *   <li>Consistent with NewTransactionMessage TTL format (1 byte)</li>
 *   <li>Default TTL = 5 hops (sufficient for most P2P topologies)</li>
 *   <li>TTL prevents excessive block propagation in large networks</li>
 *   <li>Combined with "recently seen" cache for loop prevention</li>
 * </ul>
 *
 * @see NewTransactionMessage for transaction broadcast with TTL
 * @see Block for block structure
 */
@Getter
@Setter
public class NewBlockMessage extends Message {

  /**
   * Maximum TTL value (default: 5 hops)
   * <p>
   * This limits block propagation to 5 network hops, which is sufficient for most P2P networks
   * while preventing excessive propagation.
   */
  public static final int DEFAULT_TTL = 5;

  /**
   * Block instance (block structure)
   */
  private final Block block;

  /**
   * Time-To-Live (hop count remaining)
   * <p>
   * Decrements by 1 with each forward. When TTL reaches 0, message is dropped.
   */
  private final int ttl;

  /**
   * Constructor for receiving message from network
   *
   * <p>Deserializes the block from message body.
   *
   * @param body serialized message body containing TTL + block
   * @throws IllegalArgumentException if deserialization fails
   */
  public NewBlockMessage(byte[] body) {
    super(XdagMessageCode.NEW_BLOCK, null);

    // Deserialize: [1 byte TTL] + [Block bytes]
    if (body == null || body.length < 2) {
      throw new IllegalArgumentException("Message body too short");
    }

    // Read TTL (first byte)
    this.ttl = body[0] & 0xFF;

    // Read block (remaining bytes)
    byte[] blockBytes = new byte[body.length - 1];
    System.arraycopy(body, 1, blockBytes, 0, blockBytes.length);
    this.block = Block.fromBytes(blockBytes);

    this.body = body;
  }

  /**
   * Constructor for sending message to network (initial broadcast)
   *
   * <p>Creates message with default TTL.
   *
   * @param block the block to broadcast (must not be null)
   * @throws IllegalArgumentException if block is null
   */
  public NewBlockMessage(Block block) {
    this(block, DEFAULT_TTL);
  }

  /**
   * Constructor for sending message with custom TTL
   *
   * <p>Used when forwarding received blocks with decremented TTL.
   *
   * @param block the block to broadcast (must not be null)
   * @param ttl   Time-To-Live (hop count)
   * @throws IllegalArgumentException if block is null
   */
  public NewBlockMessage(Block block, int ttl) {
    super(XdagMessageCode.NEW_BLOCK, null);

    // BUGFIX (BUG-048): Add null check for block parameter
    // Previously: Would throw NPE in encode() when calling block.toBytes()
    // Now: Validate input and provide clear error message
    if (block == null) {
      throw new IllegalArgumentException("Block cannot be null");
    }

    this.block = block;
    this.ttl = Math.max(0, Math.min(ttl, DEFAULT_TTL));  // Clamp to [0, DEFAULT_TTL]

    // Serialize block
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    // Serialize: [1 byte TTL] + [Block bytes]
    enc.writeByte((byte) ttl);

    byte[] blockBytes = block.toBytes();
    enc.write(blockBytes);
  }

  /**
   * Check if this message should be forwarded
   *
   * @return true if TTL > 0, false otherwise
   */
  public boolean shouldForward() {
    return ttl > 0;
  }

  /**
   * Create a new message for forwarding with decremented TTL
   *
   * @return new message with TTL - 1
   */
  public NewBlockMessage decrementTTL() {
    return new NewBlockMessage(block, ttl - 1);
  }

  @Override
  public String toString() {
    return String.format(
        "NewBlockMessage[hash=%s, height=%s, ttl=%d, size=%d bytes]",
        block != null ? block.getHash().toHexString().substring(0, 16) + "..." : "null",
        block != null && block.getInfo() != null ? block.getInfo().getHeight() : "unknown",
        ttl,
        body != null ? body.length : 0
    );
  }
}
