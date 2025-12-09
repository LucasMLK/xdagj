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
   * @param code The message code
   * @param body The message body (must not be null)
   * @return The decoded message, or null if the message type is unknown
   * @throws IllegalArgumentException if body is null
   * @throws MessageException when the encoding is illegal
   */
  public Message create(byte code, byte[] body) throws MessageException {
    if (body == null) {
      throw new IllegalArgumentException("Message body cannot be null");
    }

    XdagMessageCode c = XdagMessageCode.of(code);
    if (c == null) {
      log.debug("Unknown message code: 0x{}", String.format("%02X", code & 0xFF));
      return null;
    }

    try {
      return switch (c) {
        //  Transaction broadcast message (Phase 3)
        case NEW_TRANSACTION -> new NewTransactionMessage(body);

        // FastDAG Sync Protocol Messages (v3.0)
        case NEW_BLOCK_HASH -> new NewBlockHashMessage(body);
        case GET_BLOCKS -> new GetBlocksMessage(body);
        case BLOCKS_REPLY -> new BlocksReplyMessage(body);
        case GET_EPOCH_HASHES -> new GetEpochHashesMessage(body);
        case EPOCH_HASHES_REPLY -> new EpochHashesReplyMessage(body);

        // Status exchange messages 
        case GET_STATUS -> new GetStatusMessage(body);
        case STATUS_REPLY -> new StatusReplyMessage(body);

        default -> {
            log.warn("Received deprecated or unknown message code: {}", c);
            yield null;
        }
      };
    } catch (Exception e) {
      throw new MessageException("Failed to decode message", e);
    }
  }

}
