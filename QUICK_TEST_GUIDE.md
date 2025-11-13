# HTTP API Quick Test Guide

## Prerequisites

- XDAG node compiled: `mvn clean package -DskipTests`
- Configuration file updated with HTTP API settings
- Port 10001 available (or your configured port)

## Quick Start

### 1. Start a Test Node

```bash
cd test-nodes/node1
bash start.sh
```

Wait for the node to start (check logs):
```bash
tail -f node1.log
```

Look for:
```
HTTP API server started successfully
```

### 2. Test HTTP API Endpoints

Run the automated test script:
```bash
cd ../..
bash test-rpc.sh
```

### 3. Manual Testing

```bash
# Test basic endpoints
curl http://127.0.0.1:10001/api/v1/network/protocol
curl http://127.0.0.1:10001/api/v1/network/chainId
curl http://127.0.0.1:10001/api/v1/blocks/number
curl http://127.0.0.1:10001/api/v1/accounts

# Test OpenAPI spec
curl http://127.0.0.1:10001/openapi.json
```

### 4. Test with Authentication (Optional)

If you enabled authentication in config:

```bash
# Edit test-nodes/node1/xdag-devnet.conf
rpc.http.auth.enabled = true
rpc.http.auth.apiKeys = ["test-key-123:WRITE"]

# Restart node
bash stop.sh
bash start.sh

# Test with API key
curl -H "Authorization: Bearer test-key-123" \
  http://127.0.0.1:10001/api/v1/accounts
```

### 5. Test Pagination

```bash
# Get first page (10 items)
curl "http://127.0.0.1:10001/api/v1/accounts?page=1&size=10"

# Get second page
curl "http://127.0.0.1:10001/api/v1/accounts?page=2&size=10"
```

### 6. Stop the Node

```bash
cd test-nodes/node1
bash stop.sh
```

## Expected Test Results

### Successful Output

```
==========================================================
XDAG HTTP API Test Suite
Testing RESTful API Endpoints
==========================================================

========================================
RESTful API Tests
========================================

--- Network Information ---
Test 1: Get protocol version... PASSED
Test 2: Get chain ID... PASSED
Test 3: Get coinbase address... PASSED
Test 4: Get sync status... PASSED
Test 5: Get peer count... PASSED

--- Block Queries ---
Test 6: Get current block number... PASSED
Test 7: Get latest block... PASSED
Test 8: Get genesis block... PASSED

--- Account Queries ---
Test 9: Get wallet accounts... PASSED

--- Documentation Endpoints ---
Test 10: OpenAPI spec availability... PASSED

==========================================================
Test Summary
==========================================================
Total Tests: 10
Passed: 10
Failed: 0
✓ All tests passed!

Available endpoints:
  - RESTful API:  http://127.0.0.1:10001/api/v1/
  - OpenAPI Spec: http://127.0.0.1:10001/openapi.json
  - API Docs:     http://127.0.0.1:10001/docs
```

## Troubleshooting

### Node Fails to Start

Check the log file:
```bash
cat test-nodes/node1/node1.log
```

Common issues:
- Port already in use: `lsof -i :10001`
- Java not found: Check `JAVA_HOME`
- Missing dependencies: `mvn dependency:resolve`

### HTTP API Not Responding

1. Verify API is enabled:
```bash
grep "rpc.http.enabled" test-nodes/node1/xdag-devnet.conf
```

2. Check if server started:
```bash
grep "HTTP API server" test-nodes/node1/node1.log
```

3. Verify port:
```bash
netstat -an | grep 10001
```

### Authentication Errors

1. Check API key format in config (must be "key:PERMISSION")
2. Verify Authorization header: `Authorization: Bearer your-key`
3. Check permission level matches endpoint requirements

## Testing HTTPS (Advanced)

### 1. Generate Self-Signed Certificate

```bash
# Generate private key
openssl genrsa -out key.pem 2048

# Generate certificate
openssl req -new -x509 -key key.pem -out cert.pem -days 365

# Move to config directory
mv cert.pem key.pem test-nodes/node1/
```

### 2. Update Configuration

```bash
# Edit test-nodes/node1/xdag-devnet.conf
rpc.http.https.enabled = true
rpc.http.https.certFile = cert.pem
rpc.http.https.keyFile = key.pem
```

### 3. Test HTTPS Endpoint

```bash
# Restart node
cd test-nodes/node1
bash stop.sh && bash start.sh

# Test with curl (ignore self-signed cert warning)
curl -k https://127.0.0.1:10001/api/v1/network/protocol
```

## Performance Testing

### Load Test with curl

```bash
# Simple load test (100 requests)
for i in {1..100}; do
  curl -s http://127.0.0.1:10001/api/v1/network/protocol > /dev/null &
done
wait
echo "Load test completed"
```

### Monitor Response Times

```bash
# Measure response time
time curl http://127.0.0.1:10001/api/v1/blocks/latest
```

## Next Steps

1. Read the complete [HTTP API Guide](HTTP_API_GUIDE.md)
2. Configure HTTPS for production
3. Set up API key authentication
4. Integrate with your application
5. Monitor API performance and logs

## Notes

- Default port: 10001 (configurable)
- Default host: 127.0.0.1 (localhost only)
- Logs location: `test-nodes/node1/node1.log`
- PID file: `test-nodes/node1/xdag.pid`
