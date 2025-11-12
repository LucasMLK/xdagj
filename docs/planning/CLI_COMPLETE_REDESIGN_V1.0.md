# XDAG CLI Complete Redesign for v1.0

**Date**: 2025-11-11
**Purpose**: Complete CLI command redesign for xdagj 1.0
**Status**: Design Proposal

---

## 📋 Current Commands Analysis

### Existing 20 Commands

| # | Current Command | Issues | Proposed New Name | Category |
|---|----------------|---------|-------------------|----------|
| 1 | `account` | OK | Keep | Account |
| 2 | `balance` | OK | Keep | Account |
| 3 | `block` | OK | Keep | Block |
| 4 | `lastblocks` | ❌ Redundant | **DELETE** | Block |
| 5 | `mainblocks` | ⚠️ Unclear | `chain` or `blocks` | Block |
| 6 | `minedblocks` | ⚠️ Unclear | `mined` | Block |
| 7 | `state` | OK | Keep | System |
| 8 | `stats` | OK | Keep | System |
| 9 | `xfer` | ❌ Cryptic | **DELETE** | Transaction |
| 10 | `xfertonew` | ❌ Cryptic | **DELETE** | Transaction |
| 11 | `xferv2` | ❌ Confusing | **DELETE** | Transaction |
| 12 | `xfertonewv2` | ❌ Confusing | **DELETE** | Transaction |
| 13 | `pool` | OK | Keep | Mining |
| 14 | `keygen` | OK | Keep or `newkey` | Wallet |
| 15 | `net` | OK | Keep or `network` | Network |
| 16 | `ttop` | OK | Keep or `monitor` | System |
| 17 | `terminate` | OK | Keep or `stop` | System |
| 18 | `address` | OK | Keep | Account |
| 19 | `oldbalance` | ❌ Confusing | `maxbalance` | Account |
| 20 | `txQuantity` | ❌ Cryptic | `nonce` | Account |
| -- | *NEW* | -- | `transfer` | Transaction |
| -- | *NEW* | -- | `consolidate` | Transaction |
| -- | *NEW* | -- | `epoch` | Block |

---

## 🎯 Proposed Command Structure

### Option 1: Flat Structure (Simple Migration)

保持简单的命令结构，只重命名混乱的命令：

```bash
# Account & Wallet Commands
account [size]                          # List accounts with balances
balance [address]                       # Show balance
address <address> [page]                # Show address history
nonce [address]                         # Show transaction nonce
maxbalance                              # Show maximum transferable balance
keygen                                  # Generate new keypair

# Transaction Commands
transfer <amount> <to> [remark] [fee]   # Transfer XDAG (替代 xfer/xferv2)
consolidate                             # Consolidate to default address (替代 xfertonew/xfertonewv2)

# Block & Chain Commands
block <hash|address>                    # Show block details
chain [size] [offset]                   # List main chain blocks (替代 mainblocks)
mined [size]                            # List mined blocks (替代 minedblocks)
epoch [number]                          # Show epoch information

# Network & Mining Commands
network [--list|--connect=HOST:PORT]    # Network operations (替代 net)
pool                                    # Show pool information
stats                                   # Show network statistics
state                                   # Show node state

# System Commands
monitor                                 # System monitor (替代 ttop)
stop                                    # Stop node (替代 terminate)
```

**变更总结**：
- ❌ DELETE: `lastblocks`, `xfer`, `xfertonew`, `xferv2`, `xfertonewv2`, `oldbalance`, `txQuantity`
- ✅ NEW: `transfer`, `consolidate`, `nonce`, `maxbalance`
- 🔄 RENAME: `mainblocks`→`chain`, `minedblocks`→`mined`, `net`→`network`, `ttop`→`monitor`, `terminate`→`stop`
- ✔️ KEEP: `account`, `balance`, `block`, `address`, `keygen`, `pool`, `stats`, `state`, `epoch`

---

### Option 2: Hierarchical Structure (Modern Design)

采用子命令结构，更加现代化：

```bash
# Account Management
xdag account list [--limit 20]
xdag account show [address]
xdag account create
xdag account nonce [address]
xdag account available                  # 最大可转账余额

# Transaction Operations
xdag tx send <amount> <to> [--remark TEXT] [--fee MILLI_XDAG]
xdag tx history <address> [--limit 20] [--page 1]
xdag tx pending

# Block & Chain Queries
xdag block show <hash|address>
xdag block list [--limit 20] [--offset 0]
xdag block mined [--limit 20]

# Chain Information
xdag chain status                       # Chain stats
xdag chain epoch [number]               # Epoch information

# Wallet Operations
xdag wallet create                      # 生成新密钥
xdag wallet consolidate                 # 合并余额
xdag wallet balance                     # 总余额

# Network Management
xdag network status
xdag network peers
xdag network connect <host:port>

# Mining & Pool
xdag mining status                      # 替代 pool
xdag mining start
xdag mining stop

# System Operations
xdag node status                        # 替代 state
xdag node stats                         # 替代 stats
xdag node monitor                       # 替代 ttop
xdag node stop                          # 替代 terminate
```

---

## 📊 Comparison

| Aspect | Option 1: Flat | Option 2: Hierarchical |
|--------|----------------|------------------------|
| **Complexity** | Low | High |
| **Learning Curve** | Easy | Moderate |
| **Scalability** | Limited | Excellent |
| **Discoverability** | Moderate | Excellent |
| **Modernness** | Traditional | Modern |
| **Implementation** | 1-2 days | 5-7 days |
| **Breaking Change** | Medium | Large |

---

## 💡 Recommendation

### Phase 1 (v1.0): Option 1 - Flat Structure

**Reasons**:
1. ✅ **快速实施**：1-2天完成，不影响进度
2. ✅ **简单迁移**：用户容易理解
3. ✅ **清晰命名**：解决当前最大的问题（xfer系列混乱）
4. ✅ **保持兼容**：大部分命令保持不变

**Implementation Plan**:
```
1. DELETE commands:
   - lastblocks (redundant)
   - xfer, xfertonew, xferv2, xfertonewv2 (confusing)
   - oldbalance, txQuantity (unclear names)

2. RENAME commands:
   - mainblocks → chain
   - minedblocks → mined
   - net → network
   - ttop → monitor
   - terminate → stop

3. ADD commands:
   - transfer (replaces xfer/xferv2)
   - consolidate (replaces xfertonew/xfertonewv2)
   - nonce (replaces txQuantity)
   - maxbalance (replaces oldbalance)
```

### Phase 2 (v2.0): Option 2 - Hierarchical Structure

在 v1.0 稳定后，考虑升级到子命令结构。

---

## 📝 Detailed Command Specifications (Option 1)

### Transaction Commands

#### `transfer` - Transfer XDAG

**Usage:**
```bash
transfer <amount> <to> [remark] [fee]
```

**Parameters:**
- `amount` - Amount to send (XDAG)
- `to` - Recipient address (Base58)
- `remark` - Optional transaction remark
- `fee` - Optional fee in milli-XDAG (default: 100 = 0.1 XDAG)

**Examples:**
```bash
transfer 10.5 2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM
transfer 10.5 2gHjwW7k... "payment for services"
transfer 10.5 2gHjwW7k... "payment" 200
```

**Output:**
```
Transaction created successfully!
  Transaction hash: 0xtx1234...
  Block hash: 2gHjwW7k...
  From: 0x12ab...
  To: 0x78ab...
  Amount: 10.500000000 XDAG
  Fee: 0.100000000 XDAG
  Remark: payment for services
  Nonce: 43
  Status: IMPORTED_BEST
```

---

#### `consolidate` - Consolidate balances

**Usage:**
```bash
consolidate
```

**Description:**
Transfers all account balances to the default address.

**Output:**
```
Account Balance Consolidation:

Found 3 accounts with confirmed balances

  Account 0: 100.5 XDAG → 100.4 XDAG (✅ 2gHjwW7k...)
  Account 1: 50.25 XDAG → 50.15 XDAG (✅ 3kLmNo8p...)
  Account 2: 0.05 XDAG (too small, skipped)

Summary:
  Successful transfers: 2
  Total transferred: 150.55 XDAG

It will take several minutes to complete the transactions.
```

---

### Block Commands

#### `chain` - List main chain blocks

**Usage:**
```bash
chain [size] [offset]
```

**Parameters:**
- `size` - Number of blocks (default: 20, max: 100)
- `offset` - Starting offset from current height (default: 0)

**Examples:**
```bash
chain           # Last 20 blocks
chain 50        # Last 50 blocks
chain 20 100    # 20 blocks starting from height (current - 100)
```

**Output:**
```
Main Chain Blocks (Latest 20)
═══════════════════════════════════════════════════════════════════════════════════════
Height    Address                            Time                  Difficulty    Txs
═══════════════════════════════════════════════════════════════════════════════════════
00012345  2gHjwW7kNTj8VTg7yoS5fMT1APU7gG...  2025-11-11 18:30:45   1a2b3c4d5e...  125
00012344  3kLmNo8pQRs2tUvWxYz9ABCDEFGHIJ...  2025-11-11 18:29:41   1a2b3c4d5d...   98
```

---

#### `mined` - List mined blocks

**Usage:**
```bash
mined [size]
```

**Parameters:**
- `size` - Number of blocks (default: 20)

**Output:**
```
Blocks Mined by This Node (Latest 20)
═══════════════════════════════════════════════════════════════════════════════════════
Height    Address                            Time                  Difficulty    Txs
═══════════════════════════════════════════════════════════════════════════════════════
00012340  2gHjwW7kNTj8VTg7yoS5fMT1APU7gG...  2025-11-11 18:25:30   1a2b3c4d5e...   87

Total Mined: 20 blocks
Estimated Rewards: 20,480.000000000 XDAG
```

---

### Account Commands

#### `nonce` - Show transaction nonce

**Usage:**
```bash
nonce [address]
```

**Parameters:**
- `address` - Account address (Base58), optional

**Output:**
```bash
# Without address
nonce
Total Transaction Nonce: 156

# With address
nonce 2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM
Transaction Nonce: 43
```

---

#### `maxbalance` - Show maximum transferable balance

**Usage:**
```bash
maxbalance
```

**Output:**
```
6,913.460000000
```

---

### System Commands

#### `network` - Network operations

**Usage:**
```bash
network [--list|--connect=HOST:PORT]
```

**Options:**
- `--list` or `-l` - List active connections
- `--connect=HOST:PORT` or `-c HOST:PORT` - Connect to peer

**Examples:**
```bash
network --list
network --connect=192.168.1.100:7654
```

---

#### `monitor` - System monitor

**Usage:**
```bash
monitor
```

**Description:**
Opens a top-like system monitor (based on ttop).

---

#### `stop` - Stop node

**Usage:**
```bash
stop
```

**Description:**
Gracefully stops the XDAG node (requires admin password).

---

## 🔄 Migration Guide for Users

### Command Mapping Table

| Old Command | New Command | Notes |
|------------|-------------|-------|
| `lastblocks` | `chain` | Use chain command |
| `mainblocks` | `chain` | Renamed |
| `minedblocks` | `mined` | Renamed |
| `xfer` | `transfer` | New syntax |
| `xfertonew` | `consolidate` | Renamed |
| `xferv2` | `transfer` | Merged |
| `xfertonewv2` | `consolidate` | Merged |
| `oldbalance` | `maxbalance` | Clearer name |
| `txQuantity` | `nonce` | Clearer name |
| `net` | `network` | Full word |
| `ttop` | `monitor` | Clearer name |
| `terminate` | `stop` | Simpler |

---

## ✅ Implementation Checklist

### Commands.java
- [x] Rename `xferV2()` → `transfer()`
- [x] Rename `xferToNewV2()` → `consolidate()`
- [ ] Add `maxBalance()` method (rename from `balanceMaxXfer()`)
- [ ] Update `txQuantity()` method name to `nonce()`
- [ ] Rename `mainblocks()` → `chain()`
- [ ] Rename `minedBlocks()` → `mined()`

### Shell.java
- [ ] Remove: `lastblocks`, `xfer`, `xfertonew`, `xferv2`, `xfertonewv2`, `oldbalance`, `txQuantity`
- [ ] Add: `transfer`, `consolidate`, `maxbalance`, `nonce`
- [ ] Rename: `mainblocks`→`chain`, `minedblocks`→`mined`, `net`→`network`, `ttop`→`monitor`, `terminate`→`stop`

### Documentation
- [ ] Update CLI_COMMAND_CLEANUP.md
- [ ] Update cli-commands-v5.1.md
- [ ] Create MIGRATION_GUIDE.md for v0.x → v1.0 users

---

## 📈 Success Criteria

1. ✅ **Clear Command Names**: No cryptic abbreviations (xfer, ttop, etc.)
2. ✅ **No Redundancy**: Remove duplicate commands (lastblocks)
3. ✅ **Consistent Naming**: Use full words, lowercase
4. ✅ **Intuitive**: Users can guess command names
5. ✅ **Documented**: Complete help and examples

---

## 🔗 Related Documents

- [CLI Redesign v5.1](./CLI_REDESIGN_V5.1.md)
- [CLI Command Cleanup](./CLI_COMMAND_CLEANUP.md)
- [CLI Commands Reference](../guides/cli-commands-v5.1.md)

---

**Status**: Awaiting Approval
**Recommended**: Option 1 (Flat Structure) for v1.0
**Estimated Effort**: 1-2 days (Option 1), 5-7 days (Option 2)
