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

package io.xdag.config.spec;

import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import lombok.Builder;
import lombok.Data;

/**
 * Configuration specification for TransactionPool.
 *
 * <p>This class defines the configuration parameters for the transaction pool:
 * <ul>
 *   <li>Pool capacity limits</li>
 *   <li>Transaction expiration settings</li>
 *   <li>Minimum fee requirements</li>
 *   <li>Cleanup intervals</li>
 * </ul>
 *
 * @since Phase 1 - TransactionPool Implementation
 */
@Data
@Builder
public class TransactionPoolSpec {

  /**
   * Maximum number of transactions in the pool.
   * <p>Default: 10,000 transactions
   */
  @Builder.Default
  private int maxPoolSize = 10000;

  /**
   * Maximum transaction age in milliseconds before expiration.
   * <p>Default: 1 hour (3,600,000 ms)
   */
  @Builder.Default
  private long maxTxAgeMillis = 3600_000L;

  /**
   * Minimum transaction fee in nano XDAG.
   * <p>Default: 100 milli-XDAG = 100,000,000 nano XDAG
   */
  @Builder.Default
  private long minFeeNano = 100_000_000L;

  /**
   * Cleanup task interval in milliseconds.
   * <p>Default: 5 minutes (300,000 ms)
   */
  @Builder.Default
  private long cleanupIntervalMillis = 300_000L;

  /**
   * Get minimum fee as XAmount.
   *
   * @return minimum fee
   */
  public XAmount getMinFee() {
    return XAmount.of(minFeeNano, XUnit.NANO_XDAG);
  }

  /**
   * Create default configuration.
   *
   * @return default TransactionPoolSpec
   */
  public static TransactionPoolSpec createDefault() {
    return TransactionPoolSpec.builder().build();
  }

  @Override
  public String toString() {
    return String.format(
        "TransactionPoolSpec{maxPoolSize=%d, maxTxAge=%dms, minFee=%s, cleanupInterval=%dms}",
        maxPoolSize,
        maxTxAgeMillis,
        XAmount.of(minFeeNano, XUnit.NANO_XDAG).toDecimal(9, XUnit.XDAG),
        cleanupIntervalMillis);
  }
}
