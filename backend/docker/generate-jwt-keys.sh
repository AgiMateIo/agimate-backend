#!/bin/bash

# JWT Key Generation Script for Agimate Backend
# This script generates ES256 (ECDSA P-256) keys for JWT signing

set -e

echo "Generating JWT keys (ES256 - ECDSA P-256)..."
echo ""

# Create temporary directory
TMP_DIR=$(mktemp -d)
cd "$TMP_DIR"

# Generate private key
echo "1. Generating private key..."
openssl ecparam -name prime256v1 -genkey -noout -out ec-private-key.pem

# Extract public key
echo "2. Extracting public key..."
openssl ec -in ec-private-key.pem -pubout -out ec-public-key.pem 2>/dev/null

# Convert private key to PKCS#8 format
echo "3. Converting private key to PKCS#8 format..."
openssl pkcs8 -topk8 -nocrypt -in ec-private-key.pem -out ec-private-key-pkcs8.pem

# Convert to Base64 (remove headers and newlines)
echo "4. Converting to Base64 format..."
JWT_PRIVATE_KEY=$(cat ec-private-key-pkcs8.pem | grep -v "BEGIN" | grep -v "END" | tr -d '\n')
JWT_PUBLIC_KEY=$(cat ec-public-key.pem | grep -v "BEGIN" | grep -v "END" | tr -d '\n')

# Display results
echo ""
echo "JWT Keys Generated Successfully!"
echo "=================================================="
echo ""
echo "JWT_PRIVATE_KEY=$JWT_PRIVATE_KEY"
echo ""
echo "JWT_PUBLIC_KEY=$JWT_PUBLIC_KEY"
echo ""
echo "=================================================="


echo ""
echo "Temporary directory: $TMP_DIR"
echo "To delete: rm -rf $TMP_DIR"
echo ""
