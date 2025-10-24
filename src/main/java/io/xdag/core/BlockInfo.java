package io.xdag.core;

import io.xdag.config.Constants;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt256;

import java.io.Serializable;

/**
 * 不可变的区块元信息
 *
 * 设计原则：
 * 1. 不可变：线程安全，易于缓存和推理
 * 2. 类型安全：使用 Bytes32, UInt256 代替 byte[], BigInteger
 * 3. 清晰的语义：提供辅助方法而非直接操作 flags
 * 4. 构建器模式：易于创建和修改
 */
@Value
@Builder(toBuilder = true)
@With
public class BlockInfo implements Serializable {

    // ========== 基本标识 ==========

    /**
     * 区块哈希（完整32字节，唯一标识）
     * 使用完整哈希以确保唯一性和清晰性
     */
    Bytes32 hash;

    /**
     * 区块时间戳（秒）
     */
    long timestamp;

    /**
     * 区块高度
     */
    long height;

    // ========== 区块类型和状态 ==========

    /**
     * 区块类型字段（16个4位字段，共64位）
     * 每4位表示一个字段的类型
     */
    long type;

    /**
     * 区块标志位
     * - BI_MAIN (0x01): 主块
     * - BI_MAIN_CHAIN (0x02): 主链
     * - BI_APPLIED (0x04): 已应用
     * - BI_MAIN_REF (0x08): 主块引用
     * - BI_REF (0x10): 引用
     * - BI_OURS (0x20): 我们的块
     * - BI_EXTRA (0x40): 额外块
     * - BI_REMARK (0x80): 有备注
     */
    int flags;

    // ========== 难度和链接 ==========

    /**
     * 区块难度（使用 UInt256 保证固定大小）
     */
    UInt256 difficulty;

    /**
     * 引用块的哈希（可选）
     */
    Bytes32 ref;

    /**
     * 最大难度链接的哈希（可选）
     */
    Bytes32 maxDiffLink;

    // ========== 金额和费用 ==========

    /**
     * 区块金额
     */
    XAmount amount;

    /**
     * 交易费用
     */
    XAmount fee;

    // ========== 备注 ==========

    /**
     * 备注数据（可选，最大32字节）
     */
    Bytes remark;

    // ========== 快照相关 ==========

    /**
     * 是否为快照块
     */
    boolean isSnapshot;

    /**
     * 快照信息（可选，仅快照块存在）
     */
    SnapshotInfo snapshotInfo;

    // ========== 辅助方法：状态判断 ==========

    /**
     * 是否为主块
     */
    public boolean isMainBlock() {
        return (flags & Constants.BI_MAIN) != 0;
    }

    /**
     * 是否在主链上
     */
    public boolean isOnMainChain() {
        return (flags & Constants.BI_MAIN_CHAIN) != 0;
    }

    /**
     * 是否已应用
     */
    public boolean isApplied() {
        return (flags & Constants.BI_APPLIED) != 0;
    }

    /**
     * 是否为我们的块（我们创建或挖出的）
     */
    public boolean isOurs() {
        return (flags & Constants.BI_OURS) != 0;
    }

    /**
     * 是否有备注
     */
    public boolean hasRemark() {
        return remark != null && !remark.isEmpty();
    }

    /**
     * 是否为额外块
     */
    public boolean isExtra() {
        return (flags & Constants.BI_EXTRA) != 0;
    }

    // ========== 辅助方法：计算值 ==========

    /**
     * 获取所属 epoch
     */
    public long getEpoch() {
        return timestamp / 64;
    }

    // ========== 构建器辅助方法 ==========

    public static class BlockInfoBuilder {

        /**
         * 设置为主块
         */
        public BlockInfoBuilder mainBlock(boolean isMain) {
            if (isMain) {
                this.flags |= Constants.BI_MAIN;
            } else {
                this.flags &= ~Constants.BI_MAIN;
            }
            return this;
        }

        /**
         * 设置在主链上
         */
        public BlockInfoBuilder onMainChain(boolean onChain) {
            if (onChain) {
                this.flags |= Constants.BI_MAIN_CHAIN;
            } else {
                this.flags &= ~Constants.BI_MAIN_CHAIN;
            }
            return this;
        }

        /**
         * 设置为我们的块
         */
        public BlockInfoBuilder ours(boolean isOurs) {
            if (isOurs) {
                this.flags |= Constants.BI_OURS;
            } else {
                this.flags &= ~Constants.BI_OURS;
            }
            return this;
        }

        /**
         * 标记为已应用
         */
        public BlockInfoBuilder applied(boolean isApplied) {
            if (isApplied) {
                this.flags |= Constants.BI_APPLIED;
            } else {
                this.flags &= ~Constants.BI_APPLIED;
            }
            return this;
        }
    }

    // ========== 从旧版本转换 ==========

    /**
     * 从旧版本 LegacyBlockInfo 转换
     */
    public static BlockInfo fromLegacy(LegacyBlockInfo legacy) {
        // Convert BigInteger difficulty to UInt256
        UInt256 diff;
        if (legacy.getDifficulty() == null) {
            diff = UInt256.ZERO;
        } else {
            // BigInteger to bytes, then pad to 32 bytes for UInt256
            byte[] diffBytes = legacy.getDifficulty().toByteArray();
            Bytes paddedBytes;
            if (diffBytes.length < 32) {
                // Pad with zeros on the left
                byte[] padded = new byte[32];
                System.arraycopy(diffBytes, 0, padded, 32 - diffBytes.length, diffBytes.length);
                paddedBytes = Bytes.wrap(padded);
            } else if (diffBytes.length > 32) {
                // BigInteger might have an extra sign byte, trim it
                paddedBytes = Bytes.wrap(diffBytes, diffBytes.length - 32, 32);
            } else {
                paddedBytes = Bytes.wrap(diffBytes);
            }
            diff = UInt256.fromBytes(paddedBytes);
        }

        // Convert legacy hash format to full hash format
        // Legacy hash format (24-byte truncated): [0,0,0,0,0,0,0,0, hash_24_bytes]
        // We need to reconstruct the "full hash" by extracting bytes [8-31] from legacy hash
        // and creating a 32-byte hash as [hash_24_bytes, 0,0,0,0,0,0,0,0]
        // This allows toLegacy() to correctly compute back to the original legacy format
        Bytes32 fullHash;
        if (legacy.getHashlow() != null) {
            // Extract the 24-byte hash data from legacy format[8-31]
            Bytes hashData = Bytes.wrap(legacy.getHashlow()).slice(8, 24);
            // Create full hash: put the 24 bytes at the beginning, pad with 8 zeros at the end
            MutableBytes32 tempHash = MutableBytes32.create();
            tempHash.set(0, hashData);
            fullHash = Bytes32.wrap(tempHash);
        } else {
            fullHash = null;
        }

        return BlockInfo.builder()
                .hash(fullHash)
                .timestamp(legacy.getTimestamp())
                .height(legacy.getHeight())
                .type(legacy.type)
                .flags(legacy.flags)
                .difficulty(diff)
                .ref(legacy.getRef() != null ? Bytes32.wrap(legacy.getRef()) : null)
                .maxDiffLink(legacy.getMaxDiffLink() != null ? Bytes32.wrap(legacy.getMaxDiffLink()) : null)
                .amount(legacy.getAmount())
                .fee(legacy.getFee())
                .remark(legacy.getRemark() != null ? Bytes.wrap(legacy.getRemark()) : null)
                .isSnapshot(legacy.isSnapshot())
                .snapshotInfo(legacy.getSnapshotInfo())
                .build();
    }

    /**
     * 转换为旧版本（用于兼容）
     */
    public LegacyBlockInfo toLegacy() {
        LegacyBlockInfo legacy = new LegacyBlockInfo();
        // Compute legacy 24-byte truncated hash format: 8 zero bytes + first 24 bytes of full hash
        if (hash != null) {
            MutableBytes32 legacyHash = MutableBytes32.create();
            legacyHash.set(8, hash.slice(0, 24));
            legacy.setHashlow(legacyHash.toArray());
        } else {
            legacy.setHashlow(null);
        }
        legacy.setTimestamp(timestamp);
        legacy.setHeight(height);
        legacy.type = type;
        legacy.flags = flags;
        legacy.setDifficulty(difficulty.toBigInteger());
        legacy.setRef(ref != null ? ref.toArray() : null);
        legacy.setMaxDiffLink(maxDiffLink != null ? maxDiffLink.toArray() : null);
        legacy.setAmount(amount);
        legacy.setFee(fee);
        legacy.setRemark(remark != null ? remark.toArray() : null);
        legacy.setSnapshot(isSnapshot);
        legacy.setSnapshotInfo(snapshotInfo);
        return legacy;
    }

    @Override
    public String toString() {
        return String.format(
            "BlockInfo{height=%d, hash=%s, timestamp=%d, isMain=%b, amount=%s}",
            height,
            hash != null ? hash.toHexString().substring(0, 16) + "..." : "null",
            timestamp,
            isMainBlock(),
            amount
        );
    }
}
