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

import io.xdag.p2p.utils.SimpleEncoder;

/**
 * SyncHeightRequestMessage - Query peer's main chain height
 *
 * <p>Hybrid Sync Protocol - Height Query Message (0x1D)
 *
 * <p><strong>Purpose</strong>:
 * Query remote peer's main chain height information at the start of
 * hybrid sync protocol to determine the synchronization range.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [Empty body]
 * </pre>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Send height query request
 * SyncHeightRequestMessage request = new SyncHeightRequestMessage();
 * channel.sendMessage(request);
 *
 * // Wait for reply
 * SyncHeightReplyMessage reply = channel.waitForResponse(SyncHeightReplyMessage.class);
 * long remoteHeight = reply.getMainHeight();
 * }</pre>
 *
 * @see SyncHeightReplyMessage for the response message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since XDAGJ
 */
public class SyncHeightRequestMessage extends Message {

    /**
     * Constructor for receiving message from network
     *
     * <p>This message has no body, so the constructor is simple.
     *
     * @param body serialized message body (empty)
     */
    public SyncHeightRequestMessage(byte[] body) {
        super(XdagMessageCode.SYNC_HEIGHT_REQUEST, SyncHeightReplyMessage.class);
        this.body = body;
    }

    /**
     * Constructor for sending message to network
     *
     * <p>Creates an empty message to query peer's height.
     */
    public SyncHeightRequestMessage() {
        super(XdagMessageCode.SYNC_HEIGHT_REQUEST, SyncHeightReplyMessage.class);
        // Empty message body
        this.body = new byte[0];
    }

    @Override
    public void encode(SimpleEncoder enc) {
        // Empty message body - no data to encode
    }

    @Override
    public String toString() {
        return "SyncHeightRequestMessage[empty]";
    }
}
