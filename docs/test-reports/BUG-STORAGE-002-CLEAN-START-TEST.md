# BUG-STORAGE-002 全新启动测试报告

## 测试目的

验证BUG-STORAGE-002修复在全新环境下的有效性：
1. 从零开始生成区块
2. 验证数据在两个节点间完全一致
3. 验证MemTable flush机制正常工作
4. 验证节点重启后数据持久化

## 测试环境

### 清理前状态
- Node1: Height 202, 有历史gaps (heights 1-93)
- Node2: Height 202, 有历史gaps (heights 1-93)
- Heights 94+: 100%一致

### 清理操作
**时间**: 2025-11-28 13:44

**清理内容**:
- ✅ 删除devnet数据目录
- ✅ 删除所有日志文件
- ✅ 删除PID文件

**目的**: 确保从Genesis block全新开始

## 节点启动

### Node1 (Suite1)
- **启动时间**: 13:45
- **PID**: 28682
- **端口**:
  - P2P: 8001
  - HTTP API: 10001
- **配置**: test-nodes/suite1/node/xdag-devnet.conf

### Node2 (Suite2)
- **启动时间**: 13:45
- **PID**: 29119
- **端口**:
  - P2P: 8002
  - HTTP API: 10002
- **配置**: test-nodes/suite2/node/xdag-devnet.conf

## 初始化验证（T+0分钟）

### Node1日志证据

```
2025-11-28 | 13:46:40.004 [EpochTimer] -- Importing block for epoch 27567324
2025-11-28 | 13:46:40.018 [EpochTimer] -- MemTable flushed and WAL synced to disk (flush=3ms, total=13ms)
2025-11-28 | 13:46:43.235 [peerClient-1] -- 📨 Received message type 0x34 from /127.0.0.1:8002
```

**验证点**:
- ✅ 区块生成正常
- ✅ MemTable flush正常（3ms）
- ✅ P2P连接到Node2成功

### Node2日志证据

```
2025-11-28 | 13:47:39.006 [BackupMiner] -- ✓ Backup miner found solution for epoch 27567325
2025-11-28 | 13:47:44.015 [EpochTimer] -- MemTable flushed and WAL synced to disk (flush=0ms, total=9ms)
2025-11-28 | 13:47:48.292 [peerClient-1] -- 📨 Received message type 0x34 from /127.0.0.1:8002
```

**验证点**:
- ✅ 区块生成正常
- ✅ Backup mining工作
- ✅ MemTable flush正常（0ms表示无新数据需flush）
- ✅ P2P连接正常

## 测试计划

### Phase 1: 初始区块生成（T+0 ~ T+2分钟）
**状态**: 🔄 进行中

**目标**:
- 等待2个epoch（128秒）
- 让两个节点各自生成区块
- 观察MemTable flush日志

**预期结果**:
- 每64秒一次MemTable flush
- 两个节点应有2-4个主块
- 区块hash应该相同

### Phase 2: 数据一致性验证（T+2分钟）
**状态**: ⏳ 待执行

**测试步骤**:
1. 查询两个节点的最新height
2. 对比最近10个blocks的hash
3. 验证epoch竞争记录
4. 检查error log（应该为空）

**通过标准**:
- ✅ 所有block hash完全匹配
- ✅ Heights相同
- ✅ 无"Link target not found"错误

### Phase 3: 节点重启测试（T+3分钟）
**状态**: ⏳ 待执行

**测试步骤**:
1. 停止Node2（正常停止，触发shutdown hook）
2. 等待5秒
3. 重启Node2
4. 验证：
   - 重启前的所有blocks都能查询到
   - mainBlockCount保持不变
   - 可以继续生成新blocks
   - 与Node1同步正常

**通过标准**:
- ✅ 数据完整保留（无丢失）
- ✅ Height连续
- ✅ 能正常同步新blocks

### Phase 4: 长时间稳定性测试（T+5分钟 ~ T+30分钟）
**状态**: ⏳ 待执行

**测试内容**:
1. 让两个节点运行30分钟
2. 每5分钟检查一次数据一致性
3. 观察MemTable flush性能
4. 监控error log

**指标**:
- MemTable flush时间应稳定在15ms以下
- 无数据丢失
- 无"Link target not found"错误
- 数据100%一致

### Phase 5: 压力重启测试（T+30分钟）
**状态**: ⏳ 待执行

**测试步骤**:
1. 在epoch中间停止Node1（模拟异常关闭）
2. 立即重启Node1
3. 验证数据完整性
4. 重复3次

**通过标准**:
- ✅ 每次重启后数据都完整
- ✅ 可以继续参与共识
- ✅ 与Node2保持同步

## 性能指标

### MemTable Flush性能

| 节点 | Flush时间 | Total时间 | 状态 |
|------|-----------|-----------|------|
| Node1 | 3ms | 13ms | ✅ 正常 |
| Node2 | 0ms | 9ms | ✅ 正常 |

**基准线**: <20ms视为正常

### 内存使用

待测量

### CPU使用

待测量

## 问题追踪

### 已知问题

无

### 新发现问题

待测试

## 结论

### 当前状态（T+0分钟）

✅ **初始化成功**:
- 两个节点启动正常
- MemTable flush机制生效
- P2P连接建立
- 开始生成区块

### 待验证项

- [ ] 数据一致性
- [ ] 节点重启持久化
- [ ] 长时间稳定性
- [ ] 压力重启测试

## 测试日志

### 13:45 - 节点启动
- Node1 (PID: 28682) 启动成功
- Node2 (PID: 29119) 启动成功

### 13:46 - 首个epoch完成
- Node1生成epoch 27567324 block
- MemTable flush: 3ms/13ms

### 13:47 - 第二个epoch
- Node2生成epoch 27567325 block
- MemTable flush: 0ms/9ms

### 13:49 - 开始等待测试
- 设置2个epoch等待（128秒）
- 目标: 生成足够的测试数据

## 附录

### 配置文件对比

两个节点使用相同的配置模板，仅端口不同：

**Node1**: 8001 (P2P), 10001 (HTTP)
**Node2**: 8002 (P2P), 10002 (HTTP)

### 关键修复验证

BUG-STORAGE-002修复的四重保护机制：
1. ✅ Epoch结束时flush (已验证日志)
2. ✅ Shutdown hook flush (待重启测试验证)
3. ✅ Stop方法flush (待重启测试验证)
4. ✅ 10秒延迟kill (待重启测试验证)

## 签名

**测试执行者**: Claude Code (Anthropic AI Assistant)
**测试开始时间**: 2025-11-28 13:45 (GMT+8)
**报告更新时间**: 2025-11-28 13:49 (GMT+8)
**当前阶段**: Phase 1 - 初始区块生成
**状态**: 🔄 测试进行中
