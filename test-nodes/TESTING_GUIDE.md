# XDAG Node Testing Guide

## ⚠️ Critical Reminders

### XDAG time system and epoch duration
XDAG does **not** use milliseconds internally. Every timestamp uses 1/1024-second precision and the
epoch number is derived from that value.

**TimeUtils definitions** (`src/main/java/io/xdag/utils/TimeUtils.java`):
- Precision: `1 / 1024` second per tick
- Epoch calculation: `epoch_number = XDAG_timestamp >> 16`
- Epoch duration: `2^16` XDAG ticks = `65536 * (1/1024)` second = exactly **64,000 ms**

**Key formulas**:
```java
// milliseconds -> XDAG full timestamp
xdagEpoch = (milliseconds * 1024) / 1000

// XDAG timestamp -> milliseconds
milliseconds = (xdagEpoch * 1000) / 1024

// Epoch number -> epoch end (milliseconds)
epochEndMs = ((epochNumber << 16) | 0xffff) * 1000 / 1024
```

**Validation** (`test_epoch_precision.py`):
- ✅ Every epoch boundary is exactly 64,000 ms apart
- ✅ Integer math introduces no drift at epoch boundaries
- ⚠️ Converting arbitrary millisecond values back and forth introduces ~1 ms rounding error

**Coding rules**:
- ✅ Always use `TimeUtils` helpers instead of hard-coding `64000`
- ✅ `EpochTimer` dynamically computes the epoch duration (lines 128-129)
- ❌ Never assume “64 seconds of wall-clock time” – it must respect the XDAG time system

### Jar deployment rules
`xdag.sh` loads the executable jar inside the node directory (lines 6 and 45):
```bash
XDAG_JARNAME="xdagj-${XDAG_VERSION}-executable.jar"
-cp .:${XDAG_JARNAME}
```

**Takeaways**:
- ✅ The jar must live directly under each node directory, e.g. `test-nodes/suite1/node/xdagj-1.0.0-executable.jar`
- ✅ Filename must match exactly: `xdagj-1.0.0-executable.jar`
- ⚠️ A jar inside `xdagj-1.0.0/` (from the distribution zip) is ignored
- ⚠️ Always copy the rebuilt jar into the node root before launching

## 📋 Recommended deployment flow

### Option 1: Copy the jar directly (simplest)
```bash
# 1. Build from project root
cd <xdagj-project-root>  # e.g., ~/dev/xdagj
mvn clean install -DskipTests -q

# 2. Enter the node directory and clean artifacts
cd test-nodes/suite1/node
kill $(cat xdag.pid) 2>/dev/null && sleep 2
rm -rf devnet/rocksdb devnet/reputation logs/*.log xdag.pid node1.log

# 3. Copy the latest jar
cp ../../../target/xdagj-1.0.0-executable.jar .

# 4. Verify timestamp (ensure it is fresh)
ls -lh xdagj-1.0.0-executable.jar

# 5. Launch the node
./start.sh
```

### Option 2: Deploy from `distribution.zip`
```bash
# Steps 1-2 are the same as Option 1

# 3a. Unzip the distribution
unzip -q ../../../target/xdagj-1.0.0-distribution.zip

# 3b. Copy the jar to the node root (critical step!)
cp xdagj-1.0.0/xdagj-1.0.0-executable.jar .

# 3c. (Optional) Delete the extracted directory
rm -rf xdagj-1.0.0

# 4-5. Same as Option 1
```

## ✅ Verification steps

### 1. Validate BUG-CONSENSUS-004 fix
After starting the node, wait five seconds and read the EpochTimer logs:
```bash
sleep 5 && grep "EpochTimer starting" logs/xdag-info.log
```
Expected log entry (abbreviated):
```
EpochTimer starting: current_epoch=X, target_epoch=Y, target_end=...ms,
  initial_delay=...ms, epoch_duration=64000ms
```
Checklist:
- ✅ New log format contains `current_epoch` and `target_epoch`
- ✅ `initial_delay < 64000ms`
- ❌ If you see the legacy fields or `initial_delay > 64000ms`, the jar was not updated

### 2. Ensure the backup miner works
```bash
grep "Backup miner found solution" logs/xdag-info.log | head -3
```
Expected output:
```
✓ Backup miner found solution for epoch X: difficulty=0x...
```

### 3. Monitor epoch boundary events
After ~70 seconds:
```bash
grep "═══════════ Epoch.*ended" logs/xdag-info.log | tail -5
```
Expect an entry every 64 seconds with strictly increasing epoch numbers.

### 4. Confirm block height growth
```bash
curl -s http://127.0.0.1:10001/api/stats | jq .height
```
Height should rise by one per epoch.

## 🐛 Troubleshooting

### Q1: Jar updated but logs still look old
**Cause**: the jar is missing or misnamed inside the node directory.

Diagnostics:
```bash
cd test-nodes/suite1/node
ls -lh xdagj-1.0.0-executable.jar   # timestamp
md5 xdagj-1.0.0-executable.jar      # hash of deployed jar
md5 ../../../target/xdagj-1.0.0-executable.jar  # hash of build output
```
Fix: recopy the jar to the node directory.

### Q2: `initial_delay > 64` seconds
**Cause**: outdated code.

Fix:
1. Check the source contains the latest logging changes: `grep "target_epoch" src/main/java/io/xdag/consensus/epoch/EpochTimer.java`
2. Rebuild: `mvn clean compile -DskipTests -q`
3. Copy the new jar into each node folder.

### Q3: Epoch numbers are skipped
**Cause**: BUG-CONSENSUS-004 was not applied and EpochTimer miscalculates the initial delay.

Symptoms:
- Logs jump from epoch X to epoch X+2 (missing X+1)
- `initial_delay` exceeds 64,000 ms

Fix: apply the BUG-CONSENSUS-004 patch and redeploy.
