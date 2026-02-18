# device-api

Device API for device registration, tool delivery, trigger submission, and AI agent integration.

## Configuration

| Setting         | Value         |
|-----------------|---------------|
| Port            | 8080          |
| Context Path    | `/device-api` |
| Management Port | 8088          |
| gRPC Port       | 9090          |
| Database        | am_device_db  |

## Authentication

- **Device Auth**: Header `X-Device-Auth-Key` for device endpoints
- **JWT**: Bearer token for management endpoints
- **API Key**: Header `X-Api-Key` for agent/external API endpoints

## Environment Variables

| Variable                | Description                       |
|-------------------------|-----------------------------------|
| `JWT_PUBLIC_KEY`        | ECDSA public key (Base64, X.509)  |
| `CENTRIFUGO_API_KEY`    | Centrifugo HTTP API key           |
| `CENTRIFUGO_PRIVATEKEY` | Centrifugo JWT private key        |
| `CENTRIFUGO_PUBLICKEY`  | Centrifugo JWT public key         |

## API Endpoints

### Device Endpoints (Device Auth)

| Method | Path                            | Description                             |
|--------|---------------------------------|-----------------------------------------|
| POST   | `/device-api/tools/result`      | Submit tool result from device          |
| GET    | `/device-api/tools/get`         | Get pending tools for device            |
| GET    | `/device-api/tools/test`        | Test endpoint (publishes to Centrifugo) |
| POST   | `/device-api/trigger/new`       | Submit trigger from device              |
| POST   | `/device-api/registration/link` | Link device to auth key                 |
| POST   | `/device-api/centrifugo/token`  | Get Centrifugo subscription token       |

### Agent/External API (API Key)

| Method | Path                                              | Description                          |
|--------|----------------------------------------------------|--------------------------------------|
| POST   | `/device-api/api/device/call/{deviceAuthKeyId}`    | Push tool_use to device              |
| GET    | `/device-api/api/device/agent/settings`            | Get agent settings (from API key)    |
| POST   | `/device-api/api/device/centrifugo/token`          | Get Centrifugo token for agent       |
| GET    | `/device-api/api/device/`                          | List connected devices               |
| GET    | `/device-api/api/device/triggers/`                 | Get all device triggers              |
| GET    | `/device-api/api/device/triggers/{deviceId}`       | Get device triggers                  |
| GET    | `/device-api/api/device/tools/{deviceId}`          | Get device tools                     |

### Device Management (JWT)

| Method | Path                                                       | Description                   |
|--------|-------------------------------------------------------------|-------------------------------|
| GET    | `/device-api/manage/devices/`                               | List user devices             |
| POST   | `/device-api/manage/devices/{connectionId}/disconnect`      | Disconnect device             |

### Device Auth Key Management (JWT)

| Method | Path                                                       | Description                   |
|--------|-------------------------------------------------------------|-------------------------------|
| GET    | `/device-api/manage/device-keys/`                           | List all device auth keys     |
| POST   | `/device-api/manage/device-keys/`                           | Create new device auth key    |
| GET    | `/device-api/manage/device-keys/{connectionId}`             | Get specific device auth key  |
| PUT    | `/device-api/manage/device-keys/{connectionId}`             | Update device auth key        |
| DELETE | `/device-api/manage/device-keys/{connectionId}`             | Delete device auth key (soft) |
| POST   | `/device-api/manage/device-keys/{connectionId}/regenerate`  | Regenerate auth key           |

### Agent Settings Management (JWT)

| Method | Path                                                 | Description                   |
|--------|------------------------------------------------------|-------------------------------|
| GET    | `/device-api/manage/agent-settings/`                 | List agent settings           |
| POST   | `/device-api/manage/agent-settings/`                 | Create agent settings         |
| GET    | `/device-api/manage/agent-settings/{apiKeyPubId}`    | Get agent settings            |
| PUT    | `/device-api/manage/agent-settings/{apiKeyPubId}`    | Update agent settings         |
| DELETE | `/device-api/manage/agent-settings/{apiKeyPubId}`    | Delete agent settings         |

### Tool Use Logs (JWT)

| Method | Path                                  | Description                        |
|--------|---------------------------------------|------------------------------------|
| GET    | `/device-api/manage/tool-use-logs/`   | List tool use logs (filter by apiKeyPubId) |

### Public

| Method | Path                      | Description                 |
|--------|---------------------------|-----------------------------|
| GET    | `/device-api/`            | Application info and uptime |
| GET    | `/device-api/favicon.ico` | Empty favicon               |

## Centrifugo Integration

device-api integrates with Centrifugo for real-time messaging:
- **Device channels** (`device:{deviceId}`) — push tool_use requests to devices
- **Agent channels** (`agent:{apiKeyPubId}`) — deliver tool_result and trigger events to agents
- Connection and subscription token generation (ES256 JWT)
- gRPC server for connectors-api to push actions

## AI Agent Flow

1. Agent authenticates with API Key (`X-Api-Key` header)
2. Gets settings via `GET /api/device/agent/settings` (prompt, available tools, triggers)
3. Gets Centrifugo token via `POST /api/device/centrifugo/token` for channel `agent:{apiKeyPubId}`
4. Subscribes to agent channel for real-time events
5. Calls `POST /api/call/{deviceAuthKeyId}` to invoke a tool on a device
   - Tool authorization checked against `agent_tools` table
   - `tool_use_log` entry created
6. Device executes tool and sends result via `POST /tools/result`
   - `tool_use_log` updated with result
   - Result published to agent's Centrifugo channel
7. Device triggers (`POST /trigger/new`) are routed to subscribed agents:
   - `triggers_to=centrifugo` — published to agent's Centrifugo channel
   - `triggers_to=webhook` — sent via gRPC to connectors-api
   - `triggers_to=ignore` — not routed (default)
   - `triggers_allow_all=true` — agent receives all triggers without explicit subscription

## Database Tables

- `device_auth_keys` — Device authentication keys and metadata
- `device` — Linked devices with triggers/tools capabilities (JSONB)
- `trigger_logs` — Logged trigger events with `routed_to` field
- `tool_use_logs` — Tool invocation logs (request + result)
- `agent_settings` — Agent configuration (prompt, triggers_allow_all, triggers_to)
- `agent_tools` — Agent-to-tool access mapping
- `agent_triggers` — Agent-to-trigger subscription mapping

Migrations: `services/device-api/src/main/resources/db/changelog/`
