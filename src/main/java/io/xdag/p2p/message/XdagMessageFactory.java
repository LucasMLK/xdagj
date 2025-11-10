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

import lombok.extern.slf4j.Slf4j;

/**
 * XDAG application layer message factory.
 *
 * <p><b>Note:</b> Renamed from MessageFactory to XdagMessageFactory to avoid
 * package namespace collision with xdagj-p2p's MessageFactory class.
 */
@Slf4j
public class XdagMessageFactory {
    /**
     * Decode a raw message.
     *
     * @param code
     *            The message code
     * @param body
     *            The message body
     * @return The decoded message, or NULL if the message type is not unknown
     * @throws MessageException
     *             when the encoding is illegal
     */
    public Message create(byte code, byte[] body) throws MessageException {

        XdagMessageCode c = XdagMessageCode.of(code);
        if (c == null) {
            //log.debug("Invalid message code: {}", Hex.encode0x(Bytes.of(code)));
            return null;
        }

        try {
            return switch (c) {
                case BLOCK_REQUEST -> new BlockRequestMessage(body);
                case SYNCBLOCK_REQUEST -> new SyncBlockRequestMessage(body);
                // Phase 3: Block messages
                case NEW_BLOCK -> new NewBlockMessage(body);
                case SYNC_BLOCK -> new SyncBlockMessage(body);
                // Phase 1.5: Hybrid sync protocol messages
                case SYNC_HEIGHT_REQUEST -> new SyncHeightRequestMessage(body);
                case SYNC_HEIGHT_REPLY -> new SyncHeightReplyMessage(body);
                case SYNC_MAIN_BLOCKS_REQUEST -> new SyncMainBlocksRequestMessage(body);
                case SYNC_MAIN_BLOCKS_REPLY -> new SyncMainBlocksReplyMessage(body);
                case SYNC_EPOCH_BLOCKS_REQUEST -> new SyncEpochBlocksRequestMessage(body);
                case SYNC_EPOCH_BLOCKS_REPLY -> new SyncEpochBlocksReplyMessage(body);
                case SYNC_BLOCKS_REQUEST -> new SyncBlocksRequestMessage(body);
                case SYNC_BLOCKS_REPLY -> new SyncBlocksReplyMessage(body);
                case SYNC_TRANSACTIONS_REQUEST -> new SyncTransactionsRequestMessage(body);
                case SYNC_TRANSACTIONS_REPLY -> new SyncTransactionsReplyMessage(body);
            };
        } catch (Exception e) {
            throw new MessageException("Failed to decode message", e);
        }
    }

}
