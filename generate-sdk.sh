#!/bin/bash

# XDAG SDK Generator Script
# Generates client SDKs for multiple programming languages from OpenAPI spec

set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Configuration
OPENAPI_SPEC_URL="http://localhost:10001/openapi.json"
OPENAPI_SPEC_FILE="../docs/api/openapi.yaml"
SDK_OUTPUT_DIR="./sdk"

echo "========================================"
echo "XDAG Client SDK Generator"
echo "========================================"
echo ""

# Check if OpenAPI Generator is installed
if ! command -v openapi-generator-cli &> /dev/null; then
    echo -e "${YELLOW}OpenAPI Generator CLI not found. Installing...${NC}"
    echo ""
    echo "Please install OpenAPI Generator CLI first:"
    echo "  npm install @openapitools/openapi-generator-cli -g"
    echo "  OR"
    echo "  brew install openapi-generator"
    exit 1
fi

# Create SDK output directory
mkdir -p "$SDK_OUTPUT_DIR"

# Function to generate SDK
generate_sdk() {
    local lang=$1
    local generator=$2
    local package_name=$3
    local output_dir="$SDK_OUTPUT_DIR/$lang"

    echo -e "${GREEN}Generating $lang SDK...${NC}"

    openapi-generator-cli generate \
        -i "$OPENAPI_SPEC_FILE" \
        -g "$generator" \
        -o "$output_dir" \
        --additional-properties="$package_name" \
        --skip-validate-spec

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ $lang SDK generated successfully at: $output_dir${NC}"
    else
        echo -e "${RED}✗ Failed to generate $lang SDK${NC}"
    fi
    echo ""
}

# Menu
echo "Select SDKs to generate:"
echo "1) All SDKs"
echo "2) Java SDK"
echo "3) Python SDK"
echo "4) TypeScript SDK"
echo "5) Go SDK"
echo "6) Rust SDK"
echo "7) C# SDK"
echo ""
read -p "Enter choice [1-7]: " choice

case $choice in
    1)
        echo -e "${YELLOW}Generating all SDKs...${NC}"
        echo ""
        generate_sdk "java" "java" "groupId=io.xdag,artifactId=xdag-sdk,artifactVersion=5.1.0"
        generate_sdk "python" "python" "packageName=xdag_sdk,packageVersion=5.1.0"
        generate_sdk "typescript" "typescript-axios" "npmName=xdag-sdk,npmVersion=5.1.0"
        generate_sdk "go" "go" "packageName=xdag"
        generate_sdk "rust" "rust" "packageName=xdag"
        generate_sdk "csharp" "csharp-netcore" "packageName=Xdag.Sdk,packageVersion=5.1.0"
        ;;
    2)
        generate_sdk "java" "java" "groupId=io.xdag,artifactId=xdag-sdk,artifactVersion=5.1.0"
        ;;
    3)
        generate_sdk "python" "python" "packageName=xdag_sdk,packageVersion=5.1.0"
        ;;
    4)
        generate_sdk "typescript" "typescript-axios" "npmName=xdag-sdk,npmVersion=5.1.0"
        ;;
    5)
        generate_sdk "go" "go" "packageName=xdag"
        ;;
    6)
        generate_sdk "rust" "rust" "packageName=xdag"
        ;;
    7)
        generate_sdk "csharp" "csharp-netcore" "packageName=Xdag.Sdk,packageVersion=5.1.0"
        ;;
    *)
        echo -e "${RED}Invalid choice${NC}"
        exit 1
        ;;
esac

echo ""
echo "========================================"
echo -e "${GREEN}SDK Generation Complete!${NC}"
echo "========================================"
echo ""
echo "Generated SDKs are available in: $SDK_OUTPUT_DIR"
echo ""
echo "Next steps:"
echo "  1. Review generated code in $SDK_OUTPUT_DIR"
echo "  2. Run tests for each SDK"
echo "  3. Publish to package repositories"
echo ""
