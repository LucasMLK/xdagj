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
package io.xdag.core;

import java.io.Serializable;
import lombok.Builder;
import lombok.Value;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * BlockInfo - Block metadata (极简设计)
 *
 * Design principles (from CORE_DATA_STRUCTURES.md):
 * 1. 职责单一：只包含Block的元信息（索引、状态判断、PoW验证）
 * 2. 极简设计：只有4个必需字段
 * 3. DRY原则：所有可推导的数据都不存储
 *
 * 4 core fields:
 * - hash: 区块哈希（唯一标识）
 * - height: 主链高度（height > 0 = 主块，= 0 = 孤块）
 * - difficulty: PoW难度
 * - epoch: XDAG epoch number (NOT Unix timestamp!)
 *
 * DRY principle - DO NOT store:
 * - prevMainBlock → query via getBlockByHeight(height - 1)
 * - amount/fee → calculate from Block's Transactions
 * - snapshot → managed by independent SnapshotManager
 * - type, flags, ref, maxDiffLink → removed
 *
 * @see <a href="docs/refactor-design/CORE_DATA_STRUCTURES.md">Design</a>
 */
@Value
@Builder(toBuilder = true)
public class BlockInfo implements Serializable {

    /**
     * 区块哈希（完整32字节，唯一标识）
     */
    Bytes32 hash;

    /**
     * 主链高度
     * - height > 0: 主块（在main chain上）
     * - height = 0: 孤块（候选块，未被选为主块）
     *
     * This is the key field for determining if a block is on the main chain.
     */
    long height;

    /**
     * PoW难度（32 bytes）
     */
    UInt256 difficulty;

    /**
     * XDAG epoch number (each epoch = 64 seconds)
     *
     * <p><strong>IMPORTANT</strong>: This is NOT a Unix timestamp!
     * <p>XDAG uses a special epoch-based time system:
     * <ul>
     *   <li>XDAG timestamp = (Unix milliseconds * 1024) / 1000</li>
     *   <li>XDAG epoch = XDAG timestamp >> 16 (NOT / 64!)</li>
     *   <li>Each epoch = 65536 XDAG timestamp units = 64 seconds</li>
     * </ul>
     *
     * <p>To convert epoch to Unix time for display:
     * <pre>
     *   long unixMillis = XdagTime.epochToMs(epoch);
     *   Date date = new Date(unixMillis);
     * </pre>
     *
     * @see io.xdag.utils.XdagTime#getEpoch(long)
     * @see io.xdag.utils.XdagTime#epochToTimeMillis(long)
     */
    long epoch;

    // ========== 辅助方法 ==========

    /**
     * 是否为主块
     *  Simply check height > 0
     */
    public boolean isMainBlock() {
        return height > 0;
    }

    /**
     * 是否为孤块
     *  Simply check height == 0
     */
    public boolean isOrphanBlock() {
        return height == 0;
    }

    /**
     * 获取所属epoch
     *
     * <p>Simply returns the epoch field. This method is kept for backward compatibility.
     *
     * @return XDAG epoch number
     */
    public long getEpoch() {
        return epoch;
    }

    @Override
    public String toString() {
        return String.format(
            "BlockInfo{height=%d, hash=%s, epoch=%d, isMain=%b}",
            height,
            hash != null ? hash.toHexString().substring(0, 16) + "..." : "null",
            epoch,
            isMainBlock()
        );
    }
}
