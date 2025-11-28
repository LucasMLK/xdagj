# BUG-STORAGE-002: Block Data Loss Due to Async Write + Force Kill

## 状态
**CONFIRMED** - Root cause identified

## 严重性
🔴 **CRITICAL** - Data loss bug affecting all blocks except Genesis

## 现象

节点运行时：
- ✅ 日志显示 "Epoch X block imported successfully"
- ✅ 日志显示 "ChainStats saved successfully to RocksDB (sync write)"
- ✅ mainBlockCount正确增加（例如 4, 5）
- ✅ API查询返回正确的blockNumber

节点重启后：
- ✅ Genesis block可访问（height=1）
- ❌ 其他所有blocks不可访问（height=2-5返回null）
- ✅ ChainStats正确保留（mainBlockCount=4或5）
- ❌ 数据库中只有ChainStats和Genesis，其他block丢失

## 根本原因

### 1. 异步写入配置不一致

**DagStoreImpl.saveBlock()** (line 254):
```java
db.write(writeOptions, batch);  // 使用异步写
```

**DagStoreImpl.saveChainStats()** (line 744):
```java
db.put(syncWriteOptions, key, data);  // 使用同步写
```

**DagStoreRocksDBConfig.createWriteOptions()** (lines 235-245):
```java
public static WriteOptions createWriteOptions() {
    WriteOptions writeOptions = new WriteOptions();
    writeOptions.setSync(false);  // ❌ 异步写 - 不立即fsync
    writeOptions.setDisableWAL(false);  // 写WAL但不flush
    return writeOptions;
}
```

**DagStoreRocksDBConfig.createSyncWriteOptions()** (lines 258-268):
```java
public static WriteOptions createSyncWriteOptions() {
    WriteOptions writeOptions = new WriteOptions();
    writeOptions.setSync(true);  // ✅ 同步写 - 立即fsync
    writeOptions.setDisableWAL(false);
    return writeOptions;
}
```

### 2. Stop脚本强制Kill

**test-nodes/suite1/node/stop.sh** (lines 14-20):
```bash
kill $PID      # 发送SIGTERM
sleep 2        # 等待2秒
if ps -p $PID > /dev/null 2>&1; then
    kill -9 $PID  # ❌ 强制SIGKILL - 无法清理
fi
```

### 3. 数据丢失链路

```
Block Import
    ↓
saveBlock() → WriteBatch.put() → db.write(writeOptions)
    ↓                                      ↓
    ↓                          writeOptions.setSync(false)
    ↓                                      ↓
    ↓                          数据写入WAL (内存/OS缓冲)
    ↓                          但不立即fsync到磁盘
    ↓
saveChainStats() → db.put(syncWriteOptions)
    ↓                                      ↓
    ↓                          syncWriteOptions.setSync(true)
    ↓                                      ↓
    ↓                          ChainStats立即fsync到磁盘 ✅
    ↓
节点正常运行 (数据在WAL中，未落盘)
    ↓
stop.sh 执行
    ↓
kill $PID (SIGTERM)
    ↓
等待2秒 (RocksDB shutdown需要5-10秒来flush WAL)
    ↓
kill -9 $PID (SIGKILL) ❌
    ↓
RocksDB被强制杀死
    ↓
WAL未flush → Block数据丢失 ❌
ChainStats已落盘 → 保留 ✅
```

## 为什么Genesis Block保留？

Genesis block在节点首次启动时创建，经过长时间运行后：
- RocksDB后台compaction已经将其合并到SST文件
- SST文件已经fsync到磁盘
- 不依赖WAL

新block（height 2-5）：
- 刚导入不久（几分钟内）
- 只在WAL中，未compaction到SST
- 强制kill后丢失

## 复现步骤

1. 启动节点：`./start.sh`
2. 等待导入几个blocks（mainBlockCount=4-5）
3. 查询blocks正常：`curl http://127.0.0.1:10001/api/v1/blocks/4` ✅
4. 停止节点：`./stop.sh` （会在2秒后强制kill）
5. 重启节点：`./start.sh`
6. 查询blocks失败：`curl http://127.0.0.1:10001/api/v1/blocks/4` → null ❌
7. 但mainBlockCount仍为4：`curl http://127.0.0.1:10001/api/v1/blocks/number` → "0x4" ✅

## 影响范围

- ✅ **已影响**：所有使用stop.sh停止的节点
- ✅ **已影响**：所有异常崩溃的节点
- ❌ **未影响**：正常关闭且有足够时间flush WAL的节点

## 修复方案

### 方案1：修改saveBlock使用同步写（简单但性能差）

**优点**：
- 简单直接
- 彻底解决问题

**缺点**：
- 每个block都fsync，性能下降严重
- 可能影响epoch竞争时序

### 方案2：Epoch结束时同步WAL（推荐）

在EpochConsensusManager.onEpochEnd()结尾调用`dagStore.syncWal()`：

**优点**：
- 平衡性能和持久性
- 每64秒fsync一次，性能影响小
- 最多丢失当前epoch数据（可接受）

**缺点**：
- 需要DagStore添加syncWal()接口

### 方案3：改进stop.sh（配合方案2）

```bash
kill $PID
sleep 10  # 增加到10秒，给RocksDB充足时间
if ps -p $PID > /dev/null 2>&1; then
    echo "警告：进程未响应，将强制终止"
    kill -9 $PID
fi
```

**优点**：
- 允许RocksDB正常shutdown和flush WAL
- 配合方案2效果最佳

**缺点**：
- 停止时间变长

### 方案4：使用Java shutdown hook

在DagKernel添加shutdown hook：
```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    log.info("Shutdown hook: flushing RocksDB WAL...");
    dagStore.syncWal();
    dagStore.stop();
}));
```

**优点**：
- 捕获SIGTERM信号
- 有机会在强制kill前flush

**缺点**：
- SIGKILL无法捕获
- 依赖stop.sh给足够时间

## 推荐修复方案（组合）

**方案2 + 方案3 + 方案4**：

1. ✅ **EpochConsensusManager**: 每个epoch结束时syncWal()
2. ✅ **DagKernel**: 添加shutdown hook
3. ✅ **stop.sh**: 增加等待时间到10秒

这样三重保护：
- 正常情况：每64秒自动sync
- SIGTERM关闭：shutdown hook flush
- 强制kill：有10秒缓冲时间

## 时间线

- **2025-11-27 14:48**: 首次发现block查询返回null
- **2025-11-27 14:52**: 确认mainBlockCount与实际数据不一致
- **2025-11-27 15:10**: 确定根本原因（async write + force kill）

## 测试验证

修复后需验证：
1. ✅ 节点正常运行，block可查询
2. ✅ stop.sh停止后重启，blocks仍可查询
3. ✅ kill -9强制杀死后重启，blocks仍可查询（最多丢失当前epoch）
4. ✅ 性能测试：sync操作不影响epoch竞争

## 相关文件

- `src/main/java/io/xdag/store/rocksdb/impl/DagStoreImpl.java` (saveBlock, saveChainStats)
- `src/main/java/io/xdag/store/rocksdb/config/DagStoreRocksDBConfig.java` (WriteOptions配置)
- `src/main/java/io/xdag/consensus/epoch/EpochConsensusManager.java` (onEpochEnd)
- `test-nodes/suite1/node/stop.sh` (强制kill逻辑)
- `src/main/java/io/xdag/DagKernel.java` (lifecycle管理)
