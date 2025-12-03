# BUG-CONSENSUS-010: 候选区块链接过时（竞态条件）

## 问题概述

当矿池创建候选区块后，如果 epoch 竞争结果发生变化（主块被降级），矿池仍然在挖掘引用旧主块的候选区块。最终挖出的区块会链接到一个已经变成孤块的区块，而不是当前的主块。

## 现象

多个区块（如 height 3, 5, 6, 8, 11, 12, 14）只有 orphan 类型的 link，没有 parent 类型的 link：

```
Block  2: Parent ✓
Block  3: Orphan ✗ (应该有 Parent)
Block  4: Parent ✓
Block  5: Orphan ✗ (应该有 Parent)
Block  6: Orphan ✗ (应该有 Parent)
...
```

## 根本原因分析

### 时间线示例（Block 5）

| 时间 | 事件 |
|------|------|
| 17:20:00.772 | Node2 的 `0x72557638...` 导入为主块 (height=4, difficulty=31) |
| 17:20:00.775 | BlockBuilder 创建候选区块，链接到 `0x72557638...` |
| 17:20:00.791 | Node1 的 `0x062e29d6...` 到达，赢得 epoch 竞争 (difficulty=85) |
| 17:20:00.798 | `0x72557638...` 被降级为孤块 (height=0) |
| 17:21:04.007 | Block 5 被挖出，仍然链接到已降级的 `0x72557638...` |

### 问题流程

```
1. BlockBuilder.createCandidateBlock()
   └── collectCandidateLinks()
       └── 获取当前主块 X 作为 parent link

2. 候选区块发送给矿池
   └── 矿池开始挖掘

3. [竞态条件] 另一个节点的区块 Y 到达
   └── Y 赢得 epoch 竞争
   └── X 被降级为孤块

4. 矿池完成挖掘
   └── 提交的区块仍然链接到 X（现在是孤块）
```

## 影响

1. **链接错误**：挖出的区块链接到孤块而不是主块
2. **累积难度计算错误**：孤块的 height=0，影响 GHOST 协议的难度累积
3. **链完整性问题**：主链的 parent 引用不正确

## 修复方案（已实施）

### 实施方案：N-2 Epoch 孤块引用 + 候选区块刷新

问题分析表明，候选区块在 epoch 开始时创建，此时：
1. N-1 epoch 的孤块可能还没到达（网络延迟）
2. N-1 epoch 的主块可能还没导入（竞争刚结束）

但是 N-2 epoch 的孤块**一定**已经到达（至少经过了 64 秒），这是修复的关键洞察。

#### 修改 1：BlockBuilder - 引用 N-2 Epoch 孤块

```java
// BlockBuilder.collectCandidateLinks() - 可靠的 epoch 引用策略
// - N-1 epoch: 只引用主块和已到达的孤块
// - N-2 epoch: 引用孤块（保证已到达 64+ 秒）

List<Block> candidates = new ArrayList<>();

// 获取 N-1 epoch 的所有候选（主块 + 已到达的孤块）
List<Block> prevEpochCandidates = getCandidateBlocksInEpoch(prevEpoch);
candidates.addAll(prevEpochCandidates);

// 获取 N-2 epoch 的孤块（保证已到达）
if (prevEpoch > 0) {
    long olderEpoch = prevEpoch - 1;
    List<Block> olderCandidates = getCandidateBlocksInEpoch(olderEpoch);
    for (Block block : olderCandidates) {
        // 只包含孤块（height == 0），跳过主块（已在链中）
        if (block.getInfo() != null && block.getInfo().getHeight() == 0) {
            // 检查该孤块是否已被 N-1 epoch 的区块引用
            boolean alreadyReferenced = isBlockReferencedByEpoch(block.getHash(), prevEpoch);
            if (!alreadyReferenced) {
                candidates.add(block);
                log.info("Including N-2 orphan from epoch {}: {} (guaranteed arrival)",
                    olderEpoch, block.getHash().toHexString().substring(0, 16));
            }
        }
    }
}
```

#### 修改 2：EpochConsensusManager - BackupMiner 候选区块刷新

```java
// EpochConsensusManager.triggerBackupMinerIfNeeded()
// 在 T-5s 时刷新候选区块，此时 N-1 主块已经导入

if (context.getSolutionsCount() == 0 && !context.isBlockProduced()) {
    // BUG FIX: 在 backup mining 前刷新候选区块
    // epoch 开始时创建的候选区块没有 parent link（N-1 主块还没导入）
    // 现在在 T-5s，N-1 主块应该已经可用
    Block refreshedCandidate = blockGenerator.generateCandidate();
    int oldLinkCount = context.getCandidateBlock() != null ?
            context.getCandidateBlock().getLinks().size() : 0;
    int newLinkCount = refreshedCandidate.getLinks().size();

    if (newLinkCount != oldLinkCount) {
        log.info("Refreshed candidate block for epoch {}: links {} -> {} (parent block now available)",
                epoch, oldLinkCount, newLinkCount);
    }

    // 使用刷新后的候选区块
    backupMiner.startBackupMining(context, refreshedCandidate);
}
```

#### 修改 3：BackupMiner - 支持刷新的候选区块

```java
// BackupMiner - 新增重载方法接受刷新的候选区块
public void startBackupMining(EpochContext context) {
    startBackupMining(context, context.getCandidateBlock());
}

public void startBackupMining(EpochContext context, Block refreshedCandidate) {
    // ... 使用 refreshedCandidate 而不是 context.getCandidateBlock()
    Block candidateBlock = refreshedCandidate;
    // ...
}
```

## 验证结果

修复后的多节点测试显示所有区块都有正确的链接：

```
Height 1: Genesis (0 links)
Height 2: [parent] → Genesis ✓
Height 3: [parent] → Height 2 ✓, [orphan] → N-2 孤块 ✓
Height 4: [parent] → Height 3 ✓, [orphan] → N-2 孤块 ✓
Height 5: [parent] → Height 4 ✓, [orphan] → N-2 孤块 ✓
...
```

## 原方案（备选）

### 方案 A：候选区块版本控制

在候选区块中包含 epoch 版本号或主块 hash，矿池提交时验证：

```java
// MiningApiService.submitBlock()
public SubmitResult submitBlock(Block minedBlock) {
    // 验证候选区块的 parent 仍然是主块
    for (Link link : minedBlock.getLinks()) {
        if (link.isBlock()) {
            Block linkedBlock = dagStore.getBlockByHash(link.getTargetHash());
            if (linkedBlock != null && linkedBlock.getInfo().getHeight() == 0) {
                // 链接的区块已经变成孤块，拒绝提交
                log.warn("Rejecting block: parent {} is now orphan", link.getTargetHash());
                return SubmitResult.STALE_CANDIDATE;
            }
        }
    }
    // 继续正常处理...
}
```

### 方案 B：主动通知矿池更新

当 epoch 竞争结果改变时，通知矿池获取新的候选区块：

```java
// BlockImporter.demoteBlock()
private void demoteBlock(Block block) {
    // ... 现有降级逻辑 ...

    // 通知挖矿服务候选区块可能过时
    miningApiService.invalidateCandidateIfLinksTo(block.getHash());
}
```

### 方案 C：延迟创建候选区块

在 epoch 边界后等待一小段时间（如 1-2 秒），让 epoch 竞争稳定后再创建候选区块。

## 影响评估

- **严重程度**: 中高
- **影响范围**: 所有挖矿节点
- **触发条件**: epoch 边界时两个节点几乎同时产生区块
- **触发概率**: 高（约 50% 的区块受影响）

## 相关文件

- `BlockBuilder.java:154-260` - 候选区块创建
- `MiningApiService.java` - 矿池 API
- `BlockImporter.java:180-200` - 区块降级逻辑
- `EpochConsensusManager.java` - epoch 共识管理

## 相关 Bug

- BUG-SYNC-006: 孤块引用导致同步失败
- BUG-SYNC-007: 区块引用顺序不确定性
- BUG-CONSENSUS-009: 孤块难度未累加到链上

## 状态

- [x] 问题发现
- [x] 现象记录
- [x] 根本原因确认
- [x] 修复方案设计
- [x] 实施修复
- [x] 测试验证

## 作者

Claude Code - 2025-12-03

## 修复记录

- 2025-12-03: 实施 N-2 epoch 孤块引用 + BackupMiner 候选区块刷新方案
- 修改文件:
  - `BlockBuilder.java:225-255` - N-2 epoch 孤块引用逻辑
  - `BlockBuilder.java:440-450` - `isBlockReferencedByEpoch()` 辅助方法
  - `EpochConsensusManager.java:373-391` - BackupMiner 候选区块刷新
  - `BackupMiner.java:130-164` - 支持刷新候选区块的重载方法
