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
package io.xdag.net.message;

import io.xdag.p2p.message.IMessageCode;
import lombok.Getter;

/**
 * Application layer message codes for XDAG protocol.
 *
 * <p>These codes are defined in the application layer (xdagj) and must not conflict
 * with P2P framework codes (0x00-0x1F). Application layer should use 0x20-0xFF range.
 *
 * <p><b>Reserved Ranges:</b>
 * <ul>
 *   <li>0x00-0x0F: KAD protocol (xdagj-p2p framework)</li>
 *   <li>0x10-0x1F: Node protocol (xdagj-p2p framework)</li>
 *   <li>0x20-0xFF: Application layer (XDAG protocol)</li>
 * </ul>
 *
 * @see IMessageCode
 */
@Getter
public enum MessageCode implements IMessageCode {

    BLOCK_REQUEST(0x16),
    SYNCBLOCK_REQUEST(0x1A),

    // v5.1 messages (Phase 3 - Network Layer Migration)
    /**
     * [0x1B] NEW_BLOCK_V5 - Broadcast new Block to peers
     * Uses Block structure instead of legacy Block
     * @see io.xdag.net.message.consensus.NewBlockMessage
     */
    NEW_BLOCK(0x1B),

    /**
     * [0x1C] SYNC_BLOCK_V5 - Synchronize Block during sync
     * Uses Block structure instead of legacy Block
     * @see io.xdag.net.message.consensus.SyncBlockMessage
     */
    SYNC_BLOCK(0x1C),

    // =======================================
    // [0x1D, 0x24] Reserved for hybrid sync protocol
    // =======================================

    /**
     * [0x1D] SYNC_HEIGHT_REQUEST - Query peer's main chain height
     * Used by hybrid sync protocol to determine sync range
     * @see io.xdag.net.message.consensus.SyncHeightRequestMessage
     */
    SYNC_HEIGHT_REQUEST(0x1D),

    /**
     * [0x1E] SYNC_HEIGHT_REPLY - Reply with main chain height information
     * Contains current height, finalized height, and tip block hash
     * @see io.xdag.net.message.consensus.SyncHeightReplyMessage
     */
    SYNC_HEIGHT_REPLY(0x1E),

    /**
     * [0x1F] SYNC_MAIN_BLOCKS_REQUEST - Request main blocks by height range
     * Used for linear main chain synchronization (Phase 1 of hybrid sync)
     * @see io.xdag.net.message.consensus.SyncMainBlocksRequestMessage
     */
    SYNC_MAIN_BLOCKS_REQUEST(0x1F),

    /**
     * [0x20] SYNC_MAIN_BLOCKS_REPLY - Reply with main blocks in height range
     * Returns ordered list of main blocks for efficient batch sync
     * @see io.xdag.net.message.consensus.SyncMainBlocksReplyMessage
     */
    SYNC_MAIN_BLOCKS_REPLY(0x20),

    /**
     * [0x21] SYNC_EPOCH_BLOCKS_REQUEST - Request all block hashes in an epoch
     * Used for DAG area synchronization (Phase 2 of hybrid sync)
     * @see io.xdag.net.message.consensus.SyncEpochBlocksRequestMessage
     */
    SYNC_EPOCH_BLOCKS_REQUEST(0x21),

    /**
     * [0x22] SYNC_EPOCH_BLOCKS_REPLY - Reply with block hashes in epoch
     * Returns list of all block hashes in the requested epoch
     * @see io.xdag.net.message.consensus.SyncEpochBlocksReplyMessage
     */
    SYNC_EPOCH_BLOCKS_REPLY(0x22),

    /**
     * [0x23] SYNC_BLOCKS_REQUEST - Batch request blocks by hash list
     * Used for filling missing blocks during solidification
     * @see io.xdag.net.message.consensus.SyncBlocksRequestMessage
     */
    SYNC_BLOCKS_REQUEST(0x23),

    /**
     * [0x24] SYNC_BLOCKS_REPLY - Reply with requested blocks
     * Returns list of blocks matching requested hashes
     * @see io.xdag.net.message.consensus.SyncBlocksReplyMessage
     */
    SYNC_BLOCKS_REPLY(0x24),

    /**
     * [0x25] SYNC_TRANSACTIONS_REQUEST - Batch request transactions by hash list
     * Used for filling missing transactions during solidification
     * @see io.xdag.net.message.consensus.SyncTransactionsRequestMessage
     */
    SYNC_TRANSACTIONS_REQUEST(0x25),

    /**
     * [0x26] SYNC_TRANSACTIONS_REPLY - Reply with requested transactions
     * Returns list of transactions matching requested hashes
     * @see io.xdag.net.message.consensus.SyncTransactionsReplyMessage
     */
    SYNC_TRANSACTIONS_REPLY(0x26);

    /**
     * [0x1D] Block_REQUEST - Request specific Block by hash (Phase 7.3)
     * Used when a Block references a missing parent block
     * @see io.xdag.net.message.consensus.BlockRequestMessage
     */
//    Block_REQUEST(0x1D);


    private static final MessageCode[] map = new MessageCode[256];

    static {
        for (MessageCode mc : MessageCode.values()) {
            map[mc.code] = mc;
        }
    }

    /**
     * Get MessageCode from byte value.
     *
     * @param code byte code value
     * @return MessageCode or null if not found
     */
    public static MessageCode of(int code) {
        return map[0xff & code];
    }

    private final int code;

    MessageCode(int code) {
        // Validate that application layer codes don't conflict with P2P framework
        // Note: Some legacy codes (0x00-0x1F) are kept for backward compatibility
        // but should be migrated to use P2P framework messages
        if (code >= 0x00 && code <= 0x0F) {
            // KAD protocol range - should use xdagj-p2p MessageCode instead
            System.err.println("WARNING: MessageCode 0x" + Integer.toHexString(code) +
                             " conflicts with P2P KAD protocol range (0x00-0x0F). " +
                             "Consider using xdagj-p2p framework messages.");
        } else if (code >= 0x10 && code <= 0x15) {
            // Node protocol range - should use xdagj-p2p MessageCode instead
            System.err.println("WARNING: MessageCode 0x" + Integer.toHexString(code) +
                             " conflicts with P2P Node protocol range (0x10-0x1F). " +
                             "Consider using xdagj-p2p framework messages.");
        }
        this.code = code;
    }

    /**
     * Get the byte representation of this message code.
     *
     * @return message code as byte
     */
    @Override
    public byte toByte() {
        return (byte) code;
    }
}
