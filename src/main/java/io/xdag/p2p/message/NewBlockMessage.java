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
import io.xdag.p2p.utils.SimpleDecoder;
import io.xdag.p2p.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;

/**
 * NewBlockMessage - new block broadcast message
 *
 * Phase 3 - Network Layer Migration: This message enables Block transmission over P2P network.
 *
 * Design:
 * - Similar to NewBlockMessage but uses Block instead of legacy Block
 * - Message format: [Block bytes] + [TTL (4 bytes)]
 * - Uses NEW_BLOCK_V5 message code (0x1B)
 *
 * Usage:
 * ```java
 * // Sending a new Block to peers
 * NewBlockMessage msg = new NewBlockMessage(Block, ttl);
 * channel.sendMessage(msg);
 *
 * // Receiving a Block from network
 * NewBlockMessage msg = new NewBlockMessage(messageBody);
 * Block block = msg.getBlock();
 * ```
 *
 * Protocol Compatibility:
 * - v1 nodes use NEW_BLOCK (0x18) with legacy Block
 * - v2 nodes use NEW_BLOCK_V5 (0x1B) with Block
 * - Protocol negotiation determines which message type to use
 *
 * @see NewBlockMessage for legacy Block version
 * @see Block for block structure
 */
@Getter
@Setter
public class NewBlockMessage extends Message {

    /**
     * Block instance (block structure)
     */
    private Block block;

    /**
     * Time-to-live: number of hops this message can propagate
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
    public NewBlockMessage(byte[] body) {
        super(XdagMessageCode.NEW_BLOCK, null);

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
     * @param block Block to broadcast
     * @param ttl time-to-live (number of hops)
     */
    public NewBlockMessage(Block block, int ttl) {
        super(XdagMessageCode.NEW_BLOCK, null);

        this.block = block;
        this.ttl = ttl;

        // Serialize message body
        SimpleEncoder enc = new SimpleEncoder();
        encode(enc);
        this.body = enc.toBytes();
    }

    @Override
    public void encode(SimpleEncoder enc) {
        // Serialize Block
        byte[] blockBytes = this.block.toBytes();
        enc.writeBytes(blockBytes);

        // Serialize TTL
        enc.writeInt(ttl);
    }

  @Override
    public String toString() {
        return String.format(
            "NewBlockMessage[block=%s, ttl=%d, size=%d bytes]",
            block != null ? block.getHash().toHexString().substring(0, 16) + "..." : "null",
            ttl,
            body != null ? body.length : 0
        );
    }
}
