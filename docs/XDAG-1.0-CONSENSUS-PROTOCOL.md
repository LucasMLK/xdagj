# XDAG 1.0 Consensus Protocol

**Version**: 1.0
**Status**: Draft
**Last Updated**: 2025-11-21

## Abstract

XDAG 1.0 is an epoch-based Directed Acyclic Graph (DAG) blockchain that combines Proof-of-Work (PoW) consensus with a unique block selection mechanism. Unlike traditional blockchains where blocks form a linear chain, XDAG allows multiple blocks to compete within the same time window (epoch), with the winner determined by hash value. This design enables high transaction throughput while maintaining decentralization and security.

---

## 1. Core Concepts

### 1.1 Time System and Epoch

XDAG uses a unique time representation system:

#### Time Units

**Base Time Unit**: XDAG uses **1/1024 second** as its base time precision (not the standard 1/1000 second millisecond)

**XDAG Epoch (Full Timestamp)**:
- Time value with 1/1024 second precision
- Example: 1552800481279
- Conversion formula: `xdag_epoch = (milliseconds * 1024) / 1000`
- This is the complete timestamp used internally by XDAG

**Epoch Number (Time Period ID)**:
- Represents a 64-second period number
- Example: 23693854
- Calculation formula: `epoch_number = xdag_epoch >> 16` (right shift 16 bits)
- This is the epoch number used for block storage and competition

#### Epoch Characteristics

**Duration of One Epoch Number**:
```
Duration = 2^16 * (1/1024 second) = 65536 / 1024 = 64 seconds
```

**Key Calculations**:
- Get current epoch number from milliseconds: `(ms * 1024 / 1000) >> 16`
- Get start time from epoch number: `epoch_number << 16`
- Get end time from epoch number: `(epoch_number << 16) | 0xffff`

**Purpose**:
- Groups blocks into 64-second competition rounds
- All candidate blocks within an epoch number compete
- Genesis epoch number defined by network configuration (mainnet/testnet/devnet)

**Example**:
```
Millisecond timestamp:  1552800481279
↓ (ms * 1024 / 1000)
XDAG Epoch:             1590068716382
↓ (>> 16)
Epoch Number:           24261
```

### 1.2 Block Production and Types

#### Block Production Timing

**Key Rule**: XDAG block production occurs at the **end of each epoch**

- **Production Moment**: When the epoch time slice reaches its end moment (`epoch | 0xffff`), all nodes connected to the network can start producing blocks
- **Epoch Uniformity**: Within the same epoch period, all blocks produced by all nodes have the **exact same epoch number**
- **Concurrent Competition**: Multiple nodes can simultaneously produce blocks at the same epoch end moment, and these blocks enter epoch competition

**Example**:
```
Epoch 23693854 end moment: XdagTime.epochNumberToMainTime(23693854)
- Node A produces block: epoch = 23693854, hash = 0x0000012345...
- Node B produces block: epoch = 23693854, hash = 0x0000056789...
- Node C produces block: epoch = 23693854, hash = 0x0000098765...

All three blocks have identical epoch numbers and will compete
```

#### Block Types

All blocks in XDAG 1.0 are **candidate blocks** that participate in epoch competition:

- **Candidate Block**: Any block mined at an epoch end moment
- **Main Block (Winner)**: The candidate with smallest hash (most hashpower) in the epoch
- **Orphan Block (Loser)**: All other candidates in the epoch
- **Genesis Block**: The first block (epoch 0, difficulty = 1)

**Important Notes**:
1. There is no structural difference between block types; the distinction is determined by consensus rules
2. To prevent 1000 nodes from storing 1000 candidate blocks, each epoch period **retains only the top 16 candidates by hashpower**
3. The candidate with highest hashpower (smallest hash) becomes the main block

### 1.3 Block Structure

#### Header (Fixed Size)
- **Epoch Number**: 8 bytes (which 64-second window)
- **Difficulty Target**: 32 bytes (PoW validation threshold)
- **Nonce**: 32 bytes (PoW solution)
- **Coinbase Address**: 20 bytes (mining reward recipient)
- **Hash**: 32 bytes (cached block hash)

#### Links (Variable Size)
A block contains **references** to other blocks and transactions:

- **Block Links**: References to previous epoch's candidate blocks
  - **Maximum**: 16 block references (`MAX_BLOCK_LINKS`)
  - **Purpose**: Connect to previous epoch's top candidates

- **Transaction Links**: References to pending transactions
  - **Maximum**: 1,484,984 transaction references
  - **Current Limit**: 1,024 transactions (conservative initial deployment)
  - **Purpose**: Include user transactions in the blockchain

**Total Link Capacity**: Up to 1,485,000 links per block (`MAX_LINKS_PER_BLOCK`)

#### Block Metadata (Not Serialized)
- **Height**: Sequential number in main chain
- **Cumulative Difficulty**: Sum of all difficulties from genesis
- **Status**: MAIN, ORPHAN, or PENDING

---

## 2. Proof-of-Work (PoW)

### 2.1 Hash Calculation

Block hash is computed as:
```
hash = Keccak256(epoch || difficulty || nonce || coinbase || links_count || link[0] || link[1] || ...)
```

Where:
- `epoch`: 8 bytes
- `difficulty`: 32 bytes
- `nonce`: 32 bytes
- `coinbase`: 20 bytes
- `links_count`: 4 bytes (number of links)
- Each `link[i]`: 33 bytes (type + hash)

### 2.2 PoW Validation

A block is considered valid if:
```
hash ≤ baseDifficultyTarget
```

Where:
- `hash`: Block's Keccak256 hash (interpreted as UInt256)
- `baseDifficultyTarget`: Network-wide difficulty threshold
- Smaller hash = more computational work

### 2.3 Initial Difficulty Targets

**Mainnet/Testnet**:
```
baseDifficultyTarget = 2^192
```
- Requires approximately 8 zero bytes in hash
- Average mining time: ~5 hours per block with 1 GH/s
- Network effect (1000 miners @ 10 GH/s): ~346 blocks/epoch

**Devnet**:
```
baseDifficultyTarget = 2^256 - 1 (MAX)
```
- Accepts any block (no PoW required)
- Used for development and testing

---

## 3. Epoch-Based Consensus

### 3.1 Within-Epoch Competition

#### Block Production Rules

**Production Timing**: At the end of each epoch
- All mining nodes connected to the network can produce blocks at the epoch end moment
- All blocks produced within the same epoch have **identical epoch numbers**
- Multiple nodes' blocks concurrently produced enter the same epoch's competition pool

**Validation**:
- All candidate blocks satisfying `hash ≤ baseDifficultyTarget` are valid
- PoW validation ensures each block has sufficient computational work

#### Candidate Block Storage Strategy

**Storage Limit**: To prevent unbounded storage growth (e.g., 1000 nodes producing 1000 candidate blocks)
- Each epoch period **retains only the top 16 candidates by hashpower**
- Hashpower calculation: `work = MAX_UINT256 / hash` (smaller hash = more hashpower)
- Candidates beyond the top 16 are discarded

**Retention Policy**:
1. Collect all valid candidate blocks in the epoch
2. Sort by hashpower (work) in descending order
3. Retain the top 16 highest-hashpower candidates
4. Discard remaining candidates

#### Winner Selection

**Main Block Determination Rule**:
```
Main Block (Winner) = highest-hashpower candidate in epoch
                    = smallest-hash candidate in epoch
```

**Rationale**: `work = MAX_UINT256 / hash`, therefore smaller hash = more hashpower

**Example**:
```
Epoch 23693854 end moment, 1000 nodes simultaneously produce blocks:
  - Block A: hash = 0x0000012345... work = 1.23e+73 (winner - smallest hash, most hashpower)
  - Block B: hash = 0x0000056789... work = 1.18e+73 (orphan, Top 16)
  - Block C: hash = 0x0000098765... work = 1.15e+73 (orphan, Top 16)
  ...
  - Block P: hash = 0x0000999999... work = 1.02e+73 (orphan, Top 16)
  - Block Q, R, ..., Z: (discarded, not in Top 16)

Retained: Blocks A-P (16 highest-hashpower blocks)
Discarded: Blocks Q-Z and others (984 lower-hashpower blocks)
```

### 3.2 Main Chain Selection

The **main chain** is determined by cumulative difficulty:

```
Main Chain = chain with maximum(cumulative_difficulty)
```

Where:
- `cumulative_difficulty` = sum of all main block difficulties from genesis
- Each main block adds its `difficulty` to the cumulative total
- Follows "heaviest chain wins" principle (similar to Bitcoin)

**Difficulty Calculation**:
```
block_difficulty = MAX_UINT256 / hash
```
- Smaller hash → larger difficulty
- Encourages miners to find smaller hashes

### 3.3 Height Assignment

Main blocks receive sequential heights:
- Genesis block: height = 1
- Next main block: height = previous_height + 1
- Orphan blocks: height = 0 (not part of main chain)

**Example**:
```
Epoch    Main Block    Height
-----------------------------
0        Genesis       1
1        Block A1      2
2        Block B1      3
3        (no winner)   -
4        Block D1      4
```

---

## 4. Mining Strategy

### 4.1 Reference Selection

When creating a new candidate block (at epoch end moment), miners must reference:

**1. Previous Epoch's Top 16 Candidates** (`MAX_BLOCK_LINKS`):
- Get the previous main block's epoch
- Collect all candidates retained in that epoch
- Sort by hashpower (work = MAX_UINT256 / hash) in descending order
- Select top 16 candidates (or all if fewer than 16)
- Add block links to these 16 blocks to the new candidate

**Rationale**:
- Ensures new blocks connect to the previous epoch's strongest candidates
- Since each epoch only retains Top 16 candidates, these are all highest-hashpower blocks
- The main block (highest hashpower) is necessarily among these 16
- Gives high-ranking orphan blocks a chance to be referenced
- Maintains DAG structure integrity

**2. Pending Transactions from Pool** (up to 1,024):
- Query transaction pool for pending transactions
- Sort by transaction fee (highest first)
- Select up to 1,024 transactions
- Add transaction links to the candidate block

**Total Links**: 16 block links + up to 1,024 transaction links = up to 1,040 links per block

### 4.2 Mining Restrictions

**Reference Depth Limit**:
```
current_epoch - previous_main_block_epoch ≤ MINING_MAX_REFERENCE_DEPTH (16 epochs)
```

**Purpose**: Prevent outdated nodes from mining blocks with stale references

**Enforcement**:
- If node is more than 16 epochs behind: mining is blocked
- Node must sync to latest epoch before mining can resume
- Exception: Devnet (allows any reference depth for testing)

### 4.3 Orphan Block Retention

**Immediate Storage Strategy** (Epoch Level):
- Each epoch only retains the top **16 highest-hashpower candidates**
- Candidates ranked 17+ are **immediately discarded**
- Among these 16 candidates: 1 becomes main block, 15 become orphan blocks

**Long-Term Retention Window** (Time Level): 16,384 epochs (~12 days)

**Rationale**:
- XDAG protocol allows referencing blocks within a 12-day window
- The 15 retained orphan blocks can be referenced by future blocks within 12 days
- Orphan blocks older than 12 days cannot be referenced by new blocks and can be safely deleted
- Periodic cleanup prevents unbounded storage growth

**Cleanup Policy**:
- Runs every 100 epochs (`ORPHAN_CLEANUP_INTERVAL`)
- Deletes orphan blocks older than 16,384 epochs
- Main blocks are never deleted

**Storage Effect**:
- Assumption: 1000 nodes produce blocks per epoch → 1000 candidate blocks generated
- Immediate filtering: Only retain Top 16 (discard 984)
- After 12 days cleanup: Delete expired orphan blocks (retain main blocks)
- Storage growth: Controlled and predictable

---

## 5. Difficulty Adjustment

### 5.1 Goals

Maintain optimal block production rate:
- **Target**: 150 qualifying blocks per epoch
- **Retain**: Only top 16 highest-hashpower candidates per epoch
- **Effect**:
  - Controls storage growth (1000 nodes → only retain 16)
  - Ensures sufficient competition (150 qualifying → select Top 16)
  - Maintains network decentralization (allows multiple nodes to participate)

### 5.2 Adjustment Algorithm

**Triggers**:
- Every 1,000 epochs (`DIFFICULTY_ADJUSTMENT_INTERVAL`)
- Recalculates `baseDifficultyTarget` based on recent block counts

**Formula** (simplified):
```
If avg_blocks_per_epoch > TARGET_BLOCKS_PER_EPOCH:
    baseDifficultyTarget = baseDifficultyTarget * 0.9  (harder, 10% decrease)
Else if avg_blocks_per_epoch < TARGET_BLOCKS_PER_EPOCH * 0.8:
    baseDifficultyTarget = baseDifficultyTarget * 1.1  (easier, 10% increase)
```

**Constraints**:
- Maximum adjustment: ±10% per interval
- Minimum target: INITIAL_BASE_DIFFICULTY_TARGET
- Maximum target: 2^256 - 1 (effectively no difficulty)

### 5.3 Network Hashrate Adaptation

**Low Hashrate** (few miners):
- Fewer blocks produced per epoch
- Difficulty adjusts down automatically
- Maintains blockchain progress

**High Hashrate** (many miners):
- Many blocks produced per epoch
- Difficulty adjusts up automatically
- Prevents excessive orphan accumulation

---

## 6. Transaction Processing

### 6.1 Transaction Lifecycle

1. **Submission**: User creates and signs transaction
2. **Validation**: Transaction pool validates signature, balance, nonce
3. **Pending**: Transaction enters pool, waiting for inclusion
4. **Selection**: Miner selects high-fee transactions for block
5. **Inclusion**: Transaction link added to mined block
6. **Execution**: When block becomes main block, transaction executes
7. **Finalization**: Transaction effects are permanent after execution

### 6.2 Transaction Pool

**Selection Strategy**:
- Sort pending transactions by fee (descending)
- Sort by nonce (ascending) for same account
- Sort by timestamp (oldest first) as tiebreaker

**Limits**:
- Maximum pool size: configurable (default: 10,000 transactions)
- Per-block limit: 1,024 transactions (initial conservative limit)
- Future scaling: up to 1,484,984 transactions per block

### 6.3 Transaction Fees

**Fee Calculation**:
```
fee = gasPrice * gasUsed
```

**Miner Rewards**:
- Block reward: Fixed per block (halves every ~4 years)
- Transaction fees: Sum of all transaction fees in block
- Total reward: block_reward + sum(transaction_fees)

---

## 7. Network Synchronization

### 7.1 Sync Strategies

**Full Sync**:
- Download all main blocks from genesis
- Validate each block's PoW and links
- Execute all transactions
- Build complete account state

**Fast Sync** (future):
- Download recent state snapshot
- Sync only recent blocks
- Faster initial sync for new nodes

### 7.2 Block Propagation

**New Block Message**:
- When miner finds valid block, broadcast to network
- Peers validate and relay to their peers
- Block propagates to entire network within seconds

**Block Validation**:
1. Check PoW: `hash ≤ baseDifficultyTarget`
2. Verify block structure and limits
3. Validate all block links exist
4. Check epoch correctness
5. Verify signature and nonce

### 7.3 Fork Resolution

**Scenario**: Two chains with different histories

**Resolution**:
```
Select chain with maximum cumulative difficulty
```

**Process**:
1. Node receives competing blocks
2. Calculate cumulative difficulty for each chain
3. Switch to heavier chain if necessary
4. Orphan blocks from abandoned chain
5. Re-execute transactions from new main chain

---

## 8. Economic Model

### 8.1 Block Rewards

**Initial Reward**: Network-dependent (configured in genesis)

**Halving Schedule**:
- Halves every ~4 years
- Based on block count, not time
- Ensures predictable supply schedule

### 8.2 Supply Dynamics

**Total Supply**:
- Defined by genesis configuration
- Approaches asymptotic limit due to halving
- No hard cap (tail emission possible)

**Inflation Rate**:
- Decreases over time due to halving
- Transaction fees become primary miner incentive
- Transition from inflationary to fee-driven security

---

## 9. Security Properties

### 9.1 51% Attack Resistance

**Attack Vector**: Majority hashrate rewrites history

**Defense**:
- Cumulative difficulty makes history rewrite expensive
- Deeper blocks require exponentially more work
- Checkpoints can be added for finality

**Cost**:
```
Cost to rewrite N epochs = network_hashrate * N * 64 seconds
```

### 9.2 Double-Spend Prevention

**Attack Vector**: Spend same coins twice on different chains

**Defense**:
- Transactions execute only on main chain
- Fork resolution based on cumulative difficulty
- Merchants should wait for confirmations

**Recommended Confirmations**:
- Small transactions: 3-6 epochs (~3-6 minutes)
- Large transactions: 20-30 epochs (~20-30 minutes)
- Critical transactions: 100+ epochs (~100+ minutes)

### 9.3 Orphan Flooding

**Attack Vector**: Flood network with low-quality orphan blocks

**Defense**:
- **PoW Requirement** (`baseDifficultyTarget`) limits block production
  - Each block must satisfy `hash ≤ baseDifficultyTarget`
  - Computational cost limits attacker's ability to produce many blocks
- **Top 16 Storage Strategy** (strongest defense)
  - Each epoch only retains the top 16 highest-hashpower candidates
  - Even if attacker produces 10,000 low-quality blocks, only Top 16 are retained
  - Blocks ranked 17+ are immediately discarded
- **Orphan Retention Window** limits long-term storage impact
  - Orphan blocks older than 12 days (16,384 epochs) are automatically cleaned up
- **Only reference Top 16 per epoch**
  - New blocks only reference previous epoch's Top 16
  - Low-quality orphan blocks cannot be referenced by future blocks

**Effect**:
- Attacker must have hashpower exceeding Top 16 to impact storage
- Storage growth fully controlled: maximum 16 blocks per epoch

---

## 10. Performance Characteristics

### 10.1 Current Throughput

**Block Production**:
- Epoch duration: 64 seconds
- Target candidate blocks: ~150 per epoch (maintained through difficulty adjustment)
- Retained candidate blocks: Top 16 per epoch (highest hashpower)
- Main blocks: 1 per epoch (Top 16 with smallest hash)
- Effective block rate: 1 main block / 64 seconds = 0.016 blocks/second

**Transaction Throughput**:
- Transactions per block: 1,024 (current limit)
- Main blocks per epoch: 1
- **Current TPS**: 1,024 / 64 = **16 TPS**

**Storage Efficiency**:
- 1000 nodes produce blocks: 1000 candidate blocks generated
- Storage filtering: Only retain Top 16 (99.84% discarded)
- Net increase per epoch: 1 main block + 15 orphan blocks = 16 blocks

### 10.2 Future Scaling

**Maximum Capacity**:
- Links per block: 1,485,000 (`MAX_LINKS_PER_BLOCK`)
- Transactions per block: 1,484,984 (after 16 block links)
- **Theoretical TPS**: 1,484,984 / 64 = **23,203 TPS**
- **Comparison**: 96.7% of Visa's peak capacity (~24,000 TPS)

**Scaling Path**:
1. Phase 1: 1,024 txs/block (16 TPS) - current
2. Phase 2: 10,000 txs/block (156 TPS) - medium-term
3. Phase 3: 100,000 txs/block (1,562 TPS) - long-term
4. Phase 4: 1,484,984 txs/block (23,203 TPS) - ultimate capacity

### 10.3 Storage Requirements

**Block Size**:
- Header: ~124 bytes
- Links: 33 bytes per link
- 1,040 links: ~34 KB per block
- Full capacity (1.485M links): ~48 MB per block

**Chain Growth** (current limit):
- Blocks per day: ~1,350 (1 per epoch)
- Data per day: ~46 MB (34 KB * 1,350)
- Annual growth: ~16.5 GB/year

**Chain Growth** (full capacity):
- Blocks per day: ~1,350
- Data per day: ~64 GB (48 MB * 1,350)
- Annual growth: ~23 TB/year

---

## 11. Comparison with Other Protocols

### 11.1 vs Bitcoin

| Feature | Bitcoin | XDAG 1.0 |
|---------|---------|----------|
| Block Time | 10 minutes | 64 seconds (epoch) |
| TPS | ~7 | 16 (current), 23,203 (max) |
| Finality | ~60 minutes | ~6 minutes (6 epochs) |
| Structure | Linear chain | DAG with epoch competition |
| Orphans | Wasted work | Preserved and referenced |

### 11.2 vs Ethereum

| Feature | Ethereum | XDAG 1.0 |
|---------|----------|----------|
| Block Time | ~12 seconds | 64 seconds (epoch) |
| TPS | ~15-30 | 16 (current), 23,203 (max) |
| Consensus | PoS (post-merge) | PoW + epoch competition |
| Smart Contracts | Full VM | Account-based transfers |
| Uncle Blocks | Rewarded but not included | Orphans preserved, can be referenced |

### 11.3 vs IOTA

| Feature | IOTA | XDAG 1.0 |
|---------|------|----------|
| Structure | Tangle (DAG) | Epoch-based DAG |
| Consensus | Coordinator / Consensus Protocol | PoW + heaviest chain |
| Mining | No mining | PoW mining with rewards |
| Orphans | No concept | Explicit orphan management |
| Finality | Probabilistic | Cumulative difficulty based |

---

## 12. Network Parameters

### 12.1 Mainnet

```
- Genesis Epoch: TBD (mainnet launch)
- Epoch Duration: 64 seconds
- Initial Block Reward: TBD
- Halving Interval: TBD blocks (~4 years)
- Difficulty Target: 2^192
- Max Block Links: 16
- Max TX Links: 1,024 (current), 1,484,984 (capacity)
- Mining Reference Depth: 16 epochs
- Orphan Retention: 16,384 epochs (~12 days)
```

### 12.2 Testnet

```
- Genesis Epoch: TBD
- Epoch Duration: 64 seconds
- Initial Block Reward: TBD (same as mainnet)
- Difficulty Target: 2^192 (or lower for testing)
- Other parameters: Same as mainnet
```

### 12.3 Devnet

```
- Genesis Epoch: Configurable
- Epoch Duration: 64 seconds
- Initial Block Reward: Configurable
- Difficulty Target: 2^256 - 1 (no PoW)
- Mining Reference Depth: Unlimited
- Other parameters: Same as mainnet
```

---

## 13. Future Improvements

### 13.1 Transaction Throughput

**Gradual Increase**:
- Monitor network stability and storage growth
- Increase MAX_TX_LINKS_PER_BLOCK gradually
- Path: 1K → 10K → 100K → 1M+ transactions per block

**Benefits**:
- Higher TPS without protocol changes
- Competitive with centralized payment systems
- Maintains decentralization

### 13.2 Fast Sync

**Snapshot-Based Sync**:
- Periodic state snapshots
- New nodes download recent snapshot + recent blocks
- Reduces initial sync time from days to hours

### 13.3 Layer 2 Solutions

**Payment Channels**:
- Off-chain micropayments
- Periodic settlement on main chain
- Instant finality for channel participants

**State Channels**:
- Off-chain state updates
- Dispute resolution on main chain
- Application-specific logic

---

## 14. Conclusion

XDAG 1.0 presents a unique consensus mechanism that combines:

1. **Epoch-based competition**: Multiple miners can succeed in the same time window
2. **DAG structure**: Orphan blocks are preserved and can be referenced
3. **PoW security**: Work-based chain selection prevents history rewriting
4. **High throughput**: Capable of 23,203 TPS with full link capacity
5. **Fair mining**: All valid blocks contribute to network security

The protocol balances decentralization, security, and performance while maintaining a simple and elegant design. Its epoch-based competition reduces orphan waste compared to traditional blockchains, and its massive link capacity enables Visa-level transaction throughput without sacrificing the security properties of Proof-of-Work consensus.

---

## Appendix A: Terminology

- **Epoch**: 64-second time window for block competition
- **Candidate Block**: Any block mined in an epoch
- **Main Block**: Winner of epoch competition (smallest hash)
- **Orphan Block**: Non-winning candidate block
- **Height**: Sequential position in main chain
- **Cumulative Difficulty**: Sum of difficulties from genesis
- **Link**: Reference to another block or transaction
- **PoW**: Proof-of-Work (hash ≤ difficulty target)
- **TPS**: Transactions Per Second

## Appendix B: Key Constants

```
EPOCH_DURATION = 64 seconds
MAX_BLOCK_LINKS = 16 (references to previous epoch blocks)
MAX_LINKS_PER_BLOCK = 1,485,000 (total capacity)
MAX_TX_LINKS_PER_BLOCK = 1,024 (current conservative limit)
INITIAL_BASE_DIFFICULTY_TARGET = 2^192 (mainnet/testnet)
DEVNET_DIFFICULTY_TARGET = 2^256 - 1 (no PoW)
MINING_MAX_REFERENCE_DEPTH = 16 epochs (~17 minutes)
ORPHAN_RETENTION_WINDOW = 16,384 epochs (~12 days)
TARGET_BLOCKS_PER_EPOCH = 150
TOP_CANDIDATES_PER_EPOCH = 16
DIFFICULTY_ADJUSTMENT_INTERVAL = 1,000 epochs
ORPHAN_CLEANUP_INTERVAL = 100 epochs
```

**Storage Strategy Explanation**:
- `TOP_CANDIDATES_PER_EPOCH = 16`: Each epoch only retains the top 16 highest-hashpower candidates
  - 1 becomes main block (smallest hash)
  - 15 become orphan blocks (hashpower ranks 2-16)
  - Remaining candidates are immediately discarded
- `TARGET_BLOCKS_PER_EPOCH = 150`: Difficulty adjustment target, ensures sufficient competition
  - 150 candidates compete → select Top 16 → ensures fairness

---

**Document Status**: Draft - Subject to updates based on implementation and testing

**Feedback**: Issues and suggestions can be submitted via GitHub
