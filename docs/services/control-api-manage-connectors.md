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
          "credentialFields": {"token": "Bot API token"},
          "supportsWebhooks": true
        }
      }
    ],
    "totalElements": 2,
    "totalPages": 1,
    "size": 20,
    "number": 0
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
| `credentialFields` | `object (map)` | no | Required credential fields for creating an integration of this type: field code → human-readable label |
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
      "credentialFields": {"token": "Bot API token"},
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

---

## Connector Catalog Tool & Trigger Endpoints

These endpoints expose **type-level** (catalog) tool and trigger definitions for a connector type — they describe what tools and triggers the connector type provides in principle, independent of any user-owned connection instance.

The distinction between catalog-level and instance-level tools:

- **Catalog tools** (this section) — defined by the connector type itself via `@Tool` reflection. Available for **STATIC** connectors (e.g., `time`, `board`, `persist-memory`, `telegram`). **DYNAMIC** connectors (e.g., `mcp`) return an empty list here because their tools are defined per-instance.
- **Instance tools** — tools of a specific owned connection instance. Fetched via `GET /manage/connections/{connectionId}/tools/` (see `control-api-manage-connections.md`).

> The old `/manage/tools/{code}` group has been removed. Type-level tools now live here under `/manage/connectors/{code}/tools/`.

---

### GET `/control/manage/connectors/{code}/tools/`

Returns the connector type's catalog tool list.

- **STATIC** connectors (e.g., `time`, `board`, `persist-memory`, `telegram`): returns all tools discovered via `@Tool` annotation reflection on the connector's handler.
- **DYNAMIC** connectors (e.g., `mcp`): always returns an empty list (`[]`). Their per-instance tools are available via `GET /manage/connections/{connectionId}/tools/`.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `code` | `string` | Connector code |

**Response `200`:**

```json
{
  "response": [
    {
      "name": "get_current_time",
      "title": "Get Current Time",
      "description": "Returns the current UTC time as an ISO-8601 string.",
      "inputSchema": {
        "type": "object",
        "properties": {},
        "required": []
      },
      "outputSchema": {
        "type": "string",
        "description": "ISO-8601 UTC timestamp"
      },
      "annotations": {
        "readOnlyHint": true,
        "destructiveHint": false,
        "idempotentHint": true,
        "openWorldHint": false
      }
    }
  ]
}
```

**`ConnectorToolSpec` fields:**

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `name` | `string` | no | Tool name — unique within the connector; used as the tool identifier in agent calls |
| `title` | `string` | yes | Human-readable display name |
| `description` | `string` | yes | What the tool does |
| `inputSchema` | `JsonSchema` | yes | JSON Schema (draft-2020-12) describing the tool's input parameters |
| `outputSchema` | `JsonSchema` | yes | JSON Schema describing the tool's output |
| `annotations` | `ToolAnnotationsSpec` | yes | MCP behavioural hints; `null` means MCP defaults apply |
| `_meta` | `object (map<string,string>)` | yes | Opaque connector-specific metadata; serialised as `_meta` in JSON |

**`annotations` fields** (MCP `ToolAnnotations`):

| Field | Type | MCP default | Description |
|-------|------|-------------|-------------|
| `readOnlyHint` | `boolean` | `false` | Tool does not modify state |
| `destructiveHint` | `boolean` | `true` | Tool may have destructive side effects |
| `idempotentHint` | `boolean` | `false` | Repeated calls with same input produce the same result |
| `openWorldHint` | `boolean` | `true` | Tool may interact with external systems |

**`JsonSchema` fields** (standard JSON Schema draft-2020-12 keywords):

| Field | Type | Description |
|-------|------|-------------|
| `type` | `string` | JSON type: `string`, `integer`, `number`, `boolean`, `object`, `array` |
| `description` | `string` | Field or schema description |
| `properties` | `object (map<string, JsonSchema>)` | Object properties (when `type=object`) |
| `required` | `array<string>` | Required property names (when `type=object`) |
| `items` | `JsonSchema` | Array item schema (when `type=array`) |
| `enum` | `array<string>` | Allowed string values |
| `additionalProperties` | `JsonSchema` | Value schema for map-typed objects |

Additional JSON Schema keywords (e.g., `anyOf`, `oneOf`, `$ref`, `format`, `default`, `minimum`) are passed through transparently in an open extra-properties map.

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | Connector does not expose tool definitions (no `definitionBinding` configured) |
| 401 | Missing or invalid JWT |
| 403 | JWT does not carry sufficient privileges |
| 404 | No connector found with the given `code` |

---

### GET `/control/manage/connectors/{code}/tools/{toolName}`

Returns the parameter JSON Schema for a single catalog tool by name.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `code` | `string` | Connector code |
| `toolName` | `string` | Tool name (as returned in the list) |

**Response `200`:** single `ConnectorToolSpec` object (same shape as an item in the list above).

```json
{
  "response": {
    "name": "get_current_time",
    "title": "Get Current Time",
    "description": "Returns the current UTC time as an ISO-8601 string.",
    "inputSchema": {
      "type": "object",
      "properties": {},
      "required": []
    },
    "outputSchema": {
      "type": "string",
      "description": "ISO-8601 UTC timestamp"
    },
    "annotations": {
      "readOnlyHint": true,
      "destructiveHint": false,
      "idempotentHint": true,
      "openWorldHint": false
    }
  }
}
```

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | Connector does not expose tool definitions |
| 401 | Missing or invalid JWT |
| 403 | JWT does not carry sufficient privileges |
| 404 | Connector not found, or tool name not found for this connector |

---

### GET `/control/manage/connectors/{code}/triggers/`

Returns the predefined triggers exposed by an integration connector type. Only applicable to connectors of `type=INTEGRATION` that have a registered integration handler; calling this on a non-integration connector returns `400`.

Each trigger represents an inbound event type the connector can receive (e.g., an incoming Telegram message). The trigger `name` is used when configuring `AgentTriggerPolicy` entries and when routing inbound events in `TriggerRouterService`.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `code` | `string` | Connector code (must be an integration connector) |

**Response `200`:**

```json
{
  "response": [
    {
      "name": "telegram.message_received",
      "description": "Fired when a new message arrives in the bot chat.",
      "params": ["text", "chat_id", "username", "message_id"]
    }
  ]
}
```

**`TriggerSpecificationResponse` fields:**

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `name` | `string` | no | Trigger identifier (e.g., `telegram.message_received`) — used as the key in `AgentTriggerPolicy` |
| `description` | `string` | yes | Human-readable description of when this trigger fires |
| `params` | `array<string>` | yes | Parameter names that this trigger delivers to the agent at runtime |

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | Connector is not an integration (no integration handler registered) |
| 401 | Missing or invalid JWT |
| 403 | JWT does not carry sufficient privileges |
| 404 | No connector found with the given `code` |
