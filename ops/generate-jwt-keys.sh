#!/bin/bash

# Generate an ECDSA P-256 (ES256) key pair.
#
# The stack needs TWO independent pairs — run this script once per purpose:
#
#   1. User JWT      → JWT_PRIVATEKEY / JWT_PUBLICKEY in services/.env
#   2. Centrifugo    → CENTRIFUGO_PRIVATEKEY / CENTRIFUGO_PUBLICKEY in services/.env,
#                      and the PEM public key into client.token.ecdsa_public_key
#                      in ops/centrifugo/config.json (control-api signs the client
#                      tokens, Centrifugo verifies them with that key)

set -e

TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

openssl ecparam -name prime256v1 -genkey -noout -out "$TEMP_DIR/ec-key.pem" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$TEMP_DIR/ec-key.pem" -out "$TEMP_DIR/ec-private.pem" 2>/dev/null
openssl ec -in "$TEMP_DIR/ec-key.pem" -pubout -out "$TEMP_DIR/ec-public.pem" 2>/dev/null

# Base64, single line, no PEM headers — the form Spring binds to the *PRIVATEKEY/*PUBLICKEY properties.
PRIVATE_KEY=$(grep -v 'BEGIN\|END' "$TEMP_DIR/ec-private.pem" | tr -d '\n')
PUBLIC_KEY=$(grep -v 'BEGIN\|END' "$TEMP_DIR/ec-public.pem" | tr -d '\n')

echo "PRIVATE_KEY=$PRIVATE_KEY"
echo "PUBLIC_KEY=$PUBLIC_KEY"
echo
echo "# same public key in PEM form (one line, \\n escaped) — only needed for"
echo "# client.token.ecdsa_public_key in ops/centrifugo/config.json:"
awk '{printf "%s\\n", $0}' "$TEMP_DIR/ec-public.pem"
echo
