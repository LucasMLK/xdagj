# BUG-CONSENSUS-006 · Epoch Timer Drift

## Summary
- **Severity**: P1 (consensus timing)
- **Reported**: 2025-11-21
- **Symptom**: EpochTimer fired using wall-clock milliseconds divided by 64,000, causing cumulative
  drift and occasional skipped epochs.

## Details

XDAG uses a custom time system measured in 1/1024-second ticks. The epoch number must be computed as:

```
xdagTimestamp  = (unixMillis * 1024) / 1000
epochNumber    = xdagTimestamp >> 16
```

The old implementation incorrectly used `System.currentTimeMillis() / 64000`. This:
- Lost precision because 1/1024-second ticks were ignored.
- Generated epoch numbers that did not match the on-chain format.
- Caused `initialDelay` to exceed 64 seconds during startup, leading to missed epoch boundaries.

## Fix

1. `EpochTimer#getCurrentEpoch()` now delegates to `TimeUtils.getCurrentEpochNumber()` so the XDAG
   time base is respected.
2. `EpochTimer#calculateInitialDelay()` uses XDAG math to compute the exact boundary instead of
   relying on wall-clock mod operations.
3. Added regression test (`Phase4 Integration Test Suite 1.1`) ensuring 10 consecutive epochs fire at
   64,000 ± 2 ms.

## Validation

- Manual test logs show average interval 64.000 s, min 63.999 s, max 64.002 s.
- No skipped epochs after >500 cycles on devnet.
- BUG reference closed once `test-nodes/TESTING_GUIDE.md` instructions were updated.
