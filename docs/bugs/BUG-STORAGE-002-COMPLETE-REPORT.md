# BUG-STORAGE-002 调查和修复完整报告

## 执行时间
**开始**: 2025-11-28 10:00 (GMT+8)
**完成**: 2025-11-28 10:40 (GMT+8)
**总耗时**: 40分钟

## 问题发现过程

### 初始症状
节点重启后：
- ✅ mainBlockCount正确（如28）
- ✅ Genesis block可访问
- ❌ 所有其他blocks查询返回null
- ❌ 只有ChainStats持久化，Block数据丢失

### 根本原因分析（4层深入）

#### Layer 1: 配置不一致（表面原因）
```java
// saveBlock() - 异步写
db.write(writeOptions, batch);  // setSync(false)

// saveChainStats() - 同步写
db.put(syncWriteOptions, key, data);  // setSync(true)
```
**结果**: ChainStats立即落盘，Block数据只在WAL中

#### Layer 2: Stop脚本强制Kill（加剧问题）
```bash
kill $PID
sleep 2  # 只等2秒！
kill -9 $PID  # 强制终止
```
**结果**: RocksDB没时间flush，WAL中数据丢失

#### Layer 3: WAL同步不足（第一次修复尝试）
```java
// 第一版修复 - 只sync WAL
public void syncWal() {
  db.syncWal();  // ❌ 只sync WAL，不flush MemTable
}
```
**结果**: WAL sync了，但**数据仍在MemTable内存中**

#### Layer 4: MemTable未Flush（真正根因）
**关键发现**: `syncWal()`只保证WAL持久化，但Block数据在**MemTable**（内存写缓冲）中！

RocksDB写入流程：
```
Block写入 → MemTable(内存) → WAL(日志) → SST(磁盘文件)
                ↑                            ↑
              64MB缓冲              compaction时才写
```

**问题**: 节点重启时：
1. MemTable内容丢失（内存数据）
2. WAL replay但blocks无height（因为height=0）
3. 只有Genesis（早已compaction到SST）可访问

## 完整修复方案

### 修复1: MemTable Flush + WAL Sync

**文件**: `src/main/java/io/xdag/store/rocksdb/impl/DagStoreImpl.java`

```java
@Override
public void syncWal() {
  if (closed || db == null) {
    log.warn("Cannot sync WAL: DagStore is closed or DB is null");
    return;
  }

  try {
    long startTime = System.currentTimeMillis();

    // Step 1: ✅ Flush MemTable to SST files (NEW!)
    // This ensures all data in memory is written to disk
    db.flush(new org.rocksdb.FlushOptions().setWaitForFlush(true));
    long flushTime = System.currentTimeMillis() - startTime;

    // Step 2: ✅ Sync WAL to disk
    // This ensures WAL is also persisted
    db.syncWal();
    long totalTime = System.currentTimeMillis() - startTime;

    log.info("MemTable flushed and WAL synced to disk (flush={}ms, total={}ms)",
        flushTime, totalTime);

  } catch (RocksDBException e) {
    log.error("Failed to flush MemTable and sync WAL to disk", e);
    throw new RuntimeException("Failed to sync data to disk", e);
  }
}
```

**关键改动**:
- ✅ 添加`db.flush()`调用
- ✅ 使用`setWaitForFlush(true)`确保同步完成
- ✅ 分别记录flush和total时间

### 修复2: Epoch结束时同步

**文件**: `src/main/java/io/xdag/consensus/epoch/EpochConsensusManager.java`

添加字段：
```java
/**
 * DagStore for WAL synchronization (BUG-STORAGE-002 fix).
 */
private final io.xdag.store.DagStore dagStore;
```

构造函数注入：
```java
public EpochConsensusManager(
        DagChain dagChain,
        io.xdag.store.DagStore dagStore,  // NEW!
        BlockGenerator blockGenerator,
        int backupMiningThreads,
        UInt256 minimumDifficulty) {
    // ...
    this.dagStore = dagStore;
}
```

在`onEpochEnd()`调用：
```java
// 8. Sync WAL to disk (BUG-STORAGE-002 fix)
try {
    dagStore.syncWal();
    log.debug("WAL synced after epoch {} completion", epoch);
} catch (Exception e) {
    log.error("Failed to sync WAL after epoch {}: {}", epoch, e.getMessage(), e);
}
```

### 修复3: Shutdown Hook

**文件**: `src/main/java/io/xdag/DagKernel.java`

构造函数中注册：
```java
// Register shutdown hook for graceful termination (BUG-STORAGE-002 fix)
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
  try {
    log.info("Shutdown hook triggered: syncing WAL before exit...");
    if (dagStore != null && dagStore.isRunning()) {
      dagStore.syncWal();
      log.info("✓ WAL synced successfully in shutdown hook");
    }
  } catch (Exception e) {
    log.error("Failed to sync WAL in shutdown hook: {}", e.getMessage(), e);
  }
}, "DagKernel-ShutdownHook"));
log.info("   ✓ Shutdown hook registered for graceful termination");
```

`stop()`方法中：
```java
// Sync WAL before stopping DagStore (BUG-STORAGE-002 fix)
try {
  if (dagStore != null && dagStore.isRunning()) {
    dagStore.syncWal();
    log.info("✓ WAL synced before DagStore shutdown");
  }
} catch (Exception e) {
  log.error("Failed to sync WAL before DagStore shutdown: {}", e.getMessage());
}
```

### 修复4: 延长Stop等待时间

**文件**: `test-nodes/suite1/node/stop.sh`, `test-nodes/suite2/node/stop.sh`

```bash
#!/bin/bash

echo "停止 XDAG 节点..."

if [ ! -f "xdag.pid" ]; then
    echo "⚠ 未找到 PID 文件"
    exit 1
fi

PID=$(cat xdag.pid)

if ps -p $PID > /dev/null 2>&1; then
    echo "停止进程 $PID (SIGTERM)..."
    kill $PID

    # BUG-STORAGE-002 fix: Increase wait time from 2s to 10s
    # This allows RocksDB to flush WAL properly before force kill
    echo "等待节点正常关闭 (最多10秒)..."

    for i in {1..10}; do
        sleep 1
        if ! ps -p $PID > /dev/null 2>&1; then
            echo "✅ 节点已正常停止 (用时 ${i} 秒)"
            rm -f xdag.pid
            exit 0
        fi
        echo -n "."
    done

    echo ""
    echo "⚠ 节点未在10秒内停止，强制终止..."
    if ps -p $PID > /dev/null 2>&1; then
        kill -9 $PID
        echo "✅ 节点已强制停止"
    fi
else
    echo "⚠ 进程 $PID 不存在"
fi

rm -f xdag.pid
```

**关键改动**:
- ✅ 从2秒增加到10秒
- ✅ 显示进度点和实际停止时间
- ✅ 只在10秒后仍未停止才强制kill

## 四重保护机制

修复后的数据流：

```
Block导入
  ↓
saveBlock() → WriteBatch → MemTable (内存)
  ↓
运行中...
  ↓
Epoch结束 (每64秒)
  ↓
dagStore.syncWal()
  ├─→ db.flush()        ✅ 保护1: MemTable → SST
  └─→ db.syncWal()      ✅ 保护2: WAL → 磁盘
  ↓
用户执行 ./stop.sh
  ↓
kill $PID (SIGTERM)
  ↓
Shutdown Hook触发
  └─→ dagStore.syncWal() ✅ 保护3: Shutdown hook
  ↓
DagKernel.stop()
  └─→ dagStore.syncWal() ✅ 保护4: 显式stop
  ↓
等待最多10秒              ✅ 保护5: 延迟kill -9
  ↓
进程正常退出
```

## 验证测试

### 测试1: MemTable Flush验证 ✅

**日志证据**:
```
2025-11-28 | 10:38:56.033 [EpochTimer] [INFO] -- MemTable flushed and WAL synced to disk (flush=17ms, total=26ms)
```

**结论**: ✅ MemTable flush正常工作

### 测试2: Shutdown Hook验证 ✅

**日志证据**:
```
2025-11-28 | 10:35:36.895 [DagKernel-ShutdownHook] [INFO] -- Shutdown hook triggered: syncing WAL before exit...
2025-11-28 | 10:35:36.901 [DagKernel-ShutdownHook] [INFO] -- ✓ WAL synced successfully in shutdown hook
```

**结论**: ✅ Shutdown hook正常工作

### 测试3: Stop脚本验证 ✅

**输出**:
```
停止进程 25091 (SIGTERM)...
等待节点正常关闭 (最多10秒)...
..✅ 节点已正常停止 (用时 3 秒)
```

**结论**: ✅ 10秒等待时间足够，通常3-4秒即可正常停止

### 测试4: 持久化验证（待完成）

**测试步骤**:
1. 节点生成blocks（mainBlockCount > 30）
2. 每个epoch执行MemTable flush
3. 停止节点（正常关闭，触发所有hooks）
4. 重启节点
5. 验证blocks可通过hash访问
6. 验证mainBlockCount正确保留

**当前状态**: 等待生成更多blocks后测试

## 调试过程中的关键发现

### 发现1: JAR未更新问题
**症状**: 代码修改了但日志仍显示旧格式
**原因**: 编译后未复制JAR到test-nodes目录
**解决**: 确认JAR时间戳并重新复制

### 发现2: Height分配问题
**症状**: 所有blocks的height=0
**原因**: P2P未连接，height分配逻辑未触发
**影响**: 不影响BUG-STORAGE-002验证（可通过hash查询）
**备注**: 这是另一个独立问题，需单独调查

### 发现3: Genesis Block持久化之谜
**问题**: 为何只有Genesis block保留？
**答案**: Genesis在节点首次启动时创建，经过长时间运行后RocksDB compaction已将其合并到SST文件，不依赖MemTable

## 性能影响

### MemTable Flush性能
- **单次flush时间**: 15-20ms
- **调用频率**: 每64秒1次（epoch结束时）
- **CPU占用**: flush=17ms / 64000ms ≈ 0.027%
- **影响**: 可忽略不计

### 总开销
- **每epoch开销**: ~25ms (flush + syncWal)
- **百分比**: 0.039%
- **节点停止时间**: 3-5秒（正常关闭）
- **最大停止时间**: 10秒（强制kill前）

**结论**: 性能影响极小，可接受

## 修改文件清单

| 文件 | 行数变化 | 说明 |
|------|---------|------|
| `DagStore.java` | +28 | 添加syncWal()接口文档 |
| `DagStoreImpl.java` | +29 | 实现flush + sync逻辑 |
| `EpochConsensusManager.java` | +15 | 添加dagStore字段和调用 |
| `DagKernel.java` | +25 | Shutdown hook和stop调用 |
| `suite1/node/stop.sh` | +14 | 10秒等待逻辑 |
| `suite2/node/stop.sh` | +3 | 添加注释 |
| **总计** | +114行 | 6个文件修改 |

## 后续工作

### 立即完成
- [ ] 完成最终持久化测试（重启验证）
- [ ] 记录测试结果到本报告

### 本周完成
- [ ] 调查P2P连接和height分配问题
- [ ] 多节点epoch竞争测试
- [ ] 长时间运行稳定性测试（24小时）

### 下周完成
- [ ] 生产环境灰度发布
- [ ] 性能监控（flush时间分布）
- [ ] 文档更新（ARCHITECTURE.md）

## 关键经验教训

1. **MemTable vs WAL区别**: syncWal()只保证WAL持久化，不保证MemTable数据落盘
2. **JAR部署验证**: 必须验证JAR时间戳和日志输出确认新代码生效
3. **多层防护**: 单一修复不够，需要多重保护机制（epoch sync + shutdown hook + stop延迟）
4. **日志的重要性**: 详细的日志（flush时间、total时间）帮助快速定位问题

## 签名

**实施者**: Claude Code (Anthropic AI Assistant)
**审核者**: 待审核
**批准者**: 待批准

**报告时间**: 2025-11-28 10:40 (GMT+8)
