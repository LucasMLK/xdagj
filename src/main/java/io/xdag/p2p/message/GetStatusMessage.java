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
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * GetStatusMessage - Request peer's current sync status.
 *
 * <p>Sent when a new peer connects to determine their chain state.
 * The peer should respond with {@link StatusReplyMessage}.
 *
 * <p>Structure: Empty body (no payload required)
 */
@EqualsAndHashCode(callSuper = false)
@ToString
public class GetStatusMessage extends Message {

  /**
   * Create from raw bytes (for deserialization)
   */
  public GetStatusMessage(byte[] body) {
    super(XdagMessageCode.GET_STATUS, null);
    this.body = body;
  }

  /**
   * Create a new GET_STATUS request
   */
  public GetStatusMessage() {
    super(XdagMessageCode.GET_STATUS, null);
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    // Empty body - no payload needed
  }
}
