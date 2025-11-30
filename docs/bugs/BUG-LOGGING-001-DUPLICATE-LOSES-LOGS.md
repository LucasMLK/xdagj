# BUG-LOGGING-001 – Repeated “LOSES competition” Log Spam

## Summary

Every time a block loses an epoch competition, the same “LOSES competition” message is emitted hundreds of times from multiple threads (`peerWorker-*`, `EpochTimer`). Running the devnet for ~50 minutes produced ~60,000 log lines (>10 MB), making troubleshooting almost impossible.

## Impact

- Severe log noise (97% of all log lines)
- Extra CPU and disk I/O spent on duplicate logging
- Difficult to determine whether a real problem occurred

## Root Cause

1. **No state check before printing.**  
   `importBlock()` prints the warning every time the same block is re-imported. There is no guard for blocks that are already known as orphans.

2. **Block re-imports are expected.**  
   The P2P layer, orphan retry logic, and epoch synchronisation all attempt to re-import the same block when it arrives from different peers, so the log is triggered hundreds of times per block.

3. **Log level is too high.**  
   A normal loss in an epoch competition is not an exceptional situation; WARN is inappropriate.

## Proposed Fix

1. Add a duplicate check at the beginning of `BlockImporter.importBlock` to skip processing if the block already exists with `height > 0` or is flagged as an orphan.
2. When a block loses, only log the first occurrence at INFO level; subsequent losses for the same hash should be DEBUG (or suppressed entirely).
3. Optionally lower the log level to DEBUG for the detailed lines (“loser hash”, “winner hash”).

## Verification Plan

1. Run a dual-node devnet for at least an hour.
2. Record the total number of log lines and file size; target <5,000 lines / <500 KB.
3. Confirm that every block prints at most one informative “LOSES competition” message.
4. Ensure block import logic still demotes losers correctly.

## Current Status

- Duplicate logging is still present; only the severity was downgraded previously.
- Implementing the above state checks will reduce log volume by ~99% without hiding meaningful information.
