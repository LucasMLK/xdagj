# XDAGJ 1.0 Sync Protocol Design v3.1 (FastDAG with Binary Search)

## 1. Background & Constraints

### 1.1 Current state
* **Transaction flooding** – transactions are broadcast network-wide in real time, so each node's tx pool already holds most pending transactions.
* **Controlled storage growth** – `MAX_BLOCKS_PER_EPOCH = 16` caps annual data growth at ~15 GB. Because of this, the system **removed** the logic that deletes orphans older than 12 days; keeping the full DAG guarantees deterministic validation.
* **Missing broadcast** – `MiningApiService` previously failed to announce newly mined blocks over P2P.

### 1.2 Goals
1. **Enable real-time broadcast** – new blocks must be propagated within seconds via an Inv/Get flow.
2. **Unified sync model** – since historical data is no longer pruned, both historical and live synchronization use the same epoch-based DAG sync.
3. **Efficient completion** – leverage `GetEpochHashes` to fetch the top 16 blocks per epoch in bulk.
4. **Binary search for large gaps** – when local chain is far behind (>1024 epochs), use binary search to locate the starting point efficiently.
5. **Sync gating** – prevent mining before synchronization is complete to avoid creating orphan blocks.

---

## 2. Protocol architecture: unified DAG sync

The old split between "historical" and "live" lanes is gone; a single DAG sync pipeline handles all states.

### 2.1 Sync state machine

```
                    ┌─────────────────┐
                    │  FORWARD_SYNC   │◄──────────────────┐
                    └────────┬────────┘                   │
                             │                            │
                    gap > 1024 epochs?                    │
                             │                            │
                    ┌────────▼────────┐                   │
                    │  BINARY_SEARCH  │                   │
                    └────────┬────────┘                   │
                             │                            │
                    range <= 256 or                       │
                    iterations >= 20?                     │
                             │                            │
                    ┌────────▼────────┐                   │
                    │ BINARY_SEARCH   │                   │
                    │   COMPLETE      │───────────────────┘
                    └─────────────────┘
```

### 2.2 Forward sync strategy
* **Range**: from `LocalMaxEpoch + 1` up to `RemoteMaxEpoch`.
* **Granularity**: request data per epoch (max 256 epochs per request).
* **Pipeline gap check**: pause if `lastRequestedEpoch - localTipEpoch > 4096` (prevents memory overflow).
* **Flow**:
  1. Send `GET_EPOCH_HASHES(start, end)`.
  2. Peer replies with every block hash per epoch (main block + orphans, up to 16 each).
  3. Request missing bodies via `GET_BLOCKS(List<Hash>)`.
  4. **Validate** strictly – every parent reference must exist and signatures must verify.

### 2.3 Binary search sync (BUG-SYNC-004)

When the gap between local and remote tip exceeds 1024 epochs, binary search is used to find the minimum valid epoch where peer has blocks:

1. **Initiation**: `gap = remoteTipEpoch - localTipEpoch > BINARY_SEARCH_THRESHOLD (1024)`
2. **Probe**: Send `GET_EPOCH_HASHES` for a single epoch at the midpoint
3. **Narrow range**:
   - If blocks found at epoch N → search lower half (peer has older blocks)
   - If no blocks → search upper half (peer's history starts later)
4. **Termination**: When range <= 256 epochs OR iterations >= 20
5. **Transition**: Switch to forward sync from `minValidEpochFound`

This prevents O(n) dependency fetching for 2+ years of history on long-running nodes.

### 2.4 Real-time broadcast
* **Scenario**: node is online and either mined a block or learned a new block from a peer.
* **Flow**:
  1. Mining succeeds → `NewBlockListener` fires → send `NEW_BLOCK_HASH` (Inv message).
  2. Receiver inspects local storage; if missing, it issues `GET_BLOCKS`.
* **Sync filter (BUG-SYNC-004)**: During initial sync, `NEW_BLOCK_HASH` messages are ignored to prevent height inconsistency. Blocks are fetched through ordered epoch sync instead.

### 2.5 Status exchange (BUG-SYNC-001)
* **On connection**: Send `GET_STATUS` to learn peer's chain tip.
* **Response**: Peer replies with `STATUS_REPLY(tipEpoch, height, difficulty)`.
* **Purpose**: Enables accurate sync completion detection and mining gating.

### 2.6 Mining sync gating (BUG-SYNC-001)
* **Problem**: New nodes mining before sync creates orphan blocks (missing parent references).
* **Solution**: Block mining operations until `isSynchronized()` returns true.
* **Condition**: `localTipEpoch >= remoteTipEpoch - SYNC_TOLERANCE (2)` AND `localHeight >= remoteHeight - 1`
* **Affected components**:
  - `MiningApiService.createCandidateBlock()` - returns null if not synchronized
  - `EpochConsensusManager` backup miner - skips if not synchronized

---

## 3. Message specification

### A. Status layer (BUG-SYNC-001)
| Message | Code | Payload | Purpose |
| :--- | :--- | :--- | :--- |
| `GET_STATUS` | 0x35 | (empty) | Request peer's chain status |
| `STATUS_REPLY` | 0x36 | `tipEpoch`, `height`, `difficulty` | Return chain status |

### B. Real-time layer
| Message | Code | Payload | Purpose |
| :--- | :--- | :--- | :--- |
| `NEW_BLOCK_HASH` | 0x30 | `hash`, `epoch`, `ttl` | Broadcast that a block exists (Inv) |
| `GET_BLOCKS` | 0x31 | `List<hash>` | Request block bodies |
| `BLOCKS_REPLY` | 0x32 | `List<Block>` | Return block bodies |

### C. Sync layer
| Message | Code | Payload | Purpose |
| :--- | :--- | :--- | :--- |
| `GET_EPOCH_HASHES` | 0x33 | `startEpoch`, `endEpoch` | Request all hashes in an epoch range |
| `EPOCH_HASHES_REPLY` | 0x34 | `Map<Epoch, List<Hash>>` | Return block hashes grouped by epoch |

### D. Transaction layer
| Message | Code | Payload | Purpose |
| :--- | :--- | :--- | :--- |
| `NEW_TRANSACTION` | 0x20 | `Transaction`, `ttl` | Broadcast new transaction |
| `GET_TRANSACTIONS` | 0x40 | `List<hash>` | Request missing transactions |
| `TRANSACTIONS_REPLY` | 0x41 | `List<Tx>` | Return transaction data |

---

## 4. Implementation

### 4.1 Core components

| Component | File | Responsibility |
| :--- | :--- | :--- |
| `SyncManager` | `io.xdag.p2p.SyncManager` | Sync state machine, binary search, epoch requests |
| `XdagP2pEventHandler` | `io.xdag.p2p.XdagP2pEventHandler` | Message handling, status exchange, block import |
| `DagChain` | `io.xdag.core.DagChainImpl` | Block validation, import, epoch queries |

### 4.2 SyncManager key methods

```java
// Sync state tracking
private final AtomicReference<SyncState> syncState;  // FORWARD_SYNC, BINARY_SEARCH, BINARY_SEARCH_COMPLETE
private final AtomicLong remoteTipEpoch;             // From STATUS_REPLY
private final AtomicLong remoteTipHeight;            // From STATUS_REPLY

// Binary search state
private final AtomicLong binarySearchLow;
private final AtomicLong binarySearchHigh;
private final AtomicLong minValidEpochFound;
private final AtomicLong forwardSyncStartEpoch;      // Prevents re-triggering binary search

// Key methods
public boolean isSynchronized();                      // Check sync completion
public void performSync();                            // Main sync loop
private void performBinarySearch();                   // Binary search logic
private void performForwardSync();                    // Epoch-based sync
public void onBinarySearchResponse(boolean hasBlocks, long minEpoch);  // Handle probe results
```

### 4.3 Sync completion detection

```java
public boolean isSynchronized() {
    long remoteTip = remoteTipEpoch.get();
    long remoteHeight = remoteTipHeight.get();

    // No peer info yet - considered synchronized (first node scenario)
    if (remoteTip == 0 && remoteHeight == 0) {
        return true;
    }

    long localTip = getLocalTipEpoch();
    long localHeight = dagChain.getMainChainLength();

    // Check both epoch AND height to prevent early mining
    boolean epochSynced = localTip >= remoteTip - SYNC_TOLERANCE;
    boolean heightSynced = localHeight >= remoteHeight - 1;

    return epochSynced && heightSynced;
}
```

---

## 5. Configuration

| Parameter | Default | Description |
| :--- | :--- | :--- |
| `SYNC_INTERVAL_MS` | 5000 | Sync loop interval |
| `MAX_EPOCHS_PER_REQUEST` | 256 | Max epochs per GET_EPOCH_HASHES |
| `MAX_PIPELINE_GAP` | 4096 | Max outstanding epochs before pausing |
| `BINARY_SEARCH_THRESHOLD` | 1024 | Gap to trigger binary search |
| `MAX_BINARY_SEARCH_ITERATIONS` | 20 | Max binary search iterations |
| `SYNC_TOLERANCE` | 2 | Epochs behind remote still considered synced |

---

## 6. Testing

### 6.1 Unit tests (`SyncManagerTest`)
- Binary search triggering at threshold
- Binary search iteration and termination
- Forward sync after binary search completion
- Sync completion detection with epoch AND height
- Pipeline gap check behavior

### 6.2 Integration tests (`TwoNodeSyncIntegrationTest`)
- Two-node sync with height consistency verification
- Binary search sync for large gaps
- Real-time broadcast after sync completion

---

## 7. Bug fixes included

| Bug ID | Issue | Fix |
| :--- | :--- | :--- |
| BUG-SYNC-001 | Nodes mine before sync, creating orphans | Status exchange + mining gating |
| BUG-SYNC-004 | Memory explosion on large gaps | Binary search algorithm |
| BUG-HEIGHT-001 | Height inconsistency between nodes | Assign heights by epoch order |
