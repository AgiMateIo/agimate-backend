# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build entire project
./gradlew build

# Run tests
./gradlew test

# Run a specific module's tests
./gradlew :user-api:test
./gradlew :mobile-api:test
./gradlew :connectors-api:test
./gradlew :libs:common:test

# Run a single test class
./gradlew :libs:common:test --tests "ru.agimate.common.util.UUIDUtilsTest"

# Run services locally
./gradlew :user-api:bootRun
./gradlew :mobile-api:bootRun
./gradlew :connectors-api:bootRun

# Create deployment JARs
./gradlew :user-api:bootJar
./gradlew :mobile-api:bootJar
./gradlew :connectors-api:bootJar
```

## Architecture

This is a Spring Boot 4.0 microservices backend with Java 21 (virtual threads enabled).

### Modules

- **libs/common** - Shared library containing exception hierarchy, REST response wrappers (`SuccessResponse`, `ErrorResponse`), base error handler, JWT/API Key utilities, and `UUIDUtils` (UUIDv8 generation)
- **user-api** - Authentication service (port 8080, context path `/`). Handles Google OAuth2, JWT access/refresh tokens, user management. Owns database migrations via Liquibase.
- **mobile-api** - Mobile device API (port 8080, context path `/`). Dual authentication: Device Auth Key for device endpoints + JWT for management endpoints. Owns separate database with migrations via Liquibase.
- **connectors-api** - External integrations service (port 8280, actuator 8288, context path `/connectors-api/`). Manages third-party connector definitions, credentials storage with encryption, method execution, and API key management. Dual authentication: API Key for connector calls + JWT for management endpoints. Owns separate database with migrations via Liquibase.

### Key Patterns

- **JWT Strategy**: Access tokens returned in response body, refresh tokens stored in HTTP-only cookies
- **Exception Handling**: All services extend `BaseErrorHandlerControllerAdvice` from common lib. Use status exceptions like `NotFoundStatusException`, `BadRequestStatusException` for HTTP error responses.
- **Response Wrappers**: Wrap successful responses with `SuccessResponse<T>`, errors with `ErrorResponse`
- **Entity IDs**: Use `UUIDUtils.generateUuidV8()` for public-facing entity identifiers (time-ordered, tamper-detectable)

### Database

**Core Database** - PostgreSQL (localhost:5432, database: `am_core_db`)
- Tables: `users`, `user_oauth_accounts`
- Migrations in `user-api/src/main/resources/db/changelog/`
- Owned by user-api

**Connectors Database** - PostgreSQL (localhost:5432, database: `am_connectors_db`)
- Tables: `connectors`, `credentials`, `connectors_api_keys`, `webhook_registrations`
- Migrations in `connectors-api/src/main/resources/db/changelog/`
- Owned by connectors-api

**Mobile Devices Database** - PostgreSQL (localhost:5432, database: `am_mobile_db`)
- Tables: `device_auth_keys`
- Migrations in `mobile-api/src/main/resources/db/changelog/`
- Owned by mobile-api

**Migration Best Practices**:
- Prefer `TEXT` type over `VARCHAR(n)` for string columns in PostgreSQL. PostgreSQL handles TEXT efficiently and avoids arbitrary length limits.
- Entity `@Column` definitions should match migration types: use `columnDefinition = "TEXT"` for TEXT columns.
- Exception: Use specific types like `UUID`, `TIMESTAMP`, `BOOLEAN`, `INTEGER`, `BIGINT` where appropriate.

### Environment Variables

```
JWT_PRIVATE_KEY             - Base64-encoded ECDSA P-256 private key in PKCS#8 format (all APIs)
JWT_PUBLIC_KEY              - Base64-encoded ECDSA P-256 public key in X.509 format (all APIs)
GOOGLE_CLIENT_ID            - OAuth2 client ID (user-api)
GOOGLE_CLIENT_SECRET        - OAuth2 client secret (user-api)
CONNECTORS_ENCRYPTION_KEY   - AES-256 encryption key for credentials (connectors-api, base64-encoded 32 bytes)
```

#### JWT Key Generation

The system uses ES256 (ECDSA with P-256 curve) for JWT signing. Generate keys using:

```bash
# Generate private key
openssl ecparam -name prime256v1 -genkey -noout -out ec-private-key.pem

# Extract public key
openssl ec -in ec-private-key.pem -pubout -out ec-public-key.pem

# Convert private key to PKCS#8 format
openssl pkcs8 -topk8 -nocrypt -in ec-private-key.pem -out ec-private-key-pkcs8.pem

# Convert to Base64 for environment variables (remove headers and newlines)
cat ec-private-key-pkcs8.pem | grep -v "BEGIN" | grep -v "END" | tr -d '\n' > JWT_PRIVATE_KEY.txt
cat ec-public-key.pem | grep -v "BEGIN" | grep -v "END" | tr -d '\n' > JWT_PUBLIC_KEY.txt
```

Set the contents of these files as environment variables.

### API Endpoints

#### Authentication Types

All services use one or more of these authentication methods:

- **JWT** - JSON Web Token authentication for web applications and user-specific management operations. Access token passed in `Authorization: Bearer <token>` header.
- **API Key** - Special format API key for executing connector methods. Generated and managed per user.
- **Device Auth** - Unique authentication key for each mobile device. Generated and managed per device.
- **None** - Public endpoints (health checks, OAuth2 callbacks, error pages)

#### User API (port 8080, context `/`)

**User Management (JWT)**
```
GET  /user/{pub_id}  - Get user by public ID (with access control)
GET  /user/me        - Get current authenticated user
```

**OAuth2 Authentication**
```
POST /oauth2/refresh  - Refresh access token (requires refresh token in cookie)
POST /oauth2/logout   - Logout (invalidates refresh token)
GET  /oauth2/error    - OAuth2 error handler
```

**Public**
```
GET  /               - Application uptime and build info
GET  /favicon.ico    - Returns empty favicon
```

#### Mobile API (port 8080, context `/`)

**Device Endpoints (Device Auth)**
```
GET  /device/actions/get    - Get pending actions for mobile device
GET  /device/actions/test   - Test endpoint - publishes test message to Centrifugo
POST /device/trigger/new    - Submit trigger from mobile device
POST /device/registration/link - Link device to auth key (stores deviceId, deviceName, deviceOs)
```

**Device Management (JWT)**
```
GET    /manage/devices/                  - List all device auth keys for current user
POST   /manage/devices/                  - Create new device auth key (value shown once)
GET    /manage/devices/{connectionId}    - Get specific device auth key
PUT    /manage/devices/{connectionId}    - Update device auth key
DELETE /manage/devices/{connectionId}    - Delete device auth key (soft delete)
POST   /manage/devices/{connectionId}/regenerate - Regenerate device auth key
```

**Public**
```
GET  /               - Application uptime and build info
GET  /favicon.ico    - Returns empty favicon
```

#### Connectors API (port 8280, context `/connectors-api/`)

**Connector & Credentials Info (API Key)**
```
GET  /api/connectors/                          - List available connectors for user (with credentials + mobile)
GET  /api/connectors/credentials/{code}/       - List available credentials for connector
GET  /api/connectors/methods/{connectorCode}/  - List available methods for connector (parsed from OpenAPI spec)
```

**Connector Method Execution (API Key)**
```
POST /api/connectors/call/ozon/getProductList       - Get Ozon product list with pagination
POST /api/connectors/call/ozon/getProductInfo       - Get Ozon product info by ID
POST /api/connectors/call/wildberries/getCards      - Get Wildberries product cards
GET  /api/connectors/call/wildberries/getOrders     - Get Wildberries new orders
```

**Mobile Device Integration (API Key)**
```
GET  /api/device/                   - Get connected devices for user
GET  /api/device/triggers/{deviceId}  - Get triggers for specific device
GET  /api/device/actions/{deviceId}   - Get actions for specific device
POST /api/device/call/{deviceAuthKeyId} - Push action to mobile device via Centrifugo
```

**Connector Management (JWT)**
```
GET  /manage/connectors/  - List all available connectors with metadata
```

**Credentials Management (JWT)**
```
GET    /manage/credentials/                          - Get credentials summary for all connectors
GET    /manage/credentials/{connectorCode}           - List credentials for specific connector
POST   /manage/credentials/{connectorCode}           - Create new credential
GET    /manage/credentials/{connectorCode}/{id}      - Get credential details
PUT    /manage/credentials/{connectorCode}/{id}      - Update credential
DELETE /manage/credentials/{connectorCode}/{id}      - Delete credential
```

**API Keys Management (JWT)**
```
GET    /manage/api-keys/            - List all API keys for current user
POST   /manage/api-keys/            - Create new API key (value shown once)
PUT    /manage/api-keys/{keyId}     - Update API key
DELETE /manage/api-keys/{keyId}     - Delete API key
```

**Webhooks Management (JWT)**
```
GET    /manage/webhooks/              - Get all webhook registrations (optional ?eventType filter)
GET    /manage/webhooks/{webhookId}   - Get webhook registration details
POST   /manage/webhooks               - Create new webhook registration
PUT    /manage/webhooks/{webhookId}   - Update webhook registration
DELETE /manage/webhooks/{webhookId}   - Delete webhook registration (soft delete)
```

**Public**
```
GET  /               - Application uptime and build info
GET  /favicon.ico    - Returns empty favicon
```

**Connector Architecture**:
- Each connector has its own dedicated CallController (e.g., `OzonCallController`, `WildberriesCallController`)
- Each controller provides typed endpoints with full OpenAPI annotations for code generation
- Credentials are encrypted using AES-256 before storage
- OpenAPI specification is generated at build-time and saved to `src/main/resources/static/openapi.json`
- ConnectorsApiController dynamically reads available methods from OpenAPI spec via `OpenApiMethodExtractor`
- Currently implemented: Ozon (2 methods), Wildberries (2 methods)

### Spring Profiles

- `local` - Development with debug logging, Swagger enabled
- `develop` - Development environment
- `test` - Test configuration

Swagger UI available at `/{context-path}/docs/ui` when enabled.

## OpenAPI Specification Generation

The connectors-api generates an OpenAPI 3.x specification at build-time for use with code generation tools (e.g., n8n, OpenAPI Generator).

### Generating OpenAPI Spec

```bash
# Generate OpenAPI specification and copy to resources
./gradlew :connectors-api:generateOpenApi

# Individual tasks:
./gradlew :connectors-api:generateOpenApiTest  # Run test that fetches spec
./gradlew :connectors-api:copyOpenApiSpec      # Copy spec to resources
```

The generated specification is saved to:
- Build output: `connectors-api/build/generated/openapi/openapi.json`
- Resources: `connectors-api/src/main/resources/static/openapi.json` (committed to git)

### OpenAPI Configuration

- Configured in `OpenApiConfig.java`
- Security schemes: API Key (header `X-API-Key`) and JWT (Bearer token)
- Endpoints grouped by: api-connectors, api-mobile, management
- Swagger UI enabled only in `local` profile at `/connectors-api/docs/ui`
- OpenAPI JSON available at `/connectors-api/docs/api` when enabled
- **ConnectorsApiController** reads from static file `static/openapi.json` (loaded at startup via `OpenApiMethodExtractor`)

### Adding New Connector

To add a new connector (e.g., YandexMarket):

1. **Create DTOs** in `controller/dto/yandexmarket/`:
   - Request DTOs with `@Schema` and Jakarta validation
   - Response DTOs with `result` and `durationMs` fields

2. **Create Service** `YandexMarketCallService.java`:
   - Manually create `ConnectorMethod` objects for each method
   - Implement business logic using `ConnectorClient.execute()`

3. **Create Controller** `YandexMarketCallController.java`:
   - Map endpoints under `/api/connectors/call/yandexmarket/`
   - Add full OpenAPI annotations (`@Operation`, `@ApiResponses`, etc.)
   - Use `@Tag` for grouping in OpenAPI spec

4. **Create Client** implementing `ConnectorClient` interface

5. **Update ConnectorController** to add required credential fields to `CONNECTOR_REQUIRED_FIELDS` map

6. **Generate OpenAPI**: Run `./gradlew :connectors-api:generateOpenApi`

7. **Commit** the updated `openapi.json` to git

**IMPORTANT**: The `openapi.json` file must be committed to git and included in the JAR. The MethodController reads this static file at runtime to provide method metadata via `/api/connectors/methods/{connectorCode}/` endpoint.

### Adding Method to Existing Connector

1. Create Request/Response DTOs with proper annotations
2. Add method to Service (e.g., `OzonCallService`)
3. Add endpoint to Controller with `@Operation` annotations
4. Run `./gradlew :connectors-api:generateOpenApi`
5. Method automatically appears in `/api/connectors/methods/{connectorCode}/`

### Endpoint URL Structure

All connector method endpoints follow the pattern:
```
/api/connectors/call/{connectorCode}/{methodName}
```

Examples:
- `/api/connectors/call/ozon/getProductList` (POST)
- `/api/connectors/call/ozon/getProductInfo` (POST)
- `/api/connectors/call/wildberries/getCards` (POST)
- `/api/connectors/call/wildberries/getOrders` (GET)

Mobile device endpoints follow the pattern:
```
/api/device/call/{deviceAuthKeyId}
```
