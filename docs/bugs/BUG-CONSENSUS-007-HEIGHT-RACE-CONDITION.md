# BUG-CONSENSUS-007 · Height Assignment Race Condition

## Summary
- **Severity**: P2 (consensus remains safe but data/API consistency breaks)
- **Reported**: 2025-11-28 19:30 GMT+8
- **Symptom**: two nodes may assign different heights to the same pair of blocks inside one epoch,
  resulting in more than one “main” block per epoch.

## Reproduction Snapshot
Epoch `27567487` with blocks A (`0xab3255e2…`, difficulty 10048) and B (`0x5c29cb2f…`, difficulty
8402):

| Node | Height 150 | Height 151 |
|------|------------|------------|
| Node1 | Block A | Block B |
| Node2 | Block B | Block A |

Both nodes store both blocks, yet each node promotes a different winner. API consumers therefore
receive inconsistent answers for `height=150`.

## Root Cause

`BlockImporter.importBlock()` persisted the incoming block (height=0) **before** running epoch
competition:

1. Save block + `BlockInfo` with `height=0`.
2. Call `determineEpochWinner()`.
3. `determineEpochWinner()` calls `getWinnerBlockInEpoch()`, which scans RocksDB and now sees the
   newly inserted block.
4. The freshly inserted block compares against itself, decides it is already the winner, and enters
   Case 2 (“winner already known”). Height assignment proceeds without demoting the previous winner,
   so both blocks remain `height > 0`.

The race is triggered by arrival order. If block B arrives first, it temporarily becomes the winner.
When block A (with a smaller hash) arrives later, the stale query sees A as the current winner
because it reads the DB state that already contains A (height 0). The demotion logic fails to demote
block B in time, so block A is assigned a new height instead of reusing height 150.

## Fix

1. **Defer persistence** – candidate blocks are no longer written to RocksDB until the epoch
   competition completes. Competition therefore compares only previously committed candidates.
2. **Deterministic iteration** – epoch winner selection now reads the cached in-memory candidates
   collected before persistence.
3. **Height assignment rewrite** – heights are assigned in a dedicated `checkNewMain()` pass that
   scans epoch winners in order, so height numbering is no longer tied to import order.
4. **Additional guard** – the demotion logic explicitly demotes *all* other main blocks in the epoch
   (not just the first match). This guarantees only one block holds `height > 0` per epoch.

## Verification

1. Multi-node tests (`test-nodes/compare-nodes.sh`) confirm identical heights/hashes after dozens of
   epochs.
2. Historical epochs imported from RocksDB snapshots now yield identical results regardless of block
   arrival order.
3. API regression tests assert that for every epoch there is exactly one `height > 0` block.

## Status

- Fix merged in `refactor/core-v5.1` (commit `e86cd849`).
- Documentation updated in `docs/ARCHITECTURE.md` to describe the epoch-first height assignment
  model.
