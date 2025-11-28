# XDAG Test Nodes

This directory contains test node configurations for multi-node testing.

## Test Suites

### Suite1
- **Node Port**: 8001 (P2P), 10001 (HTTP API)
- **Location**: `suite1/node/`
- **Configuration**: `suite1/node/node-config.conf`

### Suite2
- **Node Port**: 8002 (P2P), 10002 (HTTP API)
- **Location**: `suite2/node/`
- **Configuration**: `suite2/node/node-config.conf`

## Node Comparison Script

### Usage

```bash
# Run comparison (script is in test-nodes directory)
cd /Users/reymondtu/dev/github/xdagj/test-nodes
./compare-nodes.sh
```

### What It Does

The `compare-nodes.sh` script:

1. **Queries Both Nodes**: Fetches current block height from both Suite1 and Suite2
2. **Compares All Blocks**: For each block height (1 to max), retrieves and compares:
   - Block hash
   - Epoch number
   - Cumulative difficulty
   - Block state (Main/Orphan)
3. **Detects Differences**: Identifies any mismatches between nodes
4. **Color-Coded Output**:
   - 🟢 Green = Identical blocks
   - 🔴 Red = Different blocks or errors
   - 🟡 Yellow = Missing blocks or warnings
5. **Summary Report**: Shows statistics and final verdict

### Example Output

```
========================================
  XDAG Node Block Comparison Tool
========================================

Querying current block heights...
  Suite1 (Port 10001): 10 blocks
  Suite2 (Port 10002): 10 blocks

========================================
  Comparing Blocks (1 to 10)
========================================

✓ [Height 1] IDENTICAL
  Hash: 0x0fdf7044d55d3878d7...
  Epoch: 27555273, Difficulty: 0x...0010, State: Main

✓ [Height 2] IDENTICAL
  Hash: 0xb1a7275343ddc65550...
  Epoch: 27563249, Difficulty: 0x...0011, State: Main

...

========================================
  Comparison Summary
========================================

Total blocks compared:   10
Identical blocks:        10
Different blocks:        0
Missing blocks:          0

✓ SUCCESS: All blocks are identical!
The two nodes are perfectly synchronized.
```

### Exit Codes

- **0**: All blocks match (success)
- **1**: Blocks differ or missing (failure)

### API Endpoints Used

The script uses XDAG HTTP API v1:

- `GET /api/v1/blocks/number` - Get current block height
- `GET /api/v1/blocks/{height}` - Get block by height

### Customization

To compare different nodes, edit the script variables:

```bash
NODE1_API="http://127.0.0.1:10001/api/v1"  # Suite1
NODE2_API="http://127.0.0.1:10002/api/v1"  # Suite2
```

## Testing Workflow

### 1. Clean Previous Data

```bash
cd suite1/node && rm -rf devnet logs && cd ../../
cd suite2/node && rm -rf devnet logs && cd ../../
```

### 2. Start Nodes

```bash
# Start Suite1
cd suite1/node
./start.sh
cd ../../

# Wait a few seconds, then start Suite2
cd suite2/node
./start.sh
cd ../../
```

### 3. Wait for Synchronization

Wait ~30 seconds for:
- P2P connections to establish
- Blocks to be mined and synchronized

### 4. Run Comparison

```bash
./compare-nodes.sh
```

### 5. Monitor Logs

```bash
# Suite1 logs
tail -f suite1/node/logs/xdag-info.log

# Suite2 logs
tail -f suite2/node/logs/xdag-info.log
```

## Verification Checklist

After running the comparison script, verify:

- ✅ Both nodes have same block height
- ✅ All blocks have identical hashes
- ✅ No "Link target not found" errors in logs
- ✅ P2P connections established (check logs)
- ✅ Blocks importing successfully on both nodes

## Recent Refactoring

### OrphanManager Cleanup Removal (2025-11-25)

**Change**: Removed `OrphanManager.cleanupOldOrphans()` method

**Reason**: Storage is bounded by `MAX_BLOCKS_PER_EPOCH=16` (~134MB max), making cleanup unnecessary and risky

**Impact**:
- Orphan blocks are no longer deleted
- Prevents "Link target not found" errors
- Ensures DAG reference integrity

**Documentation**: See `docs/refactoring/ORPHAN-CLEANUP-REMOVAL.md`

## Troubleshooting

### Nodes Not Synchronizing

1. Check if both nodes are running:
   ```bash
   ps aux | grep xdagj
   ```

2. Check P2P connections in logs:
   ```bash
   grep "Peer connected" suite1/node/logs/xdag-info.log
   ```

3. Verify API is responding:
   ```bash
   curl http://127.0.0.1:10001/api/v1/blocks/number
   curl http://127.0.0.1:10002/api/v1/blocks/number
   ```

### Different Block Hashes

If comparison shows different hashes:

1. Check if one node started before the other (expected initially)
2. Wait for next epoch boundary (64 seconds)
3. Re-run comparison script
4. If still different, check logs for errors

### Script Fails to Run

```bash
# Make sure script is executable
chmod +x compare-nodes.sh

# Verify curl is installed
which curl

# Check if nodes are listening
netstat -an | grep -E "10001|10002"
```

## Additional Scripts

You can create more comparison scripts:

### Compare Specific Block Range

```bash
#!/bin/bash
# compare-range.sh - Compare blocks in specific range

START_HEIGHT=$1
END_HEIGHT=$2

for height in $(seq $START_HEIGHT $END_HEIGHT); do
  node1=$(curl -s "http://127.0.0.1:10001/api/v1/blocks/$height")
  node2=$(curl -s "http://127.0.0.1:10002/api/v1/blocks/$height")
  # ... comparison logic ...
done
```

### Watch Mode (Continuous Monitoring)

```bash
#!/bin/bash
# watch-sync.sh - Continuously monitor sync status

while true; do
  clear
  ./compare-nodes.sh
  sleep 10
done
```

## Notes

- Test nodes use DEVNET configuration (relaxed difficulty)
- Epoch duration: 64 seconds
- Block creation: Backup miner creates blocks automatically
- No real PoW required in test environment
