# BUG-ORPHAN-001 · Redundant Orphan Retries

## Summary
- **Severity**: P2 (performance degradation; consensus intact)
- **Discovered**: 2025-11-22
- **Symptom**: OrphanManager re-imports the same orphan hundreds of times even after it lost the
  epoch competition and can never become a main block.

## Background

Two very different orphan types exist:
1. **Missing dependency** – child arrives before parent; must be retried once the parent is imported.
2. **Lost competition** – valid block that lost the epoch; parents already exist and re-importing will
   never promote it.

The legacy OrphanManager stored both types in one queue and retried all of them every time a new
block was imported. As a result, blocks that already lost the competition kept getting re-validated
hundreds of times (432+ retries observed for a single block in epoch 27,569,886).

## Root Cause

`OrphanManager.retryOrphanBlocks()` scanned the entire orphan table on each import and did not track
why the block became an orphan. Even after the block was successfully processed (but demoted because
it lost the epoch), it stayed in the retry queue forever.

## Fix

1. **Reason flag** – `OrphanReason` enum introduced (`MISSING_DEPENDENCY`, `LOST_COMPETITION`).
   Orphans that lose the epoch are marked as `LOST_COMPETITION` and excluded from retries.
2. **Async retry service** – persistence now keeps a missing-parent index. When a parent arrives the
   dependent hashes are enqueued immediately and processed by a scheduled executor with back-off.
3. **Retry throttling** – DagChain triggers orphan retries at most once per minute and only after a
   best-chain import succeeds, avoiding storms during heavy sync.
4. **Cleanup** – when validation ultimately fails for reasons other than missing dependencies,
   OrphanManager removes the orphan entry.

## Validation

- Instrumentation shows retry counts drop from ~400 per orphan to fewer than 5.
- Unit tests cover both reasons and ensure only missing-dependency orphans re-enter the queue.
- Two-node devnet runs confirm no main-chain divergence after the change.

## Status

Merged as part of the orphan pipeline refactor (`fix: Orphan pipeline - retain missing deps
(BUG-CONSENSUS-008)`).
