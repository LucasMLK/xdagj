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
import io.xdag.net.message.MessageCode;
import io.xdag.p2p.message.Message;
import io.xdag.p2p.utils.SimpleDecoder;
import io.xdag.p2p.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;
import org.apache.tuweni.bytes.Bytes32;

import java.util.ArrayList;
import java.util.List;

/**
 * SyncBlocksReplyMessage - Reply with requested blocks
 *
 * <p>Hybrid Sync Protocol - Blocks Batch Reply (0x24)
 *
 * <p><strong>Purpose</strong>:
 * Returns a batch of blocks matching the requested hash list, in response
 * to a {@link SyncBlocksRequestMessage}.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [4 bytes]  blockCount         - Number of blocks returned
 * [variable] blocks[0..N-1]     - Block data list
 *     Each block:
 *     [32 bytes] blockHash      - Block hash
 *     [1 byte]   hasBlock       - Whether block exists (1=yes, 0=missing)
 *     [4 bytes]  blockSize      - Block data size (if hasBlock=1)
 *     [variable] blockData      - Serialized block data (if hasBlock=1)
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code blocks}: List of blocks matching request order</li>
 *   <li>Order corresponds to request hash list</li>
 *   <li>If a hash is not found, corresponding position is null</li>
 *   <li>blocks.size() == request.hashes.size()</li>
 *   <li>blocks.get(i) corresponds to request.hashes.get(i)</li>
 * </ul>
 *
 * <p><strong>Data Structure</strong>:
 * <pre>{@code
 * public class SyncBlocksReplyMessage {
 *     private final List<Block> blocks;  // May contain nulls
 *
 *     // blocks.size() == request.hashes.size()
 *     // blocks.get(i) corresponds to request.hashes.get(i)
 * }
 * }</pre>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Receiving reply
 * SyncBlocksReplyMessage reply = new SyncBlocksReplyMessage(messageBody);
 * List<Block> blocks = reply.getBlocks();
 *
 * // Process blocks
 * for (int i = 0; i < blocks.size(); i++) {
 *     Block block = blocks.get(i);
 *     if (block != null) {
 *         Bytes32 requestedHash = requestHashes.get(i);
 *         blockStore.saveBlock(block);
 *     }
 * }
 *
 * // Sending reply
 * List<Block> blocks = new ArrayList<>();
 * for (Bytes32 hash : requestHashes) {
 *     Block block = dagStore.getBlockByHash(hash, isRaw);
 *     blocks.add(block);  // May be null if not found
 * }
 * SyncBlocksReplyMessage reply = new SyncBlocksReplyMessage(blocks);
 * channel.sendMessage(reply);
 * }</pre>
 *
 * @see SyncBlocksRequestMessage for the request message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since v5.1 Phase 1.5
 */
@Getter
@Setter
public class SyncBlocksReplyMessage extends Message {

    /**
     * List of blocks (may contain nulls for missing blocks)
     * Order corresponds to request hash list
     */
    private List<Block> blocks;

    /**
     * Constructor for receiving message from network
     *
     * <p>Deserializes message body:
     * <ol>
     *   <li>Read blockCount (int, 4 bytes)</li>
     *   <li>For each block:
     *     <ul>
     *       <li>Read blockHash (32 bytes)</li>
     *       <li>Read hasBlock (boolean, 1 byte)</li>
     *       <li>If hasBlock: read blockSize (int, 4 bytes) and blockData</li>
     *       <li>If !hasBlock: add null to list</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param body serialized message body
     * @throws IllegalArgumentException if deserialization fails
     */
    public SyncBlocksReplyMessage(byte[] body) {
        super(MessageCode.SYNC_BLOCKS_REPLY, null);

        SimpleDecoder dec = new SimpleDecoder(body);

        // Read block count
        int blockCount = dec.readInt();
        this.blocks = new ArrayList<>(blockCount);

        // Read each block
        for (int i = 0; i < blockCount; i++) {
            // Read block hash (32 bytes) - for reference/verification
            byte[] hashBytes = new byte[32];
            dec.readBytes(hashBytes);
            @SuppressWarnings("unused")
            Bytes32 blockHash = Bytes32.wrap(hashBytes);

            // Check if block exists
            boolean hasBlock = dec.readBoolean();

            if (hasBlock) {
                // Read block size and data
                int blockSize = dec.readInt();
                byte[] blockBytes = new byte[blockSize];
                dec.readBytes(blockBytes);

                // Deserialize block
                Block block = Block.fromBytes(blockBytes);
                this.blocks.add(block);
            } else {
                // Missing block
                this.blocks.add(null);
            }
        }

        // Set body for reference
        this.body = body;
    }

    /**
     * Constructor for sending message to network
     *
     * <p>Serializes message:
     * <ol>
     *   <li>Write blockCount (int, 4 bytes)</li>
     *   <li>For each block:
     *     <ul>
     *       <li>Write blockHash (32 bytes)</li>
     *       <li>Write hasBlock (boolean, 1 byte)</li>
     *       <li>If block != null: write blockSize and blockData</li>
     *       <li>If block == null: write hasBlock=false only</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param blocks list of blocks (may contain nulls)
     */
    public SyncBlocksReplyMessage(List<Block> blocks) {
        super(MessageCode.SYNC_BLOCKS_REPLY, null);

        this.blocks = blocks;

        // Serialize message body
        SimpleEncoder enc = encode();
        this.body = enc.toBytes();
    }

    /**
     * Encode message to bytes
     *
     * <p>Format:
     * [4 bytes blockCount] + for each block: [32 bytes hash] + [1 byte hasBlock] + (if hasBlock: [4 bytes size] + [variable data])
     *
     * @return encoder with serialized data
     */
    private SimpleEncoder encode() {
        SimpleEncoder enc = new SimpleEncoder();

        // Write block count
        enc.writeInt(blocks.size());

        // Write each block
        for (Block block : blocks) {
            if (block != null) {
                // Write block hash (32 bytes)
                enc.write(block.getHash().toArray());

                // Block exists
                enc.writeBoolean(true);

                // Serialize block
                byte[] blockBytes = block.toBytes();
                enc.writeInt(blockBytes.length);
                enc.write(blockBytes);
            } else {
                // Write placeholder hash (all zeros)
                enc.write(new byte[32]);

                // Block missing
                enc.writeBoolean(false);
            }
        }

        return enc;
    }

    @Override
    public String toString() {
        long nonNullCount = blocks != null ? blocks.stream().filter(b -> b != null).count() : 0;
        return String.format(
            "SyncBlocksReplyMessage[total=%d, nonNull=%d, size=%d bytes]",
            blocks != null ? blocks.size() : 0,
            nonNullCount,
            body != null ? body.length : 0
        );
    }
}
