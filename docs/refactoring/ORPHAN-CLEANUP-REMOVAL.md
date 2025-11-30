# Orphan Cleanup Removal Decision

**Date**: 2025-11-22  
**Decision**: Remove the old “delete orphans older than 12 days” logic and keep the full DAG.

---

## 1. Background

The XDAG 1.0b protocol stores up to 16 candidate blocks per epoch (1 main + 15 orphans). An earlier
optimization attempted to delete orphans older than ~12 days to save disk space. That approach
introduced inconsistent validation because new nodes could not reconstruct historical references and
spent blocks might be missing parents.

Storage growth is now controlled (`MAX_BLOCKS_PER_EPOCH = 16` → ~15 GB/year), so pruning is no longer
necessary.

---

## 2. Problems with the old cleanup

1. **Consensus violations** – when an orphan referenced by a newer block was deleted, nodes could no
   longer verify the relationship and rejected otherwise valid histories.
2. **Complex retry logic** – OrphanManager had to guard against blocks disappearing underneath it and
   contained race conditions (see BUG-ORPHAN-001).
3. **Unnecessary RocksDB churn** – deleting orphan metadata triggered compaction and I/O spikes with
   no real storage benefit.

---

## 3. New approach

1. **Persist all orphans** – RocksDB keeps every candidate block. Disk usage remains predictable.
2. **Async retry pipeline** – OrphanManager now:
   - Listens to block-import events.
   - Maintains a retry queue with exponential back-off.
   - Distinguishes between `MISSING_DEPENDENCY` and `LOST_COMPETITION` reasons (only the former are
     retried).
3. **Parent index** – missing parents are tracked via a reverse index so once a parent arrives the
   child can be retried immediately without scanning the entire orphan set.

---

## 4. Impact

| Area | Before | After |
|------|--------|-------|
| Storage cost | ~12-day sliding window | Full DAG (still <15 GB/year) |
| Consistency  | Missing references when orphans were deleted | Deterministic validation |
| Retry logic  | Mixed reasons, brute-force scans | Event-driven, reason-specific retries |
| Node reboot  | Required resync of orphan metadata | Metadata survives restarts |

---

## 5. Action items

1. Remove `cleanupOldOrphans()` deletion logic in `DagStore`.
2. Persist orphan reason and missing-parent index (already merged).
3. Update monitoring to expose orphan counts per reason.
4. Document the new policy in `docs/ARCHITECTURE.md` and `docs/bugs/BUG-ORPHAN-001-REDUNDANT-RETRY.md`.
