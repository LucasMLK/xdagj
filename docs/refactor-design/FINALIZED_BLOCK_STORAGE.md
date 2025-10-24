# XDAG 已固化块的存储策略

## 核心问题

当块被标记为`finalized`后，应该如何存储？是否要转换为纯线性链式结构？

## 设计决策：**保留完整DAG结构**

### 理由

#### 1. 签名验证需要完整数据

**问题**：如果删除DAG结构，签名无法验证

```java
// 块的hash是基于完整内容计算的
DagNode block = ...;
Bytes32 hash = calculateHash(block);  // 包括所有inputs/outputs

// 签名验证需要完整的块数据
boolean valid = verifySignature(
    block.getPublicKeys(),
    block.getSignature(),
    block.getHash()  // hash基于完整内容
);
```

如果删除inputs/outputs，hash会改变，签名验证失败！

#### 2. DAG是XDAG的本质特性

XDAG的区块包含：
- **Inputs**：引用的父块（可能有多个）
- **Outputs**：引用的子块（可能有多个）
- **Transactions**：通过inputs/outputs的amount实现

如果删除这些，就不再是XDAG了！

#### 3. 审计和追溯需要完整历史

用户可能需要：
- 追溯交易历史
- 验证历史余额
- 审计完整的DAG结构
- 分析网络拓扑

#### 4. 向后兼容性

旧节点不理解"纯线性链"格式，会导致分叉。

## 存储策略

### 策略1：完整存储（推荐用于主网）

**所有块保留完整DAG结构**

```
存储内容：
- 完整的DagNode（所有fields）
- 所有inputs/outputs
- 完整签名数据
- 原始hash值

标志：
- BI_FINALIZED：表示已固化
- BI_MAIN：表示主块
- BI_MAIN_CHAIN：表示在主链上

索引：
- main_chain: height -> block_hash
- blocks: hash -> DagNode
- epoch_index: epoch -> List<hash>
```

**优点**：
- ✅ 完整性
- ✅ 可验证性
- ✅ 审计友好
- ✅ 向后兼容

**缺点**：
- ❌ 存储空间大

### 策略2：分层存储（推荐用于轻节点）

**主链全量 + 非主块精简**

```java
public class TieredStorage {

    /**
     * 已固化的主块：完整存储
     */
    public void storeFinalizedMainBlock(DagNode mainBlock) {
        assert (mainBlock.getFlags() & BI_MAIN) != 0;
        assert (mainBlock.getFlags() & BI_FINALIZED) != 0;

        // 完整存储到 main_blocks CF
        db.put(mainBlocksCF, mainBlock.getHash().toArray(), serialize(mainBlock));

        // 建立索引
        db.put(mainChainCF,
            Longs.toByteArray(mainBlock.getHeight()),
            mainBlock.getHash().toArray());
    }

    /**
     * 已固化的非主块：可以精简或归档
     */
    public void storeFinalizedNonMainBlock(DagNode block) {
        assert (block.getFlags() & BI_MAIN) == 0;
        assert (block.getFlags() & BI_FINALIZED) != 0;

        // 选项A：完整存储（如果需要完整审计）
        db.put(blocksCF, block.getHash().toArray(), serialize(block));

        // 选项B：归档（移到单独的archive数据库）
        archiveDB.put(block.getHash().toArray(), serialize(block));

        // 选项C：只存元数据（极端节省空间，但无法验证）
        DagMetadata metadata = extractMetadata(block);
        db.put(metadataCF, block.getHash().toArray(), serialize(metadata));
    }
}
```

### 策略3：归档存储（长期运行节点）

**旧的固化块移到冷存储**

```java
public class ArchivalStorage {

    // 热存储：最近6个月的块
    private RocksDB hotDB;

    // 冷存储：6个月以前的块
    private RocksDB coldDB;

    // 或者：S3/对象存储
    private ObjectStorage cloudStorage;

    /**
     * 定期归档旧块
     */
    public void archiveOldBlocks() {
        long currentHeight = blockchain.getMainHeight();
        long archiveThreshold = currentHeight - ARCHIVE_EPOCHS; // 如 180天

        for (long h = 0; h < archiveThreshold; h++) {
            DagNode block = getMainBlockByHeight(h);

            // 移到冷存储
            coldDB.put(block.getHash().toArray(), serialize(block));

            // 从热存储删除
            hotDB.delete(block.getHash().toArray());
        }
    }
}
```

## 混合同步协议的处理

### 新节点同步已固化块

**场景**：新节点同步12天前的固化主链

```java
public class FinalizedChainSync {

    /**
     * 同步已固化的主链
     *
     * 选项A：只下载主链（快速）
     * 选项B：下载主链 + 相关DAG（完整）
     */
    public void syncFinalizedChain(long fromHeight, long toHeight, SyncMode mode) {

        if (mode == SyncMode.MAIN_CHAIN_ONLY) {
            // 只下载主链块
            List<DagNode> mainBlocks = requestMainBlocks(fromHeight, toHeight);

            for (DagNode block : mainBlocks) {
                // 验证块的hash和签名
                if (!verifyBlock(block)) {
                    throw new SyncException("Invalid block");
                }

                // 保存完整的块（保留所有DAG结构）
                blockStore.save(block);

                // 应用到链
                blockchain.tryToConnect(block);
            }

        } else if (mode == SyncMode.FULL_DAG) {
            // 下载主链 + 所有引用的块
            List<DagNode> mainBlocks = requestMainBlocks(fromHeight, toHeight);

            for (DagNode mainBlock : mainBlocks) {
                // 保存主块
                blockStore.save(mainBlock);

                // 递归下载所有引用的块
                downloadReferencedBlocks(mainBlock);

                // 应用到链
                blockchain.tryToConnect(mainBlock);
            }
        }
    }

    /**
     * 验证块（即使是固化的块也要验证）
     */
    private boolean verifyBlock(DagNode block) {
        // 1. 验证hash
        Bytes32 computedHash = calculateBlockHash(block);
        if (!computedHash.equals(block.getHash())) {
            return false;
        }

        // 2. 验证PoW难度
        if (!verifyPoW(block)) {
            return false;
        }

        // 3. 验证签名（需要完整的block数据）
        if (!verifySignature(block)) {
            return false;
        }

        // 4. 验证主链连续性（如果是主块）
        if (block.isMainBlock()) {
            DagNode prevMain = getMainBlockByHeight(block.getHeight() - 1);
            if (prevMain != null && !block.getMaxDiffLink().equals(prevMain.getHash())) {
                return false; // 主链不连续
            }
        }

        return true;
    }
}
```

### 关键点：完整性和可验证性

即使是"线性同步"固化主链，传输的仍然是**完整的DagNode**：

```java
// 网络传输的消息
public class MainBlocksReply {
    List<DagNode> blocks;  // 完整的DagNode，不是精简版！
}

// 每个DagNode包含：
DagNode {
    hash: Bytes32
    timestamp: long
    type: DagNodeType
    inputs: List<DagLink>      // 完整的inputs
    outputs: List<DagLink>     // 完整的outputs
    difficulty: UInt256
    maxDiffLink: Bytes32
    amount: XAmount
    fee: XAmount
    publicKeys: List<PublicKey>
    signature: Signature
    // ... 其他字段
}
```

## 存储空间优化

虽然保留完整DAG结构，但仍可优化：

### 1. 非主块归档

```java
// 12天前的非主块可以归档
if ((block.getFlags() & BI_FINALIZED) != 0
    && (block.getFlags() & BI_MAIN) == 0) {
    // 移到archive存储
    archiveBlock(block);
}
```

### 2. 元数据缓存

```java
// 常用查询只需元数据
public class BlockMetadataCache {
    // 轻量级元数据（180 bytes）
    DagMetadata metadata = extractMetadata(block);

    // 缓存元数据，减少完整块读取
    metadataCache.put(hash, metadata);
}
```

### 3. 压缩存储

```java
// RocksDB 压缩配置
options.setCompressionType(CompressionType.LZ4_COMPRESSION);
options.setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION);

// 已固化的块可以用更强的压缩
// 因为不需要频繁修改
```

## 总结

### 核心结论

**固化（Finalization）≠ 线性化（Linearization）**

- `markAsFinalized`：只是添加标志 + 建立索引
- 块仍然保持完整的DAG结构
- hash、签名、所有links都保留
- 完全可以验证

### 存储策略对比

| 策略 | 主块存储 | 非主块存储 | 空间 | 可验证性 | 审计能力 |
|------|---------|-----------|------|---------|---------|
| **完整存储** | 完整DAG | 完整DAG | 100% | ✅ 完全 | ✅ 完全 |
| **分层存储** | 完整DAG | 归档/精简 | 60-80% | ✅ 主链完全 | ⚠️ 部分 |
| **归档存储** | 完整DAG | 冷存储 | 变化 | ✅ 完全（需加载） | ✅ 完全（需加载） |

### 推荐配置

```java
public class StorageConfig {
    // 主网全节点：完整存储
    public static final StorageStrategy MAINNET_FULL_NODE = StorageStrategy.FULL;

    // 轻节点：分层存储
    public static final StorageStrategy LIGHT_NODE = StorageStrategy.TIERED;

    // 归档节点：冷热分离
    public static final StorageStrategy ARCHIVE_NODE = StorageStrategy.HOT_COLD;

    // 固化阈值：12天
    public static final int FINALITY_EPOCHS = 16384;

    // 归档阈值：6个月（可选）
    public static final int ARCHIVE_EPOCHS = 262144; // ≈6个月
}
```

---

**作者**: Claude Code
**日期**: 2025-01
**状态**: 设计完成
