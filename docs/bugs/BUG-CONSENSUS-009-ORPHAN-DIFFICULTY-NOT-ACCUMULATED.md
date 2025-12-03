# BUG-CONSENSUS-009: 孤块难度未累加到链上

## 问题概述

当前的累积难度计算只取所有 parent 中难度最大的一个，**没有累加被引用孤块的难度**。这违背了 GHOST 协议的核心思想：孤块的算力不应该被浪费。

## 现象

假设 epoch N 有 1 个主块 M 和 3 个孤块 O1, O2, O3：
- M 的 work = 100
- O1 的 work = 80
- O2 的 work = 60
- O3 的 work = 40

**当前行为**（错误）：
```
新区块的累积难度 = max(M.difficulty, O1.difficulty, O2.difficulty, O3.difficulty) + 新区块.work
                 = M.difficulty + 新区块.work
                 (O1, O2, O3 的 work 被浪费)
```

**期望行为**（GHOST 协议）：
```
新区块的累积难度 = M.difficulty + O1.work + O2.work + O3.work + 新区块.work
                 (所有引用的孤块 work 都被累加)
```

## 根本原因分析

`BlockImporter.calculateCumulativeDifficulty()` (第 571-610 行)：

```java
for (Link link : block.getLinks()) {
  if (link.isBlock()) {
    Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
    if (parent != null && parent.getInfo() != null) {
      long parentEpoch = parent.getEpoch();

      if (parentEpoch < blockEpoch) {
        // 只取最大难度！
        if (parentDifficulty.compareTo(maxParentDifficulty) > 0) {
          maxParentDifficulty = parentDifficulty;
        }
      }
    }
  }
}
// 只用了 maxParentDifficulty，没有累加孤块难度
UInt256 cumulativeDifficulty = maxParentDifficulty.add(blockWork);
```

## GHOST 协议设计

### Bitcoin vs GHOST

| 特性 | Bitcoin | GHOST (XDAG) |
|------|---------|--------------|
| 链结构 | 线性链 | DAG |
| 孤块处理 | 完全丢弃 | 可被引用 |
| 孤块算力 | 浪费 | 累加到链上 |
| 链选择 | 最长链 | 最重子树 |

### XDAG 的 GHOST 变体

XDAG 的设计：
1. **epoch 内竞争**：同一 epoch 内多个区块，最小 hash 获胜成为主块
2. **孤块引用**：新区块可以引用前一个 epoch 的孤块（最多 16 个）
3. **难度累加**：引用孤块时，孤块的 work 应该累加到链上

## 修复方案

### 修改 calculateCumulativeDifficulty()

```java
private UInt256 calculateCumulativeDifficulty(Block block) {
    long blockEpoch = block.getEpoch();
    UInt256 maxPreviousEpochDifficulty = UInt256.ZERO;
    UInt256 sameEpochOrphanWorkSum = UInt256.ZERO;

    for (Link link : block.getLinks()) {
        if (!link.isBlock()) continue;

        Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
        if (parent == null || parent.getInfo() == null) continue;

        long parentEpoch = parent.getEpoch();

        if (parentEpoch < blockEpoch) {
            // Parent from PREVIOUS epoch
            // Take the max cumulative difficulty (this includes all previous epochs' work)
            UInt256 parentDifficulty = parent.getInfo().getDifficulty();
            if (parentDifficulty.compareTo(maxPreviousEpochDifficulty) > 0) {
                maxPreviousEpochDifficulty = parentDifficulty;
            }

            // BUG-CONSENSUS-009: Also accumulate orphan work from the same epoch
            // If parent is an orphan (height=0), add its work to the sum
            if (parent.getInfo().getHeight() == 0) {
                UInt256 orphanWork = calculateBlockWork(parent.getHash());
                sameEpochOrphanWorkSum = sameEpochOrphanWorkSum.add(orphanWork);
                log.debug("Accumulating orphan work: {} (work={})",
                    formatHash(parent.getHash()), orphanWork.toDecimalString());
            }
        }
        // Same epoch parents are skipped (XDAG rule)
    }

    // Calculate this block's work
    UInt256 blockWork = calculateBlockWork(block.getHash());

    // GHOST: Cumulative = max previous + orphan work sum + this block's work
    UInt256 cumulativeDifficulty = maxPreviousEpochDifficulty
        .add(sameEpochOrphanWorkSum)
        .add(blockWork);

    log.debug("Calculated cumulative difficulty: previous={}, orphanWork={}, blockWork={}, total={}",
        maxPreviousEpochDifficulty.toDecimalString(),
        sameEpochOrphanWorkSum.toDecimalString(),
        blockWork.toDecimalString(),
        cumulativeDifficulty.toDecimalString());

    return cumulativeDifficulty;
}
```

### 排序规则确认

`BlockBuilder.collectCandidateLinks()` 应该按 **work 降序**（hash 升序）排列孤块：

```java
// BUG-SYNC-007: Deterministic multi-level sorting
List<Block> top16 = candidates.stream()
    .sorted((b1, b2) -> {
        // Level 1: Sort by work descending (smallest hash = largest work first)
        UInt256 work1 = calculateBlockWork(b1.getHash());
        UInt256 work2 = calculateBlockWork(b2.getHash());
        int workCompare = work2.compareTo(work1);
        if (workCompare != 0) return workCompare;

        // Level 2: Sort by hash ascending (for determinism)
        return b1.getHash().compareTo(b2.getHash());
    })
    .limit(Block.MAX_BLOCK_LINKS)
    .toList();
```

这样：
- **第一个是主块**（最小 hash = 最大 work）
- **后续是孤块**，按 work 降序排列
- 保证所有节点产生相同顺序

## 链选择规则

当发生分叉时，选择**累积难度最高的链**：

```
Chain A: Genesis -> B1 -> B2 (with 2 orphans) -> B3
Chain B: Genesis -> B1 -> B2' -> B3' -> B4'

If Chain A has higher cumulative difficulty (due to orphan work),
choose Chain A even though Chain B is "longer"
```

## 影响评估

- **严重程度**: 高
- **影响范围**: 整个共识机制、链选择、安全性
- **触发条件**: 所有有孤块引用的场景
- **用户感知**: 链选择可能不正确，算力浪费

## 安全考虑

### 防止重复计算

**问题**：如果孤块 O1 引用了孤块 O2，是否会重复计算 O2 的 work？

**解决**：
1. 只累加**直接引用**的孤块 work
2. 不递归计算孤块引用的孤块
3. 每个孤块的 work 只在被直接引用时计入一次

### 防止攻击

**攻击场景**：恶意节点创建大量低难度孤块来增加累积难度

**防御**：
1. 每个区块最多引用 16 个孤块 (MAX_BLOCK_LINKS)
2. 孤块必须满足 PoW 难度要求
3. 同一 epoch 最多保留 16 个非孤块

## 相关文件

- `BlockImporter.java:571-610` - 累积难度计算
- `BlockBuilder.java:240-256` - 孤块排序
- `DagChainImpl.java:594-609` - 类似的计算（需要同步修改）

## 相关 Bug

- BUG-SYNC-006: 孤块引用导致同步失败（P2P 不请求缺失区块）
- BUG-SYNC-007: 区块引用顺序不确定性

## 状态

- [x] 问题发现
- [x] 现象记录
- [x] 根本原因确认
- [x] 修复方案设计
- [x] 实施修复 (2025-12-03)
  - BlockImporter.calculateCumulativeDifficulty()
  - DagChainImpl.calculateCumulativeDifficulty()
- [x] 测试验证 (GhostProtocolTest - 3 difficulty accumulation tests)

## 作者

Claude Code - 2025-12-03
