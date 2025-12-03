# BUG-SYNC-006: 孤块引用导致同步失败

## 问题概述

当节点创建新区块时，`BlockBuilder` 正确地引用了本地存储的孤块(orphan blocks)作为 DAG 链接。但当这个新区块同步到其他节点时，由于 P2P 层**没有主动请求缺失的孤块**，导致区块导入失败。

## 现象

节点日志显示重复的错误：
```
Failed to import epoch 27574098 block: Link target not found: 0x45720b2d32403e9672a00463e284856821c62ae10dc06a6b16bfb95708e0d5c0
```

错误持续出现在每个新 epoch，导致主链高度停止增长。

## 设计澄清

### DAG vs 线性链

**引用孤块是正确的 DAG 设计**！

- 比特币（线性链）：只引用前一个主块，孤块完全被抛弃
- XDAG（DAG 结构）：可以引用多个父区块，包括孤块
- 这是 GHOST 协议的核心思想，孤块的工作量不浪费

**问题不在于引用孤块，而在于 P2P 同步没有正确传播孤块。**

## 根本原因分析

### 当前行为

1. **BlockBuilder.collectCandidateLinks()** (BlockBuilder.java:229):
   - 正确地获取 epoch 中所有候选区块（包括孤块）
   - 新区块引用这些孤块 ✓

2. **P2P 同步时**：
   - 其他节点收到新区块
   - `BlockValidator.validateLinks()` 发现引用的孤块不存在
   - 返回 `missingDependency` 错误
   - **OrphanManager 保存为 pending，但不主动请求缺失的区块！** ✗

3. **OrphanManager** 只会：
   - 本地等待缺失的区块到达
   - 定期重试导入
   - **但从不向 P2P 节点请求缺失的区块**

### 代码位置

- `XdagP2pEventHandler.handleBlocksReply()` - 处理收到的区块
- `OrphanManager.registerMissingDependency()` - 注册缺失依赖但不请求
- `BlockValidator.validateLinks():379` - 返回 missingDependency

## 修复方案

### 推荐方案：主动请求缺失的区块

当检测到 `MISSING_DEPENDENCY` 时，向发送区块的 peer 请求缺失的区块：

```java
// 在 XdagP2pEventHandler.handleBlocksReply() 中
DagImportResult result = dagChain.tryToConnect(block);

if (result.getStatus() == ImportStatus.INVALID_LINK) {
    Bytes32 missingHash = result.getErrorDetails().getMissingDependency();
    if (missingHash != null) {
        // 1. 注册为 pending（已有）
        orphanManager.registerMissingDependency(block, List.of(missingHash));

        // 2. 【新增】主动请求缺失的区块
        log.info("Requesting missing dependency: {}", missingHash.toHexString());
        channel.send(new GetBlocksMessage(List.of(missingHash)));
    }
}
```

### 优点

1. **保持 DAG 特性** - 孤块仍然被引用，工作量不浪费
2. **按需请求** - 只请求实际缺失的区块，不增加不必要的流量
3. **兼容现有架构** - OrphanManager 已经处理 pending 和重试逻辑
4. **最小改动** - 只需在一处添加请求逻辑

## 影响评估

- **严重程度**: 高
- **影响范围**: 所有节点同步，特别是新节点加入网络
- **触发条件**: 收到引用孤块的区块，且本地没有该孤块
- **用户感知**: 主链高度停止增长

## 相关文件

- `XdagP2pEventHandler.java` - P2P 消息处理
- `OrphanManager.java` - 孤块管理（已有 pending 机制）
- `BlockValidator.java:368-451` - 链接验证
- `BlockBuilder.java:229` - 链接选择（正确引用孤块）

## 相关 Bug

- BUG-SYNC-003: 早期 Epoch 链分叉不收敛（已修复）
- BUG-SYNC-004: 二分搜索同步（已实现）
- BUG-SYNC-005: 空 epoch 检测（已修复）

## 状态

- [x] 问题发现
- [x] 现象记录
- [x] 根本原因确认
- [x] 设计澄清（DAG 应该引用孤块）
- [x] 修复方案设计
- [x] 实施修复 (2025-12-03)
  - XdagP2pEventHandler.handleBlocksReply() - 检测 MISSING_DEPENDENCY
  - XdagP2pEventHandler.handleMissingDependency() - 主动请求缺失区块
  - 添加 recentlyRequestedBlocks 缓存防止重复请求
- [x] 测试验证 (MissingDependencyRequestTest - 5 tests)

## 作者

Claude Code - 2025-12-03
