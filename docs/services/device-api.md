# device-api

Device API for connector registration, tool delivery, trigger submission, and AI agent integration.

## Configuration

| Setting         | Value        |
|-----------------|--------------|
| Port            | 8080         |
| Context Path    | `/device`    |
| Management Port | 8088         |
| Database        | am_device_db |

## Authentication

- **Connector Auth**: Header `X-App-Auth-Key` for device/connector endpoints
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

### Device Endpoints (Connector Auth)

| Method | Path                          | Description                             |
|--------|-------------------------------|-----------------------------------------|
| POST   | `/device/tools/result`        | Submit tool result from device          |
| GET    | `/device/tools/get`           | Get pending tools for device            |
| GET    | `/device/tools/test`          | Test endpoint (publishes to Centrifugo) |
| POST   | `/device/trigger/new`         | Submit trigger from device              |
| POST   | `/device/registration/link`   | Link device to connector                |
| POST   | `/device/centrifugo/token`    | Get Centrifugo subscription token       |

### Agent/External API (API Key)

| Method | Path                                              | Description                          |
|--------|----------------------------------------------------|--------------------------------------|
| POST   | `/device/api/connectors/call/{connectorId}`        | Push tool_use to connector/device    |
| GET    | `/device/api/connectors/agent/settings`            | Get agent settings (from API key)    |
| POST   | `/device/api/connectors/centrifugo/token`          | Get Centrifugo token for agent       |
| GET    | `/device/api/connectors/`                          | List connected connectors            |
| GET    | `/device/api/connectors/triggers/`                 | Get all connector triggers           |
| GET    | `/device/api/connectors/triggers/{connectorId}`    | Get connector triggers               |
| GET    | `/device/api/connectors/tools/{connectorId}`       | Get connector tools                  |
| GET    | `/device/api/connectors/tools/`                    | List all connector tools             |

### Connector Management (JWT)

| Method | Path                                                    | Description                          |
|--------|---------------------------------------------------------|--------------------------------------|
| GET    | `/device/manage/connectors/`                            | List all connectors                  |
| POST   | `/device/manage/connectors/`                            | Create new connector                 |
| GET    | `/device/manage/connectors/{connectorId}`               | Get specific connector               |
| PUT    | `/device/manage/connectors/{connectorId}`               | Update connector                     |
| DELETE | `/device/manage/connectors/{connectorId}`               | Delete connector (soft)              |
| POST   | `/device/manage/connectors/{connectorId}/regenerate`    | Regenerate auth key                  |
| GET    | `/device/manage/connectors/{connectorId}/detail`        | Get connector detail with device info|
| POST   | `/device/manage/connectors/{connectorId}/disconnect`    | Disconnect device from connector     |

### Tool Management (JWT)

| Method | Path                        | Description              |
|--------|-----------------------------|--------------------------|
| GET    | `/device/manage/tools/`     | List all device tools    |

### Trigger Management (JWT)

| Method | Path                            | Description                        |
|--------|---------------------------------|------------------------------------|
| GET    | `/device/manage/triggers/`      | List all device triggers           |

### Trigger Logs (JWT)

| Method | Path                              | Description                                     |
|--------|-----------------------------------|-------------------------------------------------|
| GET    | `/device/manage/trigger-logs/`    | List trigger logs (filter by deviceId, connectorPubId) |

### Webhook Delivery Logs (JWT)

| Method | Path                                    | Description                                          |
|--------|-----------------------------------------|------------------------------------------------------|
| GET    | `/device/manage/webhook-deliveries/`    | List webhook delivery logs (optional `?apiKeyPubId`, pagination) |

### Agent Management (JWT)

| Method | Path                                       | Description                   |
|--------|--------------------------------------------|-------------------------------|
| GET    | `/device/manage/agents/`                   | List agents                   |
| POST   | `/device/manage/agents/`                   | Create agent                  |
| GET    | `/device/manage/agents/{apiKeyPubId}`      | Get agent                     |
| PUT    | `/device/manage/agents/{apiKeyPubId}`      | Update agent                  |
| DELETE | `/device/manage/agents/{apiKeyPubId}`      | Delete agent                  |

### Agentic Teams (JWT)

| Method | Path                                           | Description                   |
|--------|-------------------------------------------------|-------------------------------|
| GET    | `/device/manage/agentic-teams/`                | List agentic teams            |
| POST   | `/device/manage/agentic-teams/`                | Create agentic team           |
| GET    | `/device/manage/agentic-teams/{pubId}`         | Get agentic team              |
| PUT    | `/device/manage/agentic-teams/{pubId}`         | Update agentic team           |
| DELETE | `/device/manage/agentic-teams/{pubId}`         | Delete agentic team           |

### Tool Use Logs (JWT)

| Method | Path                            | Description                        |
|--------|---------------------------------|------------------------------------|
| GET    | `/device/manage/tool-use-logs/` | List tool use logs (filter by apiKeyPubId) |

### Integration Management (JWT)

| Method | Path                                              | Description                     |
|--------|---------------------------------------------------|---------------------------------|
| GET    | `/device/manage/integrations/`                    | List all integrations           |
| POST   | `/device/manage/integrations/`                    | Create integration              |
| GET    | `/device/manage/integrations/{id}`                | Get integration details         |
| PUT    | `/device/manage/integrations/{id}/credentials`    | Update integration credentials  |
| PATCH  | `/device/manage/integrations/{id}`                | Update integration settings     |
| DELETE | `/device/manage/integrations/{id}`                | Delete integration              |

### Platform Management (JWT)

| Method | Path                                 | Description              |
|--------|--------------------------------------|--------------------------|
| GET    | `/device/manage/platforms/`          | List all platforms       |
| GET    | `/device/manage/platforms/{code}`    | Get platform by code     |

### Integration Webhooks (Public)

| Method | Path                                                | Description                     |
|--------|-----------------------------------------------------|---------------------------------|
| POST   | `/device/webhook/integration/{integrationPubId}`    | Receive inbound webhook events  |

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
2. Gets settings via `GET /api/connectors/agent/settings` (prompt, available tools, triggers)
3. Gets Centrifugo token via `POST /api/connectors/centrifugo/token` for channel `agent:{apiKeyPubId}`
4. Subscribes to agent channel for real-time events
5. Calls `POST /api/connectors/call/{connectorId}` to invoke a tool on a connector/device
   - Tool authorization checked against `agent_tools` table
   - `tool_use_log` entry created
6. Device executes tool and sends result via `POST /tools/result`
   - `tool_use_log` updated with result
   - Result published to agent's Centrifugo channel
7. Device triggers (`POST /trigger/new`) are routed to subscribed agents:
   - `triggers_to=centrifugo` — published to agent's Centrifugo channel
   - `triggers_to=webhook` — delivered directly by device-api to the webhook URL configured in agent
   - `triggers_to=ignore` — not routed (default)
   - `triggers_allow_all=true` — agent receives all triggers without explicit subscription

## Integration Flow (Telegram, etc.)

1. User creates integration via `POST /manage/integrations/` with platform token
   - Token validated against platform API (e.g., Telegram getMe)
   - Outbound Connector created with predefined triggers/tools
   - Token encrypted (AES-GCM) and stored
   - Platform webhook configured (e.g., Telegram setWebhook)
2. User subscribes Agent to integration triggers (AgentTrigger) and tools (AgentTool)
3. Inbound messages hit webhook endpoint → normalized → routed via TriggerRouterService
4. Agent calls `POST /api/connectors/call/{connectorId}` with integration tool → executed directly against platform API
5. Tool result pushed back to agent via Centrifugo

## Database Tables

- `connectors` — Connector authentication keys, linked device info (deviceId, deviceFeatures JSONB), triggers/tools capabilities (JSONB)
- `trigger_logs` — Logged trigger events
- `trigger_log_agents` — Trigger routing log per agent
- `tool_use_logs` — Tool invocation logs (request + result)
- `agents` — Agent configuration (prompt, triggers_allow_all, triggers_to, webhook_url, webhook_auth_header)
- `agent_tools` — Agent-to-tool access mapping
- `agent_triggers` — Agent-to-trigger subscription mapping
- `webhook_delivery_logs` — Webhook delivery attempt logs
- `platforms` — Platform definitions (Telegram, etc.)
- `integrations` — Platform integrations linked to connectors
- `agentic_teams` — Agentic team groupings

Migrations: `services/device-api/src/main/resources/db/changelog/`
