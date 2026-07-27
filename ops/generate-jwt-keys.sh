#!/bin/bash

# Generate an ECDSA P-256 (ES256) key pair for JWT signing.
#
# The two base64 values go into services/.env; the PEM public key additionally goes into
# ops/centrifugo/config.json — Centrifugo verifies the client tokens that control-api signs
# with the same key pair.

set -e

TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

openssl ecparam -name prime256v1 -genkey -noout -out "$TEMP_DIR/ec-key.pem" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$TEMP_DIR/ec-key.pem" -out "$TEMP_DIR/ec-private.pem" 2>/dev/null
openssl ec -in "$TEMP_DIR/ec-key.pem" -pubout -out "$TEMP_DIR/ec-public.pem" 2>/dev/null

# Base64, single line, no PEM headers — the form Spring binds to jwt.privateKey / jwt.publicKey.
PRIVATE_KEY=$(grep -v 'BEGIN\|END' "$TEMP_DIR/ec-private.pem" | tr -d '\n')
PUBLIC_KEY=$(grep -v 'BEGIN\|END' "$TEMP_DIR/ec-public.pem" | tr -d '\n')

# Variable names follow Spring relaxed binding for jwt.privateKey — no underscore inside.
echo "# --> services/.env"
echo "JWT_PRIVATEKEY=$PRIVATE_KEY"
echo "JWT_PUBLICKEY=$PUBLIC_KEY"
echo
echo "# --> ops/centrifugo/config.json, client.token.ecdsa_public_key (one line, \\n escaped)"
echo "$(awk '{printf "%s\\n", $0}' "$TEMP_DIR/ec-public.pem")"
