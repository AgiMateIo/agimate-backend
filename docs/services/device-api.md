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

| Mechanism | Header | Scope |
|-----------|--------|-------|
| **Connector Auth** | `X-App-Auth-Key: <key>` | `/app/**` — device/connector endpoints |
| **API Key** | `X-Api-Key: <key>` | `/agent/**` — agent API endpoints |
| **JWT** | `Authorization: Bearer <jwt>` | `/manage/**` — management endpoints |
| **Public** | — | `/`, `/webhook/**`, `/actuator/health` |

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

> All paths below are relative to the context path `/device`.

### App Endpoints (Connector Auth — `X-App-Auth-Key`)

> For full request/response schemas see [device-api-agent-app.md](device-api-agent-app.md).

| Method | Path                            | Description                        |
|--------|---------------------------------|------------------------------------|
| POST   | `/device/app/registration/link` | Link device to connector           |
| POST   | `/device/app/centrifugo/token`  | Get Centrifugo subscription token  |
| POST   | `/device/app/tools/result`      | Submit tool result from device     |
| POST   | `/device/app/trigger/new`       | Submit trigger from device         |

### Agent API (API Key — `X-Api-Key`)

> For full request/response schemas see [device-api-agent-app.md](device-api-agent-app.md).

| Method | Path                                              | Description                          |
|--------|---------------------------------------------------|--------------------------------------|
| GET    | `/device/agent/settings`                          | Get agent settings (from API key)    |
| POST   | `/device/agent/centrifugo/token`                  | Get Centrifugo token for agent       |
| POST   | `/device/agent/tool/call/{connectorId}`           | Push tool_use to connector/device    |
| POST   | `/device/agent/tool/check/{connectorId}`          | Check tool_use permission (no push)  |
| POST   | `/device/agent/tool/result`                       | Save tool_use result from agent      |
| GET    | `/device/agent/connectors/`                       | List connected connectors            |
| GET    | `/device/agent/connectors/triggers/`              | Get all connector triggers           |
| GET    | `/device/agent/connectors/triggers/{connectorId}` | Get connector triggers               |
| GET    | `/device/agent/connectors/tools/`                 | List all connector tools (stub)      |
| GET    | `/device/agent/connectors/tools/{connectorId}`    | Get connector tools                  |
| GET    | `/device/agent/skills/`                            | List agent's skills (paginated)      |
| GET    | `/device/agent/skills/{skillPubId}.zip`            | Download skill files as ZIP          |

### App Management (JWT)

| Method | Path                                            | Description                      |
|--------|-------------------------------------------------|----------------------------------|
| GET    | `/device/manage/apps/`                          | List all apps                    |
| POST   | `/device/manage/apps/`                          | Create new app                   |
| GET    | `/device/manage/apps/{appId}`                   | Get specific app                 |
| PUT    | `/device/manage/apps/{appId}`                   | Update app                       |
| DELETE | `/device/manage/apps/{appId}`                   | Delete app (soft)                |
| POST   | `/device/manage/apps/{appId}/regenerate`        | Regenerate auth key              |
| GET    | `/device/manage/apps/{appId}/detail`            | Get app detail with device info  |
| POST   | `/device/manage/apps/{appId}/disconnect`        | Disconnect device from app       |

### Connector Catalog (JWT)

> For full request/response schemas see [device-api-manage-connectors.md](device-api-manage-connectors.md).

| Method | Path                           | Description                                                                   |
|--------|--------------------------------|-------------------------------------------------------------------------------|
| GET    | `/device/manage/connectors/`   | List connectors (paginated, filter by `type`, search by `name`/`description`) |

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

### Board Management (JWT)

| Method | Path                                                   | Description                   |
|--------|--------------------------------------------------------|-------------------------------|
| GET    | `/device/manage/boards/`                               | List boards                   |
| POST   | `/device/manage/boards/`                               | Create board                  |
| GET    | `/device/manage/boards/{pubId}`                        | Get board                     |
| GET    | `/device/manage/boards/{boardPubId}/tasks/`            | Get board tasks by status     |
| POST   | `/device/manage/boards/{boardPubId}/tasks/`            | Create board task             |
| PATCH  | `/device/manage/boards/tasks/{taskPubId}/status`       | Update task status            |
| GET    | `/device/manage/boards/tasks/{taskPubId}/comments/`    | Get task comments             |
| POST   | `/device/manage/boards/tasks/{taskPubId}/comments/`    | Create task comment           |

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

| Method | Path                  | Description                 |
|--------|-----------------------|-----------------------------|
| GET    | `/device/`            | Application info and uptime |
| GET    | `/device/favicon.ico` | Empty favicon               |

## Centrifugo Integration

device-api integrates with Centrifugo for real-time messaging:
- **Device channels** (`device:{deviceId}`) — push tool_use requests to devices
- **Agent channels** (`agent:{apiKeyPubId}`) — deliver tool_result and trigger events to agents
- Connection and subscription token generation (ES256 JWT)

## AI Agent Flow

1. Agent authenticates with API Key (`X-Api-Key` header)
2. Gets settings via `GET /agent/settings` (prompt, available tools, triggers)
3. Gets Centrifugo token via `POST /agent/centrifugo/token` for channel `agent:{apiKeyPubId}`
4. Subscribes to agent channel for real-time events
5. Calls `POST /agent/tool/call/{connectorId}` to invoke a tool on a connector/device
   - Tool authorization checked against `agent_tools` table
   - `tool_use_log` entry created
6. Device executes tool and sends result via `POST /app/tools/result`
   - `tool_use_log` updated with result
   - Result published to agent's Centrifugo channel
7. Device triggers (`POST /app/trigger/new`) are routed to subscribed agents:
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
4. Agent calls `POST /agent/tool/call/{connectorId}` with integration tool → executed directly against platform API
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
- `boards` — Boards linked to agentic teams
- `board_tasks` — Tasks on boards (EPIC, TASK, SUBTASK)
- `board_task_comments` — Comments on board tasks

Migrations: `services/device-api/src/main/resources/db/changelog/`
