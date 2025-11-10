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
import org.apache.tuweni.bytes.Bytes32;

/**
 * SyncHeightReplyMessage - Reply with main chain height information
 *
 * <p>Hybrid Sync Protocol - Height Query Reply (0x1E)
 *
 * <p><strong>Purpose</strong>:
 * Returns the local node's main chain height information in response
 * to a {@link SyncHeightRequestMessage}.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [8 bytes]  mainHeight        - Current main chain height
 * [8 bytes]  finalizedHeight   - Finalized height (mainHeight - 16384)
 * [32 bytes] mainBlockHash     - Current main chain tip block hash
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code mainHeight}: Current main chain highest height</li>
 *   <li>{@code finalizedHeight}: Finalized boundary height = max(0, mainHeight - 16384)</li>
 *   <li>{@code mainBlockHash}: Hash of the current main chain tip block</li>
 * </ul>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Receiving reply
 * SyncHeightReplyMessage reply = new SyncHeightReplyMessage(messageBody);
 * long remoteHeight = reply.getMainHeight();
 * long remoteFinalized = reply.getFinalizedHeight();
 * Bytes32 remoteTip = reply.getMainBlockHash();
 *
 * // Sending reply
 * long mainHeight = blockchain.getMainChainLength();
 * long finalizedHeight = FinalityBoundary.getFinalizedHeight(mainHeight);
 * Bytes32 tipHash = blockchain.getTopMainBlock().getHash();
 * SyncHeightReplyMessage reply = new SyncHeightReplyMessage(
 *     mainHeight, finalizedHeight, tipHash);
 * channel.sendMessage(reply);
 * }</pre>
 *
 * @see SyncHeightRequestMessage for the request message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since v5.1 Phase 1.5
 */
@Getter
@Setter
public class SyncHeightReplyMessage extends Message {

    /**
     * Current main chain height
     */
    private long mainHeight;

    /**
     * Finalized height (mainHeight - FINALITY_EPOCHS)
     */
    private long finalizedHeight;

    /**
     * Hash of the current main chain tip block
     */
    private Bytes32 mainBlockHash;

    /**
     * Constructor for receiving message from network
     *
     * <p>Deserializes message body:
     * <ol>
     *   <li>Read mainHeight (long, 8 bytes)</li>
     *   <li>Read finalizedHeight (long, 8 bytes)</li>
     *   <li>Read mainBlockHash (Bytes32, 32 bytes)</li>
     * </ol>
     *
     * @param body serialized message body
     * @throws IllegalArgumentException if deserialization fails
     */
    public SyncHeightReplyMessage(byte[] body) {
        super(XdagMessageCode.SYNC_HEIGHT_REPLY, null);

        SimpleDecoder dec = new SimpleDecoder(body);

        // Deserialize fields
        this.mainHeight = dec.readLong();
        this.finalizedHeight = dec.readLong();

        // Read 32 bytes for block hash
        byte[] hashBytes = new byte[32];
        dec.readBytes(hashBytes);
        this.mainBlockHash = Bytes32.wrap(hashBytes);

        // Set body for reference
        this.body = body;
    }

    /**
     * Constructor for sending message to network
     *
     * <p>Serializes message:
     * <ol>
     *   <li>Write mainHeight (long, 8 bytes)</li>
     *   <li>Write finalizedHeight (long, 8 bytes)</li>
     *   <li>Write mainBlockHash (Bytes32, 32 bytes)</li>
     * </ol>
     *
     * @param mainHeight current main chain height
     * @param finalizedHeight finalized boundary height
     * @param mainBlockHash hash of main chain tip block
     */
    public SyncHeightReplyMessage(long mainHeight, long finalizedHeight, Bytes32 mainBlockHash) {
        super(XdagMessageCode.SYNC_HEIGHT_REPLY, null);

        this.mainHeight = mainHeight;
        this.finalizedHeight = finalizedHeight;
        this.mainBlockHash = mainBlockHash;

        // Serialize message body
        SimpleEncoder enc = new SimpleEncoder();
        encode(enc);
        this.body = enc.toBytes();
    }

    @Override
    public void encode(SimpleEncoder enc) {
        // Serialize heights
        enc.writeLong(mainHeight);
        enc.writeLong(finalizedHeight);

        // Serialize block hash (32 bytes)
        enc.write(mainBlockHash.toArray());
    }

    @Override
    public String toString() {
        return String.format(
            "SyncHeightReplyMessage[mainHeight=%d, finalizedHeight=%d, tipHash=%s, size=%d bytes]",
            mainHeight,
            finalizedHeight,
            mainBlockHash != null ? mainBlockHash.toHexString().substring(0, 16) + "..." : "null",
            body != null ? body.length : 0
        );
    }
}
