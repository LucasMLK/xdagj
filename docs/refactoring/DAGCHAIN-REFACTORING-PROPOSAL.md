# DagChain Refactoring Proposal

**Author**: Core Engineering Team  
**Date**: 2025-11-20  
**Scope**: DagChainImpl, consensus helpers, storage integration

---

## 1. Objectives

1. **Modularize DagChainImpl** – extract validation, import, orphan handling, block building, and fork
   logic into dedicated classes.
2. **Introduce async orphan pipeline** – persist missing dependencies, retry only when parents arrive,
   and avoid re-importing blocks that lost the epoch competition.
3. **Prepare for epoch-first consensus** – isolate height assignment from import flow so epoch
   competition can evolve independently.
4. **Improve observability** – expose metrics and logging hooks per subsystem (validation time,
   orphan queue depth, retry rate).

---

## 2. Proposed architecture

```
DagChainImpl
├── BlockValidator        # Syntax, signature, PoW, dependency checks
├── BlockImporter         # Atomic persistence + account updates
├── BlockBuilder          # Candidate block creation
├── ChainReorganizer      # Fork detection and rollback/replay
├── DifficultyAdjuster    # Periodic target recalculation
└── OrphanManager         # Missing dependency tracking and retry queue
```

### Event flow
1. P2P delivers a block → DagChainImpl delegates to BlockValidator.
2. On success, BlockImporter persists the block via RocksDB write batch.
3. DagChainImpl decides whether the block joins the best chain, informs ChainReorganizer if needed,
   and emits events to OrphanManager (for children) and listeners (API, mining, P2P).

---

## 3. Orphan handling redesign

| Concern | Old behavior | Proposed behavior |
|--------|--------------|-------------------|
| Storage | In-memory map only | Persist orphans + missing parents in RocksDB |
| Retry trigger | Every import triggers brute-force retry | Event-driven: when parent arrives or periodic fallback |
| Orphan reasons | Undifferentiated | `MISSING_DEPENDENCY` vs `LOST_COMPETITION` |
| Queue discipline | Unbounded re-import loops | Exponential back-off + deduped queue |

Implementation details:
- `DagStore.saveMissingDependencyBlock()` writes the block plus missing-parent index.
- `DagStore.getBlocksWaitingForParent(hash)` returns dependents via prefix scan.
- OrphanManager owns `ScheduledExecutorService` to drain the retry queue.

---

## 4. Epoch-first consensus

Preparatory steps:
1. `tryToConnect()` focuses solely on epoch competition; height calculation removed.
2. `checkNewMain()` scans epoch winners, sorts by epoch, and assigns heights separately.
3. Fork handling compares cumulative difficulty first, then recomputes heights.

Benefits:
- Height mismatches no longer block epoch competition.
- Offline nodes can rejoin even if epochs are skipped; heights stay continuous.
- Chain reorganizer operates on epoch boundaries, simplifying rollback.

---

## 5. Atomic block processing

| Component | Responsibility |
|-----------|----------------|
| `RocksDBTransactionManager` | Wraps all write batches in try-with-resources and guarantees commit/rollback semantics. |
| `BlockImporter` | Persists the block, block info, links, and account updates in a single batch. |
| `DagCache` | Only updated after the transaction commits to avoid dirty reads. |

Future work: migrate AccountStore into the same RocksDB instance so account updates are part of the
transaction batch.

---

## 6. Testing plan

1. **Unit tests** – each module gains dedicated coverage (validators, importer, orphan manager).
2. **Integration tests** – multi-node devnet runs from a clean RocksDB snapshot.
3. **Regression tools** – `test-nodes/compare-nodes.sh` ensures consistent heights/hashes.
4. **Performance benchmarks** – measure block import throughput before/after refactor.

---

## 7. Rollout

1. Merge refactor behind a feature flag for devnet.
2. Run multi-node soak tests for 48 hours.
3. Enable by default in the next minor release if no regressions are observed.
4. Communicate API/logging changes in release notes and architecture docs.
