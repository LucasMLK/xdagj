# XDAG CLI Commands Reference (v1.0)

**Version**: v1.0
**Last Updated**: 2025-11-11
**Status**: Production Ready

---

## 📖 Overview

This document provides a comprehensive reference for all XDAG CLI commands in v1.0. The CLI has been completely redesigned with clearer command names, enhanced output formatting, detailed transaction information, and new epoch querying capabilities.

**Changes from v0.x**: Legacy commands (`xfer`, `xfertonew`, `lastblocks`, etc.) have been removed and replaced with clearer, more intuitive names. See [Migration Guide](#-migration-from-v0x-to-v10) below.

---

## 🎯 Command Categories

### Block & Chain Commands (4 commands)
- [`block`](#block---query-block-information) - Query detailed block information
- [`chain`](#chain---list-main-chain-blocks) - List main chain blocks
- [`mined`](#mined---list-mined-blocks) - List blocks mined by this node
- [`epoch`](#epoch---query-epoch-information) - Query epoch information ✨ NEW

### Account & Wallet Commands (6 commands)
- [`account`](#account---list-wallet-accounts) - List wallet accounts with balances
- [`balance`](#balance---query-account-balance) - Query account or block balance
- [`address`](#address---query-address-details) - Query address transaction history
- [`maxbalance`](#maxbalance---query-transferable-balance) - Query maximum transferable balance
- [`nonce`](#nonce---query-transaction-nonce) - Query transaction nonce
- [`keygen`](#keygen---generate-new-keypair) - Generate new keypair

### Transaction Commands (2 commands)
- [`transfer`](#transfer---transfer-xdag) - Transfer XDAG ✨ RECOMMENDED
- [`consolidate`](#consolidate---consolidate-account-balances) - Consolidate account balances to default address

### Network & Mining Commands (4 commands)
- [`network`](#network---network-operations) - Network operations (connect, list)
- [`pool`](#pool---mining-pool-information) - Mining pool information
- [`stats`](#stats---show-network-statistics) - Show network statistics
- [`state`](#state---show-node-state) - Show node state

### System Commands (2 commands)
- [`monitor`](#monitor---system-monitor) - System monitor (top-like interface)
- [`stop`](#stop---shutdown-node) - Shutdown node

**Total: 18 commands**

---

## 📚 Detailed Command Reference

### Block & Chain Commands

#### `block` - Query Block Information

**Enhanced in v5.1** with detailed Link and Transaction information.

**Usage:**
```bash
block <ADDRESS|HASH>
```

**Parameters:**
- `ADDRESS|HASH` - Block address (32 chars) or hash (hex format)

**Output Example:**
```
═══════════════════════════════════════════════════
Block Information (v5.1)
═══════════════════════════════════════════════════
Hash:            0x1234abcd5678ef90...
Timestamp:       2025-11-11 18:30:45 UTC
Epoch:           23,693,854
Height:          12,345 (Main Chain)
State:           Main

Block Details:
  Difficulty:    1a2b3c4d5e6f7890
  Coinbase:      2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM

Links (Block References):
  → 0xabcd1234... (height: 12,344, epoch: 23,693,853)
  → 0xef567890... (height: 12,343, epoch: 23,693,852)
  [Total: 2 block links, 3 transaction links]

Transactions:
  ✓ 0xtx1234... (from: 0x12ab..., to: 0x34cd..., amount: 10.5 XDAG, fee: 0.1 XDAG)
  ✓ 0xtx5678... (from: 0x56ef..., to: 0x78ab..., amount: 5.25 XDAG, fee: 0.1 XDAG)
  [Total: 3 transactions, 15.75 XDAG transferred, 0.3 XDAG fees]
```

**Features:**
- Shows block hash, timestamp, epoch, and height
- Displays difficulty and coinbase address
- Lists linked blocks with their heights and epochs
- Shows transaction details (first 10 if more than 10)
- Includes transaction summary statistics

---

#### `chain` - List Main Chain Blocks

**Enhanced in v1.0** with difficulty and transaction count.

**Usage:**
```bash
chain [SIZE]
```

**Parameters:**
- `SIZE` - Number of blocks to display (default: 20, max: 100)

**Output Example:**
```
Main Chain Blocks (Latest 20)
═══════════════════════════════════════════════════════════════════════════════════════
Height    Address                            Time                  Difficulty    Txs
═══════════════════════════════════════════════════════════════════════════════════════
00012345  2gHjwW7kNTj8VTg7yoS5fMT1APU7gG...  2025-11-11 18:30:45   1a2b3c4d5e...  125
00012344  3kLmNo8pQRs2tUvWxYz9ABCDEFGHIJ...  2025-11-11 18:29:41   1a2b3c4d5d...   98
00012343  4mNoPq9rSt3vXyZ0BCDEFGHIJKLMNO...  2025-11-11 18:28:37   1a2b3c4d5c...  103
```

**Features:**
- Shows height, address, timestamp, difficulty, and transaction count
- Professional table formatting with Unicode borders
- Difficulty shown in hex (truncated for readability)
- Transaction count from TransactionStore

---

#### `mined` - List Mined Blocks

**Enhanced in v1.0** with reward estimation.

**Usage:**
```bash
mined [SIZE]
```

**Parameters:**
- `SIZE` - Number of blocks to display (default: 20)

**Output Example:**
```
Blocks Mined by This Node (Latest 20)
═══════════════════════════════════════════════════════════════════════════════════════
Height    Address                            Time                  Difficulty    Txs
═══════════════════════════════════════════════════════════════════════════════════════
00012340  2gHjwW7kNTj8VTg7yoS5fMT1APU7gG...  2025-11-11 18:25:30   1a2b3c4d5e...   87
00012330  3kLmNo8pQRs2tUvWxYz9ABCDEFGHIJ...  2025-11-11 18:15:20   1a2b3c4d5d...   92

Total Mined: 20 blocks
Estimated Rewards: 20,480.000000000 XDAG
```

**Features:**
- Same detailed format as chain command
- Shows total mined block count
- Estimates total rewards (1024 XDAG per block)

---

#### `epoch` - Query Epoch Information

**New in v1.0** - Query epoch-specific information and consensus details.

**Usage:**
```bash
epoch [EPOCH_NUMBER]
```

**Parameters:**
- `EPOCH_NUMBER` - Specific epoch to query (optional, defaults to current epoch)

**Output Example (Current Epoch):**
```
═══════════════════════════════════════════════════
Current Epoch Information
═══════════════════════════════════════════════════
Epoch Number:    23,693,854 (Current)
Time Range:      2025-11-11 18:29:12 - 18:30:16
Duration:        64 seconds
Elapsed:         32 / 64 seconds (50.0%)
Next Epoch:      In 32 seconds

Blocks in Epoch:
  Total Candidates:  5 blocks
  Winner:            0x1234abcd... (height: 12,345)
  Orphans:           4 blocks

Consensus:
  Selection Rule:    Smallest hash wins
  Average Difficulty: 1.23e15
  Total Difficulty:   6.15e15
```

**Output Example (Historical Epoch):**
```
═══════════════════════════════════════════════════
Epoch 23,693,850 Information
═══════════════════════════════════════════════════
Epoch Number:    23,693,850
Time Range:      2025-11-11 18:24:48 - 18:25:52
Duration:        64 seconds

Blocks in Epoch:
  Total Candidates:  3 blocks
  Winner:            0xabcd1234... (height: 12,341)
  Orphans:           2 blocks

Consensus:
  Selection Rule:    Smallest hash wins
  Average Difficulty: 1.22e15
  Total Difficulty:   3.66e15
```

**Features:**
- Shows epoch time range and duration
- Displays current progress for current epoch
- Lists all candidate blocks in the epoch
- Identifies the winner block (smallest hash)
- Shows consensus statistics (difficulty metrics)
- Useful for understanding epoch competition and consensus

---

### Account & Wallet Commands

#### `account` - List Wallet Accounts

**Usage:**
```bash
account [SIZE]
```

**Parameters:**
- `SIZE` - Number of accounts to display (default: 20)

**Output Example:**
```
2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM 1234.560000000 XDAG  [Nonce: 42]
3kLmNo8pQRs2tUvWxYz9ABCDEFGHIJKLMNOPQRSTUVWX 5678.900000000 XDAG  [Nonce: 15]
```

**Features:**
- Shows Base58 address, balance, and transaction nonce
- Sorted by balance (descending)
- Uses AccountStore for v5.1 compatibility

---

#### `balance` - Query Account Balance

**Usage:**
```bash
balance [ADDRESS]
```

**Parameters:**
- `ADDRESS` - Account address (Base58) or block address (optional)
- If no address provided, shows total wallet balance

**Output Examples:**
```bash
# Total wallet balance
balance
Balance: 6,913.460000000 XDAG

# Specific account balance
balance 2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM
Account balance: 1,234.560000000 XDAG

# Block balance (calculated from transactions)
balance 0x1234abcd5678ef90...
Block balance: 156.750000000 XDAG (from 3 transactions)
```

**Features:**
- Supports both account addresses (Base58) and block addresses (hex)
- Account balances from AccountStore
- Block balances calculated from TransactionStore
- Shows transaction count for block balances

---

### Transaction Commands

#### `transfer` - Transfer XDAG

**v1.0 Command** - Replaces legacy `xfer`/`xferv2` commands with clearer naming.

**Usage:**
```bash
transfer <AMOUNT> <ADDRESS> [REMARK] [FEE_MILLI_XDAG]
```

**Parameters:**
- `AMOUNT` - Amount to send in XDAG
- `ADDRESS` - Recipient address (Base58 format)
- `REMARK` - Optional transaction remark
- `FEE_MILLI_XDAG` - Optional fee in milli-XDAG (default: 100 = 0.1 XDAG)

**Examples:**
```bash
# Default fee (0.1 XDAG)
transfer 10.5 2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM

# With remark
transfer 10.5 2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM "payment for services"

# Custom fee (0.2 XDAG)
transfer 10.5 2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM "payment" 200
```

**Output Example:**
```
Transaction created successfully!
  Transaction hash: 0xtx1234abcd5678...
  Block hash: 2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM
  From: 0x12ab34cd56ef...
  To: 0x78ab90cd12ef...
  Amount: 10.500000000 XDAG
  Fee: 0.100000000 XDAG
  Remark: payment for services
  Nonce: 43
  Status: IMPORTED_BEST
```

**Features:**
- v5.1 native Transaction + Block architecture
- Configurable transaction fee
- Supports transaction remarks
- Account-level nonce tracking
- Detailed transaction receipt

---

#### `consolidate` - Consolidate Account Balances

**v1.0 Command** - Replaces legacy `xfertonew`/`xfertonewv2` commands with clearer naming.

**Usage:**
```bash
consolidate
```

**Description:**
Transfers all account balances to the default address. This is useful for combining funds from multiple accounts into one.

**Output Example:**
```
Account Balance Consolidation:

Found 3 accounts with confirmed balances

  Account 0: 100.500000000 XDAG → 100.400000000 XDAG (✅ 2gHjwW7k...)
  Account 1: 50.250000000 XDAG → 50.150000000 XDAG (✅ 3kLmNo8p...)
  Account 2: 0.050000000 XDAG (too small, skipped)

Summary:
  Successful transfers: 2
  Total transferred: 150.550000000 XDAG

It will take several minutes to complete the transactions.
```

**Features:**
- Consolidates all account balances to default address
- Automatic fee deduction per account
- Skips accounts with insufficient balance
- Shows detailed transfer results
- v5.1 Transaction + Block architecture

---

### Network & System Commands

#### `stats` - Show Network Statistics

**Enhanced in v5.1** with professional formatting.

**Usage:**
```bash
stats
```

**Output Example:**
```
═══════════════════════════════════════════════════
XDAG Network Statistics (v5.1)
═══════════════════════════════════════════════════

Chain Status:
  Main Chain Length:      12,345 blocks
  Total Blocks:           15,678
  Current Epoch:          23,693,854
  Top Block Hash:         0x1234abcd5678ef90...

Network:
  Connected Peers:        0 of 42
  Orphan Blocks:          3,333
  Waiting Sync:           12
  Sync Progress:          99.9%

Consensus:
  Current Difficulty:     0x1a2b3c4d5e6f7890...
  Max Difficulty:         0x1a2b3c4d5e6f7890...
  Finality Window:        16,384 epochs (≈12 days)

Wallet:
  XDAG in Wallets:        6,913.460000000 XDAG
  Number of Wallets:      3
```

**Features:**
- Professional table formatting
- Comprehensive chain statistics
- Network status and sync progress
- Consensus metrics (difficulty, finality)
- Wallet balance summary

---

## 🔧 Command Tips & Best Practices

### 1. Transaction Commands
- **Always use `xferv2`** for new transactions instead of legacy `xfer`
- Set appropriate fees based on network congestion
- Keep transaction remarks short (stored on-chain)
- Verify recipient address before sending

### 2. Query Commands
- Use `epoch` to understand consensus and competition
- Use `block` to inspect transaction details
- Use `stats` to monitor sync progress

### 3. Performance
- Limit list sizes for large chains (max 100 recommended)
- Use specific epoch queries for historical analysis

---

## 📋 Command Quick Reference

| Command | Purpose | v5.1 Status |
|---------|---------|-------------|
| `block` | Block details | ✅ Enhanced |
| `mainblocks` | Main blocks list | ✅ Enhanced |
| `minedblocks` | Mined blocks list | ✅ Enhanced |
| `epoch` | Epoch information | ✨ NEW |
| `account` | Wallet accounts | ✅ v5.1 Compatible |
| `balance` | Account balance | ✅ v5.1 Compatible |
| `address` | Address history | ✅ v5.1 Compatible |
| `xferv2` | Transfer (native) | ✨ NEW |
| `xfertonewv2` | Transfer to new (native) | ✨ NEW |
| `stats` | Network stats | ✅ Enhanced |
| `state` | Node state | ✅ v5.1 Compatible |
| `keygen` | Generate keypair | ✅ v5.1 Compatible |
| `net` | Network ops | ✅ v5.1 Compatible |
| `pool` | Pool info | ⏸️ Temporarily Disabled |
| `ttop` | System monitor | ✅ v5.1 Compatible |
| `terminate` | Shutdown | ✅ v5.1 Compatible |

---

## 🆘 Troubleshooting

### "Block not found"
- Verify the block hash or address is correct
- Ensure the node is fully synced

### "Balance not enough"
- Check account balance with `balance` command
- Consider reducing amount or fee

### "Invalid address format"
- Use Base58 format for account addresses
- Use hex format for block hashes

### "Transaction validation failed"
- Ensure sufficient balance including fee
- Check nonce sequence (use `txQuantity`)

---

## 📚 Related Documentation

- [v5.1 Architecture](../architecture/ARCHITECTURE_V5.1.md)
- [v5.1 Design Documentation](../design/)
- [CLI Redesign Plan](../planning/CLI_REDESIGN_V5.1.md)

---

**For support, please visit**: https://github.com/XDagger/xdagj/issues
