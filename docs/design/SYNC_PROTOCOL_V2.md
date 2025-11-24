# XDAGJ 1.0 同步协议设计文档 v3.0 (Unified FastDAG)

## 1. 设计背景与约束

### 1.1 现状分析
*   **Tx 泛洪**：全网 Transaction 实时广播，节点 Mempool 中通常包含大部分未确认 Tx。
*   **存储可控**：由于 `MAX_BLOCKS_PER_EPOCH = 16` 的限制，每年数据增量约 15GB。因此，系统 **废除** 了对 12 天前孤块的物理删除逻辑，保留完整 DAG 数据以确保验证一致性。
*   **广播缺失**：当前 `MiningApiService` 挖矿后未触发 P2P 广播。

### 1.2 核心目标
1.  **打通实时广播**：让新挖出的块能通过 `Inv/Get` 机制秒级传播。
2.  **统一同步模型**：由于历史数据不再裁剪，历史同步与活跃同步统一采用 **Epoch-based DAG Sync**。
3.  **高效补全**：利用 `GetEpochHashes` 批量拉取 Top 16 区块。

---

## 2. 协议架构：统一 DAG 同步

不再区分“历史车道”和“活跃车道”，统一为单一的 DAG 同步逻辑。

### 2.1 同步策略
*   **范围**：从 `LocalMaxEpoch` 到 `RemoteMaxEpoch`。
*   **粒度**：按 Epoch 批量请求。
*   **逻辑**：
    1.  发送 `GET_EPOCH_HASHES(Start, End)`。
    2.  Peer 返回每个 Epoch 下的所有 Block Hash（包括主块和孤块，最多 16 个/Epoch）。
    3.  发送 `GET_BLOCKS(List<Hash>)` 拉取数据。
    4.  **验证**：执行严格的 DAG 验证（所有父块引用必须存在，签名必须有效）。

### 2.2 实时广播 (Real-time Broadcast)
*   **场景**：节点在线，挖到新块或收到新块。
*   **逻辑**：
    1.  挖矿成功 -> 触发 `NewBlockListener` -> 发送 `NEW_BLOCK_HASH` (Inv)。
    2.  接收方收到 Inv -> 查本地 -> `GET_BLOCKS`。

---

## 3. 协议消息定义 (Message Specification)

### A. 实时层 (Real-time)
| 消息名 | Code | 负载 | 用途 |
| :--- | :--- | :--- | :--- |
| `NEW_BLOCK_HASH` | 0x10 | `hash`, `epoch`, `ttl` | 广播新块的存在 (Inv) |
| `GET_BLOCKS` | 0x11 | `List<hash>` | 请求具体块数据 |
| `BLOCKS_REPLY` | 0x12 | `List<Block>` | 返回块数据 (Body only) |

### B. 同步层 (Sync Layer)
| 消息名 | Code | 负载 | 用途 |
| :--- | :--- | :--- | :--- |
| `GET_EPOCH_HASHES` | 0x30 | `startEpoch`, `endEpoch` | 请求 Epoch 范围内的所有 Hash |
| `EPOCH_HASHES_REPLY`| 0x31 | `Map<Epoch, List<Hash>>` | 返回 Hash 清单 |

### C. 兜底层 (Recovery)
| 消息名 | Code | 负载 | 用途 |
| :--- | :--- | :--- | :--- |
| `GET_TRANSACTIONS` | 0x40 | `List<hash>` | 请求缺失的 Tx (Mempool miss) |
| `TRANSACTIONS_REPLY`| 0x41 | `List<Tx>` | 返回 Tx 数据 |

---

## 4. 模块修改点 (Action Items)

### 4.1 Core Layer (`io.xdag.core`)
1.  **`DagChain.java`**:
    *   新增 `registerNewBlockListener(NewBlockListener listener)`。
    *   新增 `List<Bytes32> getBlockHashesByEpoch(long epoch)`。
2.  **`DagChainImpl.java`**:
    *   实现上述接口。
    *   **已完成**：废除 `cleanupOldOrphans` 中的删除逻辑。

### 4.2 Net Layer (`io.xdag.p2p`)
1.  **`SyncManager.java` (新)**:
    *   负责调度 `GET_EPOCH_HASHES`。
2.  **`XdagP2pEventHandler.java`**:
    *   增加 `handleNewBlockHash` (Inv处理)。
    *   增加 `handleGetEpochHashes` (查 DB 回复)。

### 4.3 Service Layer (`io.xdag.api`)
1.  **`MiningApiService.java`**:
    *   无需修改代码逻辑，但其产生的块将通过 `DagChain` 的 Listener 自动流向 P2P 网络。