# XDAG HTTP API Guide

## Overview

XDAG provides a RESTful HTTP API for interacting with the blockchain. The API supports JSON format requests and responses, with optional HTTPS and authentication.

## Features

- **RESTful Design**: Clean, resource-based URLs following REST principles
- **Version Management**: API versioning support (currently v1)
- **Authentication**: Optional API key-based authentication with role-based permissions
- **HTTPS Support**: Optional SSL/TLS encryption for secure communication
- **Pagination**: Built-in pagination support for list endpoints
- **CORS**: Cross-Origin Resource Sharing support for web applications
- **OpenAPI Specification**: Machine-readable API specification at `/openapi.json`

## Configuration

### Basic Configuration

Edit your configuration file (e.g., `xdag-devnet.conf`):

```hocon
# HTTP API Config
rpc.http.enabled = true
rpc.http.host = 127.0.0.1
rpc.http.port = 10001
```

### HTTPS Configuration (Recommended for Production)

```hocon
# Enable HTTPS
rpc.http.https.enabled = true
rpc.http.https.certFile = /path/to/cert.pem
rpc.http.https.keyFile = /path/to/key.pem
```

### Authentication Configuration (Recommended for Production)

```hocon
# Enable API authentication
rpc.http.auth.enabled = true
rpc.http.auth.apiKeys = [
  "your-read-only-key:READ",
  "your-write-key:WRITE"
]
```

#### Permission Levels

- **PUBLIC**: No authentication required (for public endpoints)
- **READ**: Read-only operations (queries, balance checks, etc.)
- **WRITE**: Write operations (sending transactions, mining, etc.)

### Advanced Configuration

```hocon
# CORS configuration
rpc.http.cors.origins = "*"

# Request size limit (in bytes, default 1MB)
rpc.http.maxContentLength = 1048576

# Netty thread pool configuration
rpc.http.bossThreads = 1
rpc.http.workerThreads = 4
```

## Starting the Node

### Development Environment

```bash
# Build the project
mvn clean package -DskipTests

# Start node with HTTP API enabled
cd test-nodes/node1
bash start.sh
```

### Production Environment

```bash
# Use the main startup script
bash script/xdag.sh -t
```

## API Endpoints

### Base URL

```
http://127.0.0.1:10001/api/v1
```

### Authentication

If authentication is enabled, include the API key in the request header:

```bash
curl -H "Authorization: Bearer your-api-key-here" \
  http://127.0.0.1:10001/api/v1/network/chainId
```

### Network Information

#### Get Protocol Version
```bash
GET /api/v1/network/protocol
```

Response:
```json
{
  "version": "1"
}
```

#### Get Chain ID
```bash
GET /api/v1/network/chainId
```

Response:
```json
{
  "chainId": "0x1"
}
```

#### Get Coinbase Address
```bash
GET /api/v1/network/coinbase
```

Response:
```json
{
  "address": "4sgjhDFGjhk3JHk4JHGj3k4h5jk3h5jkh"
}
```

#### Get Sync Status
```bash
GET /api/v1/network/syncing
```

Response:
```json
{
  "syncing": true,
  "startingBlock": "0x0",
  "currentBlock": "0x1234",
  "highestBlock": "0x5678"
}
```

#### Get Peer Count
```bash
GET /api/v1/network/peers/count
```

Response:
```json
{
  "count": 8
}
```

### Block Queries

#### Get Current Block Number
```bash
GET /api/v1/blocks/number
```

Response:
```json
{
  "number": "0x1234"
}
```

#### Get Block by Number
```bash
GET /api/v1/blocks/{blockNumber}?fullTransactions={true|false}
```

Example:
```bash
curl http://127.0.0.1:10001/api/v1/blocks/0?fullTransactions=false
```

Response:
```json
{
  "hash": "0x1234...",
  "number": "0x0",
  "timestamp": "0x5f5e100",
  "transactions": ["0xabc...", "0xdef..."],
  "difficulty": "0x1000"
}
```

#### Get Latest Block
```bash
GET /api/v1/blocks/latest?fullTransactions={true|false}
```

### Account Queries

#### Get Wallet Accounts (with Pagination)
```bash
GET /api/v1/accounts?page={page}&size={size}
```

Parameters:
- `page`: Page number (default: 1)
- `size`: Items per page (default: 20, max: 100)

Example:
```bash
curl http://127.0.0.1:10001/api/v1/accounts?page=1&size=10
```

Response:
```json
{
  "data": [
    {
      "address": "4sgjhDFGjhk3JHk4JHGj3k4h5jk3h5jkh",
      "balance": "1000000000000000000"
    }
  ],
  "pagination": {
    "page": 1,
    "size": 10,
    "total": 42,
    "totalPages": 5
  }
}
```

#### Get Account Balance
```bash
GET /api/v1/accounts/{address}/balance
```

Response:
```json
{
  "address": "4sgjhDFGjhk3JHk4JHGj3k4h5jk3h5jkh",
  "balance": "1000000000000000000"
}
```

#### Get Account Nonce
```bash
GET /api/v1/accounts/{address}/nonce
```

Response:
```json
{
  "address": "4sgjhDFGjhk3JHk4JHGj3k4h5jk3h5jkh",
  "nonce": "0x5"
}
```

### Transaction Operations

#### Send Transaction
```bash
POST /api/v1/transactions/send
Content-Type: application/json

{
  "from": "4sgjhDFGjhk3JHk4JHGj3k4h5jk3h5jkh",
  "to": "5tghkDFGjhk3JHk4JHGj3k4h5jk3h5jkh",
  "amount": "1000000000000000000",
  "remark": "Payment for services"
}
```

Response:
```json
{
  "hash": "0x1234567890abcdef..."
}
```

#### Get Transaction by Hash
```bash
GET /api/v1/transactions/{hash}
```

Response:
```json
{
  "hash": "0x1234567890abcdef...",
  "from": "4sgjhDFGjhk3JHk4JHGj3k4h5jk3h5jkh",
  "to": "5tghkDFGjhk3JHk4JHGj3k4h5jk3h5jkh",
  "amount": "1000000000000000000",
  "blockNumber": "0x1234",
  "timestamp": "0x5f5e100",
  "status": "success"
}
```

## OpenAPI Specification

The complete API specification is available in OpenAPI 3.0 format:

```bash
curl http://127.0.0.1:10001/openapi.json
```

## Testing the API

### Using the Test Script

A test script is provided to verify all API endpoints:

```bash
bash test-rpc.sh
```

The script will test:
- Network information endpoints
- Block query endpoints
- Account query endpoints
- OpenAPI specification availability

### Manual Testing with curl

```bash
# Test protocol version
curl http://127.0.0.1:10001/api/v1/network/protocol

# Test with authentication
curl -H "Authorization: Bearer your-api-key" \
  http://127.0.0.1:10001/api/v1/network/chainId

# Test pagination
curl "http://127.0.0.1:10001/api/v1/accounts?page=1&size=20"
```

### Testing with Authentication

```bash
# Set your API key
API_KEY="your-api-key-here"

# Make authenticated request
curl -H "Authorization: Bearer $API_KEY" \
  http://127.0.0.1:10001/api/v1/accounts
```

## Error Handling

The API returns standard HTTP status codes:

- **200 OK**: Successful request
- **400 Bad Request**: Invalid request parameters
- **401 Unauthorized**: Missing or invalid API key
- **403 Forbidden**: Insufficient permissions
- **404 Not Found**: Resource not found
- **500 Internal Server Error**: Server error

Error Response Format:
```json
{
  "error": "Error message description",
  "code": "ERROR_CODE",
  "details": "Additional error details"
}
```

## Security Best Practices

### Production Deployment

1. **Enable HTTPS**: Always use HTTPS in production
   ```hocon
   rpc.http.https.enabled = true
   rpc.http.https.certFile = /path/to/cert.pem
   rpc.http.https.keyFile = /path/to/key.pem
   ```

2. **Enable Authentication**: Require API keys for all requests
   ```hocon
   rpc.http.auth.enabled = true
   rpc.http.auth.apiKeys = ["secure-key:WRITE"]
   ```

3. **Restrict Access**: Bind to localhost or use firewall rules
   ```hocon
   rpc.http.host = 127.0.0.1  # localhost only
   ```

4. **Use Strong API Keys**: Generate cryptographically secure API keys
   ```bash
   # Generate a secure API key
   openssl rand -hex 32
   ```

5. **Configure CORS Carefully**: Restrict origins in production
   ```hocon
   rpc.http.cors.origins = "https://yourdomain.com"
   ```

6. **Rate Limiting**: Consider implementing rate limiting at reverse proxy level (nginx, HAProxy)

### API Key Management

- Store API keys securely (environment variables, secrets manager)
- Rotate keys regularly
- Use READ permission for monitoring tools
- Use WRITE permission only for trusted services
- Never commit API keys to version control

## Troubleshooting

### API Not Starting

1. Check if HTTP API is enabled in config:
   ```hocon
   rpc.http.enabled = true
   ```

2. Check if port is already in use:
   ```bash
   lsof -i :10001
   ```

3. Check logs for errors:
   ```bash
   tail -f logs/xdag.log
   ```

### Authentication Failures

1. Verify API key format in config:
   ```hocon
   rpc.http.auth.apiKeys = ["key:PERMISSION"]
   ```

2. Check Authorization header format:
   ```
   Authorization: Bearer your-api-key
   ```

3. Verify permission level matches endpoint requirements

### HTTPS Issues

1. Verify certificate and key file paths exist
2. Check certificate validity:
   ```bash
   openssl x509 -in cert.pem -text -noout
   ```
3. Verify key matches certificate:
   ```bash
   openssl rsa -in key.pem -check
   ```

## Examples

### Complete Working Example

```bash
#!/bin/bash

# Configuration
API_URL="http://127.0.0.1:10001/api/v1"
API_KEY="your-api-key-here"

# Get chain ID
echo "Chain ID:"
curl -s -H "Authorization: Bearer $API_KEY" \
  "$API_URL/network/chainId" | jq .

# Get current block number
echo -e "\nCurrent Block:"
curl -s -H "Authorization: Bearer $API_KEY" \
  "$API_URL/blocks/number" | jq .

# Get accounts (first page)
echo -e "\nAccounts:"
curl -s -H "Authorization: Bearer $API_KEY" \
  "$API_URL/accounts?page=1&size=5" | jq .

# Get specific account balance
ADDRESS="4sgjhDFGjhk3JHk4JHGj3k4h5jk3h5jkh"
echo -e "\nAccount Balance:"
curl -s -H "Authorization: Bearer $API_KEY" \
  "$API_URL/accounts/$ADDRESS/balance" | jq .
```

## Additional Resources

- OpenAPI Specification: `http://127.0.0.1:10001/openapi.json`
- API Documentation UI: `http://127.0.0.1:10001/docs` (if available)
- Source Code: `src/main/java/io/xdag/http/`
- Configuration: `src/main/resources/*.conf`

## Support

For issues or questions:
- GitHub Issues: https://github.com/XDagger/xdagj/issues
- Documentation: https://github.com/XDagger/xdagj/wiki
