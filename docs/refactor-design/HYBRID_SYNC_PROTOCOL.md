# XDAG 混合同步协议详细设计

## 核心思想

**利用最终确定性边界，将DAG同步分为两个阶段**：

1. **线性主链同步**（Chain Sync）：同步已确定的主块链（H - N 之前）
2. **DAG区域同步**（DAG Sync）：同步未确定的DAG区域（H - N 到 H）

## 1. 最终确定性边界（Finality Boundary）

### 定义

```java
public class FinalityBoundary {
    private static final int FINALITY_EPOCHS = 16384;  // 16384个周期后确定 ≈12天

    /**
     * 计算最终确定边界
     * @param currentMainHeight 当前主块高度
     * @return 最终确定的主块高度
     */
    public static long getFinalizedHeight(long currentMainHeight) {
        return Math.max(0, currentMainHeight - FINALITY_EPOCHS);
    }

    /**
     * 检查某个高度的块是否已经最终确定
     */
    public static boolean isFinalized(long height, long currentMainHeight) {
        return height <= getFinalizedHeight(currentMainHeight);
    }
}
```

### 最终确定性保证

在最终确定边界之前的块：

- ✅ **不可回滚**：已经被16384个周期（≈12天）的主块确认
- ✅ **唯一主链**：maxDiffLink 形成唯一的线性链
- ✅ **完整DAG**：所有引用的块都已经固化
- ✅ **余额确定**：所有交易金额已经确定
- ✅ **社区缓冲**：给予1-2周时间处理网络分区等问题

## 2. DAG到线性主链的转换

### 2.1 主链固化（Chain Solidification）

**重要说明**：固化（Finalization）**不改变块的存储格式**，只是：
1. 添加`BI_FINALIZED`标志
2. 建立主链索引（高度 -> hash）
3. 标记相关块为已固化

**块仍然保持完整的DAG结构**，包括：
- ✅ 所有inputs/outputs links
- ✅ 完整的签名数据
- ✅ 原始hash值
- ✅ 可以完整验证

这样设计的好处：
- ✅ **签名可验证**：块的hash是基于完整内容计算的
- ✅ **向后兼容**：旧节点仍能理解这些块
- ✅ **审计友好**：可以追溯完整的DAG历史
- ✅ **灵活同步**：新节点可以选择只下载主链，也可以下载完整DAG

```java
public class ChainSolidification {

    /**
     * 固化主块及其依赖
     *
     * 注意：这个操作不改变块的内容，只是：
     * 1. 标记块为BI_FINALIZED
     * 2. 更新索引
     * 3. 可选：归档孤立块
     *
     * @param mainBlock 要固化的主块
     */
    public void solidifyMainBlock(DagNode mainBlock) {
        // 1. 确认这是主链上的块
        assert mainBlock.isMainBlock();
        assert isOnMainChain(mainBlock);

        // 2. 固化标记（只改变标志位，不改变块内容）
        markAsFinalized(mainBlock.getHash());

        // 3. 递归固化所有引用的块（inputs/outputs）
        Set<Bytes32> solidifiedBlocks = new HashSet<>();
        solidifyReferencedBlocks(mainBlock, solidifiedBlocks);

        // 4. 更新索引：主链高度 -> 主块hash
        updateMainChainIndex(mainBlock.getHeight(), mainBlock.getHash());

        // 5. 可选：归档孤立块（不删除，只是移到archive存储）
        archiveOrphanBlocks(mainBlock.getHeight());
    }

    /**
     * 标记块为已固化（只改变标志，不改变内容）
     */
    private void markAsFinalized(Bytes32 blockHash) {
        // 读取块
        DagNode block = blockStore.get(blockHash);
        if (block == null) return;

        // 检查是否已经固化
        if ((block.getFlags() & BI_FINALIZED) != 0) {
            return; // 已经固化，跳过
        }

        // 更新标志（创建新的不可变对象）
        DagNode finalizedBlock = block.toBuilder()
            .flags(block.getFlags() | BI_FINALIZED)
            .build();

        // 保存（覆盖原有块）
        blockStore.save(finalizedBlock);
    }

    /**
     * 递归固化所有引用的块（带保护机制）
     */
    private void solidifyReferencedBlocks(DagNode node, Set<Bytes32> visited) {
        // 防护1: 检查是否已访问（防止循环引用）
        if (visited.contains(node.getHash())) {
            return;
        }
        visited.add(node.getHash());

        // 防护2: 检查访问深度（防止恶意深度引用）
        if (visited.size() > MAX_SOLIDIFY_BLOCKS) {
            log.warn("Solidification exceeded max blocks limit: {}", MAX_SOLIDIFY_BLOCKS);
            return;
        }

        // 固化这个块（只改标志）
        markAsFinalized(node.getHash());

        // 递归固化所有输入
        for (DagLink input : node.getInputs()) {
            DagNode inputNode = blockStore.get(input.getTargetHash());
            if (inputNode != null) {
                solidifyReferencedBlocks(inputNode, visited);
            }
        }

        // 递归固化所有输出
        for (DagLink output : node.getOutputs()) {
            DagNode outputNode = blockStore.get(output.getTargetHash());
            if (outputNode != null) {
                solidifyReferencedBlocks(outputNode, visited);
            }
        }
    }

    // 最大固化块数量（防止恶意DAG）
    private static final int MAX_SOLIDIFY_BLOCKS = 100000;
}
```

### 2.2 主链索引（Main Chain Index）

为了支持线性同步，需要建立主链索引：

```sql
-- RocksDB Column Families

-- 1. 主链索引：高度 -> 主块hash
CF: "main_chain"
Key:   [height (8 bytes)]
Value: [block_hash (32 bytes)]

-- 2. 块元数据：hash -> metadata
CF: "metadata"
Key:   [block_hash (32 bytes)]
Value: [DagMetadata (serialized)]

-- 3. 完整块数据：hash -> node
CF: "blocks"
Key:   [block_hash (32 bytes)]
Value: [DagNode (serialized)]

-- 4. Epoch索引：epoch -> block_hashes
CF: "epoch_index"
Key:   [epoch (8 bytes)]
Value: [List<block_hash>]

-- 5. 引用索引：hash -> referencing_hashes
CF: "refs"
Key:   [block_hash (32 bytes)]
Value: [List<referencing_hash>]
```

### 2.3 主链查询

```java
public class MainChainQuery {

    /**
     * 按高度范围获取主链块
     */
    public List<DagNode> getMainBlocksByHeightRange(long fromHeight, long toHeight) {
        List<DagNode> result = new ArrayList<>();

        // 从 main_chain CF 批量查询
        for (long h = fromHeight; h <= toHeight; h++) {
            byte[] key = Longs.toByteArray(h);
            byte[] hashBytes = db.get(mainChainCF, key);

            if (hashBytes != null) {
                Bytes32 hash = Bytes32.wrap(hashBytes);
                DagNode block = blockStore.get(hash);
                result.add(block);
            }
        }

        return result;
    }

    /**
     * 验证主链连续性
     */
    public boolean verifyMainChain(long fromHeight, long toHeight) {
        DagNode prevBlock = null;

        for (long h = fromHeight; h <= toHeight; h++) {
            DagNode block = getMainBlockByHeight(h);

            if (block == null) {
                return false; // 主链缺失
            }

            if (prevBlock != null) {
                // 验证 maxDiffLink 指向前一个主块
                if (!block.getMaxDiffLink().equals(prevBlock.getHash())) {
                    return false; // 主链不连续
                }
            }

            prevBlock = block;
        }

        return true;
    }
}
```

## 3. 混合同步协议

### 3.1 同步阶段划分

```
Timeline (Height):
    0                H-16384                         H
    |----------------------|-------------------------|
    |  Finalized Chain     |    Active DAG          |
    |  (Linear Sync)       |    (DAG Sync)          |
    |  (12天前)            |    (最近12天)           |
    |----------------------|-------------------------|
```

### 3.2 同步流程

```java
public class HybridSyncCoordinator {

    private static final int FINALITY_EPOCHS = 16384;  // ≈12天
    private static final int BATCH_SIZE = 1000;

    public void startSync() {
        // Step 1: 查询对方的高度
        long remoteHeight = queryRemoteHeight();
        long localHeight = blockchain.getMainHeight();

        if (remoteHeight <= localHeight) {
            log.info("Already synced");
            return;
        }

        // Step 2: 计算同步范围
        long finalizedHeight = Math.max(0, remoteHeight - FINALITY_EPOCHS);

        // Step 3: Phase 1 - 线性主链同步（并行批量）
        if (localHeight < finalizedHeight) {
            syncFinalizedChain(localHeight, finalizedHeight);
        }

        // Step 4: Phase 2 - DAG区域同步
        syncActiveDAG(finalizedHeight, remoteHeight);

        // Step 5: Solidification - 补全缺失块
        solidifyDAG();
    }

    /**
     * Phase 1: 线性主链同步
     */
    private void syncFinalizedChain(long fromHeight, long toHeight) {
        log.info("Syncing finalized chain: {} -> {}", fromHeight, toHeight);

        // 分批并行下载
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (long start = fromHeight; start < toHeight; start += BATCH_SIZE) {
            long end = Math.min(start + BATCH_SIZE - 1, toHeight);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                syncMainBlockBatch(start, end);
            }, executor);

            futures.add(future);
        }

        // 等待所有批次完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * 同步一批主块
     */
    private void syncMainBlockBatch(long fromHeight, long toHeight) {
        // 1. 请求主块
        MainBlocksRequest request = new MainBlocksRequest(fromHeight, toHeight);
        MainBlocksReply reply = p2pClient.request(request);

        // 2. 验证主链连续性
        if (!verifyChainContinuity(reply.getBlocks())) {
            throw new SyncException("Invalid main chain");
        }

        // 3. 批量保存
        blockStore.saveAll(reply.getBlocks());

        // 4. 应用到链（更新余额等）
        for (DagNode block : reply.getBlocks()) {
            blockchain.tryToConnect(block);
        }
    }

    /**
     * Phase 2: DAG区域同步
     */
    private void syncActiveDAG(long fromHeight, long toHeight) {
        log.info("Syncing active DAG: {} -> {}", fromHeight, toHeight);

        // 按epoch同步
        long fromEpoch = fromHeight; // 假设 1 epoch = 1 height
        long toEpoch = toHeight;

        for (long epoch = fromEpoch; epoch <= toEpoch; epoch++) {
            syncEpoch(epoch);
        }
    }

    /**
     * 同步一个epoch的所有块
     */
    private void syncEpoch(long epoch) {
        // 1. 请求这个epoch的所有块hash
        EpochBlocksRequest request = new EpochBlocksRequest(epoch);
        EpochBlocksReply reply = p2pClient.request(request);

        // 2. 过滤已有的块
        List<Bytes32> missingHashes = reply.getHashes().stream()
            .filter(hash -> !blockStore.hasBlock(hash))
            .collect(Collectors.toList());

        // 3. 批量请求缺失的块
        if (!missingHashes.isEmpty()) {
            BlocksRequest blocksReq = new BlocksRequest(missingHashes);
            BlocksReply blocksReply = p2pClient.request(blocksReq);

            // 4. 保存块
            blockStore.saveAll(blocksReply.getBlocks());

            // 5. 应用到链
            for (DagNode block : blocksReply.getBlocks()) {
                blockchain.tryToConnect(block);
            }
        }
    }

    /**
     * Phase 3: Solidification - 补全缺失块
     */
    private void solidifyDAG() {
        log.info("Running solidification to fill missing blocks");

        Set<Bytes32> missingBlocks = detectMissingReferences();

        while (!missingBlocks.isEmpty()) {
            log.info("Found {} missing blocks, requesting...", missingBlocks.size());

            // 批量请求
            BlocksRequest request = new BlocksRequest(new ArrayList<>(missingBlocks));
            BlocksReply reply = p2pClient.request(request);

            // 保存
            blockStore.saveAll(reply.getBlocks());

            // 应用
            for (DagNode block : reply.getBlocks()) {
                blockchain.tryToConnect(block);
            }

            // 重新检测
            missingBlocks = detectMissingReferences();
        }

        log.info("Solidification completed");
    }

    /**
     * 检测缺失的引用块
     */
    private Set<Bytes32> detectMissingReferences() {
        Set<Bytes32> missing = new HashSet<>();

        // 遍历所有块，检查引用的块是否存在
        blockStore.iterateAll(block -> {
            for (DagLink input : block.getInputs()) {
                if (!blockStore.hasBlock(input.getTargetHash())) {
                    missing.add(input.getTargetHash());
                }
            }
            for (DagLink output : block.getOutputs()) {
                if (!blockStore.hasBlock(output.getTargetHash())) {
                    missing.add(output.getTargetHash());
                }
            }
        });

        return missing;
    }
}
```

## 4. P2P消息协议

### 4.1 新增消息类型

```java
// 1. 查询高度
public class HeightRequest {
    // 空请求
}

public class HeightReply {
    long mainHeight;          // 主块高度
    long finalizedHeight;     // 最终确定高度
    Bytes32 mainBlockHash;    // 当前主块hash
}

// 2. 批量请求主块（按高度范围）
public class MainBlocksRequest {
    long fromHeight;
    long toHeight;
    int maxBlocks = 1000;     // 最多返回1000个块
}

public class MainBlocksReply {
    List<DagNode> blocks;     // 主块列表（按高度排序）
}

// 3. 请求epoch的所有块hash
public class EpochBlocksRequest {
    long epoch;
}

public class EpochBlocksReply {
    long epoch;
    List<Bytes32> hashes;     // 这个epoch的所有块hash
}

// 4. 批量请求完整块（按hash）
public class BlocksRequest {
    List<Bytes32> hashes;
    int maxBlocks = 1000;
}

public class BlocksReply {
    List<DagNode> blocks;
}
```

### 4.2 使用 xdagj-p2p 库

```java
public class XdagP2PClient {

    private final P2PService p2pService;

    /**
     * 请求对方高度
     */
    public HeightReply queryHeight(PeerId peerId) {
        return p2pService.request(peerId, new HeightRequest())
            .thenApply(reply -> (HeightReply) reply)
            .get();
    }

    /**
     * 批量请求主块
     */
    public MainBlocksReply requestMainBlocks(PeerId peerId, long from, long to) {
        MainBlocksRequest request = new MainBlocksRequest(from, to);
        return p2pService.request(peerId, request)
            .thenApply(reply -> (MainBlocksReply) reply)
            .get();
    }

    /**
     * 请求epoch的所有块hash
     */
    public EpochBlocksReply requestEpochBlocks(PeerId peerId, long epoch) {
        EpochBlocksRequest request = new EpochBlocksRequest(epoch);
        return p2pService.request(peerId, request)
            .thenApply(reply -> (EpochBlocksReply) reply)
            .get();
    }

    /**
     * 批量请求完整块
     */
    public BlocksReply requestBlocks(PeerId peerId, List<Bytes32> hashes) {
        BlocksRequest request = new BlocksRequest(hashes);
        return p2pService.request(peerId, request)
            .thenApply(reply -> (BlocksReply) reply)
            .get();
    }
}
```

## 5. 性能分析

### 5.1 线性主链同步

假设同步 100万 主块：

| 方式 | 网络往返 | 耗时估算 |
|------|---------|---------|
| **旧SUMS方式** | ~11000次 | 数小时 |
| **新主链同步** | ~1000次 (批量1000块) | 10-15分钟 |

**提升**: **10-20倍**

### 5.2 DAG区域同步

假设同步最近 1000 epochs，每个epoch平均 10 个块：

| 方式 | 网络往返 | 耗时估算 |
|------|---------|---------|
| **SUMS方式** | ~1000次 | 5-10分钟 |
| **Epoch查询** | ~1000次 (每epoch一次) | 2-3分钟 |

**提升**: **2-3倍**

### 5.3 Solidification

假设缺失 5% 的块（500个）：

| 方式 | 网络往返 | 耗时估算 |
|------|---------|---------|
| **逐个请求** | 500次 | 5分钟 |
| **批量请求** | 1次 (批量500) | 10秒 |

**提升**: **30倍**

### 5.4 总体效果

完整同步100万主块 + 10000个DAG块：

| 阶段 | 耗时 |
|------|------|
| 线性主链同步 | 10-15分钟 |
| DAG区域同步 | 2-3分钟 |
| Solidification | 1-2分钟 |
| **总计** | **15-20分钟** |

对比当前的数小时，提升 **10-15倍**！

## 6. 关键优势

### 6.1 充分利用最终确定性

- ✅ 已确定的主链可以**线性同步**，类似BTC/ETH
- ✅ 未确定的DAG区域用**DAG同步**，保持灵活性

### 6.2 批量并行

- ✅ 主链批量下载（1000块/批）
- ✅ 多批并行（4-8个并发）
- ✅ 网络利用率高

### 6.3 进度可预测

- ✅ 线性主链有明确的高度
- ✅ 用户可以看到同步进度条
- ✅ 估算剩余时间

### 6.4 DAG完整性

- ✅ Solidification自动补全
- ✅ 引用索引快速检测缺失
- ✅ 保证DAG >99%完整性

### 6.5 兼容性

- ✅ 协议支持版本协商
- ✅ 可以兼容旧节点（降级到SUMS）
- ✅ 平滑过渡

## 7. 实施步骤

### Phase 1: 主链索引和固化 (Week 1-2)

- [ ] 实现主链索引 (main_chain CF)
- [ ] 实现 `ChainSolidification`
- [ ] 实现 `MainChainQuery`
- [ ] 单元测试

### Phase 2: P2P消息协议 (Week 3-4)

- [ ] 定义新消息类型
- [ ] 集成 xdagj-p2p
- [ ] 实现消息序列化
- [ ] 协议测试

### Phase 3: 混合同步实现 (Week 5-6)

- [ ] 实现 `HybridSyncCoordinator`
- [ ] 实现线性主链同步
- [ ] 实现DAG区域同步
- [ ] 实现Solidification

### Phase 4: 性能优化 (Week 7)

- [ ] 批量并行优化
- [ ] 缓存优化
- [ ] 性能基准测试

### Phase 5: 测试部署 (Week 8)

- [ ] 集成测试
- [ ] 测试网部署
- [ ] 监控和调优

## 8. 总结

这个混合同步设计：

✅ **解决了DAG到线性链转换**：通过最终确定性边界
✅ **充分利用确定性**：已确定部分线性同步，未确定部分DAG同步
✅ **性能提升10-15倍**：批量并行 + 减少网络往返
✅ **DAG完整性保证**：Solidification自动补全
✅ **进度可预测**：明确的高度和进度条
✅ **向后兼容**：支持协议协商

这是一个真正高效、简单易用的同步协议！

---

**版本**: v1.0
**创建时间**: 2025-01
**最后更新**: 2025-10-29
**作者**: Claude Code
**状态**: Phase 3核心设计完成，待网络集成
