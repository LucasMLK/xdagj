# BUG-PERSISTENCE-001: 重启时数据丢失导致 "Link target not found" 错误

## 问题概述

节点重启后，尝试导入候选区块时报错：
```
Failed to import epoch 27573868 block: Link target not found: 0x007e002fc1a99d1223e7482ba62bf355b7822687c0aa63548a9572c96ac59b23
```

## 根本原因

### RocksDB 写入配置

`DagStoreRocksDBConfig.createWriteOptions()` 设置了 `sync=false`（异步写入）：

```java
// DagStoreRocksDBConfig.java:239
writeOptions.setSync(false);  // 异步写入以提高性能
writeOptions.setDisableWAL(false);  // WAL 启用
```

### 数据流问题

1. **区块接收**: P2P 收到的区块通过 `dagChain.tryToConnect()` → `blockImporter.importBlock()` → `dagStore.saveBlock()` 保存
2. **异步写入**: `saveBlock()` 使用 `db.write(writeOptions, batch)` 异步写入（数据在 WAL 内存缓冲区）
3. **候选区块创建**: `BlockBuilder.collectCandidateLinks()` 从数据库/缓存读取区块并创建链接
4. **重启**: 节点重启时，WAL 缓冲区数据丢失
5. **链接失效**: 候选区块引用的区块不存在 → "Link target not found"

### WAL 同步时机

当前 WAL 同步只在以下时机发生：
- **Epoch 结束** (每 64 秒) - `EpochConsensusManager.onEpochEnd()`
- **关机钩子** - `DagKernel` shutdown hook
- **DagStore 停止前** - `DagKernel.stop()`

**问题**: 两次 WAL 同步之间（最长 64 秒），接收的 P2P 区块只存在于内存中。

## 影响范围

- **数据丢失**: 节点重启后丢失最近 64 秒内接收的区块
- **共识中断**: 候选区块无法导入，影响挖矿
- **链不一致**: 不同节点可能有不同的区块集合

## 复现步骤

1. 启动两个 devnet 节点 (suite1, suite2)
2. 等待几个 epoch，让节点同步
3. 快速重启 suite1 节点 (Ctrl+C 后立即重启)
4. 观察 suite1 日志，会出现 "Link target not found" 错误

## 日志证据

```
suite1/node/logs/xdag-error.log:
2025-12-03 | 10:06:56.030 [EpochTimer] [ERROR] -- Failed to import epoch 27573868 block: Link target not found: 0x007e002fc1a99d1223e7482ba62bf355b7822687c0aa63548a9572c96ac59b23
2025-12-03 | 10:08:00.015 [EpochTimer] [ERROR] -- Failed to import epoch 27573869 block: Link target not found: 0x007e002fc1a99d1223e7482ba62bf355b7822687c0aa63548a9572c96ac59b23
```

## 修复方案

### 方案 A: 每次写入同步 (保守)

```java
// DagStoreImpl.saveBlock() - 在 db.write() 后立即同步
db.write(writeOptions, batch);
db.syncWal();  // 确保每个区块立即持久化
```

**优点**: 绝对可靠
**缺点**: 性能下降 (~50% 吞吐量降低)

### 方案 B: 定期同步 (平衡)

添加定时器，每 5-10 秒同步一次 WAL：

```java
// DagKernel.java - 添加定时同步
scheduledExecutor.scheduleAtFixedRate(
    () -> dagStore.syncWal(),
    5, 5, TimeUnit.SECONDS
);
```

**优点**: 性能影响小
**缺点**: 最多丢失 5-10 秒数据

### 方案 C: P2P 区块同步写入 (推荐)

对 P2P 接收的区块使用同步写入，本地区块使用异步写入：

```java
// DagStore 添加方法
void saveBlockSync(Block block);  // 使用 syncWriteOptions

// XdagP2pEventHandler.handleBlocksReply() 调用
dagChain.tryToConnectSync(block);  // 内部使用 saveBlockSync
```

**优点**:
- P2P 区块可靠持久化（无法从本地恢复）
- 本地区块保持高性能（可重新生成）
- 最佳平衡点

**缺点**:
- 需要修改多个接口

## 推荐实施

1. **短期**: 实施方案 B（定期同步），快速修复问题
2. **长期**: 实施方案 C（P2P 同步写入），最佳架构

## 相关文件

- `DagStoreRocksDBConfig.java:235-245` - WriteOptions 配置
- `DagStoreImpl.java:264` - saveBlock 使用 writeOptions
- `EpochConsensusManager.java:480-486` - WAL 同步调用
- `DagKernel.java:247-257` - Shutdown hook WAL 同步
- `BlockBuilder.java:229` - getCandidateBlocksInEpoch() 读取区块

## 相关 Bug

- BUG-STORAGE-002: 原始 WAL 同步修复（已部分修复）
- BUG-LINK-NOT-FOUND-ROOT-CAUSE.md: OrphanManager 删除引用区块问题

## 状态

- [x] 问题分析完成
- [x] 根本原因确认
- [ ] 修复方案实施
- [ ] 测试验证

## 作者

Claude Code - 2025-12-03
