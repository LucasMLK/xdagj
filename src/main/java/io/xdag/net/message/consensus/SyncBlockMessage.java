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

import io.xdag.core.Block;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;
import io.xdag.utils.SimpleDecoder;
import io.xdag.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;

/**
 * SyncBlockMessage - v5.1 block synchronization message
 *
 * Phase 3 - Network Layer Migration: This message enables Block synchronization over P2P network.
 *
 * Design:
 * - Similar to SyncBlockMessage but uses Block instead of legacy Block
 * - Message format: [Block bytes] + [TTL (4 bytes)]
 * - Uses SYNC_BLOCK_V5 message code (0x1C)
 *
 * Difference from NewBlockMessage:
 * - NewBlockMessage: Used for broadcasting new blocks (active propagation)
 * - SyncBlockMessage: Used for synchronization (request/response)
 *
 * Usage:
 * ```java
 * // Sending a Block during synchronization
 * SyncBlockMessage msg = new SyncBlockMessage(Block, 1);
 * channel.sendMessage(msg);
 *
 * // Receiving a Block during sync
 * SyncBlockMessage msg = new SyncBlockMessage(messageBody);
 * Block block = msg.getBlock();
 * ```
 *
 * Protocol Compatibility:
 * - v1 nodes use SYNC_BLOCK (0x19) with legacy Block
 * - v2 nodes use SYNC_BLOCK_V5 (0x1C) with Block
 * - Protocol negotiation determines which message type to use
 *
 * @see SyncBlockMessage for legacy Block version
 * @see NewBlockMessage for block broadcasting
 * @see Block for v5.1 block structure
 */
@Getter
@Setter
public class SyncBlockMessage extends Message {

    /**
     * Block instance (v5.1 block structure)
     */
    private Block block;

    /**
     * Time-to-live: number of hops this message can propagate
     * Usually set to 1 for sync messages (direct response)
     */
    private int ttl;

    /**
     * Constructor for receiving message from network
     *
     * Deserializes message body:
     * 1. Read Block bytes
     * 2. Deserialize Block using Block.fromBytes()
     * 3. Read TTL (int, 4 bytes)
     *
     * @param body serialized message body
     * @throws IllegalArgumentException if deserialization fails
     */
    public SyncBlockMessage(byte[] body) {
        super(MessageCode.SYNC_BLOCK, null);

        SimpleDecoder dec = new SimpleDecoder(body);

        // Deserialize Block
        byte[] blockBytes = dec.readBytes();
        this.block = Block.fromBytes(blockBytes);

        // Deserialize TTL
        this.ttl = dec.readInt();

        // Set body for reference
        this.body = body;
    }

    /**
     * Constructor for sending message to network
     *
     * Serializes message:
     * 1. Serialize Block using block.toBytes()
     * 2. Append TTL (int, 4 bytes)
     *
     * @param block Block to synchronize
     * @param ttl time-to-live (usually 1 for sync)
     */
    public SyncBlockMessage(Block block, int ttl) {
        super(MessageCode.SYNC_BLOCK, null);

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
     * [Block size (4 bytes)] + [Block bytes (variable)] + [TTL (4 bytes)]
     *
     * @return encoder with serialized data
     */
    private SimpleEncoder encode() {
        SimpleEncoder enc = new SimpleEncoder();

        // Serialize Block
        byte[] blockBytes = this.block.toBytes();
        enc.writeBytes(blockBytes);

        // Serialize TTL
        enc.writeInt(ttl);

        return enc;
    }

    @Override
    public String toString() {
        return String.format(
            "SyncBlockMessage[block=%s, ttl=%d, size=%d bytes]",
            block != null ? block.getHash().toHexString().substring(0, 16) + "..." : "null",
            ttl,
            body != null ? body.length : 0
        );
    }
}
