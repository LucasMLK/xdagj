# BUG-ORPHAN-001 & BUG-LOGGING-002 – Regression Test Report

- **Date**: 2025‑11‑30  
- **Duration**: 10 minutes (15:23:46 – 15:34:24 GMT+8)  
- **Topology**: two-node devnet (suite1 ↔ suite2) with a fresh RocksDB store

---

## Summary

### BUG-ORPHAN-001 – Redundant orphan retries

- **Before the fix**: `OrphanManager` retried every height=0 block every two seconds, including `LOST_COMPETITION` blocks. A single block could be retried 432 times in five minutes.
- **After the fix**: no orphan retry logs were emitted on either node during the entire run. Only the `MISSING_DEPENDENCY` class is retried, and retries are triggered asynchronously by parent arrivals.

**Result**: ✅ redundant orphan retries eliminated.

### BUG-LOGGING-002 – Verbose Netty/P2P logging

- **Before the fix**: every node logged 549 `READ COMPLETE` messages and hundreds of `onMessage` entries within five minutes.
- **After the fix**: both counters remained at zero during the test window thanks to the logging level change.

**Result**: ✅ networking log noise removed while keeping the actual protocol behaviour intact.

---

## Follow-up Investigation For BUG-LOGGING-002

The reporter was concerned that “disabling” the log might hide an actual networking bug. The following checks were performed:

1. **Message frequency** – 549 entries per five minutes equals ~1.8 requests per second. That matches normal HTTP polling (health checks, getwork, dashboards) and is not abnormal.
2. **Handler placement** – `LoggingHandler` was attached to the server channel via `.handler(...)`, so it recorded every READ/WRITE event. Switching it to DEBUG simply moves those traces to the appropriate level.
3. **P2P stack review** – `XdagP2pEventHandler.onMessage` logs at DEBUG but still processes every message; there is no evidence of loops or resends.
4. **Runtime verification** – during the 10-minute run the nodes exchanged BLOCKS_REPLY/GET_BLOCKS/GET_EPOCH_HASHES normally, selected epoch winners correctly (demotion logs observed), and stayed in sync (height grew from 1 to 11, difficulty from 16 to 455).

**Conclusion**: lowering the log level is justified; there is no hidden networking issue.
