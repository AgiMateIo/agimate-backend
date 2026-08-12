#!/usr/bin/env bash
#
# Local development bootstrap.
#
# Generates every key the stack needs, stores them in services/.env and renders the configs that
# read from it: ops/centrifugo/config.yaml and one application-local.yaml per service. Three pairs
# of values have to match across processes — the JWT public key (user-api signs, control-api
# verifies), the Centrifugo key pair (control-api signs client tokens, Centrifugo verifies them)
# and the worker pool key (worker presents the full key, control-api stores its hash) — which is
# exactly what breaks when the keys are assembled by hand.
#
# Idempotent: values already in services/.env are kept and only the missing ones are generated, so
# a re-run repairs a half-configured checkout instead of rotating its keys.
#
#   ./dev-init.sh          # fill in what is missing; existing application-local.yaml files are kept
#   ./dev-init.sh --force  # also re-render the application-local.yaml files from services/.env
#   ./dev-init.sh --yes    # never ask, take every default

set -euo pipefail

OPS_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$OPS_DIR/.." && pwd)"
SERVICES_DIR="$ROOT_DIR/services"
TEMPLATES_DIR="$OPS_DIR/templates"
ENV_FILE="$SERVICES_DIR/.env"

FORCE=false
ASSUME_YES=false

for arg in "$@"; do
    case "$arg" in
        --force) FORCE=true ;;
        --yes|-y) ASSUME_YES=true ;;
        --help|-h)
            sed -n '3,22p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "Unknown option: $arg (try --help)" >&2
            exit 2
            ;;
    esac
done

for tool in openssl python3 awk fold; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Required tool not found: $tool" >&2; exit 1; }
done

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

# Every value stored in services/.env, in the order it is written out.
VARS="JWT_PRIVATEKEY JWT_PUBLICKEY
CENTRIFUGO_APIKEY CENTRIFUGO_PRIVATEKEY CENTRIFUGO_PUBLICKEY CENTRIFUGO_PUBLICURL
CENTRIFUGO_ADMIN_PASSWORD CENTRIFUGO_ADMIN_SECRET
APP_SECRETS_ENCRYPTION_KEY APP_OAUTH_COOKIE_ENCRYPTION_KEY APP_FILES_URL_SECRET
APP_OAUTH_COOKIE_DOMAIN APP_OAUTH_FRONTEND_REDIRECT_URL
APP_CONTENT_LANGUAGE APP_INTEGRATION_TELEGRAM_MODE APP_INTEGRATION_WEBHOOK_BASE_URL
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_ID
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_SECRET
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_VK_CLIENT_ID
WORKER_POOLS_AUTHKEYS_0 AGENT_GRPC_AUTHTOKEN"

value_of() {
    [ -f "$ENV_FILE" ] || return 0
    sed -n "s/^$1=//p" "$ENV_FILE" | head -1
}

for name in $VARS; do
    printf -v "$name" '%s' "$(value_of "$name")"
done

# --- import from an existing checkout --------------------------------------------------------------

# Flattens the indentation-based subset of YAML these files use into "path<TAB>value" lines.
# Enough for application-local.yaml, which is either rendered from our templates or written in the
# same shape by hand; anything it fails to read is simply generated fresh instead.
flatten_yaml() {
    [ -f "$1" ] || return 0
    python3 - "$1" <<'PY'
import re, sys

stack = []
for raw in open(sys.argv[1], encoding="utf-8"):
    line = raw.rstrip("\n")
    if not line.strip() or line.lstrip().startswith("#"):
        continue
    indent = len(line) - len(line.lstrip())
    body = line.strip()

    item = body.startswith("- ")
    if item:
        body = body[2:].strip()

    match = re.match(r'^([A-Za-z0-9_.-]+):\s*(.*)$', body)
    if not match and not item:
        continue

    while stack and stack[-1][0] >= indent:
        stack.pop()
    prefix = ".".join(part for _, part in stack)

    if match:
        key, value = match.group(1), match.group(2).strip()
        path = f"{prefix}.{key}" if prefix else key
        if value:
            print(path + "\t" + value.strip('"').strip("'"))
        else:
            stack.append((indent, key))
    else:
        # A bare list item: reported under the parent key, first one wins downstream.
        print(prefix + "\t" + body.strip('"').strip("'"))
PY
}

# Takes over a value from a pre-existing application-local.yaml so that a checkout configured
# before this script existed keeps working: rotating its keys would break the pairs silently.
import_value() {
    local name="$1" path="$2" file="$3" current value
    eval "current=\$$name"
    [ -z "$current" ] || return 0
    value="$(flatten_yaml "$file" | awk -F'\t' -v p="$path" '$1 == p { print $2; exit }')"
    [ -n "$value" ] || return 0
    printf -v "$name" '%s' "$value"
    IMPORTED=true
}

IMPORTED=false
if [ ! -f "$ENV_FILE" ]; then
    USER_API_LOCAL="$SERVICES_DIR/user-api/src/main/resources/application-local.yaml"
    CONTROL_API_LOCAL="$SERVICES_DIR/control-api/src/main/resources/application-local.yaml"
    WORKER_LOCAL="$SERVICES_DIR/agent-worker/src/main/resources/application-local.yaml"

    import_value JWT_PRIVATEKEY "jwt.privateKey" "$USER_API_LOCAL"
    import_value JWT_PUBLICKEY "jwt.publicKey" "$USER_API_LOCAL"
    import_value APP_OAUTH_COOKIE_ENCRYPTION_KEY "app.oauth.cookie-encryption-key" "$USER_API_LOCAL"
    import_value APP_OAUTH_COOKIE_DOMAIN "app.oauth.cookie-domain" "$USER_API_LOCAL"
    import_value APP_OAUTH_FRONTEND_REDIRECT_URL "app.oauth.frontend-redirect-url" "$USER_API_LOCAL"
    # The .env names are the Spring property paths themselves (relaxed binding), so each of these
    # is its own yaml path with the dots turned into underscores.
    import_value SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID \
        "spring.security.oauth2.client.registration.google.client-id" "$USER_API_LOCAL"
    import_value SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET \
        "spring.security.oauth2.client.registration.google.client-secret" "$USER_API_LOCAL"
    import_value SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_ID \
        "spring.security.oauth2.client.registration.yandex.client-id" "$USER_API_LOCAL"
    import_value SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_SECRET \
        "spring.security.oauth2.client.registration.yandex.client-secret" "$USER_API_LOCAL"
    import_value SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID \
        "spring.security.oauth2.client.registration.github.client-id" "$USER_API_LOCAL"
    import_value SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET \
        "spring.security.oauth2.client.registration.github.client-secret" "$USER_API_LOCAL"
    import_value SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_VK_CLIENT_ID \
        "spring.security.oauth2.client.registration.vk.client-id" "$USER_API_LOCAL"

    import_value CENTRIFUGO_APIKEY "centrifugo.api-key" "$CONTROL_API_LOCAL"
    import_value CENTRIFUGO_PRIVATEKEY "centrifugo.privateKey" "$CONTROL_API_LOCAL"
    import_value CENTRIFUGO_PUBLICKEY "centrifugo.publicKey" "$CONTROL_API_LOCAL"
    import_value CENTRIFUGO_PUBLICURL "centrifugo.public-url" "$CONTROL_API_LOCAL"
    import_value APP_SECRETS_ENCRYPTION_KEY "app.secrets.encryption-key" "$CONTROL_API_LOCAL"
    import_value APP_FILES_URL_SECRET "app.files.url-secret" "$CONTROL_API_LOCAL"
    import_value APP_CONTENT_LANGUAGE "app.content.language" "$CONTROL_API_LOCAL"
    import_value APP_INTEGRATION_TELEGRAM_MODE "app.integration.telegram.mode" "$CONTROL_API_LOCAL"
    import_value APP_INTEGRATION_WEBHOOK_BASE_URL "app.integration.webhook-base-url" "$CONTROL_API_LOCAL"
    import_value WORKER_POOLS_AUTHKEYS_0 "worker-pools.authkeys" "$CONTROL_API_LOCAL"

    import_value AGENT_GRPC_AUTHTOKEN "agent.grpc.auth-token" "$WORKER_LOCAL"
fi

# --- generators ---------------------------------------------------------------------------------

# Sets EC_PRIVATE / EC_PUBLIC to a fresh ES256 pair, base64 in a single line without PEM headers —
# the form Spring binds to the *PRIVATEKEY / *PUBLICKEY properties.
gen_ec_pair() {
    openssl ecparam -name prime256v1 -genkey -noout -out "$TEMP_DIR/ec-key.pem" 2>/dev/null
    openssl pkcs8 -topk8 -nocrypt -in "$TEMP_DIR/ec-key.pem" -out "$TEMP_DIR/ec-private.pem" 2>/dev/null
    openssl ec -in "$TEMP_DIR/ec-key.pem" -pubout -out "$TEMP_DIR/ec-public.pem" 2>/dev/null
    EC_PRIVATE="$(grep -v 'BEGIN\|END' "$TEMP_DIR/ec-private.pem" | tr -d '\n')"
    EC_PUBLIC="$(grep -v 'BEGIN\|END' "$TEMP_DIR/ec-public.pem" | tr -d '\n')"
}

# Sets WORKER_FULL_KEY / WORKER_AUTHKEY. Mirrors AppKeyUtils.generate + ParsedWorkerAuthkey.build:
# full key = prefix(4) + keyId(12) + base64url(secret32 || crc32), authkey = prefix + keyId + sha256(secret).
gen_worker_key() {
    local generated
    generated="$(python3 - <<'PY'
import base64, hashlib, os, struct, time, zlib

prefix = "wrkp"
key_id = base64.urlsafe_b64encode(struct.pack(">I", int(time.time())) + os.urandom(5)).decode().rstrip("=")
secret = os.urandom(32)
crc = zlib.crc32(prefix.encode() + key_id.encode() + secret) & 0xFFFFFFFF
payload = base64.urlsafe_b64encode(secret + struct.pack(">I", crc)).decode().rstrip("=")

print(prefix + key_id + payload)
print(prefix + key_id + hashlib.sha256(secret).hexdigest())
PY
)"
    WORKER_FULL_KEY="$(echo "$generated" | sed -n 1p)"
    WORKER_AUTHKEY="$(echo "$generated" | sed -n 2p)"
}

interactive() {
    [ "$ASSUME_YES" = false ] && [ -t 0 ]
}

ask() {
    local prompt="$1" default="$2" answer=""
    if ! interactive; then
        echo "$default"
        return
    fi
    printf '%s [%s]: ' "$prompt" "$default" > /dev/tty
    read -r answer < /dev/tty || answer=""
    echo "${answer:-$default}"
}

# --- values -------------------------------------------------------------------------------------

echo "Reading $ENV_FILE (existing values are kept)"
if [ "$IMPORTED" = true ]; then
    echo "  = imported values from the existing application-local.yaml files"
fi
echo

if [ -z "$JWT_PRIVATEKEY" ] || [ -z "$JWT_PUBLICKEY" ]; then
    gen_ec_pair
    JWT_PRIVATEKEY="$EC_PRIVATE"
    JWT_PUBLICKEY="$EC_PUBLIC"
    echo "  + JWT ES256 pair"
fi

if [ -z "$CENTRIFUGO_PRIVATEKEY" ] || [ -z "$CENTRIFUGO_PUBLICKEY" ]; then
    gen_ec_pair
    CENTRIFUGO_PRIVATEKEY="$EC_PRIVATE"
    CENTRIFUGO_PUBLICKEY="$EC_PUBLIC"
    echo "  + Centrifugo ES256 pair"
fi

[ -n "$CENTRIFUGO_APIKEY" ] || { CENTRIFUGO_APIKEY="$(openssl rand -hex 32)"; echo "  + Centrifugo API key"; }
[ -n "$CENTRIFUGO_ADMIN_PASSWORD" ] || CENTRIFUGO_ADMIN_PASSWORD="$(openssl rand -hex 16)"
[ -n "$CENTRIFUGO_ADMIN_SECRET" ] || CENTRIFUGO_ADMIN_SECRET="$(openssl rand -hex 32)"
[ -n "$APP_SECRETS_ENCRYPTION_KEY" ] || { APP_SECRETS_ENCRYPTION_KEY="$(openssl rand -base64 32)"; echo "  + secrets KEK"; }
[ -n "$APP_OAUTH_COOKIE_ENCRYPTION_KEY" ] || { APP_OAUTH_COOKIE_ENCRYPTION_KEY="$(openssl rand -base64 32)"; echo "  + OAuth cookie key"; }
[ -n "$APP_FILES_URL_SECRET" ] || { APP_FILES_URL_SECRET="$(openssl rand -base64 32)"; echo "  + file URL signing secret"; }

if [ -z "$WORKER_POOLS_AUTHKEYS_0" ] || [ -z "$AGENT_GRPC_AUTHTOKEN" ]; then
    gen_worker_key
    WORKER_POOLS_AUTHKEYS_0="$WORKER_AUTHKEY"
    AGENT_GRPC_AUTHTOKEN="$WORKER_FULL_KEY"
    echo "  + worker pool key"
fi

[ -n "$APP_OAUTH_COOKIE_DOMAIN" ] || APP_OAUTH_COOKIE_DOMAIN="agimate.lc"
[ -n "$APP_OAUTH_FRONTEND_REDIRECT_URL" ] || APP_OAUTH_FRONTEND_REDIRECT_URL="http://www.agimate.lc:8000/login"
[ -n "$APP_INTEGRATION_TELEGRAM_MODE" ] || APP_INTEGRATION_TELEGRAM_MODE="polling"
[ -n "$APP_INTEGRATION_WEBHOOK_BASE_URL" ] || APP_INTEGRATION_WEBHOOK_BASE_URL="http://localhost:8180/control"

echo

if [ -z "$APP_CONTENT_LANGUAGE" ]; then
    APP_CONTENT_LANGUAGE="$(ask "System content language of the installation (ru | en)" "ru")"
fi

if [ -z "$CENTRIFUGO_PUBLICURL" ]; then
    if interactive; then
        echo "WebSocket URL the browser connects to. Use the machine's LAN IP (ws://192.168.x.x:8000)"
        echo "to reach the stack from a phone — *.agimate.lc does not resolve there."
    fi
    CENTRIFUGO_PUBLICURL="$(ask "Centrifugo public URL" "ws://localhost:9000")"
fi

# A provider left empty is simply not offered: user-api drops the registration before Spring
# validates it, so the service starts either way and no fake credentials are needed.
OAUTH_REGISTRATION_PREFIX="SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION"

# Asks for the credentials of one provider, unless its id is already known.
# $1 — registration id in upper case, $2 — human name, $3 — "secret" when the provider needs one.
ask_oauth_provider() {
    local id_var="${OAUTH_REGISTRATION_PREFIX}_$1_CLIENT_ID"
    local secret_var="${OAUTH_REGISTRATION_PREFIX}_$1_CLIENT_SECRET"
    local current
    eval "current=\$$id_var"
    [ -n "$current" ] && return 0

    printf -v "$id_var" '%s' "$(ask "$2 client id (empty = provider not offered)" "")"
    eval "current=\$$id_var"
    if [ -n "$current" ] && [ "$3" = "secret" ]; then
        printf -v "$secret_var" '%s' "$(ask "$2 client secret" "")"
    fi
    [ -n "$current" ] || OAUTH_CONFIGURED=false
}

OAUTH_CONFIGURED=true
ask_oauth_provider GOOGLE "Google OAuth" secret
ask_oauth_provider YANDEX "Yandex OAuth" secret
ask_oauth_provider GITHUB "GitHub OAuth" secret
# VK ID authenticates the code exchange with PKCE and takes no secret at all.
ask_oauth_provider VK "VK ID app"

# --- services/.env ------------------------------------------------------------------------------

cat > "$ENV_FILE" <<EOF
# Generated by ops/dev-init.sh — local development values, do not reuse anywhere reachable.
# This file is the source of truth: ops/centrifugo/config.yaml and the three
# application-local.yaml files are rendered from it. Re-run the script after editing it.
# The full catalogue of supported variables (S3, file storage, gRPC TLS) is in .env.example.

# ES256 pair for user tokens: user-api signs, control-api verifies with the public half.
JWT_PRIVATEKEY=$JWT_PRIVATEKEY
JWT_PUBLICKEY=$JWT_PUBLICKEY

# Centrifugo. The public key also goes into ops/centrifugo/config.yaml, which verifies the
# client tokens control-api signs with the private half.
CENTRIFUGO_APIKEY=$CENTRIFUGO_APIKEY
CENTRIFUGO_PRIVATEKEY=$CENTRIFUGO_PRIVATEKEY
CENTRIFUGO_PUBLICKEY=$CENTRIFUGO_PUBLICKEY
CENTRIFUGO_PUBLICURL=$CENTRIFUGO_PUBLICURL
CENTRIFUGO_ADMIN_PASSWORD=$CENTRIFUGO_ADMIN_PASSWORD
CENTRIFUGO_ADMIN_SECRET=$CENTRIFUGO_ADMIN_SECRET

# Encryption keys (AES-256, base64 of 32 bytes) and the HMAC secret signing /files links.
APP_SECRETS_ENCRYPTION_KEY=$APP_SECRETS_ENCRYPTION_KEY
APP_OAUTH_COOKIE_ENCRYPTION_KEY=$APP_OAUTH_COOKIE_ENCRYPTION_KEY
APP_FILES_URL_SECRET=$APP_FILES_URL_SECRET

# OAuth2 providers for user-api. The names are the Spring property paths (relaxed binding), so
# nothing maps them by hand. Empty = the provider is not offered; the service starts without it.
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_ID=$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_ID
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_SECRET=$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_SECRET
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID=$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET=$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_VK_CLIENT_ID=$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_VK_CLIENT_ID
APP_OAUTH_COOKIE_DOMAIN=$APP_OAUTH_COOKIE_DOMAIN
APP_OAUTH_FRONTEND_REDIRECT_URL=$APP_OAUTH_FRONTEND_REDIRECT_URL

# Installation content language (ru | en) — seeds presets and skills from resources/seed/<lang>/.
APP_CONTENT_LANGUAGE=$APP_CONTENT_LANGUAGE

# Telegram: polling needs no public URL, webhook does.
APP_INTEGRATION_TELEGRAM_MODE=$APP_INTEGRATION_TELEGRAM_MODE
APP_INTEGRATION_WEBHOOK_BASE_URL=$APP_INTEGRATION_WEBHOOK_BASE_URL

# Worker pool key: control-api stores the hash (authkey), the worker presents the full key.
WORKER_POOLS_AUTHKEYS_0=$WORKER_POOLS_AUTHKEYS_0
AGENT_GRPC_AUTHTOKEN=$AGENT_GRPC_AUTHTOKEN
EOF

chmod 600 "$ENV_FILE"
echo "Wrote $ENV_FILE"

# --- rendering ----------------------------------------------------------------------------------

# Escapes a value for the replacement side of a sed s|| expression.
esc() {
    printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'
}

render() {
    local template="$1" target="$2"
    sed \
        -e "s|__JWT_PRIVATEKEY__|$(esc "$JWT_PRIVATEKEY")|g" \
        -e "s|__JWT_PUBLICKEY__|$(esc "$JWT_PUBLICKEY")|g" \
        -e "s|__CENTRIFUGO_APIKEY__|$(esc "$CENTRIFUGO_APIKEY")|g" \
        -e "s|__CENTRIFUGO_PRIVATEKEY__|$(esc "$CENTRIFUGO_PRIVATEKEY")|g" \
        -e "s|__CENTRIFUGO_PUBLICKEY__|$(esc "$CENTRIFUGO_PUBLICKEY")|g" \
        -e "s|__CENTRIFUGO_PUBLICURL__|$(esc "$CENTRIFUGO_PUBLICURL")|g" \
        -e "s|__CENTRIFUGO_ADMIN_PASSWORD__|$(esc "$CENTRIFUGO_ADMIN_PASSWORD")|g" \
        -e "s|__CENTRIFUGO_ADMIN_SECRET__|$(esc "$CENTRIFUGO_ADMIN_SECRET")|g" \
        -e "s|__APP_SECRETS_ENCRYPTION_KEY__|$(esc "$APP_SECRETS_ENCRYPTION_KEY")|g" \
        -e "s|__APP_OAUTH_COOKIE_ENCRYPTION_KEY__|$(esc "$APP_OAUTH_COOKIE_ENCRYPTION_KEY")|g" \
        -e "s|__APP_FILES_URL_SECRET__|$(esc "$APP_FILES_URL_SECRET")|g" \
        -e "s|__APP_OAUTH_COOKIE_DOMAIN__|$(esc "$APP_OAUTH_COOKIE_DOMAIN")|g" \
        -e "s|__APP_OAUTH_FRONTEND_REDIRECT_URL__|$(esc "$APP_OAUTH_FRONTEND_REDIRECT_URL")|g" \
        -e "s|__APP_CONTENT_LANGUAGE__|$(esc "$APP_CONTENT_LANGUAGE")|g" \
        -e "s|__APP_INTEGRATION_WEBHOOK_BASE_URL__|$(esc "$APP_INTEGRATION_WEBHOOK_BASE_URL")|g" \
        -e "s|__GOOGLE_CLIENT_ID__|$(esc "$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID")|g" \
        -e "s|__GOOGLE_CLIENT_SECRET__|$(esc "$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET")|g" \
        -e "s|__YANDEX_CLIENT_ID__|$(esc "$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_ID")|g" \
        -e "s|__YANDEX_CLIENT_SECRET__|$(esc "$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_SECRET")|g" \
        -e "s|__GITHUB_CLIENT_ID__|$(esc "$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID")|g" \
        -e "s|__GITHUB_CLIENT_SECRET__|$(esc "$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET")|g" \
        -e "s|__VK_CLIENT_ID__|$(esc "$SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_VK_CLIENT_ID")|g" \
        -e "s|__WORKER_POOLS_AUTHKEYS_0__|$(esc "$WORKER_POOLS_AUTHKEYS_0")|g" \
        -e "s|__AGENT_GRPC_AUTHTOKEN__|$(esc "$AGENT_GRPC_AUTHTOKEN")|g" \
        "$template" > "$target"
}

# Centrifugo needs the public key in PEM, so it is rebuilt from the base64 stored in .env and
# spliced in as an indented YAML block scalar.
{
    echo "-----BEGIN PUBLIC KEY-----"
    echo "$CENTRIFUGO_PUBLICKEY" | fold -w 64
    echo "-----END PUBLIC KEY-----"
} > "$TEMP_DIR/centrifugo-public.pem"

awk -v pemfile="$TEMP_DIR/centrifugo-public.pem" '
    /__CENTRIFUGO_PUBLIC_KEY_PEM__/ {
        match($0, /^ */)
        indent = substr($0, 1, RLENGTH)
        while ((getline line < pemfile) > 0) print indent line
        next
    }
    { print }
' "$TEMPLATES_DIR/centrifugo.config.yaml" > "$TEMP_DIR/centrifugo.config.yaml"

mkdir -p "$OPS_DIR/centrifugo"
render "$TEMP_DIR/centrifugo.config.yaml" "$OPS_DIR/centrifugo/config.yaml"
echo "Wrote $OPS_DIR/centrifugo/config.yaml"

for service in user-api control-api agent-worker; do
    target="$SERVICES_DIR/$service/src/main/resources/application-local.yaml"
    if [ -f "$target" ] && [ "$FORCE" = false ]; then
        echo "Kept  $target (exists; --force to re-render)"
        continue
    fi
    render "$TEMPLATES_DIR/$service.application-local.yaml" "$target"
    echo "Wrote $target"
done

echo
if [ "$OAUTH_CONFIGURED" = false ]; then
    echo "! Some OAuth providers were skipped — user-api starts without them, and their buttons"
    echo "  simply do not work. To add one later, fill its credentials into services/.env"
    echo "  (SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<PROVIDER>_CLIENT_ID) and re-run --force."
    echo
fi
echo "Next:"
echo "  cd $OPS_DIR && docker compose --profile infra up -d"
echo "  cd $SERVICES_DIR && ./gradlew :user-api:bootRun"
echo "  cd $SERVICES_DIR && ./gradlew :control-api:bootRun"
echo "  cd $SERVICES_DIR && ./gradlew :agent-worker:bootRun"
