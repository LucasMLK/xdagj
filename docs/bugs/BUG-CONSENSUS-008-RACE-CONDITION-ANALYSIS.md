# BUG-CONSENSUS-008 – Race Condition After the BUG-CONSENSUS-007 Fix

## Context

- **Bug ID**: BUG-CONSENSUS-008  
- **Severity**: P1 (data inconsistency)  
- **Reported**: 2025‑11‑29 09:48 GMT+8  
- **Relationship**: follow-up issue after BUG-CONSENSUS-007

## Symptom

Despite deploying the BUG-CONSENSUS-007 patch, running a two-node devnet for ~14 hours still produced divergent height assignments:

```
Height 74-75   Epoch 27567743   two blocks received opposite heights
Height 331-332 Epoch 27567999   same issue
Height 588-589 Epoch 27568255   same issue

6 discrepant blocks out of 791 (~0.76%)
```

Each offending pair belongs to the same epoch but ends up with mirrored heights on node A vs. node B.

## Why BUG-CONSENSUS-007 Was Not Enough

BUG-CONSENSUS-007 introduced:

1. `findAllOtherMainBlocksInEpoch()` to locate all existing main blocks in the epoch.
2. Bulk demotion of losers when a new winner arrives.
3. `verifyEpochSingleWinner()` to enforce single-winner invariant immediately after import.

However, the verification is only invoked inside `BlockImporter.importBlock()` **when the current block is promoted to the main chain**:

```java
if (isBestChain) {
    verifyEpochSingleWinner(blockEpoch, block);
}
```

This still misses a race condition triggered by RocksDB visibility delays.

## Race Timeline

Consider epoch *E* with two competing blocks A and B arriving almost simultaneously:

```
Node 1 order: A then B
 ----------------------
T1: import A → stored height=0
T2: determine winner → A wins
T3: save A with height=H
T4: verifyEpochSingleWinner(E, A) → only A visible → OK
T5: import B → stored height=0
T6: determine winner → B < A → B should win
T7: save B with height=H
T8: verifyEpochSingleWinner(E, B) → should detect A still main and demote

Node 2 order: B then A
 ----------------------
Symmetric steps, resulting in A winning on node 2.
```

If RocksDB still keeps A’s height update in the memtable when T6 or T8 runs, `getCandidateBlocksInEpoch()` may not see A as a main block. No demotion is performed, and both main entries survive until the epoch ends. Because `verifyEpochSingleWinner` is only called from the importer, epochs handled entirely by a single block (no runner-up) are never rechecked later, so the race slips through.

## Contributing Factors

1. `DagChainImpl.tryToConnect()` is synchronized, so only one block is imported at a time, but RocksDB writes are still asynchronous with respect to later reads.
2. Import steps:
   - Step 3 writes the pending block (height 0).
   - Step 5 writes the final block (height > 0).
   - Step 4.5 demotes previous winners.
   - Step 7 immediately queries RocksDB. If the previous demotion is still in the memtable and has not been flushed, the verification query uses an older snapshot.

3. RocksDB visibility model:
   - Writes go to WAL → memtable → (eventually) SST files.
   - Readers consult the memtable, immutable memtables, then SSTs.
   - If the verification query races with the memtable flush, stale data is returned.

## Consequences

The verification inside `importBlock` is **best effort**, but not a guarantee:

- If the demoted block is still in the memtable, `verifyEpochSingleWinner` will eventually fix it.
- If both imports complete before a flush, neither node ever observes both winners simultaneously, so no fix occurs.

This explains the ≈1% failure rate observed after multiple hours of runtime.

## Fix Direction

1. **Force visibility before verification.**  
   Flush the memtable (synchronously) right before calling `verifyEpochSingleWinner`. This guarantees that demotions and promotions are visible to the next query.

2. **Run a slow verification path at epoch boundaries.**  
   Expose `BlockImporter.verifyEpochIntegrity(long epoch)` and call it from `EpochConsensusManager` after the WAL sync inside `onEpochEnd`. Even if the fast path misses a race, the epoch-end verification will demote stale winners.

3. **Keep `tryToConnect` synchronized** to ensure import order but rely on the visibility fixes to prevent snapshot inconsistencies.

With these two measures (flush before fast verification + slow verification after each epoch), the residual race condition is eliminated.
