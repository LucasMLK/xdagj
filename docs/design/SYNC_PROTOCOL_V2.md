# XDAGJ 1.0 Sync Protocol Design v3.0 (Unified FastDAG)

## 1. Background & Constraints

### 1.1 Current state
* **Transaction flooding** – transactions are broadcast network-wide in real time, so each node's tx pool already holds most pending transactions.
* **Controlled storage growth** – `MAX_BLOCKS_PER_EPOCH = 16` caps annual data growth at ~15 GB. Because of this, the system **removed** the logic that deletes orphans older than 12 days; keeping the full DAG guarantees deterministic validation.
* **Missing broadcast** – `MiningApiService` previously failed to announce newly mined blocks over P2P.

### 1.2 Goals
1. **Enable real-time broadcast** – new blocks must be propagated within seconds via an Inv/Get flow.
2. **Unified sync model** – since historical data is no longer pruned, both historical and live synchronization use the same epoch-based DAG sync.
3. **Efficient completion** – leverage `GetEpochHashes` to fetch the top 16 blocks per epoch in bulk.

---

## 2. Protocol architecture: unified DAG sync

The old split between “historical” and “live” lanes is gone; a single DAG sync pipeline handles all states.

### 2.1 Sync strategy
* **Range**: from `LocalMaxEpoch` up to `RemoteMaxEpoch`.
* **Granularity**: request data per epoch.
* **Flow**:
  1. Send `GET_EPOCH_HASHES(start, end)`.
  2. Peer replies with every block hash per epoch (main block + orphans, up to 16 each).
  3. Request missing bodies via `GET_BLOCKS(List<Hash>)`.
  4. **Validate** strictly – every parent reference must exist and signatures must verify.

### 2.2 Real-time broadcast
* **Scenario**: node is online and either mined a block or learned a new block from a peer.
* **Flow**:
  1. Mining succeeds → `NewBlockListener` fires → send `NEW_BLOCK_HASH` (Inv message).
  2. Receiver inspects local storage; if missing, it issues `GET_BLOCKS`.

---

## 3. Message specification

### A. Real-time layer
| Message | Code | Payload | Purpose |
| :--- | :--- | :--- | :--- |
| `NEW_BLOCK_HASH` | 0x10 | `hash`, `epoch`, `ttl` | Broadcast that a block exists (Inv) |
| `GET_BLOCKS` | 0x11 | `List<hash>` | Request block bodies |
| `BLOCKS_REPLY` | 0x12 | `List<Block>` | Return block bodies |

### B. Sync layer
| Message | Code | Payload | Purpose |
| :--- | :--- | :--- | :--- |
| `GET_EPOCH_HASHES` | 0x30 | `startEpoch`, `endEpoch` | Request all hashes in an epoch range |
| `EPOCH_HASHES_REPLY` | 0x31 | `Map<Epoch, List<Hash>>` | Return block hashes grouped by epoch |

### C. Recovery layer
| Message | Code | Payload | Purpose |
| :--- | :--- | :--- | :--- |
| `GET_TRANSACTIONS` | 0x40 | `List<hash>` | Request missing transactions (mempool misses) |
| `TRANSACTIONS_REPLY` | 0x41 | `List<Tx>` | Return transaction data |

---

## 4. Required changes

### 4.1 Core layer (`io.xdag.core`)
1. **`DagChain.java`**
   * Add `registerNewBlockListener(NewBlockListener listener)`.
   * Add `List<Bytes32> getBlockHashesByEpoch(long epoch)`.
2. **`DagChainImpl.java`**
   * Implement the new APIs.
   * Completed: remove deletion logic from `cleanupOldOrphans`.

### 4.2 Networking (`io.xdag.p2p`)
1. **`SyncManager.java` (new)** – schedules `GET_EPOCH_HASHES` requests.
2. **`XdagP2pEventHandler.java`**
   * Handle `NEW_BLOCK_HASH` (Inv processing).
   * Handle `GET_EPOCH_HASHES` by querying the DAG store and responding.

### 4.3 Service layer (`io.xdag.api`)
1. **`MiningApiService.java`** – no code changes required; newly generated blocks automatically flow to P2P through the `DagChain` listener.
