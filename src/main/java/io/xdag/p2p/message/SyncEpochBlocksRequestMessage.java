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
 * SyncEpochBlocksRequestMessage - Request all block hashes in an epoch
 *
 * <p>Hybrid Sync Protocol - Epoch Blocks Request (0x21)
 *
 * <p><strong>Purpose</strong>:
 * Request all block hashes for a specific epoch. Used for Phase 2
 * (DAG Area Synchronization) of hybrid sync to get all candidate
 * blocks in an epoch.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [8 bytes] epoch    - Epoch number (timestamp / 64)
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code epoch}: Epoch number, calculated as timestamp / 64</li>
 * </ul>
 *
 * <p><strong>Epoch Definition</strong>:
 * <pre>
 * epoch = timestamp / 64
 * </pre>
 *
 * <p><strong>Typical Data Size</strong>:
 * <ul>
 *   <li>Average blocks per epoch: 10-50</li>
 *   <li>Each hash: 32 bytes</li>
 *   <li>Total response size: 320-1600 bytes</li>
 * </ul>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Request all blocks in epoch 5000
 * SyncEpochBlocksRequestMessage request = new SyncEpochBlocksRequestMessage(5000);
 * channel.sendMessage(request);
 *
 * // Wait for reply
 * SyncEpochBlocksReplyMessage reply =
 *     channel.waitForResponse(SyncEpochBlocksReplyMessage.class);
 * List<Bytes32> hashes = reply.getHashes();
 *
 * // Filter for missing blocks
 * List<Bytes32> missingHashes = hashes.stream()
 *     .filter(hash -> !blockStore.hasBlock(hash))
 *     .collect(Collectors.toList());
 * }</pre>
 *
 * @see SyncEpochBlocksReplyMessage for the response message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since XDAGJ
 */
@Getter
@Setter
public class SyncEpochBlocksRequestMessage extends Message {

    /**
     * Epoch number (timestamp / 64)
     */
    private long epoch;

    /**
     * Constructor for receiving message from network
     *
     * <p>Deserializes message body:
     * <ol>
     *   <li>Read epoch (long, 8 bytes)</li>
     * </ol>
     *
     * @param body serialized message body
     * @throws IllegalArgumentException if deserialization fails
     */
    public SyncEpochBlocksRequestMessage(byte[] body) {
        super(XdagMessageCode.SYNC_EPOCH_BLOCKS_REQUEST, SyncEpochBlocksReplyMessage.class);

        SimpleDecoder dec = new SimpleDecoder(body);

        // Deserialize epoch
        this.epoch = dec.readLong();

        // Set body for reference
        this.body = body;
    }

    /**
     * Constructor for sending message to network
     *
     * <p>Serializes message:
     * <ol>
     *   <li>Write epoch (long, 8 bytes)</li>
     * </ol>
     *
     * @param epoch epoch number
     */
    public SyncEpochBlocksRequestMessage(long epoch) {
        super(XdagMessageCode.SYNC_EPOCH_BLOCKS_REQUEST, SyncEpochBlocksReplyMessage.class);

        this.epoch = epoch;

        // Serialize message body
        SimpleEncoder enc = new SimpleEncoder();
        encode(enc);
        this.body = enc.toBytes();
    }

    @Override
    public void encode(SimpleEncoder enc) {
        // Serialize epoch
        enc.writeLong(epoch);
    }

    @Override
    public String toString() {
        return String.format(
            "SyncEpochBlocksRequestMessage[epoch=%d, size=%d bytes]",
            epoch,
            body != null ? body.length : 0
        );
    }
}
