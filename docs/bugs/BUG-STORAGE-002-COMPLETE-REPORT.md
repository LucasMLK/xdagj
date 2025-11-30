# BUG-STORAGE-002 · Data Loss After Restart (Complete Report)

**Investigation window**: 2025-11-28 10:00–10:40 GMT+8  
**Severity**: P1 (storage integrity)

---

## Symptoms

After a node restart:
- `mainBlockCount` persisted correctly.
- Genesis block was readable.
- All other blocks returned `null`.
- Only `ChainStats` survived; block data evaporated.

---

## Root cause analysis

1. **Write options mismatch** – `saveBlock()` used async writes (`setSync(false)`) while
   `saveChainStats()` forced synchronous writes. Chain stats hit disk immediately, but block data sat
   in WAL/MemTable.
2. **Aggressive stop script** – `stop.sh` killed the process after two seconds (SIGTERM + immediate
   `kill -9`), not giving RocksDB time to flush.
3. **Incomplete fix attempt** – calling `db.syncWal()` alone only persists the WAL. Blocks still
   lived in the MemTable, so replaying the WAL produced entries with `height=0` and no usable data.

---

## Final fix

1. **Flush + sync**
   ```java
   db.flush(new FlushOptions().setWaitForFlush(true));
   db.syncWal();
   ```
   Both steps run before shutdown or at epoch boundaries.

2. **Epoch-level sync**
   - `EpochConsensusManager` receives a `DagStore` reference.
   - `onEpochEnd()` calls `dagStore.syncWal()` (flush + sync) to persist the last batch of blocks.

3. **Shutdown hook**
   - `DagKernel` registers a JVM shutdown hook that flushes and syncs the store before exit.
   - `DagKernel.stop()` repeats the same logic to guard against manual shutdown.

4. **Improved stop scripts**
   - `test-nodes/suite*/node/stop.sh` now waits up to 10 seconds after SIGTERM before issuing
     `kill -9`, ensuring RocksDB has time to flush.

---

## Verification

1. Restarted nodes after heavy block imports; all blocks remained accessible.
2. WAL flush logs show both flush time and total sync time, confirming the new code path executes.
3. Shutdown hook tested via `Ctrl+C` and `kill`; hooks log successful WAL sync.
4. Multi-node smoke tests confirm no further block loss on restart.

---

## Status

Bug fixed in the storage layer implementation and validated in devnet. All nodes should run with the
updated stop scripts and DagStore sync logic.
