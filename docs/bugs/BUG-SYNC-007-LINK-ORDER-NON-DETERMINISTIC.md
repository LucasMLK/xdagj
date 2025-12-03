# BUG-SYNC-007: 区块引用顺序不确定性

## 问题概述

当节点创建新区块时，`BlockBuilder.collectCandidateLinks()` 收集的 links 顺序不是完全确定性的。由于 links 顺序直接参与区块 hash 计算，这可能导致不同节点构建逻辑相同的区块却得到不同的 hash。

## 现象

两个节点在相同 epoch，引用相同的孤块集合，但产生的新区块 hash 不同。

## 根本原因分析

### Links 参与 Hash 计算

`Block.calculateHash()` (Block.java:193-197):

```java
// Serialize links
buffer.putInt(links.size());
for (Link link : links) {
  buffer.put(link.toBytes());
}
```

**Links 的顺序直接影响区块 hash**。

### 当前排序逻辑

`BlockBuilder.collectCandidateLinks()` (BlockBuilder.java:240-247):

```java
List<Block> top16 = candidates.stream()
    .sorted((b1, b2) -> {
      UInt256 work1 = calculateBlockWork(b1.getHash());
      UInt256 work2 = calculateBlockWork(b2.getHash());
      return work2.compareTo(work1);  // Descending: largest work first
    })
    .limit(Block.MAX_BLOCK_LINKS)
    .toList();
```

**问题**：
1. 只按 work 排序，如果 work 相等则顺序不确定
2. Java Stream 的 `sorted()` 不保证稳定排序
3. 不同节点可能因为处理顺序不同得到不同结果

### 影响范围

1. **区块 Hash 不一致**：相同逻辑的区块可能有不同 hash
2. **挖矿效率降低**：节点可能在"相同"区块上竞争而不知道
3. **同步复杂度增加**：需要更多通信来解决看似不同的区块

## 修复方案

### 方案：确定性多级排序

修改 `collectCandidateLinks()` 中的排序逻辑，使用确定性的多级排序：

```java
List<Block> top16 = candidates.stream()
    .sorted((b1, b2) -> {
      // 第一级：按 work 降序（work 越大 = hash 越小 = 越有价值）
      UInt256 work1 = calculateBlockWork(b1.getHash());
      UInt256 work2 = calculateBlockWork(b2.getHash());
      int workCompare = work2.compareTo(work1);
      if (workCompare != 0) {
        return workCompare;
      }

      // 第二级：按 hash 升序（字典序，确保确定性）
      // 如果 work 相同，hash 也相同（因为 work = MAX / hash）
      // 但为了安全起见，仍然加入 hash 比较
      return b1.getHash().compareTo(b2.getHash());
    })
    .limit(Block.MAX_BLOCK_LINKS)
    .toList();
```

### 设计原则

1. **确定性**：相同输入必须产生相同输出
2. **一致性**：所有节点使用相同规则
3. **优先级**：
   - 高 work（小 hash）的区块优先
   - 相同 work 时，按 hash 字典序排列

### 扩展考虑

1. **Transaction Links 顺序**：如果有多个交易 link，也需要确定性排序
2. **协议规范**：link 排序规则应该成为协议规范的一部分

## 影响评估

- **严重程度**: 中
- **影响范围**: 区块创建、挖矿效率
- **触发条件**: 两个区块有相同的 work（概率低但存在）
- **用户感知**: 可能导致挖矿结果不一致

## 相关文件

- `BlockBuilder.java:240-247` - 排序逻辑
- `Block.java:159-200` - Hash 计算（包含 links）

## 相关 Bug

- BUG-SYNC-006: 孤块引用导致同步失败（P2P 不请求缺失区块）

## 状态

- [x] 问题发现
- [x] 现象记录
- [x] 根本原因确认
- [x] 修复方案设计
- [x] 实施修复 (2025-12-03)
- [x] 测试验证 (GhostProtocolTest - 3 link ordering tests)

## 作者

Claude Code - 2025-12-03
