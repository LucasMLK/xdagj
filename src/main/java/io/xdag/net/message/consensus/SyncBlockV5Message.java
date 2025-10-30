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
 * SyncBlockV5Message - v5.1 block synchronization message
 *
 * Phase 3 - Network Layer Migration: This message enables BlockV5 synchronization over P2P network.
 *
 * Design:
 * - Similar to SyncBlockMessage but uses BlockV5 instead of legacy Block
 * - Message format: [BlockV5 bytes] + [TTL (4 bytes)]
 * - Uses SYNC_BLOCK_V5 message code (0x1C)
 *
 * Difference from NewBlockV5Message:
 * - NewBlockV5Message: Used for broadcasting new blocks (active propagation)
 * - SyncBlockV5Message: Used for synchronization (request/response)
 *
 * Usage:
 * ```java
 * // Sending a BlockV5 during synchronization
 * SyncBlockV5Message msg = new SyncBlockV5Message(blockV5, 1);
 * channel.sendMessage(msg);
 *
 * // Receiving a BlockV5 during sync
 * SyncBlockV5Message msg = new SyncBlockV5Message(messageBody);
 * BlockV5 block = msg.getBlock();
 * ```
 *
 * Protocol Compatibility:
 * - v1 nodes use SYNC_BLOCK (0x19) with legacy Block
 * - v2 nodes use SYNC_BLOCK_V5 (0x1C) with BlockV5
 * - Protocol negotiation determines which message type to use
 *
 * @see SyncBlockMessage for legacy Block version
 * @see NewBlockV5Message for block broadcasting
 * @see BlockV5 for v5.1 block structure
 */
@Getter
@Setter
public class SyncBlockV5Message extends Message {

    /**
     * BlockV5 instance (v5.1 block structure)
     */
    private BlockV5 block;

    /**
     * Time-to-live: number of hops this message can propagate
     * Usually set to 1 for sync messages (direct response)
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
    public SyncBlockV5Message(byte[] body) {
        super(MessageCode.SYNC_BLOCK_V5, null);

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
     * @param block BlockV5 to synchronize
     * @param ttl time-to-live (usually 1 for sync)
     */
    public SyncBlockV5Message(BlockV5 block, int ttl) {
        super(MessageCode.SYNC_BLOCK_V5, null);

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
            "SyncBlockV5Message[block=%s, ttl=%d, size=%d bytes]",
            block != null ? block.getHash().toHexString().substring(0, 16) + "..." : "null",
            ttl,
            body != null ? body.length : 0
        );
    }
}
