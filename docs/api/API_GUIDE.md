# XDAG v5.1 API Documentation

## Overview

XDAG v5.1 provides **dual API interfaces** for maximum flexibility:

1. **JSON-RPC 2.0** - Ethereum-compatible interface
2. **RESTful API** - Standard HTTP REST interface

Both interfaces share the same underlying API service layer and return consistent data formats.

## Quick Start

### Start XDAG Node with RPC enabled

```bash
./xdag.sh
```

The RPC server will start automatically on `http://localhost:10001` with the following endpoints:

- **JSON-RPC**: `POST http://localhost:10001/rpc`
- **RESTful API**: `GET/POST http://localhost:10001/api/v1/...`
- **OpenAPI Spec**: `GET http://localhost:10001/openapi.json`
- **Swagger UI**: `GET http://localhost:10001/docs`

## API Interfaces

### JSON-RPC 2.0 Interface

Compatible with Ethereum tools and libraries.

**Example Request:**
```bash
curl -X POST http://localhost:10001/rpc \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "xdag_blockNumber",
    "params": [],
    "id": 1
  }'
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "blockNumber": "0x1234"
  },
  "id": 1
}
```

### RESTful API

Standard HTTP REST interface with intuitive URLs.

**Example Request:**
```bash
curl http://localhost:10001/api/v1/blocks/number
```

**Response:**
```json
{
  "blockNumber": "0x1234"
}
```

## Data Format Standards

### Numbers
- All numbers use **hex format with `0x` prefix**
- Example: `"0x1234"` (decimal 4660)

### Addresses
- **Base58 encoded** 20-byte addresses
- Example: `"4dutRdvFZJdKaPZXhdfgLMoujc9N3CFouZVs8JJi"`

### Amounts
- **Decimal strings** with 9 decimal places
- Unit: XDAG
- Example: `"1000.123456789"`

### Block Identifiers
- `"latest"` - Latest main chain block
- `"earliest"` - Genesis block (block 0)
- `"pending"` - Same as latest (XDAG doesn't have separate pending)
- `"0x1234"` - Specific block number in hex

## API Methods

### Account Methods

#### Get Wallet Accounts
```bash
# RESTful
curl http://localhost:10001/api/v1/accounts

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"xdag_accounts","params":[],"id":1}'
```

#### Get Balance
```bash
# RESTful
curl http://localhost:10001/api/v1/accounts/{address}/balance?blockNumber=latest

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"xdag_getBalance","params":["address","latest"],"id":1}'
```

#### Get Transaction Count (Nonce)
```bash
# RESTful
curl http://localhost:10001/api/v1/accounts/{address}/nonce

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"xdag_getTransactionCount","params":["address","latest"],"id":1}'
```

### Block Methods

#### Get Current Block Number
```bash
# RESTful
curl http://localhost:10001/api/v1/blocks/number

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"xdag_blockNumber","params":[],"id":1}'
```

#### Get Block by Number
```bash
# RESTful
curl http://localhost:10001/api/v1/blocks/latest?fullTransactions=false

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"xdag_getBlockByNumber","params":["latest",false],"id":1}'
```

#### Get Block by Hash
```bash
# RESTful
curl http://localhost:10001/api/v1/blocks/hash/0xabcd...

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"xdag_getBlockByHash","params":["0xabcd...",false],"id":1}'
```

### Transaction Methods

#### Get Transaction
```bash
# RESTful
curl http://localhost:10001/api/v1/transactions/0xtxhash...

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"xdag_getTransactionByHash","params":["0xtxhash..."],"id":1}'
```

#### Send Raw Transaction
```bash
# RESTful
curl -X POST http://localhost:10001/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{"signedTransactionData":"0x..."}'

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"xdag_sendRawTransaction","params":["0x..."],"id":1}'
```

### Network Methods

#### Get Sync Status
```bash
# RESTful
curl http://localhost:10001/api/v1/network/syncing

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"xdag_syncing","params":[],"id":1}'
```

#### Get Chain ID
```bash
# RESTful
curl http://localhost:10001/api/v1/network/chainId

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"xdag_chainId","params":[],"id":1}'
```

#### Get Peer Count
```bash
# RESTful
curl http://localhost:10001/api/v1/network/peers/count

# JSON-RPC
curl -X POST http://localhost:10001/rpc \
  -d '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}'
```

## Generating Client SDKs

XDAG provides an OpenAPI 3.0 specification that can be used to generate client SDKs for multiple programming languages.

### Prerequisites

Install OpenAPI Generator:
```bash
# Using npm
npm install @openapitools/openapi-generator-cli -g

# Or using Homebrew (macOS)
brew install openapi-generator
```

### Generate SDKs

#### Java SDK
```bash
openapi-generator-cli generate \
  -i http://localhost:10001/openapi.json \
  -g java \
  -o sdk/java \
  --additional-properties=groupId=io.xdag,artifactId=xdag-sdk,artifactVersion=5.1.0
```

#### Python SDK
```bash
openapi-generator-cli generate \
  -i http://localhost:10001/openapi.json \
  -g python \
  -o sdk/python \
  --additional-properties=packageName=xdag_sdk,packageVersion=5.1.0
```

#### JavaScript/TypeScript SDK
```bash
openapi-generator-cli generate \
  -i http://localhost:10001/openapi.json \
  -g typescript-axios \
  -o sdk/typescript \
  --additional-properties=npmName=xdag-sdk,npmVersion=5.1.0
```

#### Go SDK
```bash
openapi-generator-cli generate \
  -i http://localhost:10001/openapi.json \
  -g go \
  -o sdk/go \
  --additional-properties=packageName=xdag
```

#### Rust SDK
```bash
openapi-generator-cli generate \
  -i http://localhost:10001/openapi.json \
  -g rust \
  -o sdk/rust \
  --additional-properties=packageName=xdag
```

### Using Generated SDKs

#### Java Example
```java
import io.xdag.sdk.ApiClient;
import io.xdag.sdk.api.BlocksApi;

ApiClient client = new ApiClient();
client.setBasePath("http://localhost:10001");

BlocksApi blocksApi = new BlocksApi(client);
BlockNumberResponse response = blocksApi.apiV1BlocksNumberGet();
System.out.println("Current block: " + response.getBlockNumber());
```

#### Python Example
```python
from xdag_sdk import ApiClient, BlocksApi

client = ApiClient()
client.configuration.host = "http://localhost:10001"

blocks_api = BlocksApi(client)
response = blocks_api.api_v1_blocks_number_get()
print(f"Current block: {response.block_number}")
```

#### TypeScript Example
```typescript
import { Configuration, BlocksApi } from 'xdag-sdk';

const config = new Configuration({
  basePath: 'http://localhost:10001'
});

const blocksApi = new BlocksApi(config);
const response = await blocksApi.apiV1BlocksNumberGet();
console.log(`Current block: ${response.data.blockNumber}`);
```

## OpenAPI Specification

The complete OpenAPI 3.0 specification is available at:
- `http://localhost:10001/openapi.json`
- Or in the repository: `docs/api/openapi.yaml`

You can view the interactive API documentation at:
- `http://localhost:10001/docs` (redirects to Swagger UI)

## Error Handling

### RESTful API Errors

```json
{
  "error": "Resource not found",
  "code": 404
}
```

### JSON-RPC Errors

```json
{
  "jsonrpc": "2.0",
  "error": {
    "code": -32600,
    "message": "Invalid Request"
  },
  "id": 1
}
```

## Migration from Old API

The old XdagApi interface is now **deprecated** but still available for backward compatibility.

**Migration Guide:**

| Old Method | New JSON-RPC Method | New RESTful Endpoint |
|------------|---------------------|---------------------|
| `xdag_getBlockByHash(hash, page)` | `xdag_getBlockByHash(hash, fullTx)` | `GET /api/v1/blocks/hash/{hash}` |
| `xdag_getBalance(address)` | `xdag_getBalance(address, blockNumber)` | `GET /api/v1/accounts/{address}/balance` |
| `xdag_blockNumber()` | `xdag_blockNumber()` | `GET /api/v1/blocks/number` |

## Support

For issues and questions:
- GitHub: https://github.com/XDagger/xdagj/issues
- Documentation: https://github.com/XDagger/xdagj/tree/master/docs

## License

MIT License
