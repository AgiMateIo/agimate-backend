# control-api — Connector Catalog Endpoints

Detailed API specification for the `/manage/connectors/**` endpoint group in control-api.

> All paths below are relative to the context path `/control`.

## Authentication

| Group | Mechanism | Header |
|-------|-----------|--------|
| `/manage/**` | **JWT** | `Authorization: Bearer <jwt>` |

**Common error responses:**

| Status | Body | Meaning |
|--------|------|---------|
| 401 | `{ "error": { "message": "Authentication credentials not found or invalid" } }` | Missing or invalid JWT |
| 403 | `{ "error": { "message": "Access denied. Insufficient permissions." } }` | Authenticated but not authorized |

---

## Connector Catalog Endpoints (`/manage/connectors/**`)

Auth: `Authorization: Bearer <jwt>` required on all endpoints.

The connector catalog is a static registry of connector type definitions — not user-specific connector instances. Each entry describes a connector variant (e.g., `APP`, `INTEGRATION`) with a stable `code` identifier used when creating connector instances.

For connectors of `type=INTEGRATION`, the response includes an `integrationMeta` block describing the credential fields required to configure an integration and whether the platform supports webhooks. This metadata is sourced from the integration handler registered in the backend.

---

### GET `/control/manage/connectors/`

Returns a paginated list of connector type definitions from the catalog, with optional filtering by `type` and full-text search by `name` / `description`.

**Request:** no body.

**Query parameters:**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `type` | `string (enum)` | no | — | Filter by connector category. Must be one of `APP`, `INTEGRATION`, `INTERNAL_SERVICE`, `LOOPBACK`. |
| `search` | `string` | no | — | Case-insensitive substring match against `name` or `description`. Blank values are ignored. |
| `page` | `int` | no | `0` | Zero-based page index. |
| `size` | `int` | no | `20` | Page size. |

Results are sorted by `name` ascending.

**Response `200`:**
```json
{
  "response": {
    "content": [
      {
        "code": "app",
        "type": "APP",
        "name": "App Connector",
        "description": "Generic device/app connector",
        "integrationMeta": null
      },
      {
        "code": "telegram",
        "type": "INTEGRATION",
        "name": "Telegram",
        "description": "Telegram bot integration",
        "integrationMeta": {
          "credentialFields": ["botToken"],
          "supportsWebhooks": true
        }
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "sorted": true, "unsorted": false, "empty": false },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 2,
    "totalPages": 1,
    "last": true,
    "first": true,
    "numberOfElements": 2,
    "size": 20,
    "number": 0,
    "sort": { "sorted": true, "unsorted": false, "empty": false },
    "empty": false
  }
}
```

**Item fields (`response.content[]`):**

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `code` | `string` | no | Unique connector code — primary key; matches pattern `^[a-z0-9:\-]{3,128}$` |
| `type` | `string (enum)` | no | Connector category — see values below |
| `name` | `string` | no | Human-readable display name |
| `description` | `string` | yes | Optional description of the connector |
| `integrationMeta` | `object` | yes | Populated only when `type=INTEGRATION` and an integration handler is registered; `null` otherwise |

**`integrationMeta` fields:**

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `credentialFields` | `string[]` | no | Required credential field names for creating an integration of this type |
| `supportsWebhooks` | `boolean` | no | Whether the integration supports inbound webhooks |

**`type` enum values:**

| Value | Meaning |
|-------|---------|
| `APP` | Physical device or custom app connector (authenticated via `X-App-Auth-Key`) |
| `INTEGRATION` | Platform integration connector (e.g., Telegram) |
| `INTERNAL_SERVICE` | Internal system-to-system service connector |
| `LOOPBACK` | Loopback connector for self-referencing flows |

**Examples:**

```
GET /control/manage/connectors/?type=INTEGRATION
GET /control/manage/connectors/?search=telegram
GET /control/manage/connectors/?type=APP&search=device&page=0&size=10
```

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | `type` is not one of the allowed enum values |
| 401 | Missing or invalid JWT |
| 403 | JWT does not carry sufficient privileges |

---

### GET `/control/manage/connectors/{code}`

Returns a single connector by its `code`. Convenient for fetching a connector's `integrationMeta` (credential fields, webhook support) without scanning the paginated list.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `code` | `string` | Connector code |

**Response `200`:** same shape as an item in the list response (`ConnectorResponse`).

```json
{
  "response": {
    "code": "telegram",
    "type": "INTEGRATION",
    "name": "Telegram",
    "description": "Telegram bot integration",
    "integrationMeta": {
      "credentialFields": ["botToken"],
      "supportsWebhooks": true
    }
  }
}
```

**Error responses:**

| Status | Condition |
|--------|-----------|
| 401 | Missing or invalid JWT |
| 403 | JWT does not carry sufficient privileges |
| 404 | No connector found with the given `code` |
