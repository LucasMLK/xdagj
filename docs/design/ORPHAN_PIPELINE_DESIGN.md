# XDAGJ 1.0 孤块处理（Orphan Pipeline）设计方案 v1.0

## 1. 背景与现状

### 1.1 XDAG 共识与同步特点
* 64 秒一个 epoch，同 epoch 最多 16 个候选块，最终只有一个主块。
* 节点通过 P2P gossip、`GET_EPOCH_HASHES` 等接口同步 DAG，区块可能乱序抵达。
* 同步阶段必须接受“父块缺失”的候选，等待后续补齐；而同 epoch 竞争失败的块永远不会晋升主链。

### 1.2 现有实现的问题
| 问题 | 影响 |
| --- | --- |
| `DagChainImpl.tryToConnect` 每次导入后同步调用 `OrphanManager.retryOrphanBlocks` | 导入线程被 RocksDB 全表扫描拖慢，导入延迟与 pending 数量成倍增长 |
| Orphan reason 只有在 epoch 竞争失败时写入，缺依赖场景依赖“默认值” | 日志与监控难以区分 orphan 类型，迁移/扩展风险大 |
| Retry 策略是“从 epoch=0 开始扫描 100 个 pending，最多 10 轮” | 早期 orphan 长期占用窗口，最近刚满足依赖的 orphan 需要等待多轮扫描 |
| 输掉竞争的 orphan 只是在重试入口被过滤 | 数据结构仍混在一起，未来 reorg / pipeline 扩展存在耦合 |

## 2. 设计目标
1. **解耦导入与孤块重试**：导入链做快速胜负判定；依赖补齐由独立线程限频执行。
2. **精准分类**：所有 orphan 必须带 `OrphanReason` 与依赖信息，避免依赖“默认值”。
3. **按依赖驱动重试**：记录父哈希 → orphan 列表，父块到达时精准触发，减少无意义扫描。
4. **便于监控与调度**：能统计 pending 队列长度、失败原因、最近重试时间，为同步与共识调优提供数据。
5. **兼容现有同步协议**：不改变 `GET_EPOCH_HASHES`、`tryToConnect` 接口，迁移可渐进完成。

## 3. 总体架构

```
┌──────────────┐      ┌────────────────────┐      ┌────────────────────────┐
│ Block Import │──▶──▶│ OrphanTracker (新) │──▶──▶│ Orphan Retry Executor  │
└──────────────┘      └────────────────────┘      └────────────────────────┘
        │                       │                             │
        │ main / demote         │ parent-ready queue          │ 调度后台线程
        ▼                       ▼                             ▼
  DagStore (Block + OrphanReason + PendingIndexes)         DagChain.tryToConnect()

> DagChainImpl 在构造时启动 `OrphanManager`（传入 `DagChain::tryToConnect` 作为回调），在 kernel 停止时调用 `DagChain.stop()`，确保后台线程随节点生命周期一起关闭。
```

* **Block Import**：负责验证、胜负判定；若缺依赖则登记 `MISSING_DEPENDENCY`，若输掉竞争/重组则登记相应 reason。
* **OrphanTracker**：维护 `missingParentHash → orphan list` 的内存索引 + RocksDB 镜像，当父块导入或 demote 时发出事件。
* **Retry Executor**：由 `ScheduledExecutorService` 或独立线程定频消费 parent-ready 队列，批量调用 `tryToConnect` 重试。

## 4. 数据模型调整

| 字段 | 说明 | 存储位置 |
| --- | --- | --- |
| `orphan_reason` | `MISSING_DEPENDENCY / LOST_COMPETITION / CHAIN_REORG` 等 | RocksDB `ORPHAN_REASON` CF |
| `missing_parents` | 最大 4 个父哈希列表（压缩存储），仅对缺依赖 orphan 有意义 | RocksDB 新列族或复用现有 block meta |
| `next_retry_at` | 退避策略的下次重试时间戳，避免频繁重复 | RocksDB，可选 |
| `parent_index` | `parent_hash → List<child_hash>` 的映射，供精准触发 | RocksDB 新 CF + 内存索引 |

> 注：现有“无记录即视为缺依赖”的逻辑在迁移期继续保留，但新逻辑写入完整元数据后会逐步淘汰 fallback。

## 5. 关键流程

### 5.1 新块导入
1. 验证、计算难度、判定 epoch winner。
2. **若缺父块**：在 `DagStore` 写入 `orphan_reason=MISSING_DEPENDENCY`，记录缺失父哈希，调用 `orphanTracker.registerMissing(block, parents)`。
3. **若输掉竞争或 demote**：写入 `LOST_COMPETITION / CHAIN_REORG`；只保留静态记录，不进重试队列。
4. 导入完成后仅发出 `orphanTracker.onMainBlockImported(block)` 事件，不直接重试。

### 5.2 父块抵达 / 链重组
1. OrphanTracker 根据父块 hash 找到等待的 orphan 集合，写入 `parent-ready queue`。
2. Retry Executor 从队列批量取出 orphan，调用 `DagChain.tryToConnect`。
3. 成功则清理 `orphan_reason` 与 `parent_index`，失败且仍缺依赖则更新 `missing_parents` 与 `next_retry_at`。

### 5.3 调度策略
* 后台线程每 `N` 秒或“父块事件累计到阈值”后触发，单次处理 `batchSize` (默认为 64)。
* 对于一直缺依赖的 orphan，使用指数退避更新 `next_retry_at`，避免热循环。
* Epoch 结束或节点重启时，主动执行一次“扫尾重试”，保证状态收敛。

## 6. 模块改动清单

| 模块 | 修改方向 |
| --- | --- |
| `DagChainImpl` | 去除同步 `orphanManager.retryOrphanBlocks`；改为发事件给 OrphanTracker。 |
| `BlockImporter` | 在 `MISSING_DEPENDENCY`、`demote`、`LOST_COMPETITION` 等路径上显式写入 `OrphanReason`，并调用 `orphanTracker`。 |
| `DagStore` | 新增 `missingParents`、`parentIndex`、`nextRetryAt` 的存取接口；现有 `getPendingBlocksByReason` 逐步迁移为基于 parent 的检索。 |
| `OrphanManager` (重命名为 OrphanTracker + RetryExecutor) | 引入线程安全队列和后台调度，提供 `registerMissing`, `onParentImported`, `pollReadyOrphans` 等 API。 |
| `EpochConsensusManager` | 在 epoch 结束后触发一次“父块事件”，确保兜底。 |
| 监控/日志 | 打印 `pending_count`、`retry_success/fail`、`reason breakdown`，供 compare-nodes 或运维脚本查看。 |

## 7. 迁移与兼容性
1. **阶段 1**：保留旧的 pending 扫描逻辑，但在导入路径写入完整 `OrphanReason + missingParents`；新增后台 executor 处理 parent-ready 队列。若后台队列暂时为空，仍可 fallback 到旧的 `getPendingBlocksByReason`。
2. **阶段 2（已完成）**：当 parent-index 与退避策略稳定后，移除 `DagChainImpl` 中的同步重试，并废弃旧的扫描接口。
3. **数据迁移**：启动时遍历现有 `height=0` 块，对没有 reason 的记录补写 `MISSING_DEPENDENCY`。可分批执行，避免长时间锁库。

## 8. 代码清理计划
为保持 1.0 主干的简洁可维护性，迁移期间同步执行以下清理步骤：

| 时机 | 清理内容 |
| --- | --- |
| parent-index 与异步 executor 首次上线后（已完成） | 删除 `orphanManager.retryOrphanBlocks` 的同步调用路径、`ThreadLocal` 递归保护逻辑，以及仅服务于“全表扫描” retry 的工具方法；更新测试与调用者指向新 API。 |
| 所有 orphan 均写入 `OrphanReason` / `missingParents` 后（已完成） | 去掉 “无记录视为缺依赖” 的 fallback，明确 reason 为必填字段，并在 BlockImporter/demote 路径上收紧断言。 |
| Phase 2 完成后 | 移除 DagStore 中的旧查询接口（如固定从 epoch=0 扫描的 pending API），以及依赖这些接口的脚本；保留必要的临时迁移工具到 `misc/` 或 `docs/refactoring`，便于回溯。 |
| 最终收尾 | 将原 `OrphanManager` 拆为 `OrphanTracker` + `OrphanRetryExecutor`，清理无用字段/日志；同步更新 `compare-nodes`、API、监控脚本以反映新的 pending 指标。 |

> 建议每完成一个阶段性目标就立即合并对应的清理，以缩短“新旧逻辑并存”的窗口。

## 9. 测试计划
1. **单元测试**
   - `BlockImporterTest`：覆盖缺依赖、竞赛失败、demote 场景，断言 `OrphanReason` 与 `missingParents` 写入正确。
   - `OrphanTrackerTest`：模拟父块到达，验证只会调度对应 orphan，其他 reason 不受影响；测试退避/nextRetryAt 行为。
   - `OrphanRetryExecutorTest`：验证批量重试、失败退避、成功清理 orphan 数据。
   - `DagStoreImplTest`：新增 parent index 的存取、迁移脚本补写 reason 的逻辑。
2. **集成测试**
   - 多节点 suite：构造“Block C 先到、父块后到”场景，确认 pending 队列长度随父块导入快速收敛。
   - compare-nodes 流程：确保主链一致时，pending 队列统计也一致，不出现 LOST_COMPETITION 被重试。
3. **回归测试**
   - 运行 `mvn test -Dtest=DagChainConsensusTest,NetworkPartitionIntegrationTest`，确保重构后 DAG 行为与共识逻辑保持一致。

## 10. 开放问题与后续
1. **parent index 存储开销**：初期可只维护内存索引 + WAL replay；若节点重启频繁，可把 index 列表写入 RocksDB CF。
2. **重试的并行度**：默认 1 个线程即可；未来若需更高吞吐，可按 epoch/高度分片。
3. **对同步协议的增强**：同步阶段可以直接读取 parent index，按“缺父哈希”批量请求，进一步减少等待时间。
4. **观测入口**：计划在 `ChainApiService` 增加 `/orphans/status` API，展示 pending 队列与 reason breakdown。

---

通过上述设计，孤块处理从“导入路径上的阻塞扫描”演进为“按依赖驱动的异步管线”。这既契合 XDAG 的 epoch 共识（确保主链推进不被孤块拖慢），也能在同步和对等节点校验时提供可预测的行为，避免把暂时的补丁变成长期负债。下一步即可按照第 6 节列出的模块修改逐项实现，并在多节点测试套件里观察 pending 队列指标是否达到预期。 
