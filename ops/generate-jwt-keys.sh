#!/bin/bash

# Generate ECDSA P-256 (ES256) key pair for JWT

set -e

# Create temporary directory
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# Generate private key
openssl ecparam -name prime256v1 -genkey -noout -out "$TEMP_DIR/ec-key.pem" 2>/dev/null

# Convert to PKCS#8 format
openssl pkcs8 -topk8 -nocrypt -in "$TEMP_DIR/ec-key.pem" -out "$TEMP_DIR/ec-private.pem" 2>/dev/null

# Extract public key
openssl ec -in "$TEMP_DIR/ec-key.pem" -pubout -out "$TEMP_DIR/ec-public.pem" 2>/dev/null

# Convert to Base64 (single line, no headers)
PRIVATE_KEY=$(grep -v 'BEGIN\|END' "$TEMP_DIR/ec-private.pem" | tr -d '\n')
PUBLIC_KEY=$(grep -v 'BEGIN\|END' "$TEMP_DIR/ec-public.pem" | tr -d '\n')

# Output
echo "JWT_PRIVATE_KEY=$PRIVATE_KEY"
echo "JWT_PUBLIC_KEY=$PUBLIC_KEY"