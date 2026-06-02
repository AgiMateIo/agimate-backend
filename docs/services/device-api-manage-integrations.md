# device-api — Integration Credentials Endpoints

Detailed API specification for the `/manage/integrations/**` endpoint group in device-api. These endpoints manage a user's **integration credentials** — instances of configured platform integrations (e.g., a specific Telegram bot) — not the integration catalog itself.

> Connector catalog and integration-platform metadata (required credential fields, webhook support) live under `/manage/connectors/**`. See [device-api-manage-connectors.md](device-api-manage-connectors.md).

> All paths below are relative to the context path `/device`.

## Authentication

| Group | Mechanism | Header |
|-------|-----------|--------|
| `/manage/**` | **JWT** | `Authorization: Bearer <jwt>` |

**Common error responses:**

| Status | Body | Meaning |
|--------|------|---------|
| 400 | `{ "error": { "message": "Unsupported platform: <code>" } }` | `connectorCode` does not match a registered integration handler |
| 401 | `{ "error": { "message": "Authentication credentials not found or invalid" } }` | Missing or invalid JWT |
| 403 | `{ "error": { "message": "Access denied. Insufficient permissions." } }` | Authenticated but not authorized |
| 404 | `{ "error": { "message": "Integration not found" } }` | The `credentialId` does not belong to the current user, or does not exist |
| 409 | `{ "error": { "message": "Integration already exists for <connectorCode>: <identifier>" } }` | Duplicate platform identifier (e.g., same bot) for this user |

---

## IntegrationResponse shape

All endpoints that return a credential use the following shape:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `UUID` | no | Integration credentials public ID |
| `connectorCode` | `string` | no | Connector code (e.g., `telegram`) |
| `platformIdentifier` | `string` | no | Platform-side identifier (e.g., Telegram bot username) |
| `name` | `string` | yes | Optional user-provided integration name |
| `enabled` | `boolean` | no | Whether the integration is currently enabled |
| `lastUsedAt` | `datetime` | yes | ISO timestamp of the last usage (`yyyy-MM-dd'T'HH:mm:ss`) |
| `createdAt` | `datetime` | no | Creation timestamp |

---

## Endpoints

### GET `/device/manage/integrations/credentials/`

List integration credentials owned by the current user. Optionally filter by connector code.

**Query parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `connectorCode` | `string` | no | When provided, returns only credentials for the specified connector (e.g., `telegram`). Blank values are ignored. |

**Response `200`:**
```json
{
  "response": [
    {
      "id": "018f0b1a-1234-7890-abcd-ef1234567890",
      "connectorCode": "telegram",
      "platformIdentifier": "my_bot",
      "name": "My Telegram Bot",
      "enabled": true,
      "lastUsedAt": "2026-04-20T13:45:00",
      "createdAt": "2026-04-01T10:00:00"
    }
  ]
}
```

Results are sorted by `createdAt` descending.

---

### POST `/device/manage/integrations/credentials/`

Create a new integration credential. Credentials are validated against the platform API (e.g., Telegram `getMe`) before being persisted; the secret values are encrypted at rest (AES-GCM). If the platform supports webhooks, a webhook is registered with the platform as part of creation.

**Request body (`CreateIntegrationRequest`):**
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
| `connectorCode` | `string` | yes | Connector code from the catalog; must correspond to an `INTEGRATION`-type connector with a registered handler |
| `credentials` | `object (map)` | yes | Key-value map of credential fields as defined by the integration handler (see `integrationMeta.credentialFields` in the connector catalog) |
| `name` | `string` | no | Optional human-readable name |

**Response `200`:** `IntegrationResponse`.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | `connectorCode` is not a registered `INTEGRATION` connector, or credential validation with the platform API failed |
| 409 | A credential for this user already exists with the same `platformIdentifier` |

---

### GET `/device/manage/integrations/credentials/{credentialId}`

Get a single credential by its public ID. The caller must own the credential.

**Response `200`:** `IntegrationResponse`.

---

### PATCH `/device/manage/integrations/credentials/{credentialId}/`

Update mutable credential settings (enable/disable, display name). Does **not** change secret values — use `PUT .../secret` for that.

**Request body (`UpdateIntegrationRequest`):**
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

**Response `200`:** `IntegrationResponse`.

---

### PUT `/device/manage/integrations/credentials/{credentialId}/secret`

Replace the secret values (credential field map) for an existing integration. The new credentials must belong to the same platform identity (e.g., the same bot) — otherwise the request is rejected. If the platform supports webhooks, the webhook is re-registered with the new credentials.

**Request body (`UpdateIntegrationCredentialsRequest`):**
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

**Response `200`:** `IntegrationResponse`.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | Credentials fail platform validation, or belong to a different platform identity than the existing record |
| 404 | Credential not found or not owned by caller |

---

### DELETE `/device/manage/integrations/credentials/{credentialId}`

Soft-delete an integration credential. If the integration supports webhooks, the webhook is de-registered from the platform (best-effort — failures are logged but do not block deletion).

**Response `200`:** empty success envelope.

```json
{ "response": null }
```

---

## Discovery Endpoints

Expose what a given integration connector offers so the UI can render tool/trigger pickers before the user subscribes a skill or agent.

### GET `/device/manage/integrations/tools/`

List predefined tools for the given integration connector.

**Query parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `connectorCode` | `string` | yes | Must reference an `INTEGRATION`-type connector with a registered handler |

**Response `200`:**
```json
{
  "response": [
    {
      "name": "telegram.send_message",
      "description": "Send a message to a Telegram chat",
      "parameters": {
        "type": "object",
        "properties": {
          "chatId": { "type": "string", "description": "Target chat id" },
          "text":   { "type": "string", "description": "Message text" }
        },
        "required": ["chatId", "text"]
      }
    }
  ]
}
```

Each entry uses the same `ToolSpecificationResponse` shape as `GET /agent/tools/{connectorCode}` (name, description, JSON Schema `parameters`).

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | `connectorCode` missing, connector is not `INTEGRATION` type, or no handler registered |
| 404 | Connector not found in catalog |

---

### GET `/device/manage/integrations/triggers/`

List predefined triggers for the given integration connector.

**Query parameters:** same as `/tools/`.

**Response `200`:**
```json
{
  "response": [
    {
      "name": "telegram.message_received",
      "description": "Text message received",
      "params": ["chatId", "text", "from", "messageId"]
    }
  ]
}
```

**Item fields:**

| Field | Type | Description |
|-------|------|-------------|
| `name` | `string` | Trigger code (e.g., `telegram.message_received`) |
| `description` | `string` | Human-readable trigger description |
| `params` | `string[]` | Parameter names delivered with the trigger payload |

**Errors:** same as `/tools/`.

---

## Telegram: webhook vs polling mode

The Telegram integration supports two inbound-delivery modes, controlled by the
`app.integration.telegram.mode` property (env: `APP_INTEGRATION_TELEGRAM_MODE`).

| Mode | When to use | How it works |
|------|-------------|--------------|
| `webhook` (default) | Production / any deployment with a public HTTPS URL | On integration create/update, `setWebhook` is called against Telegram with `app.integration.webhook-base-url` + `/webhook/integration/{pubId}`. Updates arrive via `IntegrationWebhookController`. |
| `polling` | Local development, environments without a public URL | `TelegramPollingService` starts a virtual thread per active Telegram integration on startup, runs `getUpdates` in a loop, and routes each update through the same `normalizeInbound` → `TriggerRouterService.routeWhTrigger` path used by webhooks. No public URL needed. |

**Switching modes:**
- The mode is read at startup by `TelegramHandler.supportsWebhooks()`. Existing integrations do not need to be recreated.
- When switching **polling → webhook**, update credentials (or patch-enable) to trigger `setupWebhook`. Alternatively, delete and recreate the integration.
- When switching **webhook → polling**, the polling worker calls `deleteWebhook` against Telegram on first iteration, so the previously-registered webhook is cleaned up automatically.

**Polling-mode caveats:**
- Runs only on a single instance — multiple processes polling the same bot token will race on `getUpdates` and drop messages. Intended for local dev, not horizontal scaling.
- Update offset is kept in memory. On restart, Telegram replays updates from the last 24h.
- Graceful shutdown waits up to the long-poll read timeout (~25s) per worker.
