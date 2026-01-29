# Deployment

## Environment Variables

### JWT Keys (All Services)

| Variable          | Description                                     | Required      |
|-------------------|-------------------------------------------------|---------------|
| `JWT_PRIVATE_KEY` | Base64-encoded ECDSA P-256 private key (PKCS#8) | user-api only |
| `JWT_PUBLIC_KEY`  | Base64-encoded ECDSA P-256 public key (X.509)   | All services  |

### OAuth2 (user-api)

| Variable                       | Description                                       |
|--------------------------------|---------------------------------------------------|
| `GOOGLE_CLIENT_ID`             | Google OAuth2 client ID                           |
| `GOOGLE_CLIENT_SECRET`         | Google OAuth2 client secret                       |
| `YANDEX_CLIENT_ID`             | Yandex OAuth2 client ID                           |
| `YANDEX_CLIENT_SECRET`         | Yandex OAuth2 client secret                       |
| `OAUTH2_COOKIE_ENCRYPTION_KEY` | AES-256 key for OAuth2 cookies (Base64, 32 bytes) |

### Connectors (connectors-api)

| Variable                     | Description                                               |
|------------------------------|-----------------------------------------------------------|
| `CONNECTORS_ENCRYPTION_KEY`  | AES-256 key for credentials encryption (Base64, 32 bytes) |

### Centrifugo (mobile-api)

| Variable                | Description                            |
|-------------------------|----------------------------------------|
| `CENTRIFUGO_API_KEY`    | Centrifugo HTTP API key                |
| `CENTRIFUGO_PRIVATEKEY` | Centrifugo JWT signing private key     |
| `CENTRIFUGO_PUBLICKEY`  | Centrifugo JWT verification public key |

### Mobile API

| Variable           | Description                               |
|--------------------|-------------------------------------------|
| `MOBILE_API_KEY_1` | API key for mobile device authentication  |

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

| Port | Service    | Purpose                                   |
|------|------------|-------------------------------------------|
| 8080 | All        | HTTP API                                  |
| 8088 | All        | Management (health, metrics, prometheus)  |
| 9090 | mobile-api | gRPC server for intenral s2s interactions |

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
| mobile-api     | am_mobile_db     | `jdbc:postgresql://localhost:5432/am_mobile_db`     |
| connectors-api | am_connectors_db | `jdbc:postgresql://localhost:5432/am_connectors_db` |

Configure via standard Spring datasource properties or environment variables:
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## Building for Production

```bash
# Create deployment JARs
./gradlew :user-api:bootJar
./gradlew :mobile-api:bootJar
./gradlew :connectors-api:bootJar
```

JARs are created in `services/{service}/build/libs/`.
