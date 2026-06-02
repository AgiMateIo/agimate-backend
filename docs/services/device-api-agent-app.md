# device-api — Agent & App Endpoints

Detailed API specification for the `/agent/**` and `/app/**` endpoint groups in device-api.

> All paths below are relative to the context path `/device`.

## Authentication

| Group | Mechanism | Header |
|-------|-----------|--------|
| `/agent/**` | **API Key** | `X-Api-Key: <key>` |
| `/app/**` | **Connector Auth** | `X-App-Auth-Key: <key>` |

**Common error responses:**

| Status | Body | Meaning |
|--------|------|---------|
| 401 | `{ "error": { "message": "Authentication credentials not found or invalid" } }` | Missing or invalid auth credential |
| 403 | `{ "error": { "message": "Access denied. Insufficient permissions." } }` | Authenticated but not authorized |

---

## Agent Endpoints (`/agent/**`)

Auth: `X-Api-Key: <key>` header required on all endpoints.

---

### GET `/device/agent/settings`

Returns configuration for the authenticated API key: prompt, authorized tools, and subscribed triggers.

**Request:** no body, no query parameters.

**Response `200`:**
```json
{
  "response": {
    "apiKeyPubId": "01951234-abcd-ef01-2345-6789abcdef01",
    "prompt": "You are a smart home assistant...",
    "tools": ["tool.device.tts.speak", "tool.device.light.toggle"],
    "triggers": ["trigger.door.open", "trigger.motion.detected"]
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `apiKeyPubId` | `UUID` | UUIDv8 public ID of the API key |
| `prompt` | `string` | System prompt configured for this agent |
| `tools` | `string[]` | Tool names the agent is authorized to invoke |
| `triggers` | `string[]` | Trigger names the agent is subscribed to |

---

### POST `/device/agent/centrifugo/token`

Generates Centrifugo connection and subscription tokens for the agent's real-time channel (`agent:{apiKeyPubId}`).

**Request body:**
```json
{
  "apiKeyPubId": "01951234-abcd-ef01-2345-6789abcdef01"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `apiKeyPubId` | `UUID` | yes | API key public ID — determines the agent channel |

**Response `200`:**
```json
{
  "response": {
    "connectionToken": "<jwt>",
    "subscriptionToken": "<jwt>",
    "channel": "agent:01951234-abcd-ef01-2345-6789abcdef01",
    "wsUrl": "https://centrifugo.example.com/connection/websocket"
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `connectionToken` | `string` | ES256 JWT for establishing the Centrifugo WebSocket connection |
| `subscriptionToken` | `string` | ES256 JWT for subscribing to the agent's channel |
| `channel` | `string` | Channel name (`agent:{apiKeyPubId}`) |
| `wsUrl` | `string` | Centrifugo WebSocket URL |

Tokens expire after **3600 seconds (1 hour)**.

---

### POST `/device/agent/tool/call/{connectorId}`

Sends a tool use request to a connector/device via Centrifugo. Creates a `tool_use_log` entry.

If a log for the same `id` already exists for this user, returns the existing `toolUseId` without re-sending (idempotent).

**Path parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `connectorId` | `string` | Public ID of the target connector |

**Request body:**
```json
{
  "id": "toolu_01AbCdEfGh",
  "agentSessionId": "session-abc123",
  "name": "tool.device.tts.speak",
  "params": {
    "text": "Hello, world!",
    "voice": "default"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `string` | yes | Unique tool use correlation ID (e.g., from LLM response) |
| `agentSessionId` | `string` | no | Agent session identifier for grouping related tool calls |
| `name` | `string` | yes | Fully qualified tool name, e.g. `tool.device.tts.speak` |
| `params` | `object` | no | Tool-specific parameters as key-value pairs |

**Response `200`:**
```json
{
  "response": "toolu_01AbCdEfGh"
}
```

The response value is the `toolUseId` from the created log entry (matches `id` from request).

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | Validation error — `id` or `name` missing |
| 401 | Invalid or missing API key |
| 403 | Tool use not authorized for this agent (`{ "error": { "message": "..." } }`) |

---

### POST `/device/agent/tool/check/{connectorId}`

Checks whether a tool use request would be authorized, without pushing to the device. Creates a `tool_use_log` entry with the resulting permission decision.

If a log for the same `id` already exists for this user, returns the cached `permissionDecision` without rechecking (idempotent).

**Path parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `connectorId` | `string` | Public ID of the target connector |

**Request body:** same schema as `POST /tool/call/{connectorId}`.

**Response `200`:**
```json
{
  "response": "ALLOW"
}
```

| Value | Meaning |
|-------|---------|
| `"ALLOW"` | Tool use is authorized |
| `"DENY"` | Tool use is not authorized |

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | Validation error — `id` or `name` missing |
| 401 | Invalid or missing API key |

---

### POST `/device/agent/tool/result`

Saves the result of a tool use execution back to the log. Only allowed when the tool use has `ALLOW` permission decision and the requesting agent owns it.

**Request body:**
```json
{
  "toolUseId": "toolu_01AbCdEfGh",
  "result": "{\"output\": \"Done\"}",
  "error": null
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `toolUseId` | `string` | yes | Correlation ID from the original tool use log |
| `result` | `string` | no | Tool execution result (serialized) |
| `error` | `string` | no | Error message if execution failed |

**Response `200`:**
```json
{
  "response": "toolu_01AbCdEfGh"
}
```

The response value is the `toolUseId` of the updated log entry.

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | `toolUseId` missing |
| 401 | Invalid or missing API key |
| 403 | Agent does not own this tool use log, or the decision was DENY |
| 404 | No tool use log found for the given `toolUseId` |

---

### GET `/device/agent/connectors/`

Returns all connectors currently connected (linked) for the authenticated user.

**Request:** no body, no query parameters.

**Response `200`:**
```json
{
  "response": [
    {
      "connectorPubId": "01951234-abcd-ef01-2345-6789abcdef02",
      "name": "Living Room Hub",
      "description": "Smart home device hub"
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `connectorPubId` | `string` | UUIDv8 public ID of the connector |
| `name` | `string` | Connector display name |
| `description` | `string` | Connector description |

---

### GET `/device/agent/connectors/triggers/`

Returns available triggers grouped by connector for all connectors belonging to the authenticated user.

**Request:** no body, no query parameters.

**Response `200`:**
```json
{
  "response": [
    {
      "connectorPubId": "01951234-abcd-ef01-2345-6789abcdef02",
      "deviceId": "device-abc123",
      "deviceName": "Living Room Hub",
      "triggers": [
        {
          "name": "trigger.door.open",
          "description": "Fires when the door opens",
          "params": ["doorId", "state"]
        }
      ]
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `connectorPubId` | `string` | UUIDv8 public ID of the connector |
| `deviceId` | `string` | Device identifier string |
| `deviceName` | `string` | Device display name |
| `triggers[].name` | `string` | Trigger name |
| `triggers[].description` | `string` | Human-readable description |
| `triggers[].params` | `string[]` | Parameter names emitted with this trigger |

---

### GET `/device/agent/connectors/triggers/{connectorId}`

Returns available triggers for a specific connector.

**Path parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `connectorId` | `string` | Public ID of the connector |

**Response `200`:**
```json
{
  "response": [
    {
      "name": "trigger.door.open",
      "description": "Fires when the door opens",
      "params": ["doorId", "state"]
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | `string` | Trigger name |
| `description` | `string` | Human-readable description |
| `params` | `string[]` | Parameter names emitted with this trigger |

---

### GET `/device/agent/connectors/tools/`

Returns available tools grouped by connector for all connectors belonging to the authenticated user.

> Note: currently returns `null` — not yet implemented.

**Response `200`:**
```json
{
  "response": null
}
```

---

### GET `/device/agent/connectors/tools/{connectorId}`

Returns available tools for a specific connector.

**Path parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `connectorId` | `string` | Public ID of the connector |

**Response `200`:**
```json
{
  "response": [
    {
      "name": "tool.device.tts.speak",
      "description": "Text-to-speech on device",
      "params": ["text", "voice"]
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | `string` | Tool name |
| `description` | `string` | Human-readable description |
| `params` | `string[]` | Parameter names accepted by this tool |

---

### GET `/device/agent/skills/`

Returns a paginated list of skills assigned to the authenticated agent (via `agent_skills` bindings).

**Query parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `page` | `int` | no | `0` | Zero-based page index |
| `size` | `int` | no | `20` | Page size (max `100`) |

**Response `200`:**
```json
{
  "response": {
    "content": [
      {
        "id": "01951234-abcd-ef01-2345-6789abcdef01",
        "agentPubId": "01951234-abcd-ef01-2345-000000000002",
        "skillPubId": "01951234-abcd-ef01-2345-000000000003",
        "skillName": "smart-home-control",
        "needsReinstall": false,
        "createdAt": "2026-03-10T14:30:00",
        "updatedAt": "2026-04-01T09:15:22"
      }
    ],
    "totalElements": 5,
    "totalPages": 1,
    "size": 20,
    "number": 0,
    "first": true,
    "last": true,
    "empty": false
  }
}
```

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `UUID` | no | Binding ID |
| `agentPubId` | `UUID` | no | Agent public ID |
| `skillPubId` | `UUID` | no | Skill public ID (use for ZIP download) |
| `skillName` | `string` | yes | Skill name (`null` if skill was deleted) |
| `needsReinstall` | `boolean` | no | `true` if skill version changed since binding |
| `createdAt` | `string` | no | ISO datetime without timezone (`yyyy-MM-dd'T'HH:mm:ss`) |
| `updatedAt` | `string` | no | ISO datetime without timezone |

Results are sorted by `created_at DESC`.

---

### GET `/device/agent/skills/{skillPubId}.zip`

Downloads all files of a skill as a ZIP archive. Only available for skills assigned to this agent. For featured skill clones, files are served from the parent skill's storage.

**Path parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `skillPubId` | `UUID` | Public ID of the skill (the `.zip` suffix is part of the URL pattern) |

**Response `200`:**

Binary ZIP stream.

| Header | Value |
|--------|-------|
| `Content-Type` | `application/zip` |
| `Content-Disposition` | `attachment; filename="{skill.name}.zip"` |

The ZIP preserves relative paths as stored (e.g., `SKILL.md`, `tools/my_tool.py`).

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | ZIP creation failed (I/O error) — `{ "error": { "message": "Failed to create skill archive" } }` |
| 403 | Skill belongs to a different user |
| 404 | Skill not found, soft-deleted, or not assigned to this agent |

---

## App Endpoints (`/app/**`)

Auth: `X-App-Auth-Key: <key>` header required on all endpoints.

The connector auth key is generated per connector and is only known to the linked device/app.

---

### POST `/device/app/registration/link`

Associates device hardware information with the authenticated connector. Must be called once after the device boots and before subscribing to Centrifugo.

**Request body:**
```json
{
  "deviceId": "device-abc123",
  "deviceName": "Living Room Hub",
  "deviceOs": "Linux 5.15",
  "deviceFeatures": {
    "tts": true,
    "camera": false
  },
  "triggers": {
    "trigger.door.open": { "description": "Door open event" }
  },
  "tools": {
    "tool.device.tts.speak": { "description": "Text-to-speech", "params": ["text", "voice"] }
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `deviceId` | `string` | yes | Unique identifier of the physical device |
| `deviceName` | `string` | no | Human-readable name of the device |
| `deviceOs` | `string` | no | Operating system and version string |
| `deviceFeatures` | `object` | no | Key-value map of device capability flags |
| `triggers` | `object` | no | Map of trigger names to their metadata |
| `tools` | `object` | no | Map of tool names to their metadata |

**Response `200`:**
```json
{
  "response": "success"
}
```

**Error responses:**

| Status | Condition |
|--------|-----------|
| 401 | Invalid or missing `X-App-Auth-Key` |
| 409 | Connector key already linked to a different device |

---

### POST `/device/app/centrifugo/token`

Generates Centrifugo connection and subscription tokens for the device's tools channel (`device:{deviceId}`).

The `deviceId` in the request must match the `deviceId` stored on the connector (set during registration/link). Returns 403 if the connector is not linked or the `deviceId` does not match.

**Request body:**
```json
{
  "deviceId": "device-abc123"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `deviceId` | `string` | yes | Device identifier — must match the linked device |

**Response `200`:**
```json
{
  "response": {
    "connectionToken": "<jwt>",
    "subscriptionToken": "<jwt>",
    "channel": "device:device-abc123",
    "wsUrl": "https://centrifugo.example.com/connection/websocket"
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `connectionToken` | `string` | ES256 JWT for the Centrifugo WebSocket connection |
| `subscriptionToken` | `string` | ES256 JWT for subscribing to the device's channel |
| `channel` | `string` | Channel name (`device:{deviceId}`) |
| `wsUrl` | `string` | Centrifugo WebSocket URL |

Tokens expire after **3600 seconds (1 hour)**.

**Error responses:**

| Status | Condition |
|--------|-----------|
| 401 | Invalid or missing `X-App-Auth-Key` |
| 403 | Connector is not linked, or `deviceId` does not match the linked device |

---

### POST `/device/app/tools/result`

Submits the result of a tool execution from the device back to device-api. The result is stored in the `tool_use_log` and forwarded to the agent via the agent's Centrifugo channel.

**Request body:**
```json
{
  "id": "toolu_01AbCdEfGh",
  "name": "tool.device.tts.speak",
  "result": {
    "status": "ok",
    "output": "Speech completed"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `string` | yes | Tool use correlation ID (from the original tool_use request) |
| `name` | `string` | yes | Tool name that was executed |
| `result` | `object` | yes | Execution result as a JSON object |

**Response `200`:**
```json
{
  "response": null
}
```

**Error responses:**

| Status | Condition |
|--------|-----------|
| 401 | Invalid or missing `X-App-Auth-Key` |

---

### POST `/device/app/trigger/new`

Reports a trigger event from the device. device-api routes the event to subscribed agents based on their trigger configuration (`triggers_to`, `triggers_allow_all`).

**Request body:**
```json
{
  "id": "evt-001",
  "type": "sensor",
  "name": "trigger.door.open",
  "source": "front-door-sensor",
  "deviceId": "device-abc123",
  "occurredAt": "2026-03-11T12:00:00Z",
  "data": {
    "doorId": "front",
    "state": "open"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | `string` | no | Optional event identifier |
| `type` | `string` | no | Event type category |
| `name` | `string` | yes | Trigger name, e.g. `trigger.door.open` |
| `source` | `string` | no | Source sensor or component identifier |
| `deviceId` | `string` | no | Device that fired the trigger |
| `occurredAt` | `string (ISO 8601)` | no | Timestamp of the event; server time used if omitted |
| `data` | `object` | yes | Trigger payload — schema is trigger-specific |

**Response `200`:**
```json
{
  "response": "trigger.door.open"
}
```

The response value is the `name` field from the request.

**Error responses:**

| Status | Condition |
|--------|-----------|
| 400 | `name` or `data` missing |
| 401 | Invalid or missing `X-App-Auth-Key` |

---

## End-to-end Flow Summary

### Agent Tool Invocation Flow

```
Agent                        device-api                    Device (App)
  |                              |                              |
  |-- GET /agent/settings ------>|                              |
  |<-- { prompt, tools, ... } ---|                              |
  |                              |                              |
  |-- POST /agent/centrifugo/token ->                           |
  |<-- { connectionToken, ... } -|                              |
  |                              |                              |
  |  [connect to Centrifugo WS agent:{apiKeyPubId}]             |
  |                              |                              |
  |-- POST /agent/tool/call/{connectorId} ->                    |
  |<-- { response: "toolu_..." } |                              |
  |                              |-- Centrifugo push ---------> |
  |                              |                              |
  |                              |<-- POST /app/tools/result ---|
  |                              |  (result stored in log)      |
  |<-- Centrifugo event ---------|                              |
  |   (tool result delivered)    |                              |
```

### Device Trigger Flow

```
Device (App)                 device-api                    Agent
  |                              |                              |
  |-- POST /app/trigger/new ---> |                              |
  |<-- { response: "name" } -----|                              |
  |                              |-- Centrifugo / webhook ----> |
  |                              |   (routed per agent config)  |
```
