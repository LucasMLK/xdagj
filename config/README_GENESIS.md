# Genesis Configuration Guide

XDAG XDAGJ 1.0 follows Ethereum's approach: **genesis.json is REQUIRED** to define your network.

## ⚠️ IMPORTANT: genesis.json is Required

Unlike older XDAG versions, **XDAGJ 1.0 requires an explicit genesis.json file**. This ensures:
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
  "networkId": "mainnet",                               // Network identifier
  "chainId": 1,                                         // Chain ID for replay protection
  "epoch": 23694000,                                    // Genesis block epoch (XDAG epoch number)
  "initialDifficulty": "0x1",                           // Initial mining difficulty (hex)
  "genesisCoinbase": "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi",  // Genesis coinbase address (base58check)
  "randomXSeed": "0x0000...0001",                       // RandomX initial seed (32-byte hex)

  "alloc": {                                            // Initial balance allocations
    "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi": "1000000000000000000000",  // Address -> amount in nanoxdag
    "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2": "500000000000000000000"
  }
}
```

### Field Descriptions

#### Network Identity
- **networkId**: "mainnet", "testnet", or "devnet"
- **chainId**: Numeric chain ID (mainnet=1, testnet=2, devnet=3)

#### Timing
- **epoch**: XDAG epoch number for genesis block
  - Default for mainnet: 23694000 (XDAG_ERA: 2018-01-20 00:00:00 UTC)
  - Each epoch = 64 seconds (fixed protocol constant)
  - Conversion: Unix timestamp → XDAG timestamp → epoch number

#### Consensus
- **initialDifficulty**: Starting mining difficulty (hex format)
  - Must be hex string starting with "0x"
  - Default: "0x1" (minimal difficulty for genesis)
- **genesisCoinbase**: Genesis block coinbase address
  - Format: base58check encoded XDAG address (standard format)
  - Examples: "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi"
- **randomXSeed**: RandomX initial seed (32-byte hex string)
  - Format: 0x-prefixed 64-character hex string
  - Used to initialize RandomX algorithm from genesis

#### Initial Allocations
- **alloc**: Map of address → balance
  - **Address**: base58check encoded XDAG address (standard format)
  - **Balance**: Decimal string in nanoxdag (1 XDAG = 10^9 nanoxdag)

Example:
```json
"alloc": {
  "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi": "1000000000000000000000",  // 1000 XDAG
  "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2": "500000000000000000000"     // 500 XDAG
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

The node will create a fresh genesis block at the configured epoch.

### 2. Testnet with Pre-allocated Balances

Use `genesis-testnet.json` which includes test allocations:

```json
{
  "networkId": "testnet",
  "chainId": 2,
  "epoch": 27555273,
  "alloc": {
    "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2": "1000000000000000000000",  // 1000 XDAG
    "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa": "500000000000000000000"    // 500 XDAG
  }
}
```

### 3. Devnet for Development

Use `genesis-devnet.json` with multiple test allocations:

```json
{
  "networkId": "devnet",
  "chainId": 3,
  "alloc": {
    "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi": "10000000000000000000000",  // 10000 XDAG
    "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2": "5000000000000000000000",         // 5000 XDAG
    "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa": "1000000000000000000000"          // 1000 XDAG
  }
}
```

## Security Considerations

### Genesis Block Protection

XDAGJ 1.0 includes security measures to prevent genesis block forgery:

1. **Chain State Check**: Genesis only accepted when chain is empty
2. **Epoch Validation**: Must be at configured epoch within valid range
3. **Unified Detection**: Consistent genesis block identification

### Address Format

**Important**: Use base58check format (like Bitcoin/XDAG addresses) for all addresses:
- ✅ Correct: `"4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi"`
- ✅ Correct: `"1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"`
- ❌ Discouraged: `"0x0000000000000000000000000000000000000001"` (hex format)

Hex format is still supported for backward compatibility but may be removed in future versions.

## Troubleshooting

### Genesis block creation fails

**Error**: "Genesis block has invalid timestamp"

**Solution**: Ensure epoch is within valid range (not too old, not too far in future)

---

**Error**: "Invalid address format in alloc"

**Solution**: Addresses must be base58check format (standard XDAG addresses)

---

**Error**: "Failed to import genesis block"

**Solution**: Check logs for specific error. Common causes:
- Wallet not initialized
- Storage permission issues
- Database corruption

## Examples

All example configurations are in the `config/` directory:

- **genesis-mainnet.json**: Production mainnet config
- **genesis-testnet.json**: Testnet with test allocations
- **genesis-devnet.json**: Development network with multiple test accounts

## Advanced Topics

### Custom Difficulty

For private networks, you can set custom initial difficulty:

```json
{
  "initialDifficulty": "0x100000"  // Higher difficulty requires more mining work
}
```

### Multiple Allocations

For airdrops or pre-sales:

```json
{
  "alloc": {
    "4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi": "1000000000000000000000",
    "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2": "2000000000000000000000",
    "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa": "500000000000000000000"
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

// Set epoch using TimeUtils utilities
long currentTimestamp = TimeUtils.getCurrentEpoch();
long currentEpoch = TimeUtils.getEpoch(currentTimestamp);
genesis.setEpoch(currentEpoch);

// Add allocations (use base58check addresses)
genesis.getAlloc().put("4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi", "1000000000000000000000");
genesis.getAlloc().put("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2", "500000000000000000000");

// Save to file
genesis.save(new File("./genesis.json"));
```

## Migration from Old XDAG

To migrate from old XDAG (v4.x) to XDAGJ 1.0:

### Fresh Start (Recommended)

1. Start with fresh genesis block
2. Configure initial allocations for existing holders
3. Users verify allocations match their old balances
4. Begin mining new chain

**Note**: This approach ensures clean state without carrying over any historical issues.

## Further Reading

- [XDAGJ 1.0 Architecture](../docs/ARCHITECTURE.md)
- [XDAG Address Format](../docs/ADDRESS_FORMAT.md)
- [TimeUtils API Documentation](../docs/TIME_CONVERSION.md)

---

**Version**: XDAGJ 1.0 Phase 12
**Last Updated**: 2025-11-22
**Maintained By**: XDAG Development Team
