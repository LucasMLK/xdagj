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

import io.xdag.core.ChainStats;
import io.xdag.p2p.utils.SimpleDecoder;
import io.xdag.p2p.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;

/**
 * SyncBlockRequestMessage - Request a specific block by hash during synchronization
 *
 * <p>This message is used to request a specific block from a peer during
 * the synchronization process.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [32 bytes] hash              - Block hash to request
 * [variable] chainStats        - Current chain statistics
 * </pre>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Request a block by hash during sync
 * SyncBlockRequestMessage request = new SyncBlockRequestMessage(hash, chainStats);
 * channel.sendMessage(request);
 *
 * // Peer responds with SyncBlockMessage
 * }</pre>
 *
 * @see SyncBlockMessage for the response
 * @since XDAGJ
 */
@Getter
@Setter
public class SyncBlockRequestMessage extends Message {

    /**
     * Hash of the requested block
     */
    private Bytes hash;

    /**
     * Current chain statistics (for peer synchronization)
     */
    private ChainStats chainStats;

    /**
     * Constructor for receiving message from network
     *
     * <p>Deserializes message body:
     * <ol>
     *   <li>Read hash (32 bytes)</li>
     *   <li>Read chainStats (variable size)</li>
     * </ol>
     *
     * @param body serialized message body
     * @throws IllegalArgumentException if deserialization fails
     */
    public SyncBlockRequestMessage(byte[] body) {
        super(XdagMessageCode.SYNCBLOCK_REQUEST, SyncBlockMessage.class);

        SimpleDecoder dec = new SimpleDecoder(body);

        // Deserialize hash (32 bytes)
        byte[] hashBytes = new byte[32];
        dec.readBytes(hashBytes);
        this.hash = Bytes32.wrap(hashBytes);

        // Deserialize chain stats
        this.chainStats = ChainStats.fromBytes(dec.readBytes());

        // Set body for reference
        this.body = body;
    }

    /**
     * Constructor for sending message to network
     *
     * <p>Serializes message:
     * <ol>
     *   <li>Write hash (32 bytes)</li>
     *   <li>Write chainStats (variable size)</li>
     * </ol>
     *
     * @param hash block hash to request
     * @param chainStats current chain statistics
     */
    public SyncBlockRequestMessage(MutableBytes hash, ChainStats chainStats) {
        super(XdagMessageCode.SYNCBLOCK_REQUEST, SyncBlockMessage.class);

        this.hash = Bytes32.wrap(hash);
        this.chainStats = chainStats;

        // Serialize message body
        SimpleEncoder enc = new SimpleEncoder();
        encode(enc);
        this.body = enc.toBytes();
    }

    /**
     * Encode message to bytes
     *
     * <p>Format:
     * [32 bytes hash] + [variable chainStats]
     *
     */

    @Override
    public void encode(SimpleEncoder enc) {
        // Serialize hash (32 bytes)
        enc.write(hash.toArray());

        // Serialize chain stats
        enc.writeBytes(chainStats.toBytes());
    }

    @Override
    public String toString() {
        return String.format(
            "SyncBlockRequestMessage[hash=%s, size=%d bytes]",
            hash != null ? hash.toHexString().substring(0, 16) + "..." : "null",
            body != null ? body.length : 0
        );
    }
}
