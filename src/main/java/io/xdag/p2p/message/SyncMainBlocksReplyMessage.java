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

import java.util.ArrayList;
import java.util.List;

/**
 * SyncMainBlocksReplyMessage - Reply with main blocks in height range
 *
 * <p>Hybrid Sync Protocol - Main Blocks Reply (0x20)
 *
 * <p><strong>Purpose</strong>:
 * Returns a batch of main chain blocks in response to a
 * {@link SyncMainBlocksRequestMessage}.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [4 bytes]  blockCount           - Number of blocks returned
 * [variable] blocks[0..N-1]       - Block data list
 *     Each block:
 *     [1 byte]   hasBlock          - Whether block exists (1=yes, 0=missing)
 *     [4 bytes]  blockSize         - Block data size (if hasBlock=1)
 *     [variable] blockData         - Serialized block data (if hasBlock=1)
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code blocks}: List of main blocks in ascending height order</li>
 *   <li>If a height has no block, the corresponding position is null</li>
 *   <li>List size = (toHeight - fromHeight + 1)</li>
 *   <li>blocks.get(i) corresponds to height (fromHeight + i)</li>
 * </ul>
 *
 * <p><strong>Data Structure</strong>:
 * <pre>{@code
 * public class SyncMainBlocksReplyMessage {
 *     private final List<Block> blocks;  // May contain nulls
 *
 *     // blocks.size() == (toHeight - fromHeight + 1)
 *     // blocks.get(i) corresponds to height (fromHeight + i)
 * }
 * }</pre>
 *
 * <p><strong>Example</strong>:
 * <pre>
 * Request: fromHeight=100, toHeight=105
 * Response: blocks = [block100, block101, null, block103, block104, block105]
 *           // Height 102 is missing
 * </pre>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Receiving reply
 * SyncMainBlocksReplyMessage reply = new SyncMainBlocksReplyMessage(messageBody);
 * List<Block> blocks = reply.getBlocks();
 *
 * // Process blocks
 * for (int i = 0; i < blocks.size(); i++) {
 *     Block block = blocks.get(i);
 *     if (block != null) {
 *         long height = fromHeight + i;
 *         blockchain.importBlock(block);
 *     }
 * }
 *
 * // Sending reply
 * List<Block> blocks = dagStore.getMainBlocksByHeightRange(fromHeight, toHeight, isRaw);
 * SyncMainBlocksReplyMessage reply = new SyncMainBlocksReplyMessage(blocks);
 * channel.sendMessage(reply);
 * }</pre>
 *
 * @see SyncMainBlocksRequestMessage for the request message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since v5.1 Phase 1.5
 */
@Getter
@Setter
public class SyncMainBlocksReplyMessage extends Message {

    /**
     * List of main blocks (may contain nulls for missing blocks)
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
    public SyncMainBlocksReplyMessage(byte[] body) {
        super(MessageCode.SYNC_MAIN_BLOCKS_REPLY, null);

        SimpleDecoder dec = new SimpleDecoder(body);

        // Read block count
        int blockCount = dec.readInt();
        this.blocks = new ArrayList<>(blockCount);

        // Read each block
        for (int i = 0; i < blockCount; i++) {
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
     *       <li>Write hasBlock (boolean, 1 byte)</li>
     *       <li>If block != null: write blockSize and blockData</li>
     *       <li>If block == null: write hasBlock=false only</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param blocks list of main blocks (may contain nulls)
     */
    public SyncMainBlocksReplyMessage(List<Block> blocks) {
        super(MessageCode.SYNC_MAIN_BLOCKS_REPLY, null);

        this.blocks = blocks;

        // Serialize message body
        SimpleEncoder enc = encode();
        this.body = enc.toBytes();
    }

    /**
     * Encode message to bytes
     *
     * <p>Format:
     * [4 bytes blockCount] + for each block: [1 byte hasBlock] + (if hasBlock: [4 bytes size] + [variable data])
     *
     * @return encoder with serialized data
     */
    @Override
    public void encode(SimpleEncoder enc) {
        // Write block count
        enc.writeInt(blocks.size());

        // Write each block
        for (Block block : blocks) {
            if (block != null) {
                // Block exists
                enc.writeBoolean(true);

                // Serialize block
                byte[] blockBytes = block.toBytes();
                enc.writeInt(blockBytes.length);
                enc.write(blockBytes);
            } else {
                // Block missing
                enc.writeBoolean(false);
            }
        }
    }

    private SimpleEncoder encode() {
        SimpleEncoder enc = new SimpleEncoder();

        // Write block count
        enc.writeInt(blocks.size());

        // Write each block
        for (Block block : blocks) {
            if (block != null) {
                // Block exists
                enc.writeBoolean(true);

                // Serialize block
                byte[] blockBytes = block.toBytes();
                enc.writeInt(blockBytes.length);
                enc.write(blockBytes);
            } else {
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
            "SyncMainBlocksReplyMessage[total=%d, nonNull=%d, size=%d bytes]",
            blocks != null ? blocks.size() : 0,
            nonNullCount,
            body != null ? body.length : 0
        );
    }
}
