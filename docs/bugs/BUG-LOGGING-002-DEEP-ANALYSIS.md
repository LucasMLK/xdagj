# BUG-LOGGING-002 – Excessive Netty Logging

## Problem Statement

Users observed a flood of Netty log entries similar to:

```
[INFO] [io.netty.handler.logging.LoggingHandler:148] -- [id: 0xfb0960ce, L:/127.0.0.1:10002] READ COMPLETE
```

## Root Cause Analysis

1. `HttpApiServer` configures a `LoggingHandler(LogLevel.INFO)` on the **server channel** via `.handler(...)`. That handler logs every inbound event (READ/WRITE) for each accepted connection, not just lifecycle events.

2. The observed rate (~550 messages per 5 minutes per node, i.e. ~1.8/s) matches normal HTTP polling traffic rather than an actual error.

3. The handler is useful during debugging, but INFO severity is inappropriate for production workloads; it produces noise even when everything is healthy.

## Verification Steps

To confirm that the traffic is indeed legitimate:

1. Temporarily raise the Netty logger to DEBUG and capture the request paths.
2. Optionally perform a packet capture or add counters in `HttpApiHandler` to monitor actual request frequency.

## Resolution

Set the logging handler to DEBUG (or remove it entirely) in `HttpApiServer`:

```java
.handler(new LoggingHandler(LogLevel.DEBUG))
```

This keeps the code path available for troubleshooting but prevents normal requests from polluting the logs.

## Additional Recommendations

1. Add lightweight HTTP request metrics so we can correlate Netty reads with actual API calls.
2. Remove the server-channel `LoggingHandler` completely in production builds and only enable it when needed via configuration.

## Rollback Considerations

No rollback is required. Lowering the log level eliminates the noise without touching the request-handling logic, and there is no evidence that the log spam is masking a functional defect.
