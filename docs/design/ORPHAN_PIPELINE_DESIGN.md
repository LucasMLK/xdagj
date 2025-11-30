# XDAGJ 1.0 – Orphan Pipeline Design v1.0

## 1. Background

### 1.1 Consensus characteristics
- Each epoch lasts 64 seconds, with up to 16 candidates and exactly one winner.
- P2P synchronisation relies on gossip plus `GET_EPOCH_HASHES`, so blocks arrive out of order.
- During sync we must accept “missing parent” candidates, but losers in an epoch never become main blocks.

### 1.2 Pain points in the current implementation

| Issue | Impact |
|-------|--------|
| `DagChainImpl.tryToConnect` synchronously calls `OrphanManager.retryOrphanBlocks` after every import | Import latency grows linearly with the number of pending blocks because the importer scans RocksDB each time. |
| Blocks without an explicit `OrphanReason` are implicitly treated as missing parents | Logging and monitoring cannot distinguish the real reason; migration risk increases. |
| Retry strategy is “scan 100 pending blocks starting from epoch 0, repeat up to 10 passes” | Old orphans permanently hog the window; newly satisfied orphans wait multiple passes. |
| Competitive losers remain mixed into the retry pipeline | Future reorg/refactor work is tightly coupled to retry behaviour. |

## 2. Goals
1. **Decouple import from retry** – keep the hot path focused on validation and epoch decisions; move dependency handling to an asynchronous worker.
2. **Explicit classification** – every orphan carries `OrphanReason` plus missing-parent metadata.
3. **Dependency-driven retries** – maintain `parentHash → orphan list` so that a parent arrival directly triggers the relevant orphans.
4. **Operational visibility** – expose pending length, retry success/failure, reason breakdown for compare-nodes and dashboards.
5. **Protocol compatibility** – `GET_EPOCH_HASHES` and `tryToConnect` stay unchanged so the deployment can be incremental.

## 3. Architecture Overview

```
┌──────────────┐      ┌────────────────────┐      ┌────────────────────────┐
│ Block Import │──▶──▶│ Orphan Tracker     │──▶──▶│ Orphan Retry Executor  │
└──────────────┘      └────────────────────┘      └────────────────────────┘
        │                       │                             │
        │ main/demote           │ parent-ready queue          │ background workers
        ▼                       ▼                             ▼
   DagStore (blocks + orphan metadata + parent index)    DagChain.tryToConnect()
```

- **Block Import**: validates blocks, determines winners. If a parent is missing, registers `MISSING_DEPENDENCY`; if a block loses or is demoted, records the appropriate reason.
- **Orphan Tracker**: maintains the parent index and emits events when a parent arrives or a demotion occurs.
- **Retry Executor**: processes the queue at a controlled pace and reuses `DagChain.tryToConnect` for actual re-imports.

`DagChainImpl` creates the OrphanManager, passes `this::tryToConnect` as callback, and stops the manager in `DagChain.stop()` so that background threads respect the kernel lifecycle.

## 4. Data Model Changes

| Field | Description | Storage |
|-------|-------------|---------|
| `OrphanReason` | `MISSING_DEPENDENCY / LOST_COMPETITION / CHAIN_REORG / ...` | RocksDB `ORPHAN_REASON` column family |
| `missing_parents` | Compressed list (≤4) of parent hashes for dependency tracking | RocksDB (new column family or packed into metadata) |
| `next_retry_at` | Timestamp for exponential backoff | Optional; stored alongside orphan metadata |
| `parent_index` | Mapping `parent_hash → [child_hash]` | RocksDB column family + in-memory cache |

Legacy fallback (“no reason means missing parent”) remains temporarily, but new writes always set full metadata.

## 5. Key Flows

### 5.1 Import
1. Validate block, compute difficulty, determine the epoch winner.
2. **Missing parent**: write `OrphanReason=MISSING_DEPENDENCY`, record missing parents, call `orphanTracker.registerMissing(block, parents)`.
3. **Lost competition / demotion**: write the specific reason; do **not** enqueue for retry.
4. Notify the tracker via `orphanTracker.onMainBlockImported(block)` but do not block the import path.

### 5.2 Parent arrival / demotion
1. OrphanTracker looks up children waiting for the parent hash and enqueues them.
2. Retry executor batches pending orphans and calls `DagChain.tryToConnect`.
3. Success removes orphan metadata and parent index entries; failure updates `missing_parents` / `next_retry_at`.

### 5.3 Scheduling
- Use a single-threaded `ScheduledExecutorService` (or dedicated thread) to process queues every *N* seconds or when the queue reaches a threshold.
- Each batch processes e.g. 64 orphans.
- Failed retries update `next_retry_at` with exponential backoff to avoid tight loops.
- At epoch boundaries or node shutdown/startup, trigger a one-off sweep to ensure convergence.

## 6. Module Changes

| Component | Action |
|-----------|--------|
| `DagChainImpl` | Remove synchronous retry; wire events into the tracker; expose `stop()` for cleanup. |
| `BlockImporter` | Record reasons and missing parents explicitly; invoke tracker hooks when missing dependencies or demotions occur. |
| `DagStore` | Add APIs for missing-parent lists, parent indexes, retry timestamps, and new statistics helpers (e.g. `getMissingDependencyBlockCount`). |
| `OrphanManager` | Refactor into tracker + executor with thread-safe queues. |
| `EpochConsensusManager` | After epoch completion, trigger a slow-path verification so that races are still caught. |
| Monitoring | Add metrics/log entries for pending counts, retries, and reason distribution. |

## 7. Migration Strategy
1. **Phase 1** – Write full metadata on import but keep the old retry scan as fallback; introduce the new executor gradually.
2. **Phase 2 (CURRENT)** – Once parent-index logic is stable, remove the synchronous retry and deprecate the old scan APIs.
3. **Phase 3** – Drop the “missing reason” fallback and require explicit `OrphanReason`.

## 8. Clean-up Tasks

| Trigger | Action |
|---------|--------|
| After parent-index adoption | Delete the synchronous retry code, thread-local recursion guard, and scan helpers; update tests accordingly. |
| After all orphans carry metadata | Remove the implicit fallback; tighten assertions in BlockImporter/demotion paths. |
| Phase 2 completion | Remove obsolete DagStore queries and scripts. |
| Final tidy-up | Rename the legacy OrphanManager to OrphanTracker/Executor and update tooling/monitoring to the new metrics. |

## 9. Testing Plan

1. **Unit tests**  
   - `BlockImporterTest` – missing dependency, competition loss, demotion.  
   - `OrphanTrackerTest` – parent-arrival events, reason isolation, backoff.  
   - `OrphanRetryExecutorTest` – batches, success/failure handling.  
   - `DagStoreImplTest` – persistence of the new metadata.

2. **Integration tests**  
   - Multi-node scenarios where child arrives before parent.  
   - `compare-nodes` to ensure both nodes converge and pending statistics match.

3. **Regression**  
   - Run `mvn test -Dtest=DagChainConsensusTest,NetworkPartitionIntegrationTest` to ensure DAG behaviours and consensus remain intact.

## 10. Open Questions

1. **Storage overhead** – parent index can be persisted or rebuilt from WAL; start with in-memory + RocksDB mirror if needed.
2. **Parallelism** – single-threaded executor is sufficient initially; we can shard by epoch later if necessary.
3. **Protocol extensions** – once parent indexes exist, sync code could request blocks by missing parent hash rather than epoch ranges.
4. **API exposure** – add `/orphans/status` (or similar) to report pending counts and reasons via HTTP.

---

This design turns the orphan workflow from a blocking scan into an event-driven pipeline, aligning with XDAG’s epoch-based consensus while keeping the sync protocol deterministic. With the tracker/executor in place we can continue pruning legacy logic and rely on accurate orphan statistics throughout the system.
