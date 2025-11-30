# BUG-LOGGING-002 – Netty LoggingHandler Noise

## Problem

The HTTP API server installs a Netty `LoggingHandler` at INFO level, which logs every low-level I/O event. The result is hundreds of `READ COMPLETE` entries every five minutes, drowning out meaningful information.

Example:

```
[INFO] [io.netty.handler.logging.LoggingHandler:148] -- [id: 0xfb0960ce, L:/127.0.0.1:10002] READ COMPLETE
```

## Root Cause

`HttpApiServer.java` line 129:

```java
.handler(new LoggingHandler(LogLevel.INFO))
```

When set to INFO, the handler logs every READ/WRITE/FLUSH/ACTIVE event for every accepted connection. This is useful during low-level debugging but far too verbose for normal operation.

## Impact

- Log files grow rapidly (≈550 entries per node within 5 minutes).
- Important business logs are buried under networking noise.
- Extra CPU is spent on formatting and writing unneeded messages.

## Recommended Fix

Change the severity to DEBUG so that the handler remains available for troubleshooting but stays quiet in production:

```java
.handler(new LoggingHandler(LogLevel.DEBUG))
```

Optional alternatives:

1. Remove the `LoggingHandler` entirely (fastest logs, but no built-in visibility when debugging).
2. Add a dedicated logger configuration in `log4j2.xml` to force WARN level for `io.netty.handler.logging`, though this still incurs formatting overhead.

## Verification

1. Restart the devnet nodes after the change.
2. Run for at least 5 minutes.
3. `grep -c "READ COMPLETE" logs/xdag-info.log` should return 0.

## Status

- Code change is one line.
- No functional risk; only logging behaviour is affected.
