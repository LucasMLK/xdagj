# Genesis Configuration Guide

XDAG v5.1 follows Ethereum's approach: **genesis.json is REQUIRED** to define your network.

## ⚠️ IMPORTANT: genesis.json is Required

Unlike older XDAG versions, **v5.1 requires an explicit genesis.json file**. This ensures:
- Clear network identity (mainnet vs testnet vs devnet)
- Reproducible genesis blocks across all nodes
- Prevention of accidental network mismatches

**First startup without genesis.json will fail** with instructions on how to create one.

## Quick Start

### 1. Create genesis.json for your network

```bash
# For mainnet
cp config/genesis-mainnet.json ./genesis.json

# For testnet
cp config/genesis-testnet.json ./genesis.json

# For devnet (with test allocations)
cp config/genesis-devnet.json ./genesis.json
```

### 2. Start your node

```bash
./xdag.sh
```

**First startup**:
- Loads genesis.json
- Creates genesis block with configured parameters
- Applies initial allocations (if any)

**Subsequent startups**:
- Loads genesis.json
- **Verifies existing genesis block matches configuration**
- Fails if genesis.json doesn't match blockchain data

## Genesis Configuration Format

```json
{
  "networkId": "mainnet",          // Network identifier
  "chainId": 1,                    // Chain ID for replay protection
  "epoch": 23694000,               // Genesis block epoch (XDAG epoch number)
  "initialDifficulty": "0x1",      // Initial mining difficulty (hex)
  "epochLength": 64,               // Epoch length in seconds
  "extraData": "XDAG v5.1 Genesis",// Extra data (up to 32 bytes)

  "alloc": {                       // Initial balance allocations
    "0x0000...0001": "1000000000000000000000",  // Address -> amount in nanoxdag
    "0x0000...0002": "500000000000000000000"
  },

  "snapshot": {                    // Snapshot import configuration
    "enabled": false,              // Enable snapshot import
    "height": 0,                   // Snapshot block height
    "hash": "0x00...",             // Snapshot last block hash
    "timestamp": 0,                // Snapshot timestamp
    "dataFile": "",                // Path to snapshot data file
    "verify": true,                // Verify hash after import
    "format": "v1",                // Snapshot format version
    "expectedAccounts": 0,         // Expected account count (for progress)
    "expectedBlocks": 0            // Expected block count (for progress)
  }
}
```

## Use Cases

### 1. Fresh Chain (Default)

Use `genesis-mainnet.json`, `genesis-testnet.json`, or `genesis-devnet.json`:

```bash
# Copy appropriate template
cp config/genesis-mainnet.json ./genesis.json

# Start node
./xdag.sh
```

The node will create a fresh genesis block at XDAG era time.

### 2. Testnet with Pre-allocated Balances

Use `genesis-testnet.json` which includes test allocations:

```json
{
  "networkId": "testnet",
  "chainId": 2,
  "alloc": {
    "0x0000...0001": "1000000000000000000000",  // 1000 XDAG
    "0x0000...0002": "500000000000000000000"    // 500 XDAG
  }
}
```

### 3. Import from Old XDAG Chain (Snapshot)

Use `genesis-snapshot-example.json` as template:

```json
{
  "networkId": "mainnet",
  "chainId": 1,
  "snapshot": {
    "enabled": true,
    "height": 1234567,
    "hash": "0xabcd...",
    "timestamp": 1700000000,
    "dataFile": "./snapshot/mainnet-1234567.dat",
    "expectedAccounts": 100000,
    "expectedBlocks": 1234567
  }
}
```

**Steps to import:**

1. Export snapshot from old XDAG node:
   ```bash
   # On old node
   xdag-cli export-snapshot --height 1234567 --output mainnet-1234567.dat
   ```

2. Copy snapshot file to new node:
   ```bash
   mkdir -p ./snapshot
   cp mainnet-1234567.dat ./snapshot/
   ```

3. Configure genesis.json with snapshot settings

4. Start new v5.1 node:
   ```bash
   ./xdag.sh
   ```

The node will import all blocks and accounts from the snapshot.

## Configuration Details

### Network Identity

- **networkId**: "mainnet", "testnet", or "devnet"
- **chainId**: Numeric chain ID (mainnet=1, testnet=2, devnet=3)

### Timing

- **epoch**: XDAG epoch number for genesis block (calculated as `XdagTime.getEpoch(timestamp)`)
  - Default for mainnet: 23694000 (XDAG_ERA: 2018-01-20 00:00:00 UTC)
  - Each epoch = 64 seconds
  - Example: Unix timestamp 1516406400 → XDAG epoch 23694000
- **epochLength**: Epoch duration in seconds (default: 64)

### Consensus

- **initialDifficulty**: Starting mining difficulty (hex format)
  - Must be hex string starting with "0x"
  - Default: "0x1" (minimal difficulty for genesis)

### Initial Allocations

- **alloc**: Map of address -> balance
  - Address: 32-byte hex string with "0x" prefix
  - Balance: Decimal string in nanoxdag (1 XDAG = 10^9 nanoxdag)

Example:
```json
"alloc": {
  "0x0000000000000000000000000000000000000000000000000000000000000001": "1000000000000000000000"
}
```

This allocates 1000 XDAG to address `0x00...01`.

### Snapshot Import

**enabled**: Set to `true` to import from snapshot

**height**: Block height where snapshot was taken

**hash**: Last block hash in snapshot (for verification)

**timestamp**: Timestamp of last block in snapshot

**dataFile**: Path to snapshot data file
- Can be absolute or relative to data directory
- File must exist and be readable

**verify**: Verify hash matches after import (recommended: true)

**format**: Snapshot format version
- "v1": Old XDAG binary format
- "v2": v5.1 optimized format (future)

**expectedAccounts/expectedBlocks**: For progress reporting (optional)

## Security Considerations

### Genesis Block Protection

v5.1 includes security measures to prevent genesis block forgery:

1. **Chain State Check**: Genesis only accepted when chain is empty
2. **Timestamp Validation**: Must be at configured timestamp ±64 seconds
3. **Unified Detection**: Consistent genesis block identification

### Snapshot Import Security

When importing snapshots:

1. **Hash Verification**: Always enable `"verify": true`
2. **Source Trust**: Only import snapshots from trusted sources
3. **File Integrity**: Verify snapshot file checksum before import

## Troubleshooting

### Genesis block creation fails

**Error**: "Genesis block has invalid timestamp"

**Solution**: Ensure timestamp is within ±64 seconds of configured value

---

**Error**: "Invalid address format in alloc"

**Solution**: Addresses must be 32-byte hex with "0x" prefix

---

**Error**: "Failed to import genesis block"

**Solution**: Check logs for specific error. Common causes:
- Wallet not initialized
- Storage permission issues
- Database corruption

### Snapshot import fails

**Error**: "Snapshot data file not found"

**Solution**: Verify file path in genesis.json and file exists

---

**Error**: "Snapshot hash mismatch"

**Solution**: Snapshot file may be corrupted. Re-download from source.

---

**Error**: "Snapshot import not yet implemented"

**Solution**: Snapshot import is planned for Phase 12.5. Currently only fresh genesis blocks are supported.

## Examples

All example configurations are in the `config/` directory:

- **genesis-mainnet.json**: Production mainnet config
- **genesis-testnet.json**: Testnet with test allocations
- **genesis-devnet.json**: Development network
- **genesis-snapshot-example.json**: Snapshot import template

## Migration from Old XDAG

To migrate from old XDAG (v4.x) to v5.1:

### Option 1: Fresh Start (Recommended for Testnets)

1. Start with fresh genesis block
2. Mine new chain from beginning
3. Users migrate funds manually

### Option 2: Snapshot Import (For Mainnets)

1. Coordinate snapshot height with community
2. All nodes export snapshot at agreed height
3. Create genesis.json with snapshot config
4. All nodes import snapshot
5. Resume mining from snapshot point

**Note**: Option 2 requires all nodes to use same snapshot for consensus.

## Advanced Topics

### Custom Difficulty

For private networks, you can set custom initial difficulty:

```json
{
  "initialDifficulty": "0x100000",  // Higher difficulty for faster blocks
}
```

### Multiple Allocations

For airdrops or pre-sales:

```json
{
  "alloc": {
    "0x00...01": "1000000000000000000000",
    "0x00...02": "2000000000000000000000",
    "0x00...03": "500000000000000000000",
    // ... up to thousands of addresses
  }
}
```

### Programmatic Generation

Generate genesis.json programmatically:

```java
GenesisConfig genesis = new GenesisConfig();
genesis.setNetworkId("testnet");
genesis.setChainId(2);
// Set epoch using XdagTime utilities
long currentTimestamp = XdagTime.getCurrentTimestamp();
long currentEpoch = XdagTime.getEpoch(currentTimestamp);
genesis.setEpoch(currentEpoch);

// Add allocations
genesis.getAlloc().put("0x00...01", "1000000000000000000000");
genesis.getAlloc().put("0x00...02", "500000000000000000000");

// Save to file
genesis.save(new File("./genesis.json"));
```

## Further Reading

- [XDAG v5.1 Architecture](../docs/ARCHITECTURE_V5.1.md)
- [Phase 12 Implementation](../docs/refactor-history/phases/PHASE_12_GENESIS_CONFIG.md)
- [Genesis Security](../docs/refactor-history/dagchain/DAGCHAIN_PHASE11_COMPLETE.md#security-enhancements)

---

**Version**: v5.1 Phase 12
**Last Updated**: 2025-11-07
**Maintained By**: XDAG Development Team
