# control-api — Connection Endpoints

Detailed API specification for the `/manage/connections/**` endpoint group in control-api. These endpoints manage a user's **connections** — instances of configured connectors (e.g., a specific Telegram bot, an MCP server). They are not the connector type catalog.

> The connector type catalog (connector metadata, required credential fields, webhook support) lives under `/manage/connectors/**`. See [control-api-manage-connectors.md](control-api-manage-connectors.md). That document also covers the catalog tools/triggers discovery endpoints that moved there from the old `/manage/integrations/**` group.

> All paths below are relative to the context path `/control`.

## Authentication

| Group | Mechanism | Header |
|-------|-----------|--------|
| `/manage/**` | **JWT** | `Authorization: Bearer <jwt>` |

**Common error responses:**

| Status | Body | Meaning |
|--------|------|---------|
| 400 | `{ "error": { "message": "Unsupported platform: <code>" } }` | `connectorCode` does not match a registered handler |
| 400 | `{ "error": { "message": "Integration connector not found: <code>" } }` | `connectorCode` is not an integration-type connector |
| 401 | `{ "error": { "message": "Authentication credentials not found or invalid" } }` | Missing or invalid JWT |
| 403 | `{ "error": { "message": "Access denied. Insufficient permissions." } }` | Authenticated but not authorized |
| 404 | `{ "error": { "message": "Connection not found" } }` | The `connectionId` does not belong to the current user, or does not exist |
| 409 | `{ "error": { "message": "Connection already exists for <code>: <identifier>" } }` | Duplicate platform identifier (e.g., same bot) for this user |

---

## ConnectionResponse shape

All endpoints that return a connection use the following shape:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `UUID` | no | Connection public ID |
| `connectorCode` | `string` | no | Connector code (e.g., `telegram`, `mcp`) |
| `subCode` | `string` | no | Platform instance discriminator — the identity within the connector (e.g., Telegram bot username, MCP server URL) |
| `fullCode` | `string` | no | Stable client handle composed of `connectorCode` and `subCode` (e.g., `mcp_context7`) |
| `name` | `string` | yes | Optional user-provided display name |
| `enabled` | `boolean` | no | Whether the connection is currently enabled |
| `lastUsedAt` | `datetime` | yes | ISO timestamp of the last usage (`yyyy-MM-dd'T'HH:mm:ss`) |
| `createdAt` | `datetime` | no | Creation timestamp (`yyyy-MM-dd'T'HH:mm:ss`) |

> Ownership semantics (who owns the connector's data — agent / team / none) are **not part of the
> data model**: the rule is embodied in each connector's code and documented in
> `docs/connectors/architecture.md`. Connections carry no `scope` field.

---

## Endpoints

### GET `/control/manage/connections/`

List connections owned by the current user. All filter parameters are optional; omitting them returns all connections.

**Query parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `connectorCode` | `string` | no | Filter by connector code (e.g., `telegram`). Blank values are ignored. |
| `enabled` | `boolean` | no | Filter by enabled state (`true` or `false`). |

Results are returned unsorted (repository default).

**Response `200`:**
```json
{
  "response": [
    {
      "id": "018f0b1a-1234-7890-abcd-ef1234567890",
      "connectorCode": "telegram",
      "subCode": "my_bot",
      "fullCode": "telegram_my_bot",
      "name": "My Telegram Bot",
      "enabled": true,
      "lastUsedAt": "2026-04-20T13:45:00",
      "createdAt": "2026-04-01T10:00:00"
    }
  ]
}
```

---

### POST `/control/manage/connections/`

Create a new connection. Credentials are validated against the platform API before being persisted; secret values are encrypted at rest (AES-GCM). If the connector supports webhooks, the webhook is registered with the platform as part of creation.

**Applicable to:** integration-type connectors only (those with `integrationMeta.credentialFields` in the catalog).

**Request body (`CreateConnectionRequest`):**
```json
{
  "connectorCode": "telegram",
  "credentials": {
    "token": "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"
  },
  "name": "My Telegram Bot"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `connectorCode` | `string` | yes | Connector code from the catalog; must reference an integration connector with a registered handler |
| `credentials` | `object (map)` | yes | Key-value map of credential fields as defined by the connector's `integrationMeta.credentialFields` |
| `name` | `string` | no | Optional human-readable display name |

**Response `200`:** `ConnectionResponse`.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | `connectorCode` is not an integration connector, or credential validation against the platform API failed |
| 409 | A connection for this user already exists with the same platform identity |

---

### GET `/control/manage/connections/{connectionId}`

Get a single connection by its public ID. The caller must own the connection.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `connectionId` | `UUID` | Connection public ID |

**Response `200`:** `ConnectionResponse`.

```json
{
  "response": {
    "id": "018f0b1a-1234-7890-abcd-ef1234567890",
    "connectorCode": "telegram",
    "subCode": "my_bot",
    "fullCode": "telegram_my_bot",
    "name": "My Telegram Bot",
    "enabled": true,
    "lastUsedAt": "2026-04-20T13:45:00",
    "createdAt": "2026-04-01T10:00:00"
  }
}
```

---

### PATCH `/control/manage/connections/{connectionId}`

Update mutable connection settings (enable/disable, display name). Does **not** change secret values — use `PUT .../secret` for that.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `connectionId` | `UUID` | Connection public ID |

**Request body (`UpdateConnectionRequest`):**
```json
{
  "enabled": false,
  "name": "Renamed bot"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `enabled` | `boolean` | no | When provided, flips the enabled flag |
| `name` | `string` | no | When provided, updates the display name |

Both fields are optional; supply only the ones to change.

**Response `200`:** `ConnectionResponse`.

**Side effects:** toggling `enabled` publishes a `ConnectorCreatedEvent` (enable) or `ConnectorDeletedEvent` (disable), which downstream services use to activate or deactivate the connection.

---

### PUT `/control/manage/connections/{connectionId}/secret`

Replace the credential values for an existing connection. The new credentials must resolve to the same platform identity (same bot, same account) — supplying credentials for a different identity is rejected. If the connector supports webhooks, the webhook is re-registered with the new credentials.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `connectionId` | `UUID` | Connection public ID |

**Request body (`UpdateConnectionSecretRequest`):**
```json
{
  "credentials": {
    "token": "123456:NEW-DEF1234ghIkl-zyx57W2v1u123ew22"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `credentials` | `object (map)` | yes | New credential values |

**Response `200`:** `ConnectionResponse`.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | Credentials fail platform validation, or belong to a different platform identity than the existing connection (`subCode` mismatch) |
| 404 | Connection not found or not owned by caller |

---

### DELETE `/control/manage/connections/{connectionId}`

Soft-delete a connection. If the connector supports webhooks, the webhook is de-registered from the platform (best-effort — failures are logged but do not block deletion).

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `connectionId` | `UUID` | Connection public ID |

**Response `200`:** empty success envelope.

```json
{ "response": null }
```

---

### GET `/control/manage/connections/{connectionId}/tools/`

List tools available for a specific connection instance, resolved via the connector SPI.

- **MCP connections:** returns the tool set from the cached `tools/list` result for this connection's identity.
- **Static connectors:** returns their full `@Tool`-annotated method set.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `connectionId` | `UUID` | Connection public ID |

**Response `200`:**
```json
{
  "response": [
    {
      "name": "send_message",
      "description": "Send a text message",
      "inputSchema": {
        "type": "object",
        "properties": {
          "chatId": { "type": "string", "description": "Target chat ID" },
          "text":   { "type": "string", "description": "Message text" }
        },
        "required": ["chatId", "text"]
      },
      "outputSchema": { "type": "object", "additionalProperties": {} },
      "annotations": {
        "readOnlyHint": false,
        "destructiveHint": false,
        "idempotentHint": false,
        "openWorldHint": true
      }
    }
  ]
}
```

Each entry is a `ConnectorToolSpec` (MCP-compatible): `name`, optional `title`/`description`, `inputSchema` and `outputSchema` (JSON Schema built by reflection), `annotations` (MCP behavioural hints), and optional `_meta`. Fields are omitted when null/empty.

A channel-only connector (e.g. `webchat`) that exposes no tools returns an empty list — not an error.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | Connector has no tool binding at all (misconfigured metadata) |
| 404 | Connection not found or not owned by caller |

---

### GET `/control/manage/connections/{connectionId}/triggers/`

List triggers available for a specific connection instance. The result **merges** two sources, so different connections of the same connector can expose different trigger sets:

- **Type-declared:** the connector type's static `TriggerProvider.getTriggers()` specs (e.g. `webchat` → `message_received`, `persist-memory` → `notes-by-session`/`consolidate`).
- **Dynamic instance triggers:** rows in `connection_triggers` registered per connection at link time (device-apps). A dynamic trigger with the same `name` overrides the type-declared one.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `connectionId` | `UUID` | Connection public ID |

**Response `200`:**
```json
{
  "response": [
    {
      "name": "message_received",
      "description": "Message from the user typed in the web chat",
      "params": ["sessionId", "messageId", "text"]
    }
  ]
}
```

Each entry is a `TriggerSpecificationResponse`: `name`, `description`, and `params` (available parameter names delivered in `trigger.data`). For dynamic triggers, `params` is derived best-effort from the top-level `properties` of the trigger's stored JSON Schema. A connector with no triggers returns an empty list.

**Errors:**

| Status | Condition |
|--------|-----------|
| 404 | Connection not found or not owned by caller |

---

### GET `/control/manage/connections/{connectionId}/jobs/`

List the background jobs materialized for this connection (`connector_jobs` rows), ordered by `nextRunAt` ascending. Unlike tools/triggers (which are capability **specs**), jobs are **runtime instances** with scheduler state and a lifecycle.

Lifecycle actions stay on the dedicated jobs controller, keyed by the job `id` from this list:
- `POST /control/manage/connector-jobs/{id}/pause`
- `POST /control/manage/connector-jobs/{id}/resume`
- `POST /control/manage/connector-jobs/{id}/run-now`
- `DELETE /control/manage/connector-jobs/{id}` (USER/AGENT jobs only; SYSTEM jobs are connector-managed)

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `connectionId` | `UUID` | Connection public ID |

**Response `200`:**
```json
{
  "response": [
    {
      "id": "0190aa...",
      "kind": "SYSTEM",
      "connectorCode": "persist-memory",
      "connectionId": "038b756a-1d3c-8fda-b852-f4dc0ceb5c34",
      "agentId": null,
      "name": "consolidate",
      "type": "CRON",
      "config": { "cron": "0 3 * * *", "zone": "UTC" },
      "args": {},
      "status": "PENDING",
      "nextRunAt": "2026-07-13T03:00:00",
      "pausedAt": null,
      "lastError": null,
      "createdAt": "2026-07-12T20:00:00"
    }
  ]
}
```

Each entry is a `ConnectorJobResponse`. `kind`: `SYSTEM` (declared by the connector, cannot be deleted — pause instead), `USER`, or `AGENT`. A `pausedAt != null` means the scheduler skips it until resumed. Returns an empty list if the connection has no jobs.

**Errors:**

| Status | Condition |
|--------|-----------|
| 404 | Connection not found or not owned by caller |

---

### GET `/control/manage/connections/{connectionId}/agents/`

List the agents this connection is bound to (`agent_connections` rows) — the reverse of `GET /control/manage/agents/{agentId}/connections/`. Use it before disabling, re-keying or deleting an instance to see who it will affect.

This is a **usage inventory**, so disabled agents are included. It is not the list of agents that would actually receive a trigger from this connection — that additionally requires the agent and the connection to be enabled and passes through ABAC (`effect` / `params_filter`).

Ordered by binding creation time. Bindings whose agent has been soft-deleted are omitted.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `connectionId` | `UUID` | Connection public ID |

**Response `200`:**
```json
{
  "response": [
    {
      "id": "0190bb...",
      "agentId": "038b756a-1d3c-8fda-b852-f4dc0ceb5c34",
      "name": "Support agent",
      "description": "Answers customer questions",
      "enabled": true,
      "createdAt": "2026-07-12T20:00:00"
    }
  ]
}
```

Each entry is a `ConnectionAgentResponse`:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `UUID` | no | Binding ID (`agent_connections` row) — the key for policy endpoints |
| `agentId` | `UUID` | no | Agent ID |
| `name` | `string` | no | Agent name |
| `description` | `string` | yes | Agent description |
| `enabled` | `boolean` | no | Whether the agent is enabled |
| `createdAt` | `datetime` | no | When the connection was bound to this agent (`yyyy-MM-dd'T'HH:mm:ss`) |

Returns an empty list if no agent uses the connection.

**Errors:**

| Status | Condition |
|--------|-----------|
| 404 | Connection not found or not owned by caller |

---

### POST `/control/manage/connections/{connectionId}/test`

Validate a connection's credentials and, for MCP connections, synchronously reload the tool cache.

The response always contains the validation outcome. The `toolsDiscovered` and `toolsError` fields are only populated for MCP connections — a non-null `toolsError` means credentials were valid but `tools/list` failed.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `connectionId` | `UUID` | Connection public ID |

**Response `200` (`ConnectionTestResponse`):**

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `valid` | `boolean` | no | Whether credentials are valid and the platform is reachable |
| `identifier` | `string` | yes | Resolved platform identifier (e.g., bot username, MCP server URL) |
| `displayName` | `string` | yes | Human-readable display name returned by the platform |
| `errorField` | `string` | yes | Field that failed validation; `null` if valid |
| `errorMessage` | `string` | yes | Validation error message; `null` if valid |
| `toolsDiscovered` | `integer` | yes | Number of tools reloaded into cache; `null` for non-MCP connections |
| `toolsError` | `string` | yes | Tool discovery error when credentials were valid but `tools/list` failed; `null` otherwise |

**Example — valid connection (non-MCP):**
```json
{
  "response": {
    "valid": true,
    "identifier": "my_bot",
    "displayName": "My Bot",
    "errorField": null,
    "errorMessage": null,
    "toolsDiscovered": null,
    "toolsError": null
  }
}
```

**Example — valid MCP connection with tool reload:**
```json
{
  "response": {
    "valid": true,
    "identifier": "https://mcp.context7.com",
    "displayName": "Context7 MCP",
    "toolsDiscovered": 12,
    "toolsError": null
  }
}
```

**Example — invalid credentials:**
```json
{
  "response": {
    "valid": false,
    "identifier": null,
    "displayName": null,
    "errorField": "token",
    "errorMessage": "Invalid bot token"
  }
}
```

**Errors:**

| Status | Condition |
|--------|-----------|
| 404 | Connection not found or not owned by caller |

---

## Connector Catalog: Tools and Triggers Discovery

These endpoints are part of `ManageConnectorController` (`/manage/connectors/**`) but are documented here as the closest analog to the instance-level `/{connectionId}/tools/` endpoint. They expose what a given integration connector **type** statically offers, which the UI uses to render tool/trigger pickers before a connection is created.

See [control-api-manage-connectors.md](control-api-manage-connectors.md) for authentication and catalog endpoint details.

### GET `/control/manage/connectors/{code}/tools/`

List the predefined tools for an integration connector type (from its static `@Tool` definitions).

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `code` | `string` | Connector code (e.g., `telegram`) |

**Response `200`:**
```json
{
  "response": [
    {
      "name": "send_message",
      "description": "Send a text message",
      "inputSchema": {
        "type": "object",
        "properties": {
          "chatId": { "type": "string", "description": "Target chat ID" },
          "text":   { "type": "string", "description": "Message text" }
        },
        "required": ["chatId", "text"]
      },
      "outputSchema": { "type": "object", "additionalProperties": {} },
      "annotations": {
        "readOnlyHint": false,
        "destructiveHint": false,
        "idempotentHint": false,
        "openWorldHint": true
      }
    }
  ]
}
```

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | Connector has no tool binding at all (misconfigured metadata) |
| 404 | Connector not found in catalog |

---

### GET `/control/manage/connectors/{code}/triggers/`

List the type-declared triggers for a connector **type** (any connector implementing `TriggerProvider`, integration or internal — e.g. `webchat`, `persist-memory`). Returns an empty list for a connector type with no triggers. This is catalog/type-level only; for the triggers actually available on a specific connection instance (which additionally includes dynamic per-connection triggers), use `GET /control/manage/connections/{connectionId}/triggers/`.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `code` | `string` | Connector code (e.g., `telegram`) |

**Response `200`:**
```json
{
  "response": [
    {
      "name": "message_received",
      "description": "Text message received",
      "params": ["chatId", "text", "from", "messageId"]
    }
  ]
}
```

**Item fields (`TriggerSpecificationResponse`):**

| Field | Type | Description |
|-------|------|-------------|
| `name` | `string` | Trigger code (e.g., `message_received`) |
| `description` | `string` | Human-readable trigger description |
| `params` | `string[]` | Parameter names delivered with the trigger payload |

**Errors:**

| Status | Condition |
|--------|-----------|
| 404 | No connector handler registered for `code` |

---

## Frontend Recipes

### Creating a Telegram connection

1. Fetch `/control/manage/connectors/telegram` to get `integrationMeta.credentialFields` — render a form with those fields.
2. User fills in the token and submits `POST /control/manage/connections/` with `connectorCode: "telegram"` and the credentials map.
3. On `200`, show the returned `ConnectionResponse` (including `subCode` as the confirmed bot username).
4. On `409`, tell the user they already have a connection for that bot.
5. On `400` with validation error fields, highlight the specific credential field.

### Listing connections for a connector picker

```
GET /control/manage/connections/?connectorCode=telegram&enabled=true
```

Returns only active Telegram connections — useful to populate a dropdown when the user is assigning a channel to an agent.

### Discovering available tools before assigning a skill

- **Known connector type:** `GET /control/manage/connectors/{code}/tools/` — no connection required, returns the static tool catalog.
- **Specific connection instance (e.g., MCP):** `GET /control/manage/connections/{connectionId}/tools/` — returns the live tool set from the discovered cache.

### Checking the blast radius before deleting or re-keying a connection

```
GET /control/manage/connections/{connectionId}/agents/
```

Lists every agent bound to the instance (disabled ones too) so the UI can warn "this will affect N agents" before `DELETE /control/manage/connections/{connectionId}` or a credential rotation.

### Testing and refreshing an MCP connection

```
POST /control/manage/connections/{connectionId}/test
```

On success, `toolsDiscovered` tells you how many tools were loaded. `toolsError` being non-null with `valid: true` means the server is reachable but `tools/list` failed — surface this separately from a credential error.

---

## Telegram: webhook vs polling mode

The Telegram integration supports two inbound-delivery modes, controlled by the `app.integration.telegram.mode` property (env: `APP_INTEGRATION_TELEGRAM_MODE`).

| Mode | When to use | How it works |
|------|-------------|--------------|
| `webhook` (default) | Production / any deployment with a public HTTPS URL | On connection create/secret-update, `setWebhook` is called against Telegram with `app.integration.webhook-base-url` + `/webhook/integration/{connectionId}`. Updates arrive via the webhook controller. |
| `polling` | Local development, environments without a public URL | `TelegramPollingService` starts a virtual thread per active Telegram connection on startup, runs `getUpdates` in a loop, and routes each update through the same trigger-routing path used by webhooks. No public URL needed. |

**Switching modes:**
- The mode is read at startup. Existing connections do not need to be recreated.
- When switching **polling → webhook**, update the secret (or patch-enable the connection) to trigger `setupWebhook`. Alternatively, delete and recreate the connection.
- When switching **webhook → polling**, the polling worker calls `deleteWebhook` against Telegram on first iteration, cleaning up the previously-registered webhook automatically.

**Polling-mode caveats:**
- Runs only on a single instance — multiple processes polling the same bot token will race on `getUpdates` and drop messages. Intended for local dev, not horizontal scaling.
- Update offset is kept in memory. On restart, Telegram replays updates from the last 24 h.
- Graceful shutdown waits up to the long-poll read timeout (~25 s) per worker.
