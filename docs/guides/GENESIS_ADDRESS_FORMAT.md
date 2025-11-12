# Genesis Configuration Address Format Update

## Summary

XDAG v5.1 now supports **base58check encoded addresses** in `genesis.json`, matching the standard XDAG address format used throughout the system.

## Changes

### Before (Legacy Format)
```json
{
  "genesisCoinbase": "0x0000000000000000000000000000000000000000000000000000000000000001",
  "alloc": {
    "0x0000000000000000000000000000000000000000000000000000000000000001": "10000000000000000000000",
    "0x0000000000000000000000000000000000000000000000000000000000000002": "5000000000000000000000"
  }
}
```

### After (Recommended Format)
```json
{
  "genesisCoinbase": "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
  "alloc": {
    "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2": "10000000000000000000000",
    "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa": "5000000000000000000000"
  }
}
```

## Supported Address Formats

### 1. Base58Check (Recommended)
- **Format**: Standard XDAG address like `1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa`
- **Length**: Variable (typically 33-35 characters)
- **Encoding**: Base58 with checksum
- **Generation**: `AddressUtils.toBase58Address(keyPair)`
- **Parsing**: `AddressUtils.fromBase58Address(address)`

### 2. 20-byte Hex (Alternative)
- **Format**: `0x` prefix + 40 hex characters
- **Example**: `0x89abcdefabcdefabcdefabcdefabcdefabcdefab`
- **Use Case**: Direct byte representation

### 3. 32-byte Hex (Legacy, Deprecated)
- **Format**: `0x` prefix + 64 hex characters
- **Example**: `0x000000000000000000000000000000000000000089abcdefabcdefabcdefabcdefabcdefabcdefab`
- **Note**: Bytes 0-11 are ignored, bytes 12-31 contain the actual 20-byte address
- **Status**: Supported for backward compatibility only

## Implementation Details

### Address Parsing Logic

The `GenesisConfig.parseAddress()` method handles all three formats:

```java
public static Bytes parseAddress(String address) throws AddressFormatException {
    if (address.startsWith("0x")) {
        String hex = address.substring(2);

        if (hex.length() == 64) {
            // Legacy 32-byte format - extract bytes 12-31
            Bytes32 bytes32 = Bytes32.fromHexString(hex);
            return bytes32.slice(12, 20);
        } else if (hex.length() == 40) {
            // 20-byte hex format
            return Bytes.fromHexString(hex);
        }
    }

    // Base58check format (standard)
    return AddressUtils.fromBase58Address(address);
}
```

### Validation

All addresses in `genesis.json` are validated during configuration load:

```java
genesisConfig.validate();
// - Checks address format
// - Verifies address decodes to exactly 20 bytes
// - Validates checksum (for base58check)
```

## Migration Guide

### Step 1: Generate Base58 Addresses

```java
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.AddressUtils;

// Generate a new key pair
ECKeyPair keyPair = ECKeyPair.generate();

// Get base58check address
String address = AddressUtils.toBase58Address(keyPair);
System.out.println("Address: " + address);
// Output: "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
```

### Step 2: Convert Existing Hex Addresses

If you have existing 32-byte hex addresses, you can convert them:

```java
// Old format (32-byte padded hex)
String oldAddress = "0x000000000000000000000000000000000000000089abcdefabcdefabcdefabcdefabcdefabcdefab";

// Extract 20-byte address (bytes 12-31)
Bytes32 bytes32 = Bytes32.fromHexString(oldAddress.substring(2));
Bytes address20 = bytes32.slice(12, 20);

// Convert to base58check
String newAddress = Base58.encodeCheck(address20);
System.out.println("New address: " + newAddress);
```

### Step 3: Update genesis.json

Replace all hex addresses with base58check format:

```bash
# Example for devnet
cp config/genesis-base58-example.json ./genesis.json
```

## Benefits

1. **User-Friendly**: Base58check addresses are shorter and include checksums
2. **Consistent**: Same format used throughout XDAG ecosystem
3. **Type-Safe**: Leverages `AddressUtils` from xdagj-crypto library
4. **Backward Compatible**: Legacy hex formats still supported

## References

- **AddressUtils**: `/Users/reymondtu/dev/github/xdagj-crypto/src/main/java/io/xdag/crypto/keys/AddressUtils.java`
- **GenesisConfig**: `src/main/java/io/xdag/config/GenesisConfig.java`
- **Example**: `config/genesis-base58-example.json`

## Testing

```bash
# Compile
mvn compile

# Run tests
mvn test

# Verify genesis loading
mvn test -Dtest=GenesisConfigTest
```

## Notes

- **genesisCoinbase is required**: All nodes must use the same coinbase for deterministic genesis
- **Address validation**: Invalid addresses will cause startup failure with clear error messages
- **Network compatibility**: Genesis hash depends on address bytes, not format
