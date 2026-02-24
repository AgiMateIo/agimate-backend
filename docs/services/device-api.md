# device-api

Device API for app registration, tool delivery, trigger submission, and AI agent integration.

## Configuration

| Setting         | Value        |
|-----------------|--------------|
| Port            | 8080         |
| Context Path    | `/device`    |
| Management Port | 8088         |
| Database        | am_device_db |

## Authentication

- **App Auth**: Header `X-App-Auth-Key` for app endpoints
- **JWT**: Bearer token for management endpoints
- **API Key**: Header `X-Api-Key` for agent/external API endpoints

## Environment Variables

| Variable                | Description                       |
|-------------------------|-----------------------------------|
| `JWT_PUBLICKEY`         | ECDSA public key (Base64, X.509)  |
| `CENTRIFUGO_APIKEY`     | Centrifugo HTTP API key           |
| `CENTRIFUGO_PRIVATEKEY` | Centrifugo JWT private key        |
| `CENTRIFUGO_PUBLICKEY`  | Centrifugo JWT public key         |
| `APP_INTEGRATION_ENCRYPTION_KEY` | AES-256 key for token encryption (Base64) |
| `APP_INTEGRATION_WEBHOOK_BASE_URL` | Public URL for webhook callbacks |

## API Endpoints

### App Endpoints (App Auth)

| Method | Path                          | Description                             |
|--------|-------------------------------|-----------------------------------------|
| POST   | `/device/tools/result`        | Submit tool result from device          |
| GET    | `/device/tools/get`           | Get pending tools for device            |
| GET    | `/device/tools/test`          | Test endpoint (publishes to Centrifugo) |
| POST   | `/device/trigger/new`         | Submit trigger from device              |
| POST   | `/device/registration/link`   | Link device to app                      |
| POST   | `/device/centrifugo/token`    | Get Centrifugo subscription token       |

### Agent/External API (API Key)

| Method | Path                                    | Description                          |
|--------|-----------------------------------------|--------------------------------------|
| POST   | `/device/api/apps/call/{appId}`         | Push tool_use to device              |
| GET    | `/device/api/apps/agent/settings`       | Get agent settings (from API key)    |
| POST   | `/device/api/apps/centrifugo/token`     | Get Centrifugo token for agent       |
| GET    | `/device/api/apps/`                     | List connected apps                  |
| GET    | `/device/api/apps/triggers/`            | Get all app triggers                 |
| GET    | `/device/api/apps/triggers/{appId}`     | Get app triggers                     |
| GET    | `/device/api/apps/tools/{appId}`        | Get app tools                        |
| GET    | `/device/api/apps/tools/`              | List all app tools                   |

### App Management (JWT)

| Method | Path                                          | Description                   |
|--------|-----------------------------------------------|-------------------------------|
| GET    | `/device/manage/apps/`                        | List all apps                 |
| POST   | `/device/manage/apps/`                        | Create new app                |
| GET    | `/device/manage/apps/{id}`                    | Get specific app              |
| PUT    | `/device/manage/apps/{id}`                    | Update app                    |
| DELETE | `/device/manage/apps/{id}`                    | Delete app (soft)             |
| POST   | `/device/manage/apps/{id}/regenerate`         | Regenerate auth key           |
| GET    | `/device/manage/apps/{id}/detail`             | Get app detail with device info |
| POST   | `/device/manage/apps/{id}/disconnect`         | Disconnect device from app    |

### Tool Management (JWT)

| Method | Path                        | Description              |
|--------|-----------------------------|--------------------------|
| GET    | `/device/manage/tools/`     | List all device tools    |

### Trigger Management (JWT)

| Method | Path                            | Description                        |
|--------|---------------------------------|------------------------------------|
| GET    | `/device/manage/triggers/`      | List all device triggers           |

### Trigger Logs (JWT)

| Method | Path                              | Description                        |
|--------|-----------------------------------|------------------------------------|
| GET    | `/device/manage/trigger-logs/`    | List trigger logs (filter by deviceId, appId) |

### Webhook Delivery Logs (JWT)

| Method | Path                                    | Description                                          |
|--------|-----------------------------------------|------------------------------------------------------|
| GET    | `/device/manage/webhook-deliveries/`    | List webhook delivery logs (optional `?apiKeyPubId`, pagination) |

### Agent Settings Management (JWT)

| Method | Path                                           | Description                   |
|--------|-------------------------------------------------|-------------------------------|
| GET    | `/device/manage/agent-settings/`               | List agent settings           |
| POST   | `/device/manage/agent-settings/`               | Create agent settings         |
| GET    | `/device/manage/agent-settings/{apiKeyPubId}`  | Get agent settings            |
| PUT    | `/device/manage/agent-settings/{apiKeyPubId}`  | Update agent settings         |
| DELETE | `/device/manage/agent-settings/{apiKeyPubId}`  | Delete agent settings         |

### Tool Use Logs (JWT)

| Method | Path                            | Description                        |
|--------|---------------------------------|------------------------------------|
| GET    | `/device/manage/tool-use-logs/` | List tool use logs (filter by apiKeyPubId) |

### Integration Management (JWT)

| Method | Path                                | Description              |
|--------|-------------------------------------|--------------------------|
| GET    | `/device/manage/integrations/`      | List all integrations    |
| POST   | `/device/manage/integrations/`      | Create integration       |
| GET    | `/device/manage/integrations/{id}`  | Get integration details  |
| DELETE | `/device/manage/integrations/{id}`  | Delete integration       |

### Integration Webhooks (Public)

| Method | Path                                                         | Description                     |
|--------|--------------------------------------------------------------|---------------------------------|
| POST   | `/device/webhook/integration/{platformType}/{integrationId}` | Receive inbound webhook events  |

### Public

| Method | Path                | Description                 |
|--------|---------------------|-----------------------------|
| GET    | `/device/`          | Application info and uptime |
| GET    | `/device/favicon.ico` | Empty favicon               |

## Centrifugo Integration

device-api integrates with Centrifugo for real-time messaging:
- **Device channels** (`device:{deviceId}`) — push tool_use requests to devices
- **Agent channels** (`agent:{apiKeyPubId}`) — deliver tool_result and trigger events to agents
- Connection and subscription token generation (ES256 JWT)

## AI Agent Flow

1. Agent authenticates with API Key (`X-Api-Key` header)
2. Gets settings via `GET /api/apps/agent/settings` (prompt, available tools, triggers)
3. Gets Centrifugo token via `POST /api/apps/centrifugo/token` for channel `agent:{apiKeyPubId}`
4. Subscribes to agent channel for real-time events
5. Calls `POST /api/apps/call/{appId}` to invoke a tool on a device
   - Tool authorization checked against `agent_tools` table
   - `tool_use_log` entry created
6. Device executes tool and sends result via `POST /tools/result`
   - `tool_use_log` updated with result
   - Result published to agent's Centrifugo channel
7. Device triggers (`POST /trigger/new`) are routed to subscribed agents:
   - `triggers_to=centrifugo` — published to agent's Centrifugo channel
   - `triggers_to=webhook` — delivered directly by device-api to the webhook URL configured in `agent_settings`
   - `triggers_to=ignore` — not routed (default)
   - `triggers_allow_all=true` — agent receives all triggers without explicit subscription

## Integration Flow (Telegram, etc.)

1. User creates integration via `POST /manage/integrations/` with platform token
   - Token validated against platform API (e.g., Telegram getMe)
   - Virtual App created with predefined triggers/tools
   - Token encrypted (AES-GCM) and stored
   - Platform webhook configured (e.g., Telegram setWebhook)
2. User subscribes Agent to integration triggers (AgentTrigger) and tools (AgentTool)
3. Inbound messages hit webhook endpoint → normalized → routed via TriggerRouterService
4. Agent calls `POST /api/apps/call/{appId}` with integration tool → executed directly against platform API
5. Tool result pushed back to agent via Centrifugo

## Database Tables

- `apps` — App authentication keys, linked device info (deviceId, deviceFeatures JSONB), triggers/tools capabilities (JSONB)
- `trigger_logs` — Logged trigger events
- `trigger_log_agents` — Trigger routing log per agent
- `tool_use_logs` — Tool invocation logs (request + result)
- `agent_settings` — Agent configuration (prompt, triggers_allow_all, triggers_to, webhook_url, webhook_auth_header)
- `agent_tools` — Agent-to-tool access mapping
- `agent_triggers` — Agent-to-trigger subscription mapping
- `webhook_delivery_logs` — Webhook delivery attempt logs
- `integrations` — Platform integrations (Telegram, etc.) linked to apps

Migrations: `services/device-api/src/main/resources/db/changelog/`
