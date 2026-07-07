# Deployment

## Environment Variables

### JWT Keys (All Services)

| Variable          | Description                                     | Required      |
|-------------------|-------------------------------------------------|---------------|
| `JWT_PRIVATEKEY`  | Base64-encoded ECDSA P-256 private key (PKCS#8) | user-api only |
| `JWT_PUBLICKEY`   | Base64-encoded ECDSA P-256 public key (X.509)   | All services  |

### OAuth2 (user-api)

| Variable                          | Description                                          |
|-----------------------------------|------------------------------------------------------|
| `GOOGLE_CLIENT_ID`                | Google OAuth2 client ID                              |
| `GOOGLE_CLIENT_SECRET`            | Google OAuth2 client secret                          |
| `YANDEX_CLIENT_ID`                | Yandex OAuth2 client ID                              |
| `YANDEX_CLIENT_SECRET`            | Yandex OAuth2 client secret                          |
| `APP_OAUTH_COOKIE_ENCRYPTION_KEY` | AES-256 key for OAuth2 cookies (Base64, 32 bytes)    |
| `APP_OAUTH_COOKIE_DOMAIN`         | Default cookie domain for refresh tokens             |
| `APP_OAUTH_COOKIE_SECURE`         | `true` for production (HTTPS)                        |
| `APP_OAUTH_FRONTEND_REDIRECT_URL` | Default frontend redirect URL after OAuth2 login     |
| `APP_OAUTH_ALLOWED_REDIRECT_URLS` | Comma-separated whitelist for multi-domain redirects |

### Centrifugo (control-api)

| Variable                | Description                            |
|-------------------------|----------------------------------------|
| `CENTRIFUGO_APIKEY`     | Centrifugo HTTP API key                |
| `CENTRIFUGO_PRIVATEKEY` | Centrifugo JWT signing private key     |
| `CENTRIFUGO_PUBLICKEY`  | Centrifugo JWT verification public key |

### Generic Worker gRPC (control-api)

| Variable                                | Description                                                       |
|-----------------------------------------|-------------------------------------------------------------------|
| `GRPC_SERVER_ENABLED`                   | Enable gRPC server (`true`/`false`)                               |
| `GRPC_SERVER_PORT`                      | gRPC server port (default `9091`)                                 |
| `GRPC_SERVER_SECURITY_ENABLED`          | Enable TLS (must be `true` in production)                         |
| `GRPC_SERVER_SECURITY_CERTIFICATECHAIN` | Path to PEM certificate chain                                     |
| `GRPC_SERVER_SECURITY_PRIVATEKEY`       | Path to PEM private key                                           |
| `WORKER_POOLS_AUTHKEYS_0`, ..._N        | Worker pool authkeys, one per pool. See `control-api-grpc-worker`. |

### DBOS (control-api)

| Variable                        | Description                                                    |
|---------------------------------|----------------------------------------------------------------|
| `DBOS_ENABLED`                  | Enable DBOS delivery of agent runs (`true`/`false`)            |
| `DBOS_SYSTEM_DATABASE_URL`      | JDBC URL of the DBOS system Postgres (shared with agent-worker) |
| `DBOS_SYSTEM_DATABASE_USERNAME` | DBOS Postgres user                                             |
| `DBOS_SYSTEM_DATABASE_PASSWORD` | DBOS Postgres password                                         |
| `DBOS_SYSTEM_DATABASE_SCHEMA`   | DBOS schema (default `dbos`)                                   |

### agent-worker

Full list with defaults: `services/agent-worker/.env.example`.

| Variable                          | Description                                                        |
|-----------------------------------|--------------------------------------------------------------------|
| `AGENT_GRPC_TARGET`               | control-api worker gRPC endpoint (default `localhost:9091`)        |
| `AGENT_GRPC_USE_TLS`              | Enable TLS to control-api (`true` in production)                   |
| `AGENT_GRPC_CA_CERT`              | PEM CA cert path for TLS verification (optional)                   |
| `AGENT_GRPC_AUTH_TOKEN`           | Worker-pool authkey (Bearer), must match a control-api pool key    |
| `AGENT_AGENT_ID`                  | Worker/agent deployment id                                         |
| `AGENT_AGENT_WORKFLOW_ID`         | Workflow id sent on AgentContext RPCs                              |
| `AGENT_CONCURRENCY_AGENT_RUNS`    | Concurrent agent runs per worker (default 3)                       |
| `AGENT_CONCURRENCY_LLM`           | Concurrent model requests per worker (default 3)                   |
| `AGENT_CONCURRENCY_TOOL`          | Concurrent backend tool calls per worker (default 8)               |
| `AGENT_SESSION_ON_ACTIVE_MESSAGE` | Policy on a message into an active session: `queue`/`steer`/`interrupt` |
| `AGENT_DBOS_DATABASE_URL`         | JDBC URL of the DBOS system Postgres (same as control-api's)       |
| `AGENT_DBOS_USERNAME`             | DBOS Postgres user                                                 |
| `AGENT_DBOS_PASSWORD`             | DBOS Postgres password                                             |
| `AGENT_DBOS_SCHEMA`               | DBOS schema (default `dbos`)                                       |

## Key Generation

### JWT Keys (ES256)

Use ops/generate-jwt-keys.sh

### AES-256 Keys (Encryption)

```bash
# Generate random 32-byte key, Base64 encoded
openssl rand -base64 32
```

Use for:
- `APP_OAUTH_COOKIE_ENCRYPTION_KEY`

### Centrifugo Keys

Centrifugo uses the same ES256 key format as JWT. Generate using the JWT key generation steps above.

## Ports

| Port | Service              | Purpose                                       |
|------|----------------------|-----------------------------------------------|
| 8080 | All                  | HTTP API                                      |
| 8088 | All                  | Management (health, metrics, prometheus)      |
| 9090 | user-api             | gRPC server for internal s2s interactions     |
| 9091 | control-api           | gRPC server for Generic Worker protocol (TLS) |

agent-worker exposes no ports (headless, non-web): it consumes DBOS queues from Postgres and dials out to control-api :9091.

## Spring Profiles

| Profile   | Description                                     |
|-----------|-------------------------------------------------|
| `local`   | Development with debug logging, Swagger enabled |
| `develop` | Development environment                         |
| `test`    | Test configuration                              |

Swagger UI available at `/{context-path}/docs/ui` when enabled (local profile).

## Database Configuration

Each service connects to its own PostgreSQL database:

| Service        | Database         | Default URL                                         |
|----------------|------------------|-----------------------------------------------------|
| user-api       | am_user_db       | `jdbc:postgresql://localhost:5432/am_user_db`       |
| control-api     | am_control_db     | `jdbc:postgresql://localhost:5432/am_control_db`     |

Configure via standard Spring datasource properties or environment variables:
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## Building for Production

```bash
# Create deployment JARs
./gradlew :user-api:bootJar
./gradlew :control-api:bootJar
./gradlew :agent-worker:bootJar
```

JARs are created in `services/{service}/build/libs/`.

## Running Centrifugo

Centrifugo is used for real-time messaging to devices.

### Local Development

Start Centrifugo using docker-compose:

```bash
cd ops/local
docker-compose -f docker-compose-centrifugo.yaml up -d
```

Configuration file: `ops/local/config.json`

### Ports

| Port | Purpose                |
|------|------------------------|
| 9000 | WebSocket/HTTP API     |

Admin UI available at `http://localhost:9000` (credentials in config.json).

### Configuration

Key configuration options in `config.json`:

| Field                             | Description                                 |
|-----------------------------------|---------------------------------------------|
| `client.token.ecdsa_public_key`   | Public key for verifying client JWT tokens  |
| `http_api.key`                    | API key for server-to-server communication  |
| `admin.password`                  | Admin UI password                           |
| `channel.namespaces`              | Channel namespace configuration             |

### Channel Namespaces

| Namespace | Pattern                                    | Description                        |
|-----------|--------------------------------------------|------------------------------------|
| `device`  | `device:{deviceId}:(actions\|triggers)`    | Device-related events              |
| `agent`   | `agent:{apiKeyPubId}`                      | Agent events (tool results, triggers) |

- Publish/Subscribe restricted to server-side only
- History: 100 messages, 24h TTL

### Production Deployment

For production, ensure:
1. Generate new keys (do not use development keys)
2. Set `CENTRIFUGO_APIKEY` in control-api to match `http_api.key`
3. Set `CENTRIFUGO_PUBLICKEY` in control-api to match `client.token.ecdsa_public_key`
4. Configure `allowed_origins` appropriately
5. Disable admin UI or use strong credentials
