# XDAGJ Network Protocol Specification

## Overview

XDAGJ uses a custom P2P protocol built on Netty for node discovery, block synchronization, and transaction propagation.

## Network Stack

```
┌─────────────────────────────────────┐
│       Application Layer             │
│  (XdagP2pEventHandler, SyncManager) │
├─────────────────────────────────────┤
│       Message Layer                 │
│  (XdagMessageCode, Message classes) │
├─────────────────────────────────────┤
│       Transport Layer               │
│  (Netty TCP, LengthFieldCodec)      │
├─────────────────────────────────────┤
│       Discovery Layer               │
│  (Kademlia DHT, Node Protocol)      │
└─────────────────────────────────────┘
```

## Message Format

### Frame Structure

```
┌──────────────────────────────────────────┐
│  Length (4 bytes, big-endian)            │
├──────────────────────────────────────────┤
│  Message Code (1 byte)                   │
├──────────────────────────────────────────┤
│  Payload (variable length)               │
└──────────────────────────────────────────┘
```

- **Length**: Total payload size (code + payload)
- **Message Code**: Identifies message type (see below)
- **Payload**: Message-specific data

### Message Code Ranges

| Range | Protocol | Description |
|-------|----------|-------------|
| 0x00-0x0F | KAD | Kademlia DHT (node discovery) |
| 0x10-0x1F | Node | Handshake, PING/PONG |
| 0x20-0x3F | XDAG | Application messages |

## Application Messages (0x20-0x3F)

### 0x27 NEW_TRANSACTION

Broadcast new transaction to peers.

```
┌────────────────────────────────────┐
│  TTL (1 byte)                      │  Hop limit (default: 5)
├────────────────────────────────────┤
│  Transaction (variable)            │  Serialized transaction
└────────────────────────────────────┘
```

**Behavior**:
- Receiver validates transaction
- If valid, adds to mempool
- Forwards to peers with TTL-1 (if TTL > 1)
- Prevents broadcast loops via seen-transaction cache

### 0x30 NEW_BLOCK_HASH

Announce new block hash (Inv message).

```
┌────────────────────────────────────┐
│  Hash (32 bytes)                   │  Block hash
├────────────────────────────────────┤
│  Epoch (8 bytes)                   │  Block epoch number
├────────────────────────────────────┤
│  TTL (1 byte)                      │  Hop limit (default: 3)
└────────────────────────────────────┘
```

**Behavior**:
- Receiver checks if block exists locally
- If not, sends GET_BLOCKS to request full block
- Forwards announcement with TTL-1 (if TTL > 1)

### 0x31 GET_BLOCKS

Request block data by hash list.

```
┌────────────────────────────────────┐
│  Count (4 bytes)                   │  Number of hashes
├────────────────────────────────────┤
│  Hash[0] (32 bytes)                │
├────────────────────────────────────┤
│  Hash[1] (32 bytes)                │
├────────────────────────────────────┤
│  ...                               │
└────────────────────────────────────┘
```

**Limits**: Max 500 hashes per request

### 0x32 BLOCKS_REPLY

Response with block data.

```
┌────────────────────────────────────┐
│  Count (4 bytes)                   │  Number of blocks
├────────────────────────────────────┤
│  Block[0] Length (4 bytes)         │
├────────────────────────────────────┤
│  Block[0] Data (variable)          │  Serialized block
├────────────────────────────────────┤
│  Block[1] Length (4 bytes)         │
├────────────────────────────────────┤
│  Block[1] Data (variable)          │
├────────────────────────────────────┤
│  ...                               │
└────────────────────────────────────┘
```

### 0x33 GET_EPOCH_HASHES

Request block hashes for epoch range.

```
┌────────────────────────────────────┐
│  Start Epoch (8 bytes)             │  Inclusive
├────────────────────────────────────┤
│  End Epoch (8 bytes)               │  Inclusive
└────────────────────────────────────┘
```

**Limits**: Max 1000 epochs per request

### 0x34 EPOCH_HASHES_REPLY

Response with epoch-to-hashes mapping.

```
┌────────────────────────────────────┐
│  Epoch Count (4 bytes)             │  Number of epochs
├────────────────────────────────────┤
│  Epoch[0] (8 bytes)                │
├────────────────────────────────────┤
│  Hash Count[0] (4 bytes)           │
├────────────────────────────────────┤
│  Hash[0][0] (32 bytes)             │
├────────────────────────────────────┤
│  Hash[0][1] (32 bytes)             │
├────────────────────────────────────┤
│  ...                               │
├────────────────────────────────────┤
│  Epoch[1] (8 bytes)                │
├────────────────────────────────────┤
│  ...                               │
└────────────────────────────────────┘
```

### 0x35 GET_STATUS

Request peer's chain status (no payload).

### 0x36 STATUS_REPLY

Response with chain status.

```
┌────────────────────────────────────┐
│  Tip Epoch (8 bytes)               │  Latest main block epoch
├────────────────────────────────────┤
│  Main Chain Height (8 bytes)       │  Number of main blocks
├────────────────────────────────────┤
│  Difficulty Length (4 bytes)       │  Length of difficulty bytes
├────────────────────────────────────┤
│  Difficulty (variable)             │  BigInteger bytes
└────────────────────────────────────┘
```

## Synchronization Protocol

### Connection Establishment

```
Node A                              Node B
   │                                   │
   │──────── TCP Connect ─────────────►│
   │                                   │
   │◄─────── Handshake ───────────────►│
   │         (Node Protocol)           │
   │                                   │
   │──────── GET_STATUS ──────────────►│
   │                                   │
   │◄─────── STATUS_REPLY ────────────│
   │                                   │
```

### Forward Sync (Small Gap)

When local tip is slightly behind remote:

```
Node A (behind)                     Node B (ahead)
   │                                   │
   │  GET_EPOCH_HASHES [100, 356]     │
   │─────────────────────────────────►│
   │                                   │
   │  EPOCH_HASHES_REPLY              │
   │  {100: [h1,h2], 101: [h3], ...}  │
   │◄─────────────────────────────────│
   │                                   │
   │  GET_BLOCKS [h1, h3, ...]        │
   │─────────────────────────────────►│
   │                                   │
   │  BLOCKS_REPLY [block1, block3]   │
   │◄─────────────────────────────────│
   │                                   │
   │  (Import blocks asynchronously)  │
   │                                   │
```

### Binary Search Sync (Large Gap > 1024 epochs)

When local tip is far behind remote:

```
Node A                              Node B
   │                                   │
   │  Binary search to find start     │
   │                                   │
   │  GET_EPOCH_HASHES [50000, 50256] │
   │─────────────────────────────────►│
   │                                   │
   │  EPOCH_HASHES_REPLY (empty)      │
   │◄─────────────────────────────────│
   │                                   │
   │  (No blocks, search higher)      │
   │                                   │
   │  GET_EPOCH_HASHES [75000, 75256] │
   │─────────────────────────────────►│
   │                                   │
   │  EPOCH_HASHES_REPLY {75100: ...} │
   │◄─────────────────────────────────│
   │                                   │
   │  (Found blocks, search lower)    │
   │                                   │
   │  ... (continue binary search)    │
   │                                   │
   │  (Start forward sync from found  │
   │   minimum epoch)                 │
   │                                   │
```

### Real-time Block Propagation

```
Miner                Node A              Node B              Node C
  │                    │                   │                   │
  │  New Block         │                   │                   │
  │───────────────────►│                   │                   │
  │                    │                   │                   │
  │                    │  NEW_BLOCK_HASH   │                   │
  │                    │  (TTL=3)          │                   │
  │                    │──────────────────►│                   │
  │                    │                   │                   │
  │                    │                   │  NEW_BLOCK_HASH   │
  │                    │                   │  (TTL=2)          │
  │                    │                   │──────────────────►│
  │                    │                   │                   │
  │                    │  GET_BLOCKS       │                   │
  │                    │◄──────────────────│                   │
  │                    │                   │                   │
  │                    │  BLOCKS_REPLY     │                   │
  │                    │──────────────────►│                   │
  │                    │                   │                   │
```

## Sync States

```
┌─────────────┐
│FORWARD_SYNC │◄──────────────────────────────┐
└──────┬──────┘                               │
       │ Large gap detected                   │
       ▼                                      │
┌─────────────┐                               │
│BINARY_SEARCH│                               │
└──────┬──────┘                               │
       │ Found start epoch                    │
       ▼                                      │
┌──────────────────┐                          │
│BINARY_SEARCH_    │──────────────────────────┘
│COMPLETE          │  Transition to forward
└──────────────────┘
```

## Fork Detection & Reorganization

On first peer connection, scan historical epochs:

```
┌─────────────┐
│FORWARD_SYNC │
└──────┬──────┘
       │ First peer, has local blocks
       ▼
┌──────────────┐
│FORK_DETECTION│  Scan epochs from genesis
└──────┬───────┘
       │
   ┌───┴───┐
   │       │
   ▼       ▼
No fork   Fork found
   │       │
   │       ▼
   │  ┌────────────────┐
   │  │CHAIN_          │  Sync from fork point
   │  │REORGANIZATION  │
   │  └───────┬────────┘
   │          │
   └────┬─────┘
        ▼
┌─────────────┐
│FORWARD_SYNC │
└─────────────┘
```

## Connection Management

### Timeouts

| Timeout | Value | Description |
|---------|-------|-------------|
| Connect | 2s | TCP connection timeout |
| Read | 60s | Idle read timeout (triggers disconnect) |
| Idle state | 30s | Idle read/write detection |
| PING timeout | 20s | PING response timeout |

### Duplicate Detection

- Track connections by Node ID
- Reject duplicate connections from same node
- Use `getUniqueConnectedChannels()` for broadcasting

### Async Processing

Block imports execute off Netty EventLoop to prevent blocking:

```java
// BUG-P2P-008 fix: Async block import
blockImportExecutor.submit(() -> {
    dagChain.tryToConnect(block);
});
```

## Rate Limiting

| Resource | Limit | Location |
|----------|-------|----------|
| GET_BLOCKS response | 500 hashes/batch | XdagP2pEventHandler |
| GET_EPOCH_HASHES response | 1000 epochs | XdagP2pEventHandler |
| Epoch request batch | 256 epochs | SyncManager |
| Pipeline gap | 4096 epochs | SyncManager |
| Binary search threshold | 1024 epochs | SyncManager |
| Missing dependency cooldown | 5 min | XdagP2pEventHandler |

## Security Considerations

### DoS Protection

- Message size limits (enforced by LengthFieldCodec)
- Rate limiting on requests
- Timeout disconnections for misbehaving peers

### Peer Penalization

Malformed messages trigger immediate disconnect:

```java
private void penalizePeer(Channel channel, String reason) {
    log.warn("Disconnecting peer {}: {}", channel.getRemoteAddress(), reason);
    channel.close();
}
```

### Anti-Loop

- TTL decremented on forwarding
- Seen-hash cache for transactions and block announcements
- Request tracking with sequence IDs
