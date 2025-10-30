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
package io.xdag.net.message.consensus;

import io.xdag.core.BlockV5;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;
import io.xdag.utils.SimpleDecoder;
import io.xdag.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;

/**
 * NewBlockV5Message - v5.1 new block broadcast message
 *
 * Phase 3 - Network Layer Migration: This message enables BlockV5 transmission over P2P network.
 *
 * Design:
 * - Similar to NewBlockMessage but uses BlockV5 instead of legacy Block
 * - Message format: [BlockV5 bytes] + [TTL (4 bytes)]
 * - Uses NEW_BLOCK_V5 message code (0x1B)
 *
 * Usage:
 * ```java
 * // Sending a new BlockV5 to peers
 * NewBlockV5Message msg = new NewBlockV5Message(blockV5, ttl);
 * channel.sendMessage(msg);
 *
 * // Receiving a BlockV5 from network
 * NewBlockV5Message msg = new NewBlockV5Message(messageBody);
 * BlockV5 block = msg.getBlock();
 * ```
 *
 * Protocol Compatibility:
 * - v1 nodes use NEW_BLOCK (0x18) with legacy Block
 * - v2 nodes use NEW_BLOCK_V5 (0x1B) with BlockV5
 * - Protocol negotiation determines which message type to use
 *
 * @see NewBlockMessage for legacy Block version
 * @see BlockV5 for v5.1 block structure
 */
@Getter
@Setter
public class NewBlockV5Message extends Message {

    /**
     * BlockV5 instance (v5.1 block structure)
     */
    private BlockV5 block;

    /**
     * Time-to-live: number of hops this message can propagate
     */
    private int ttl;

    /**
     * Constructor for receiving message from network
     *
     * Deserializes message body:
     * 1. Read BlockV5 bytes
     * 2. Deserialize BlockV5 using BlockV5.fromBytes()
     * 3. Read TTL (int, 4 bytes)
     *
     * @param body serialized message body
     * @throws IllegalArgumentException if deserialization fails
     */
    public NewBlockV5Message(byte[] body) {
        super(MessageCode.NEW_BLOCK_V5, null);

        SimpleDecoder dec = new SimpleDecoder(body);

        // Deserialize BlockV5
        byte[] blockBytes = dec.readBytes();
        this.block = BlockV5.fromBytes(blockBytes);

        // Deserialize TTL
        this.ttl = dec.readInt();

        // Set body for reference
        this.body = body;
    }

    /**
     * Constructor for sending message to network
     *
     * Serializes message:
     * 1. Serialize BlockV5 using block.toBytes()
     * 2. Append TTL (int, 4 bytes)
     *
     * @param block BlockV5 to broadcast
     * @param ttl time-to-live (number of hops)
     */
    public NewBlockV5Message(BlockV5 block, int ttl) {
        super(MessageCode.NEW_BLOCK_V5, null);

        this.block = block;
        this.ttl = ttl;

        // Serialize message body
        SimpleEncoder enc = encode();
        this.body = enc.toBytes();
    }

    /**
     * Encode message to bytes
     *
     * Format:
     * [BlockV5 size (4 bytes)] + [BlockV5 bytes (variable)] + [TTL (4 bytes)]
     *
     * @return encoder with serialized data
     */
    private SimpleEncoder encode() {
        SimpleEncoder enc = new SimpleEncoder();

        // Serialize BlockV5
        byte[] blockBytes = this.block.toBytes();
        enc.writeBytes(blockBytes);

        // Serialize TTL
        enc.writeInt(ttl);

        return enc;
    }

    @Override
    public String toString() {
        return String.format(
            "NewBlockV5Message[block=%s, ttl=%d, size=%d bytes]",
            block != null ? block.getHash().toHexString().substring(0, 16) + "..." : "null",
            ttl,
            body != null ? body.length : 0
        );
    }
}
