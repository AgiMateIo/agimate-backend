# Deployment

## Environment Variables

### JWT Keys (All Services)

| Variable          | Description                                     | Required      |
|-------------------|-------------------------------------------------|---------------|
| `JWT_PRIVATE_KEY` | Base64-encoded ECDSA P-256 private key (PKCS#8) | user-api only |
| `JWT_PUBLIC_KEY`  | Base64-encoded ECDSA P-256 public key (X.509)   | All services  |

### OAuth2 (user-api)

| Variable                          | Description                                          |
|-----------------------------------|------------------------------------------------------|
| `GOOGLE_CLIENT_ID`                | Google OAuth2 client ID                              |
| `GOOGLE_CLIENT_SECRET`            | Google OAuth2 client secret                          |
| `YANDEX_CLIENT_ID`                | Yandex OAuth2 client ID                              |
| `YANDEX_CLIENT_SECRET`            | Yandex OAuth2 client secret                          |
| `OAUTH2_COOKIE_ENCRYPTION_KEY`    | AES-256 key for OAuth2 cookies (Base64, 32 bytes)    |
| `APP_OAUTH_COOKIE_DOMAIN`         | Default cookie domain for refresh tokens             |
| `APP_OAUTH_COOKIE_SECURE`         | `true` for production (HTTPS)                        |
| `APP_OAUTH_FRONTEND_REDIRECT_URL` | Default frontend redirect URL after OAuth2 login     |
| `APP_OAUTH_ALLOWED_REDIRECT_URLS` | Comma-separated whitelist for multi-domain redirects |

### Connectors (connectors-api)

| Variable                     | Description                                               |
|------------------------------|-----------------------------------------------------------|
| `CONNECTORS_ENCRYPTION_KEY`  | AES-256 key for credentials encryption (Base64, 32 bytes) |

### Centrifugo (device-api)

| Variable                | Description                            |
|-------------------------|----------------------------------------|
| `CENTRIFUGO_API_KEY`    | Centrifugo HTTP API key                |
| `CENTRIFUGO_PRIVATEKEY` | Centrifugo JWT signing private key     |
| `CENTRIFUGO_PUBLICKEY`  | Centrifugo JWT verification public key |

## Key Generation

### JWT Keys (ES256)

Use ops/generate-jwt-keys.sh

### AES-256 Keys (Encryption)

```bash
# Generate random 32-byte key, Base64 encoded
openssl rand -base64 32
```

Use for:
- `OAUTH2_COOKIE_ENCRYPTION_KEY`
- `CONNECTORS_ENCRYPTION_KEY`

### Centrifugo Keys

Centrifugo uses the same ES256 key format as JWT. Generate using the JWT key generation steps above.

## Ports

| Port | Service              | Purpose                                   |
|------|----------------------|-------------------------------------------|
| 8080 | All                  | HTTP API                                  |
| 8088 | All                  | Management (health, metrics, prometheus)  |
| 9090 | All                  | gRPC server for internal s2s interactions |

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
| device-api     | am_device_db     | `jdbc:postgresql://localhost:5432/am_device_db`     |
| connectors-api | am_connectors_db | `jdbc:postgresql://localhost:5432/am_connectors_db` |

Configure via standard Spring datasource properties or environment variables:
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## Building for Production

```bash
# Create deployment JARs
./gradlew :user-api:bootJar
./gradlew :device-api:bootJar
./gradlew :connectors-api:bootJar
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

The `device` namespace is configured for device-related events:
- Pattern: `device:{deviceId}:(actions|triggers)`
- Publish/Subscribe restricted to server-side only
- History: 100 messages, 24h TTL

### Production Deployment

For production, ensure:
1. Generate new keys (do not use development keys)
2. Set `CENTRIFUGO_API_KEY` in device-api to match `http_api.key`
3. Set `CENTRIFUGO_PUBLICKEY` in device-api to match `client.token.ecdsa_public_key`
4. Configure `allowed_origins` appropriately
5. Disable admin UI or use strong credentials
