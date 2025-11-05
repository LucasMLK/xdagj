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

import io.xdag.core.ChainStats;
import io.xdag.net.message.MessageCode;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;

/**
 * BlockRequestMessage - Request for a specific Block by hash (Phase 7.3)
 *
 * <p>Used when the sync system receives a Block that references a missing parent block.
 * The node sends this message to request the missing Block from peers.
 *
 * <p><b>Message Flow:</b>
 * <ol>
 *   <li>Node A receives Block child but parent is missing</li>
 *   <li>Node A sends Block_REQUEST with parent hash to peers</li>
 *   <li>Node B responds with NEW_BLOCK_V5 or SYNC_BLOCK_V5 containing the requested block</li>
 *   <li>Node A imports parent, then processes waiting children</li>
 * </ol>
 *

 * @since 0.8.1 (Phase 7.3)
 */
public class BlockRequestMessage extends XdagMessage {

    /**
     * Constructor for sending Block request
     *
     * @param hash Hash of the requested Block
     * @param chainStats Current node statistics (Phase 7.3: XdagStats deleted)
     */
    public BlockRequestMessage(MutableBytes hash, ChainStats chainStats) {
        super(MessageCode.BLOCK_REQUEST, null, 0, 0, Bytes32.wrap(hash), chainStats);
    }

    /**
     * Constructor for receiving Block request from network
     *
     * @param body Raw message bytes
     */
    public BlockRequestMessage(byte[] body) {
        super(MessageCode.BLOCK_REQUEST, null, body);
    }
}
