# DagChain 接口设计与迁移指南

**日期**: 2025-11-05
**版本**: v5.1
**状态**: 设计完成，待实现

---

## 执行摘要

为了清晰区分新旧架构，我们创建了新的 `DagChain` 接口来替代 `Blockchain` 接口。
`DagChain` 接口基于 XDAG v5.1 的 epoch 共识和 DAG 架构进行设计，解决了原接口的多个设计问题。

### 核心改进

| 改进点 | 说明 | 优势 |
|-------|------|------|
| ✅ **清晰命名** | Position vs Epoch, Candidate vs Winner | 避免混淆，语义明确 |
| ✅ **完整 Epoch 支持** | 13 个 epoch 相关方法 | 完整支持 epoch 共识 |
| ✅ **累积难度暴露** | 公开计算方法 | 支持链选择逻辑 |
| ✅ **DAG 验证** | DAG 规则验证和遍历 | 确保 DAG 结构正确性 |
| ✅ **映射方法** | Position ↔ Epoch 双向查询 | 解决查询关系问题 |

---

## 1. 核心概念澄清

### 1.1 Position vs Epoch

这是 DagChain 设计中最关键的概念区分：

```
Position（主链位置）:
- 主链上的序号（1, 2, 3, ...）
- 总是连续递增
- 每个 position 对应唯一的 main block
- 用于遍历主链、查询第 N 个主块

Epoch（时间纪元）:
- 64 秒时间窗口（epoch = timestamp / 64）
- 可能为空（某些 epoch 没有块）
- 每个 epoch 最多 1 个 winner block
- 用于时间查询、共识验证

关键区别：
❌ Position ≠ Epoch（不是 1:1 映射）
✅ Position 是主链序号，Epoch 是时间窗口
```

**实际示例**：

```
Epoch 1000 → Block A wins → Position 1
Epoch 1001 → Block B wins → Position 2
Epoch 1002 → (no blocks) → (no position)
Epoch 1003 → Block C wins → Position 3
Epoch 1004 → Block D wins → Position 4

查询对比：
getMainBlockAtPosition(3)    → Block C (主链第3个块)
getWinnerBlockInEpoch(1003)  → Block C (Epoch 1003 的获胜块)
getWinnerBlockInEpoch(1002)  → null (空 epoch)

Position 3 对应 Epoch 1003（不是 Epoch 3！）
```

### 1.2 Candidate vs Winner

```
Candidate Block（候选块）:
- 在某个 epoch 内创建的所有块
- 包括获胜块、失败块、孤块
- 用于分析竞争情况

Winner Block（获胜块）:
- Epoch 内 hash 最小的块
- 不是孤块（被其他块引用或成为主块）
- 成为主链的一部分
```

### 1.3 Cumulative Difficulty（累积难度）

XDAG 使用累积难度选择主链：

```java
// Block work = MAX_UINT256 / hash（XDAG 哲学：更小的 hash = 更多工作量）
UInt256 blockWork = calculateBlockWork(block.getHash());

// Cumulative difficulty = parent difficulty + block work
UInt256 cumulativeDiff = parent.getInfo().getDifficulty().add(blockWork);

// 主链选择：累积难度最大的链成为主链
if (cumulativeDiff.compareTo(currentMaxDiff) > 0) {
    // This chain becomes the main chain
}
```

---

## 2. 接口方法分组

### 2.1 主链查询（Position-Based）

这组方法按主链位置查询，用于遍历主链：

```java
// 查询主链第 N 个块
Block getMainBlockAtPosition(long position);

// 获取主链总长度
long getMainChainLength();

// Position → Epoch 映射
long getEpochOfMainBlock(long position);

// 列出最近的主块
List<Block> listMainBlocks(int count);

// 获取主链路径
List<Block> getMainChainPath(Bytes32 hash);
```

**使用场景**：
- 遍历主链：`for (long p = 1; p <= getMainChainLength(); p++)`
- 计算供应量：`getSupply(getMainChainLength())`
- 查询第 N 个主块：`getMainBlockAtPosition(100)`

### 2.2 Epoch 查询（Time-Based）

这组方法按时间窗口查询，用于共识验证：

```java
// 获取当前 epoch
long getCurrentEpoch();

// 获取 epoch 时间范围
long[] getEpochTimeRange(long epoch);

// 获取 epoch 内的所有候选块
List<Block> getCandidateBlocksInEpoch(long epoch);

// 获取 epoch 的获胜块
Block getWinnerBlockInEpoch(long epoch);

// Epoch → Position 映射
long getPositionOfWinnerBlock(long epoch);

// 获取 epoch 统计
EpochStats getEpochStats(long epoch);
```

**使用场景**：
- 查询某时间段的块：`getCandidateBlocksInEpoch(1000)`
- 验证共识：`getWinnerBlockInEpoch(currentEpoch)`
- 分析网络活跃度：`getEpochStats(epoch)`

### 2.3 累积难度计算

```java
// 计算块的累积难度
UInt256 calculateCumulativeDifficulty(Block block);

// 计算块的工作量
UInt256 calculateBlockWork(Bytes32 hash);
```

**使用场景**：
- `tryToConnect()` 中判断是否成为主块
- `checkNewMain()` 中比较不同链的难度
- 区块浏览器显示难度信息

### 2.4 DAG 结构验证

```java
// 验证 DAG 规则
DAGValidationResult validateDAGRules(Block block);

// 检查是否在主链上
boolean isBlockInMainChain(Bytes32 hash);

// 获取引用关系（反向查询）
List<Block> getBlockReferences(Bytes32 hash);
```

**使用场景**：
- `tryToConnect()` 中验证 DAG 规则
- 检查孤块是否被引用
- 追踪 transaction 有效性

### 2.5 块创建和导入

```java
// 创建候选块（挖矿）
Block createCandidateBlock();

// 创建创世块
Block createGenesisBlock(ECKeyPair key, long timestamp);

// 创建奖励块
Block createRewardBlock(...);

// 导入块
ImportResult tryToConnect(Block block);
```

### 2.6 链管理

```java
// 检查并更新主链
void checkNewMain();

// 启动主链检查线程
void startCheckMain(long period);

// 停止主链检查线程
void stopCheckMain();
```

---

## 3. 关键方法详解

### 3.1 tryToConnect() 流程

```java
ImportResult result = dagChain.tryToConnect(block);

// 内部流程：
// 1. 基础验证（timestamp, structure, PoW）
// 2. Link 验证（Transaction + Block references）
// 3. DAG 规则验证
//    - validateDAGRules(block)
//    - 检查循环、时间窗口、链接数量等
// 4. 累积难度计算
//    - parentDiff = findMaxParentDifficulty(block)
//    - blockWork = calculateBlockWork(block.hash)
//    - cumulativeDiff = parentDiff + blockWork
// 5. 主链判断
//    if (cumulativeDiff > currentMaxDiff) {
//        height = mainChainLength + 1  // 成为主块
//        result = IMPORTED_BEST
//    } else {
//        height = 0  // 孤块
//        result = IMPORTED_NOT_BEST
//    }
// 6. Epoch 竞争检查
//    - 检查是否是 epoch 内 hash 最小的块
// 7. 保存并通知
//    - blockStore.saveBlock(block)
//    - blockStore.saveBlockInfo(info)
//    - onNewBlock(block)
```

### 3.2 checkNewMain() 流程

```java
dagChain.checkNewMain();

// 内部流程：
// 1. 扫描最近的 epochs（例如最近 100 个 epoch）
//    for (epoch = currentEpoch - 100; epoch <= currentEpoch; epoch++) {
//        checkEpochWinner(epoch)
//    }
// 2. 确定每个 epoch 的获胜块
//    - 找出 epoch 内所有候选块
//    - 选择 hash 最小且非孤块的块作为 winner
// 3. 计算所有竞争链的累积难度
//    - 对每条链从 genesis 到当前块累加难度
// 4. 链重组（如果需要）
//    if (alternativeChainDiff > mainChainDiff) {
//        reorganizeToChain(alternativeChain)
//        updateMainBlocks(alternativeChain)
//    }
// 5. 更新统计信息
//    - chainStats.mainBlockCount
//    - chainStats.maxDifficulty
//    - chainStats.topBlock
```

### 3.3 Position ↔ Epoch 映射

```java
// Position → Epoch
long epoch = dagChain.getEpochOfMainBlock(position);

// 实现逻辑：
// 1. 获取主块：block = getMainBlockAtPosition(position)
// 2. 计算 epoch：epoch = block.timestamp / 64
// 3. 返回 epoch

// Epoch → Position
long position = dagChain.getPositionOfWinnerBlock(epoch);

// 实现逻辑：
// 1. 获取获胜块：block = getWinnerBlockInEpoch(epoch)
// 2. 如果 block == null，返回 -1
// 3. 返回 block.info.height
```

---

## 4. 数据结构定义

### 4.1 EpochStats

```java
/**
 * Epoch 统计信息
 */
@Data
@Builder
public class EpochStats {
    /** Epoch 编号 */
    private final long epoch;

    /** 开始时间 (epoch * 64) */
    private final long startTime;

    /** 结束时间 ((epoch + 1) * 64) */
    private final long endTime;

    /** 候选块总数 */
    private final int totalBlocks;

    /** 获胜块 hash（null 如果没有获胜块） */
    private final Bytes32 winningBlockHash;

    /** Epoch 内平均出块时间（秒） */
    private final double averageBlockTime;

    /** 该 epoch 增加的总难度 */
    private final UInt256 totalDifficulty;

    /** 是否有主块 */
    private final boolean hasMainBlock;
}
```

### 4.2 DAGValidationResult

```java
/**
 * DAG 验证结果
 */
@Data
@Builder
public class DAGValidationResult {
    /** 验证是否通过 */
    private final boolean valid;

    /** 错误码 */
    private final DAGErrorCode errorCode;

    /** 错误信息 */
    private final String errorMessage;

    /** 冲突块 hash（如果适用） */
    private final Bytes32 conflictingBlockHash;

    public enum DAGErrorCode {
        VALID,
        CYCLE_DETECTED,              // 检测到循环
        TIME_WINDOW_VIOLATION,       // 时间窗口违规
        INVALID_LINK_COUNT,          // 链接数量违规
        TIMESTAMP_ORDER_VIOLATION,   // 时间戳顺序违规
        TRAVERSAL_DEPTH_EXCEEDED     // 遍历深度超限
    }

    public static DAGValidationResult valid() {
        return DAGValidationResult.builder()
            .valid(true)
            .errorCode(DAGErrorCode.VALID)
            .build();
    }

    public static DAGValidationResult invalid(DAGErrorCode code, String message) {
        return DAGValidationResult.builder()
            .valid(false)
            .errorCode(code)
            .errorMessage(message)
            .build();
    }
}
```

---

## 5. 从 Blockchain 迁移到 DagChain

### 5.1 方法映射表

| Blockchain 旧方法 | DagChain 新方法 | 变化说明 |
|------------------|----------------|---------|
| `createMainBlock()` | `createCandidateBlock()` | 重命名，更准确 |
| `getBlockByHeight(long)` | `getMainBlockAtPosition(long)` | 重命名，避免歧义 |
| `getLatestMainBlockNumber()` | `getMainChainLength()` | 重命名，统一术语 |
| ❌ 不存在 | `getEpochOfMainBlock(long)` | **新增** - Position → Epoch 映射 |
| ❌ 不存在 | `getCurrentEpoch()` | **新增** - 获取当前 epoch |
| ❌ 不存在 | `getCandidateBlocksInEpoch(long)` | **新增** - 获取 epoch 候选块 |
| ❌ 不存在 | `getWinnerBlockInEpoch(long)` | **新增** - 获取 epoch 获胜块 |
| ❌ 不存在 | `getPositionOfWinnerBlock(long)` | **新增** - Epoch → Position 映射 |
| ❌ 不存在 | `getEpochTimeRange(long)` | **新增** - 获取 epoch 时间范围 |
| ❌ 不存在 | `getEpochStats(long)` | **新增** - 获取 epoch 统计 |
| ❌ 不存在 | `calculateCumulativeDifficulty(Block)` | **新增** - 计算累积难度 |
| ❌ 不存在 | `calculateBlockWork(Bytes32)` | **新增** - 计算块工作量 |
| ❌ 不存在 | `validateDAGRules(Block)` | **新增** - 验证 DAG 规则 |
| ❌ 不存在 | `isBlockInMainChain(Bytes32)` | **新增** - 检查是否在主链 |
| ❌ 不存在 | `getBlockReferences(Bytes32)` | **新增** - 获取引用关系 |
| ❌ 不存在 | `getMainChainPath(Bytes32)` | **新增** - 获取主链路径 |
| `tryToConnect(Block)` | `tryToConnect(Block)` | 保留，但实现需修复 |
| `checkNewMain()` | `checkNewMain()` | 保留，但实现需修复 |
| `getBlockByHash(...)` | `getBlockByHash(...)` | 保留 |
| 其他方法 | 相同 | 保留 |

### 5.2 代码迁移示例

#### 迁移前（使用 Blockchain）

```java
// 查询主链第 100 个块
Block block = blockchain.getBlockByHeight(100);

// 获取主链长度
long length = blockchain.getLatestMainBlockNumber();

// 创建挖矿候选块
Block candidate = blockchain.createMainBlock();
```

#### 迁移后（使用 DagChain）

```java
// 查询主链第 100 个块
Block block = dagChain.getMainBlockAtPosition(100);

// 获取主链长度
long length = dagChain.getMainChainLength();

// 创建挖矿候选块
Block candidate = dagChain.createCandidateBlock();
```

#### 新增功能示例

```java
// 查询 Epoch 1000 的所有候选块
List<Block> candidates = dagChain.getCandidateBlocksInEpoch(1000);

// 查询 Epoch 1000 的获胜块
Block winner = dagChain.getWinnerBlockInEpoch(1000);

// Position ↔ Epoch 映射
long epoch = dagChain.getEpochOfMainBlock(100);     // 第100个主块在哪个 epoch
long position = dagChain.getPositionOfWinnerBlock(1000);  // Epoch 1000 的获胜块是第几个

// 计算累积难度
UInt256 cumulativeDiff = dagChain.calculateCumulativeDifficulty(block);

// 验证 DAG 规则
DAGValidationResult result = dagChain.validateDAGRules(block);
if (!result.isValid()) {
    System.out.println("DAG validation failed: " + result.getErrorMessage());
}

// 检查块是否在主链上
boolean inMainChain = dagChain.isBlockInMainChain(blockHash);
```

---

## 6. 实现计划

### Phase 1: 创建 DagChainImpl 骨架（2 天）

```java
public class DagChainImpl implements DagChain {
    // 实现所有接口方法的骨架
    // 大部分方法直接委托给 BlockchainImpl 的现有实现

    @Override
    public Block getMainBlockAtPosition(long position) {
        // 委托给 BlockchainImpl.getBlockByHeight()
        return blockchainImpl.getBlockByHeight(position);
    }

    @Override
    public long getMainChainLength() {
        // 委托给 BlockchainImpl.getLatestMainBlockNumber()
        return blockchainImpl.getLatestMainBlockNumber();
    }

    // ... 其他方法类似
}
```

### Phase 2: 实现核心方法（5 天）

#### 2.1 实现 tryToConnect()（2 天）

```java
@Override
public synchronized ImportResult tryToConnect(Block block) {
    // 1. 基础验证
    if (!validateBasic(block)) {
        return ImportResult.INVALID_BLOCK;
    }

    // 2. Link 验证
    if (!validateLinks(block)) {
        return ImportResult.NO_PARENT;
    }

    // 3. DAG 规则验证
    DAGValidationResult dagResult = validateDAGRules(block);
    if (!dagResult.isValid()) {
        return ImportResult.INVALID_BLOCK;
    }

    // 4. 累积难度计算
    UInt256 cumulativeDiff = calculateCumulativeDifficulty(block);

    // 5. 主链判断
    boolean isBest = cumulativeDiff.compareTo(chainStats.getMaxDifficulty()) > 0;
    long height = isBest ? chainStats.getMainBlockCount() + 1 : 0;

    // 6. 保存 BlockInfo
    BlockInfo info = BlockInfo.builder()
        .hash(block.getHash())
        .timestamp(block.getTimestamp())
        .height(height)
        .difficulty(cumulativeDiff)
        .build();

    blockStore.saveBlockInfo(info);
    blockStore.saveBlock(block.toBuilder().info(info).build());

    // 7. 更新统计
    if (isBest) {
        updateChainStats(info);
    }

    // 8. 通知
    onNewBlock(block);

    return isBest ? ImportResult.IMPORTED_BEST : ImportResult.IMPORTED_NOT_BEST;
}
```

#### 2.2 实现 calculateCumulativeDifficulty()（1 天）

```java
@Override
public UInt256 calculateCumulativeDifficulty(Block block) {
    // 1. 找出所有父块中累积难度最大的
    UInt256 maxParentDiff = UInt256.ZERO;
    for (Link link : block.getLinks()) {
        if (link.isBlock()) {
            Block parent = getBlockByHash(link.getTargetHash(), false);
            if (parent != null && parent.getInfo() != null) {
                UInt256 parentDiff = parent.getInfo().getDifficulty();
                if (parentDiff.compareTo(maxParentDiff) > 0) {
                    maxParentDiff = parentDiff;
                }
            }
        }
    }

    // 2. 计算本块的工作量
    UInt256 blockWork = calculateBlockWork(block.getHash());

    // 3. 累积难度 = 父块最大难度 + 本块工作量
    return maxParentDiff.add(blockWork);
}

@Override
public UInt256 calculateBlockWork(Bytes32 hash) {
    BigInteger hashValue = new BigInteger(1, hash.toArray());

    if (hashValue.equals(BigInteger.ZERO)) {
        return UInt256.MAX_VALUE;  // 理论上不可能
    }

    BigInteger maxUint256 = UInt256.MAX_VALUE.toBigInteger();
    BigInteger work = maxUint256.divide(hashValue);

    return UInt256.valueOf(work);
}
```

#### 2.3 实现 checkNewMain()（2 天）

```java
@Override
public synchronized void checkNewMain() {
    long currentEpoch = getCurrentEpoch();

    // 1. 扫描最近 100 个 epoch
    for (long epoch = currentEpoch - 100; epoch <= currentEpoch; epoch++) {
        checkEpochWinner(epoch);
    }

    // 2. 检查是否需要链重组
    checkChainReorganization();

    // 3. 更新统计
    updateChainStats();
}

private void checkEpochWinner(long epoch) {
    // 获取该 epoch 的所有候选块
    List<Block> candidates = getCandidateBlocksInEpoch(epoch);

    if (candidates.isEmpty()) {
        return;  // 空 epoch
    }

    // 找出 hash 最小且非孤块的块
    Block winner = findWinnerBlock(candidates);

    if (winner != null && winner.getInfo().getHeight() == 0) {
        // 需要提升为主块
        promoteToMainBlock(winner);
    }
}
```

### Phase 3: 实现 Epoch 查询方法（3 天）

```java
@Override
public List<Block> getCandidateBlocksInEpoch(long epoch) {
    long[] timeRange = getEpochTimeRange(epoch);
    return getBlocksByTimeRange(timeRange[0], timeRange[1]);
}

@Override
public Block getWinnerBlockInEpoch(long epoch) {
    List<Block> candidates = getCandidateBlocksInEpoch(epoch);
    return findWinnerBlock(candidates);
}

@Override
public long getPositionOfWinnerBlock(long epoch) {
    Block winner = getWinnerBlockInEpoch(epoch);
    if (winner == null || winner.getInfo() == null) {
        return -1;
    }
    return winner.getInfo().getHeight();
}

@Override
public long getEpochOfMainBlock(long position) {
    Block block = getMainBlockAtPosition(position);
    if (block == null) {
        return -1;
    }
    return block.getEpoch();
}
```

### Phase 4: 实现 DAG 验证方法（3 天）

```java
@Override
public DAGValidationResult validateDAGRules(Block block) {
    // 1. 检查循环
    if (hasCycle(block)) {
        return DAGValidationResult.invalid(
            DAGErrorCode.CYCLE_DETECTED,
            "Block creates a cycle in DAG"
        );
    }

    // 2. 检查时间窗口
    long currentEpoch = getCurrentEpoch();
    for (Link link : block.getLinks()) {
        if (link.isBlock()) {
            Block refBlock = getBlockByHash(link.getTargetHash(), false);
            if (refBlock != null) {
                long refEpoch = refBlock.getEpoch();
                if (currentEpoch - refEpoch > 16384) {
                    return DAGValidationResult.invalid(
                        DAGErrorCode.TIME_WINDOW_VIOLATION,
                        "Referenced block is too old (>16384 epochs)"
                    );
                }
            }
        }
    }

    // 3. 检查链接数量
    long blockLinkCount = block.getLinks().stream()
        .filter(link -> !link.isTransaction())
        .count();

    if (blockLinkCount < 1 || blockLinkCount > 16) {
        return DAGValidationResult.invalid(
            DAGErrorCode.INVALID_LINK_COUNT,
            "Block must have 1-16 block links"
        );
    }

    // 4. 检查时间戳顺序
    for (Link link : block.getLinks()) {
        if (link.isBlock()) {
            Block refBlock = getBlockByHash(link.getTargetHash(), false);
            if (refBlock != null && refBlock.getTimestamp() >= block.getTimestamp()) {
                return DAGValidationResult.invalid(
                    DAGErrorCode.TIMESTAMP_ORDER_VIOLATION,
                    "Referenced block timestamp >= current block timestamp"
                );
            }
        }
    }

    // 5. 检查遍历深度
    if (getDepthFromGenesis(block) > 1000) {
        return DAGValidationResult.invalid(
            DAGErrorCode.TRAVERSAL_DEPTH_EXCEEDED,
            "Block depth from genesis exceeds 1000 layers"
        );
    }

    return DAGValidationResult.valid();
}
```

### Phase 5: 单元测试和集成测试（3 天）

```java
@Test
public void testTryToConnect_FirstBlock() {
    // 第一个块应该成为主块
    Block block = createTestBlock();
    ImportResult result = dagChain.tryToConnect(block);

    assertEquals(ImportResult.IMPORTED_BEST, result);
    assertEquals(1, dagChain.getMainChainLength());
    assertEquals(block, dagChain.getMainBlockAtPosition(1));
}

@Test
public void testEpochMapping() {
    // Position ↔ Epoch 映射测试
    Block block1 = createBlockInEpoch(1000);
    dagChain.tryToConnect(block1);

    assertEquals(1, dagChain.getMainChainLength());
    assertEquals(1000, dagChain.getEpochOfMainBlock(1));
    assertEquals(1, dagChain.getPositionOfWinnerBlock(1000));
}

@Test
public void testCumulativeDifficulty() {
    // 累积难度计算测试
    Block genesis = dagChain.createGenesisBlock(key, timestamp);
    dagChain.tryToConnect(genesis);

    UInt256 genesisDiff = genesis.getInfo().getDifficulty();

    Block block2 = createBlockWithParent(genesis);
    UInt256 expectedDiff = genesisDiff.add(calculateBlockWork(block2.getHash()));
    UInt256 actualDiff = dagChain.calculateCumulativeDifficulty(block2);

    assertEquals(expectedDiff, actualDiff);
}
```

---

## 7. 时间线和里程碑

| 阶段 | 任务 | 时间 | 里程碑 |
|-----|------|------|-------|
| **Phase 1** | 创建 DagChainImpl 骨架 | 2 天 | 接口实现完成，可编译 |
| **Phase 2** | 实现核心方法 | 5 天 | tryToConnect, calculateCumulativeDifficulty, checkNewMain |
| **Phase 3** | 实现 Epoch 查询 | 3 天 | 所有 epoch 相关方法可用 |
| **Phase 4** | 实现 DAG 验证 | 3 天 | DAG 规则验证完整 |
| **Phase 5** | 测试 | 3 天 | 单元测试 + 集成测试通过 |
| **Total** |  | **16 天** | DagChain 完整实现 |

---

## 8. 总结

### 设计完成

✅ **DagChain 接口已完成**：`src/main/java/io/xdag/core/DagChain.java`

### 关键改进

1. ✅ **清晰的概念区分**: Position vs Epoch, Candidate vs Winner
2. ✅ **完整的 Epoch 支持**: 13 个 epoch 相关方法
3. ✅ **累积难度暴露**: 公开计算方法，支持链选择
4. ✅ **DAG 验证**: 完整的 DAG 规则验证
5. ✅ **映射方法**: Position ↔ Epoch 双向查询
6. ✅ **详细文档**: 所有方法都有完整的 Javadoc

### 下一步

1. **实现 DagChainImpl**（参考 Phase 1-5 实现计划）
2. **修复 tryToConnect()** 和 **checkNewMain()** 的逻辑
3. **编写测试**确保正确性
4. **逐步迁移**现有代码从 Blockchain 到 DagChain

---

**文档版本**: v5.1
**最后更新**: 2025-11-05
**状态**: 设计完成，待实现
