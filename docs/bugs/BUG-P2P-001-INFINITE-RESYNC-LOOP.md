# BUG-P2P-001 – Infinite P2P Resync Loop

## Summary

- **Severity**: High (huge waste of CPU/network resources)  
- **Symptom**: The same orphan block is re-imported hundreds of times. Example from a 50‑minute two-node run:

```
0x2538fcd1 (epoch 27569825) imported 741 times
0xd786b9c5 (epoch 27569826) imported 721 times
...
```

Logs explode to ~60,000 lines (>9 MB) per node.

## Root Cause

1. `DagCache` stores only 10,000 blocks with a 30‑minute TTL. Older orphan blocks are evicted.
2. `DagStoreImpl.getBlockByHash(hash, false)` checks **cache only**. When the cache misses, it returns `null` without reading RocksDB.
3. `XdagP2pEventHandler` calls `getBlockByHash(hash, false)` when processing epoch hashes. A cache miss is interpreted as “block missing”, so the block is requested again.
4. There is no duplicate-import check in `BlockImporter`, so every resend triggers full validation and logging.
5. RocksDB overwrites the same key, so disk usage remains low, masking the issue.

The above steps form a tight loop: eviction → cache miss → resync → repeated import.

## Recommended Fixes

1. **Fix `getBlockByHash`**  
   Even with `isRaw=false`, read the block from RocksDB when the cache misses and then populate the cache. The flag should only control how much data is returned, not whether the database is consulted.

2. **Add duplicate-import guard**  
   At the top of `BlockImporter.importBlock`, call `dagStore.getBlockByHash(hash, true)`. If the block already exists, return the cached metadata without repeating validation or logging.

3. **Track synced epochs**  
   Teach the epoch-sync logic to remember which epochs have already been reconciled. Skip resync requests for epochs that were processed successfully.

4. **Increase cache capacity / TTL**  
   As a defensive measure, raise the block cache to 50,000 entries with a 2‑hour TTL. This does not fix the bug by itself but reduces eviction pressure.

## Expected Outcome After Fixes

- Re-import count per block: 741 → 1 (−99.9%)
- Network traffic per epoch: thousands of redundant requests → <10 requests
- Log volume: 60,000 lines/hour → <1,000 lines/hour
- CPU usage: reduced dramatically by avoiding duplicate validation

## Test Plan

1. Run a dual-node devnet for ≥1 hour after applying the fixes.
2. Verify that each block is imported at most twice (initial import + potential legitimate resend).
3. Check log volume and network traffic to confirm the reduction.
4. Ensure chain consistency across nodes.

## Additional Notes

- The reason older blocks are hit more often is that every new epoch triggers a resync of **all** orphan blocks. The longer a block stays orphaned, the more epochs resync it again.
- The bug did not corrupt data because RocksDB overwrote duplicate keys, but it severely impacts scalability.
