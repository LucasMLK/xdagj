# XDAGJ HTTP API Reference

Base URL: `http://localhost:10001/api/v1/`

## Blocks

### List Blocks
```
GET /blocks?page=1&size=20
```

Response:
```json
{
  "data": [
    {
      "hash": "0x...",
      "height": 100,
      "epoch": 23694000,
      "timestamp": 1733500000000,
      "difficulty": "0x...",
      "transactionCount": 5
    }
  ],
  "pagination": {
    "page": 1,
    "size": 20,
    "total": 500,
    "totalPages": 25
  }
}
```

### Get Block Number (Latest Height)
```
GET /blocks/number
```

Response:
```json
{
  "blockNumber": "0x64"
}
```

### Get Block by Height
```
GET /blocks/{height}?fullTransactions=false
```

### Get Block by Hash
```
GET /blocks/hash/{hash}?fullTransactions=false
```

### Get Blocks by Epoch
```
GET /blocks/epoch/{epoch}?page=1&size=20
```

Response:
```json
{
  "epoch": 23694000,
  "blocks": [
    {
      "hash": "0x...",
      "height": 100,
      "isWinner": true,
      "difficulty": "0x..."
    }
  ]
}
```

### Get Blocks by Epoch Range
```
GET /blocks/epoch/range?fromEpoch=100&toEpoch=200
```

## Transactions

### List Transactions
```
GET /transactions?page=1&size=20
```

### Get Transaction by Hash
```
GET /transactions/{hash}
```

Response:
```json
{
  "hash": "0x...",
  "from": "0x...",
  "to": "0x...",
  "value": "1000000000000000000",
  "nonce": 5,
  "blockHash": "0x...",
  "blockHeight": 100,
  "status": "EXECUTED"
}
```

### Send Raw Transaction
```
POST /transactions
Content-Type: application/json
Authorization: Bearer <api-key>

{
  "signedTransactionData": "0x..."
}
```

Response:
```json
{
  "hash": "0x...",
  "status": "PENDING"
}
```

## Accounts

### List Accounts
```
GET /accounts?page=1&size=20
Authorization: Bearer <api-key>
```

### Get Account Balance
```
GET /accounts/{address}/balance?blockNumber=latest
Authorization: Bearer <api-key>
```

Response:
```json
{
  "address": "0x...",
  "balance": "1.000000000",
  "blockNumber": "0x64"
}
```

### Get Account Nonce
```
GET /accounts/{address}/nonce?blockNumber=latest
Authorization: Bearer <api-key>
```

Response:
```json
{
  "address": "0x...",
  "nonce": "0x5",
  "blockNumber": "0x64"
}
```

## Network

### Get Sync Status
```
GET /network/syncing
```

Response:
```json
{
  "syncing": true,
  "currentBlock": "0x64",
  "highestBlock": "0x96"
}
```

### Get Peer Count
```
GET /network/peers/count
```

Response:
```json
{
  "count": "0x5"
}
```

### Get Chain ID
```
GET /network/chainId
```

Response:
```json
{
  "chainId": "0x1"
}
```

### Get Protocol Version
```
GET /network/protocol
```

Response:
```json
{
  "protocolVersion": "1.0"
}
```

### Get Coinbase Address
```
GET /network/coinbase
```

Response:
```json
{
  "coinbase": "0x..."
}
```

## Mining

### Get RandomX Info
```
GET /mining/randomx
```

Response:
```json
{
  "seedHash": "0x...",
  "seedHeight": 64,
  "nextSeedHash": "0x...",
  "nextSeedHeight": 128
}
```

### Get Mining Difficulty
```
GET /mining/difficulty
```

Response:
```json
{
  "difficulty": "0x..."
}
```

### Get Candidate Block
```
GET /mining/candidate?poolId=pool1
```

Response:
```json
{
  "epoch": 23694000,
  "difficulty": "0x...",
  "parentHash": "0x...",
  "coinbase": "0x...",
  "template": "0x..."
}
```

### Submit Mined Block
```
POST /mining/submit
Content-Type: application/json
Authorization: Bearer <api-key>

{
  "blockData": "0x...",
  "nonce": "0x...",
  "poolId": "pool1"
}
```

Response:
```json
{
  "accepted": true,
  "message": "Block submitted successfully"
}
```

## Node

### Get Node Status
```
GET /node/status
```

Response:
```json
{
  "version": "1.0.0",
  "network": "mainnet",
  "uptime": 3600,
  "peerCount": 5,
  "syncStatus": "synced"
}
```

## Authentication

Some endpoints require API key authentication:

```
Authorization: Bearer <api-key>
```

Required for:
- `GET /accounts` - READ permission
- `GET /accounts/{address}/*` - READ permission
- `POST /transactions` - WRITE permission
- `POST /mining/submit` - WRITE permission

## Error Responses

All errors return:
```json
{
  "error": "Invalid block hash format",
  "code": 400
}
```

| Code | Description |
|------|-------------|
| 400 | Bad Request - Invalid parameters |
| 401 | Unauthorized - Invalid or missing API key |
| 404 | Not Found - Resource doesn't exist |
| 500 | Internal Server Error |

## Pagination

- Page numbering starts at 1
- Default page size: 20
- Maximum page size: 100

Request:
```
GET /blocks?page=2&size=50
```

Response includes:
```json
{
  "pagination": {
    "page": 2,
    "size": 50,
    "total": 500,
    "totalPages": 10
  }
}
```

## Data Formats

- **Hash**: 66 characters, `0x` prefix + 64 hex chars
- **Address**: 42 characters, `0x` prefix + 40 hex chars
- **Balance**: String with decimal (e.g., "1.000000000" XDAG)
- **Hex numbers**: `0x` prefix (e.g., "0x64" = 100)
- **Timestamp**: Unix milliseconds
- **Epoch**: Integer, `timestamp / 64000`
