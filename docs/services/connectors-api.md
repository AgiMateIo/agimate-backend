# connectors-api

External integrations service managing connector definitions, credentials, and marketplace API calls.

## Configuration

| Setting         | Value              |
|-----------------|--------------------|
| Port            | 8080               |
| Context Path    | `/connectors-api/` |
| Management Port | 8088               |
| Database        | am_connectors_db   |

## Authentication

- **API Key**: Header `X-API-Key` for connector/device API calls (validated via gRPC introspect to user-api, cached with Caffeine TTL 2 min)
- **JWT**: Bearer token for management endpoints

## Environment Variables

| Variable                    | Description                            |
|-----------------------------|----------------------------------------|
| `JWT_PUBLIC_KEY`            | ECDSA public key (Base64, X.509)       |
| `CONNECTORS_ENCRYPTION_KEY` | AES-256 key for credentials encryption |

## API Endpoints

### Connector Info (API Key)

| Method | Path                                                      | Description                    |
|--------|-----------------------------------------------------------|--------------------------------|
| GET    | `/connectors-api/api/connectors/`                         | List available connectors      |
| GET    | `/connectors-api/api/connectors/credentials/{code}/`      | List credentials for connector |
| GET    | `/connectors-api/api/connectors/methods/{connectorCode}/` | List methods for connector     |

### Connector Execution (API Key)

| Method | Path                                                        | Description            |
|--------|-------------------------------------------------------------|------------------------|
| POST   | `/connectors-api/api/connectors/call/ozon/getProductList`   | Get Ozon product list  |
| POST   | `/connectors-api/api/connectors/call/ozon/getProductInfo`   | Get Ozon product info  |
| POST   | `/connectors-api/api/connectors/call/wildberries/getCards`  | Get Wildberries cards  |
| GET    | `/connectors-api/api/connectors/call/wildberries/getOrders` | Get Wildberries orders |

### Mobile App Integration (API Key)

| Method | Path                                           | Description            |
|--------|-------------------------------------------------|------------------------|
| GET    | `/connectors-api/api/apps/`                     | Get connected apps     |
| GET    | `/connectors-api/api/apps/triggers/{appId}`     | Get app triggers       |
| GET    | `/connectors-api/api/apps/tools/{appId}`        | Get app tools          |
| POST   | `/connectors-api/api/apps/call/{appId}`         | Push action to device  |

### Connector Management (JWT)

| Method | Path                                  | Description                       |
|--------|---------------------------------------|-----------------------------------|
| GET    | `/connectors-api/manage/connectors/`  | List all connectors with metadata |

### Credentials Management (JWT)

| Method | Path                                                      | Description                |
|--------|-----------------------------------------------------------|----------------------------|
| GET    | `/connectors-api/manage/credentials/`                     | Get credentials summary    |
| GET    | `/connectors-api/manage/credentials/{connectorCode}`      | List connector credentials |
| POST   | `/connectors-api/manage/credentials/{connectorCode}`      | Create credential          |
| GET    | `/connectors-api/manage/credentials/{connectorCode}/{id}` | Get credential details     |
| PUT    | `/connectors-api/manage/credentials/{connectorCode}/{id}` | Update credential          |
| DELETE | `/connectors-api/manage/credentials/{connectorCode}/{id}` | Delete credential          |

### Webhooks Management (JWT)

| Method | Path                                                     | Description                                 |
|--------|----------------------------------------------------------|---------------------------------------------|
| GET    | `/connectors-api/manage/webhooks/`                       | List webhooks (optional ?eventType filter)  |
| GET    | `/connectors-api/manage/webhooks/{webhookId}`            | Get webhook details                         |
| POST   | `/connectors-api/manage/webhooks`                        | Create webhook                              |
| PUT    | `/connectors-api/manage/webhooks/{webhookId}`            | Update webhook                              |
| DELETE | `/connectors-api/manage/webhooks/{webhookId}`            | Delete webhook (soft)                       |
| GET    | `/connectors-api/manage/webhooks/{webhookId}/deliveries` | Get webhook deliveries                      |

### Public

| Method | Path                          | Description                  |
|--------|-------------------------------|------------------------------|
| GET    | `/connectors-api/`            | Application info and uptime  |
| GET    | `/connectors-api/favicon.ico` | Empty favicon                |

## OpenAPI Specification

Generated at build-time and available at:
- Build output: `connectors-api/build/generated/openapi/openapi.json`
- Resources: `connectors-api/src/main/resources/static/openapi.json`

Generate with:
```bash
./gradlew :connectors-api:generateOpenApi
```

Swagger UI (local profile): `/connectors-api/docs/ui`

## Connector Architecture

- Each connector has a dedicated CallController (e.g., `OzonCallController`)
- Credentials encrypted with AES-256 before storage
- OpenAPI spec parsed at runtime for method metadata
- Currently implemented: Ozon (2 methods), Wildberries (2 methods)

## Database Tables

- `connectors` — Connector definitions
- `credentials` — Encrypted API credentials
- `webhook_registrations` — Webhook configurations

Migrations: `services/connectors-api/src/main/resources/db/changelog/`

## Adding New Connector

1. Create DTOs in `controller/dto/{connector}/`
2. Create Service `{Connector}CallService.java`
3. Create Controller `{Connector}CallController.java`
4. Create Client implementing `ConnectorClient`
5. Update `ConnectorController` required fields map
6. Generate OpenAPI: `./gradlew :connectors-api:generateOpenApi`
7. Commit `openapi.json`
