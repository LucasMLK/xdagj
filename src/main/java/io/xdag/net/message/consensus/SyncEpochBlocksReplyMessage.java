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

import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;
import io.xdag.utils.SimpleDecoder;
import io.xdag.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;
import org.apache.tuweni.bytes.Bytes32;

import java.util.ArrayList;
import java.util.List;

/**
 * SyncEpochBlocksReplyMessage - Reply with block hashes in epoch
 *
 * <p>Hybrid Sync Protocol - Epoch Blocks Reply (0x22)
 *
 * <p><strong>Purpose</strong>:
 * Returns a list of all block hashes in the specified epoch, in response
 * to a {@link SyncEpochBlocksRequestMessage}.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [8 bytes]  epoch          - Epoch number
 * [4 bytes]  hashCount      - Number of hashes
 * [variable] hashes[0..N-1] - Block hash list (each 32 bytes)
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code epoch}: Epoch number</li>
 *   <li>{@code hashes}: List of all block hashes in this epoch</li>
 * </ul>
 *
 * <p><strong>Data Source</strong>:
 * <pre>{@code
 * List<Block> blocks = dagStore.getCandidateBlocksInEpoch(epoch);
 * List<Bytes32> hashes = blocks.stream()
 *     .map(Block::getHash)
 *     .collect(Collectors.toList());
 * }</pre>
 *
 * <p><strong>Typical Size</strong>:
 * <ul>
 *   <li>Average blocks per epoch: 10-50</li>
 *   <li>Each hash: 32 bytes</li>
 *   <li>Total size: 320-1600 bytes</li>
 * </ul>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Receiving reply
 * SyncEpochBlocksReplyMessage reply = new SyncEpochBlocksReplyMessage(messageBody);
 * List<Bytes32> hashes = reply.getHashes();
 *
 * // Process hashes
 * List<Bytes32> missingHashes = hashes.stream()
 *     .filter(hash -> !blockStore.hasBlock(hash))
 *     .collect(Collectors.toList());
 *
 * // Batch request missing blocks
 * SyncBlocksRequestMessage blocksReq = new SyncBlocksRequestMessage(missingHashes, true);
 * channel.sendMessage(blocksReq);
 *
 * // Sending reply
 * List<Block> blocks = dagStore.getCandidateBlocksInEpoch(epoch);
 * List<Bytes32> hashes = blocks.stream()
 *     .map(Block::getHash)
 *     .collect(Collectors.toList());
 * SyncEpochBlocksReplyMessage reply = new SyncEpochBlocksReplyMessage(epoch, hashes);
 * channel.sendMessage(reply);
 * }</pre>
 *
 * @see SyncEpochBlocksRequestMessage for the request message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since v5.1 Phase 1.5
 */
@Getter
@Setter
public class SyncEpochBlocksReplyMessage extends Message {

    /**
     * Epoch number
     */
    private long epoch;

    /**
     * List of block hashes in this epoch
     */
    private List<Bytes32> hashes;

    /**
     * Constructor for receiving message from network
     *
     * <p>Deserializes message body:
     * <ol>
     *   <li>Read epoch (long, 8 bytes)</li>
     *   <li>Read hashCount (int, 4 bytes)</li>
     *   <li>For each hash: read 32 bytes</li>
     * </ol>
     *
     * @param body serialized message body
     * @throws IllegalArgumentException if deserialization fails
     */
    public SyncEpochBlocksReplyMessage(byte[] body) {
        super(MessageCode.SYNC_EPOCH_BLOCKS_REPLY, null);

        SimpleDecoder dec = new SimpleDecoder(body);

        // Deserialize epoch
        this.epoch = dec.readLong();

        // Deserialize hash count
        int hashCount = dec.readInt();
        this.hashes = new ArrayList<>(hashCount);

        // Deserialize each hash (32 bytes)
        for (int i = 0; i < hashCount; i++) {
            byte[] hashBytes = new byte[32];
            dec.readBytes(hashBytes);
            this.hashes.add(Bytes32.wrap(hashBytes));
        }

        // Set body for reference
        this.body = body;
    }

    /**
     * Constructor for sending message to network
     *
     * <p>Serializes message:
     * <ol>
     *   <li>Write epoch (long, 8 bytes)</li>
     *   <li>Write hashCount (int, 4 bytes)</li>
     *   <li>For each hash: write 32 bytes</li>
     * </ol>
     *
     * @param epoch epoch number
     * @param hashes list of block hashes in this epoch
     */
    public SyncEpochBlocksReplyMessage(long epoch, List<Bytes32> hashes) {
        super(MessageCode.SYNC_EPOCH_BLOCKS_REPLY, null);

        this.epoch = epoch;
        this.hashes = hashes;

        // Serialize message body
        SimpleEncoder enc = encode();
        this.body = enc.toBytes();
    }

    /**
     * Encode message to bytes
     *
     * <p>Format:
     * [8 bytes epoch] + [4 bytes hashCount] + [32 bytes per hash]
     *
     * @return encoder with serialized data
     */
    private SimpleEncoder encode() {
        SimpleEncoder enc = new SimpleEncoder();

        // Serialize epoch
        enc.writeLong(epoch);

        // Serialize hash count
        enc.writeInt(hashes.size());

        // Serialize each hash (32 bytes)
        for (Bytes32 hash : hashes) {
            enc.write(hash.toArray());
        }

        return enc;
    }

    @Override
    public String toString() {
        return String.format(
            "SyncEpochBlocksReplyMessage[epoch=%d, hashes=%d, size=%d bytes]",
            epoch,
            hashes != null ? hashes.size() : 0,
            body != null ? body.length : 0
        );
    }
}
