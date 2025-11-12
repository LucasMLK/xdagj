# XDAG 重构命名规范

**日期**: 2025-01
**目的**: 定义符合 DAG + 区块链特性的清晰命名体系

## 核心设计原则

XDAG 是 **DAG（有向无环图）+ 区块链** 的混合架构：
- ✅ 有 DAG 的图结构（块之间的引用关系）
- ✅ 有区块链的主链概念（main chain）
- ✅ 命名应同时体现这两个特性

## 最终命名方案

### 核心数据结构

```java
// 1. 完整的块（既是区块链的块，也是 DAG 的节点）
Block.java
  - 包含完整的块数据
  - 交易信息
  - 签名
  - 可以是主块或普通块

// 2. 块的元数据（用于索引、快速查询）
BlockInfo.java
  - hash, timestamp, height
  - difficulty, flags
  - 不包含完整交易数据
  - 不可变（@Value）
  - 类型安全（Bytes32, UInt256）

// 3. 块之间的链接（体现 DAG 特性）
BlockLink.java
  - 源块 hash
  - 目标块 hash
  - 链接类型：INPUT, OUTPUT, REFERENCE
  - 金额（可选，用于交易链接）

// 4. 区块链统计信息
ChainStats.java
  - 总难度
  - 块数量
  - 主链高度
  - 其他全局统计

// 5. 快照数据
Snapshot.java
  - 快照时间点
  - 快照元数据
```

### 序列化器

```java
// 紧凑序列化器（替换 Kryo）
CompactSerializer.java
  - 支持 BlockInfo 序列化/反序列化
  - 支持 ChainStats 序列化/反序列化
  - 支持 Snapshot 序列化/反序列化
  - 变长整数编码（VarInt）
  - 位域压缩
```

### 存储层

```java
// 存储接口
BlockStore.java
  - 保存和查询 BlockInfo
  - 索引管理

// 存储实现
BlockStoreImpl.java
  - 基于 RocksDB
  - 使用 CompactSerializer
```

## 重命名映射表

| 旧名称（或计划名称） | 新名称 | 说明 |
|-------------------|--------|------|
| `ImprovedBlockInfo` | `BlockInfo` | 直接使用，去掉 "Improved" |
| `BlockInfo`（旧） | `LegacyBlockInfo` | 临时保留，最终删除 |
| `ImprovedXdagStats` | `ChainStats` | 更清晰的名称 |
| `XdagStats` | `LegacyChainStats` | 临时保留 |
| `ImprovedSnapshotInfo` | `Snapshot` | 简化名称 |
| `SnapshotInfo`（旧） | `LegacySnapshot` | 临时保留 |
| `DagNode` | `Block` | 更符合区块链习惯 |
| `DagNodeInfo` | `BlockInfo` | 更简洁 |
| `DagLink` | `BlockLink` | 更简洁 |
| `CompactEncoder/Decoder` | `CompactSerializer` | 统一命名 |

## 命名原则

### 1. 简洁优先
```java
✅ BlockInfo          // 简洁
❌ ImprovedBlockInfo  // 冗长，"Improved" 没有意义
❌ DagNodeInfo        // 冗长，Block 已经足够清晰
```

### 2. 自解释
```java
✅ BlockLink          // 清楚表明是块之间的链接
✅ ChainStats         // 清楚表明是链的统计信息
❌ XdagStats          // 不清楚 Stats 的具体含义
```

### 3. 符合业界惯例
```java
✅ BlockInfo          // 业界标准术语
✅ Snapshot           // 业界标准术语
✅ CompactSerializer  // 业界标准术语
❌ CompactEncoder     // Encoder/Decoder 分开不如 Serializer 统一
```

### 4. 体现 DAG + 区块链特性
```java
✅ Block              // 体现区块链特性
✅ BlockLink          // 体现 DAG 的链接关系
✅ ChainStats         // 体现区块链统计
```

## 避免的命名模式

### ❌ 避免版本号式命名
```java
❌ BlockInfoV2
❌ ImprovedBlockInfo
❌ NewBlockInfo
❌ BlockInfo2
```
**原因**: 未来如果还要改进，难道叫 V3、MoreImproved 吗？

### ❌ 避免过度抽象
```java
❌ DagNode            // 对于区块链项目，Block 更直观
❌ GraphVertex        // 过于抽象
```
**原因**: XDAG 是区块链，Block 是核心概念

### ❌ 避免项目前缀（除非必要）
```java
❌ XdagBlock          // 冗长
❌ XdagBlockInfo      // 冗长
✅ Block              // 简洁，已经在 io.xdag 包内
```
**原因**: 已经在 `io.xdag` 包内，不需要重复前缀

## 实施计划

### Phase 1: 重命名数据结构
```bash
# 1. 重命名新文件
mv ImprovedBlockInfo.java BlockInfo.java

# 2. 旧文件临时重命名（保留兼容）
mv BlockInfo.java LegacyBlockInfo.java

# 3. 创建新文件
touch ChainStats.java
touch BlockLink.java
touch Snapshot.java
touch CompactSerializer.java
```

### Phase 2: 更新引用
```java
// 更新所有引用
LegacyBlockInfo → BlockInfo
ImprovedBlockInfo → BlockInfo
XdagStats → ChainStats
```

### Phase 3: 删除旧代码
```bash
# Phase 2 完成后删除
rm LegacyBlockInfo.java
rm LegacyChainStats.java
rm LegacySnapshot.java
```

## 文件组织

```
src/main/java/io/xdag/core/
├── Block.java              # 完整块
├── BlockInfo.java          # 块元数据（原 ImprovedBlockInfo）
├── BlockLink.java          # 块链接
├── ChainStats.java         # 链统计
├── Snapshot.java           # 快照
└── CompactSerializer.java  # 序列化器

src/main/java/io/xdag/db/
├── BlockStore.java         # 存储接口
└── BlockStoreImpl.java     # 存储实现
```

## 总结

**核心命名**（5 个关键类）：
1. `Block` - 完整的块
2. `BlockInfo` - 块元数据
3. `BlockLink` - 块链接（体现 DAG）
4. `ChainStats` - 链统计
5. `Snapshot` - 快照

**优点**：
- ✅ 简洁易懂
- ✅ 符合业界惯例
- ✅ 体现 DAG + 区块链双重特性
- ✅ 自解释，不需要注释就能理解
- ✅ 易于维护和扩展

---

**版本**: v1.0
**创建时间**: 2025-01
**作者**: Claude Code
**状态**: 已确认，准备实施
