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
 * SyncBlocksRequestMessage - Batch request blocks by hash list
 *
 * <p>Hybrid Sync Protocol - Blocks Batch Request (0x23)
 *
 * <p><strong>Purpose</strong>:
 * Request a batch of blocks by their hash list. Used during the
 * Solidification phase to fill missing blocks discovered during sync.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [4 bytes]  hashCount          - Number of hashes
 * [variable] hashes[0..N-1]     - Block hash list (each 32 bytes)
 * [1 byte]   isRaw              - Whether to return full block data
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code hashes}: List of block hashes to request</li>
 *   <li>{@code isRaw}: true = return full block data, false = return BlockInfo only</li>
 * </ul>
 *
 * <p><strong>Limits</strong>:
 * <pre>{@code
 * // Maximum 1000 blocks per request
 * if (hashes.size() > 1000) {
 *     hashes = hashes.subList(0, 1000);
 * }
 * }</pre>
 *
 * <p><strong>Usage Scenario</strong>:
 * After syncing the DAG area, detect missing blocks and batch request them:
 * <pre>{@code
 * // Collect missing block hashes
 * Set<Bytes32> missingHashes = new HashSet<>();
 * for (Block block : syncedBlocks) {
 *     for (Link link : block.getLinks()) {
 *         if (link.isBlock() && !blockStore.hasBlock(link.getTargetHash())) {
 *             missingHashes.add(link.getTargetHash());
 *         }
 *     }
 * }
 *
 * // Batch request missing blocks
 * List<Bytes32> hashList = new ArrayList<>(missingHashes);
 * for (int i = 0; i < hashList.size(); i += 1000) {
 *     int end = Math.min(i + 1000, hashList.size());
 *     List<Bytes32> batch = hashList.subList(i, end);
 *
 *     SyncBlocksRequestMessage request = new SyncBlocksRequestMessage(batch, true);
 *     channel.sendMessage(request);
 *
 *     SyncBlocksReplyMessage reply =
 *         channel.waitForResponse(SyncBlocksReplyMessage.class);
 *     // Process blocks...
 * }
 * }</pre>
 *
 * @see SyncBlocksReplyMessage for the response message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since v5.1 Phase 1.5
 */
@Getter
@Setter
public class SyncBlocksRequestMessage extends Message {

    /**
     * List of block hashes to request
     */
    private List<Bytes32> hashes;

    /**
     * Whether to return full block data (true) or BlockInfo only (false)
     */
    private boolean isRaw;

    /**
     * Constructor for receiving message from network
     *
     * <p>Deserializes message body:
     * <ol>
     *   <li>Read hashCount (int, 4 bytes)</li>
     *   <li>For each hash: read 32 bytes</li>
     *   <li>Read isRaw (boolean, 1 byte)</li>
     * </ol>
     *
     * @param body serialized message body
     * @throws IllegalArgumentException if deserialization fails
     */
    public SyncBlocksRequestMessage(byte[] body) {
        super(MessageCode.SYNC_BLOCKS_REQUEST, SyncBlocksReplyMessage.class);

        SimpleDecoder dec = new SimpleDecoder(body);

        // Deserialize hash count
        int hashCount = dec.readInt();
        this.hashes = new ArrayList<>(hashCount);

        // Deserialize each hash (32 bytes)
        for (int i = 0; i < hashCount; i++) {
            byte[] hashBytes = new byte[32];
            dec.readBytes(hashBytes);
            this.hashes.add(Bytes32.wrap(hashBytes));
        }

        // Deserialize isRaw flag
        this.isRaw = dec.readBoolean();

        // Set body for reference
        this.body = body;
    }

    /**
     * Constructor for sending message to network
     *
     * <p>Serializes message:
     * <ol>
     *   <li>Write hashCount (int, 4 bytes)</li>
     *   <li>For each hash: write 32 bytes</li>
     *   <li>Write isRaw (boolean, 1 byte)</li>
     * </ol>
     *
     * @param hashes list of block hashes to request
     * @param isRaw true = full block data, false = BlockInfo only
     */
    public SyncBlocksRequestMessage(List<Bytes32> hashes, boolean isRaw) {
        super(MessageCode.SYNC_BLOCKS_REQUEST, SyncBlocksReplyMessage.class);

        this.hashes = hashes;
        this.isRaw = isRaw;

        // Serialize message body
        SimpleEncoder enc = encode();
        this.body = enc.toBytes();
    }

    /**
     * Encode message to bytes
     *
     * <p>Format:
     * [4 bytes hashCount] + [32 bytes per hash] + [1 byte isRaw]
     *
     * @return encoder with serialized data
     */
    private SimpleEncoder encode() {
        SimpleEncoder enc = new SimpleEncoder();

        // Serialize hash count
        enc.writeInt(hashes.size());

        // Serialize each hash (32 bytes)
        for (Bytes32 hash : hashes) {
            enc.write(hash.toArray());
        }

        // Serialize isRaw flag
        enc.writeBoolean(isRaw);

        return enc;
    }

    @Override
    public String toString() {
        return String.format(
            "SyncBlocksRequestMessage[hashes=%d, raw=%b, size=%d bytes]",
            hashes != null ? hashes.size() : 0,
            isRaw,
            body != null ? body.length : 0
        );
    }
}
