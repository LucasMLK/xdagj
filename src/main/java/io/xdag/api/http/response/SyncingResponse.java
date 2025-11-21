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

package io.xdag.api.http.response;

import lombok.Builder;
import lombok.Data;

/**
 * Response for xdag_syncing - follows RPC API v2 design
 * <p>
 * If syncing: returns object with sync details If not syncing: returns false (handled at JSON-RPC
 * serialization level)
 */
@Data
@Builder
public class SyncingResponse {

  /**
   * Whether currently syncing
   */
  private boolean syncing;

  /**
   * Starting block number (hex with 0x prefix) - only present if syncing
   */
  private String startingBlock;

  /**
   * Current block number (hex with 0x prefix) - only present if syncing
   */
  private String currentBlock;

  /**
   * Highest known block number (hex with 0x prefix) - only present if syncing
   */
  private String highestBlock;

  /**
   * Sync progress percentage - only present if syncing
   */
  private Double progress;
}
