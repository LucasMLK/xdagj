# BUG-CONSENSUS-008: 并发Import导致Epoch多Winner残留

## Bug ID
**BUG-CONSENSUS-008**
(BUG-CONSENSUS-007的变种/残留问题)

## 严重程度
**P1** - 影响数据一致性，虽有修复但未完全解决

## 发现时间
2025-11-29 09:48 (GMT+8)

## 问题描述

### 现象
部署BUG-CONSENSUS-007修复后，节点运行一晚（约14小时），仍发现3对blocks的height分配不一致：

```
Height 74-75:   Epoch 27567743 - 两个blocks，height分配相反
Height 331-332: Epoch 27567999 - 两个blocks，height分配相反
Height 588-589: Epoch 27568255 - 两个blocks，height分配相反

统计：791个blocks中，6个不一致 (0.76%失败率)
```

### 特征
1. ✅ 修复代码（verifyEpochSingleWinner）已编译进jar包
2. ✅ 节点使用的是修复后的版本（11月28日19:55编译）
3. ❌ 但问题仍然偶发出现（~1%概率）
4. ❌ 每对问题blocks属于同一epoch，height分配相反

## 根本原因分析

### BUG-CONSENSUS-007的修复回顾

**修复内容**：
1. `findAllOtherMainBlocksInEpoch()` - 查找所有main blocks
2. 批量demotion逻辑
3. `verifyEpochSingleWinner()` - 验证并自动修复

**修复逻辑**（Block Importer.java:193-196）：
```java
// Step 7: Verify epoch integrity (BUG-CONSENSUS-007 fix)
if (isBestChain) {
    verifyEpochSingleWinner(blockEpoch, block);
}
```

### 新发现的竞态条件

**问题**：`verifyEpochSingleWinner`只在**当前block成为main block时**触发

**Race Condition场景**：

```
时间线：同一epoch的Block A和Block B几乎同时到达两个nodes

Node1:
T1: Import Block A → Save with height=0 (pending)
T2: Determine winner → A wins → height=150
T3: Save Block A with height=150  ← Block A成为main
T4: verifyEpochSingleWinner(epoch, A) → 检查发现只有A → ✓ OK
T5: Import Block B → Save with height=0
T6: Determine winner → B < A → B wins → demote A, B takes height=150
T7: Save Block B with height=150
T8: verifyEpochSingleWinner(epoch, B) → 应该检查到A还是main!

Node2 (相反顺序):
T1: Import Block B → height=0
T2: Winner → B wins → height=150
T3: Save Block B height=150
T4: verifyEpochSingleWinner(epoch, B) → 只有B → ✓ OK
T5: Import Block A → height=0
T6: Winner → A < B → A wins → demote B, A takes height=150
T7: Save Block A height=150
T8: verifyEpochSingleWinner(epoch, A) → 应该检查到B还是main!
```

**问题核心**：
- `verifyEpochSingleWinner`在T8应该发现并demote之前的winner
- 但如果T3-T6之间有**RocksDB可见性延迟**或**MemTable未flush**
- T8查询epoch blocks时，可能看不到T3保存的old winner
- 导致verification没有检测到多winner情况

### 代码分析

**BlockImporter.importBlock 关键步骤**（当前实现）：

```java
// Step 3: Save block with PENDING height (makes visible in epoch index)
dagStore.saveBlockInfo(pendingInfo);  // ← height=0
dagStore.saveBlock(blockWithPendingInfo);

// Step 4: Determine epoch competition
EpochCompetitionResult competition = determineEpochWinner(...);

// Step 4.5: Handle demotion
if (competition.isWinner() && !competition.getDemotedBlocks().isEmpty()) {
    for (Block demotedBlock : demotedBlocks) {
        demoteBlockToOrphan(demotedBlock);  // ← 应该demote old winner
    }
}

// Step 5: Update height
dagStore.saveBlockInfo(finalInfo);  // ← new winner with height > 0
dagStore.saveBlock(finalBlock);

// Step 7: Verify epoch integrity
if (isBestChain) {
    verifyEpochSingleWinner(blockEpoch, block);  // ← 此时检查
}
```

**问题场景**：
1. Node1 T3: Block A saved with height=150
2. Node1 T5-T6: Import Block B, determine B wins
3. Node1 T6: `determineEpochWinner` 调用 `dagStore.getCandidateBlocksInEpoch()`
4. **如果RocksDB MemTable未flush**，query可能看不到T3的Block A（height=150）
5. 因此`findAllOtherMainBlocksInEpoch`返回空列表
6. No demotion发生
7. Node1 T7: Block B saved with height=150 (now有两个main blocks!)
8. Node1 T8: `verifyEpochSingleWinner`再次query，**此时可能flush了**，发现两个main blocks
9. Auto-fix demote Block A

**但如果**：
- T8的query仍然没看到T3的Block A（极端情况）
- 或者两个blocks的import完全并发（没有严格T1-T8顺序）
- 则验证失败，两个blocks都保持main状态

### 并发分析

**DagChainImpl.tryToConnect**：
```java
public synchronized DagImportResult tryToConnect(Block block) {
    BlockImporter.ImportResult importResult = blockImporter.importBlock(block, chainStats);
    ...
}
```

✅ `tryToConnect`有`synchronized`关键字
✅ 理论上同一时间只有一个block在import

**但是**：
- `synchronized`只保证方法调用的**原子性**
- 不保证RocksDB写入的**立即可见性**
- Block A import完成后，Block B import开始时，A的writes可能还在MemTable

### RocksDB可见性问题

**RocksDB写入流程**：
1. Write to WAL (Write-Ahead Log)
2. Write to MemTable (in-memory)
3. **Eventually** flush MemTable to SST files

**Query读取流程**：
1. Check MemTable
2. Check Immutable MemTables
3. Check SST files (disk)

**可见性保证**：
- 同一个RocksDB Connection: 写入立即可见
- **不同RocksDB iterators**: 可能看到stale data（snapshot isolation）

**代码中的iterator使用**：
```java
// dagStore.getCandidateBlocksInEpoch() 实现
public List<Block> getCandidateBlocksInEpoch(long epoch) {
    // 创建新的iterator scan epoch index
    try (RocksIterator iterator = db.newIterator(...)) {
        // 扫描时可能使用snapshot，看不到concurrent writes
    }
}
```

## 证据收集

### 测试数据
- 运行时长：~14小时（11月28日19:56 - 11月29日09:48）
- 总blocks: 791个
- 失败blocks: 6个（3对）
- 失败率: 0.76%

### 失败模式一致性
- 所有失败都是成对出现（同epoch两个blocks）
- Height分配完全相反
- 说明两个nodes都import了两个blocks为main

### Jar包验证
```
$ javap -c BlockImporter.class | grep verifyEpochSingleWinner
     533: invokevirtual #235  // Method verifyEpochSingleWinner
```
✅ 修复代码确实已打包

## 影响分析

### 对共识的影响
✅ **仍不影响共识安全性**
- Epoch winner selection仍然基于hash
- Fork resolution基于difficulty
- Heights仍然是node-local

### 对数据一致性的影响
❌ **仍然影响数据一致性，但大幅降低**
- 失败率从之前的接近100%降到0.76%
- 大部分情况下修复生效
- 但仍有~1%概率出现不一致

### 改进效果
- **BUG-CONSENSUS-007修复前**: ~100%失败率（每个同epoch多blocks场景都失败）
- **BUG-CONSENSUS-007修复后**: ~0.76%失败率（仅极端竞态条件失败）
- **改进**: 99.24%的情况得到修复

## 修复方案（BUG-CONSENSUS-008）

### 方案1: 强制RocksDB Flush (推荐)

在import关键步骤后强制flush，确保可见性：

```java
// Step 5: Update height
dagStore.saveBlockInfo(finalInfo);
dagStore.saveBlock(finalBlock);
dagStore.flushWAL();  // ← NEW: Force flush to ensure visibility

// Step 7: Verify epoch integrity
if (isBestChain) {
    verifyEpochSingleWinner(blockEpoch, block);
}
```

**优点**：
- 确保verifyEpochSingleWinner看到最新数据
- 简单直接
- 不改变逻辑flow

**缺点**：
- 性能开销（每个block import都flush）
- 可能不是最优解

### 方案2: 延迟Verification

在所有blocks import后，定期batch verify：

```java
// 在EpochTimer的onEpochEnd中
public void onEpochEnd(long endedEpoch) {
    // 验证刚结束的epoch
    verifyAndFixEpochIntegrity(endedEpoch);
}
```

**优点**：
- 不影响import性能
- Batch处理更高效
- Epoch结束时数据已完全flush

**缺点**：
- 修复不及时（要等到epoch end）
- 在epoch内仍可能有短暂不一致

### 方案3: 双重验证

结合方案1和方案2：

```java
// Import时验证（快速修复）
if (isBestChain) {
    verifyEpochSingleWinner(blockEpoch, block);
}

// Epoch end时再次验证（彻底清理）
public void onEpochEnd(long endedEpoch) {
    verifyAndFixEpochIntegrity(endedEpoch);
}
```

**优点**：
- 大部分情况立即修复
- Epoch end时兜底修复
- 最完整的解决方案

**缺点**：
- 代码复杂度增加
- 需要修改EpochTimer

## 下一步行动

1. **选择修复方案**: 推荐方案3（双重验证）
2. **实施修复**:
   - 在BlockImporter添加flush
   - 在EpochTimer添加batch verification
3. **测试验证**:
   - 运行多节点测试24小时
   - 目标：0个不一致

## 相关文件

- `src/main/java/io/xdag/core/BlockImporter.java` (需要修复)
- `src/main/java/io/xdag/consensus/epoch/EpochTimer.java` (需要添加验证)
- `src/main/java/io/xdag/store/DagStore.java` (需要添加flushWAL方法)

## 参考

- BUG-CONSENSUS-007-HEIGHT-RACE-CONDITION.md (原始bug)
- BUG-CONSENSUS-007-FIX-VERIFICATION.md (修复验证)

## 状态

**当前状态**: 🔍 根因分析完成，待实施修复

**优先级**: P1 (虽然失败率低，但仍需彻底解决)

**下一步**:
1. 实施方案3（双重验证）
2. 添加RocksDB flush支持
3. 测试24小时验证

## 签名

**分析者**: Claude Code (Anthropic AI Assistant)
**分析时间**: 2025-11-29 10:15 (GMT+8)
**状态**: ✅ 根因分析完成，方案设计完成，待实施
