# control-api

Control API for connector registration, tool delivery, trigger submission, and AI agent integration.

## Configuration

| Setting         | Value        |
|-----------------|--------------|
| Port            | 8080         |
| Context Path    | `/control`    |
| Management Port | 8088         |
| Database        | am_control_db |

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

> All paths below are relative to the context path `/control`.

### App Endpoints (Connector Auth — `X-App-Auth-Key`)

> For full request/response schemas see [control-api-agent-app.md](control-api-agent-app.md).

| Method | Path                            | Description                        |
|--------|---------------------------------|------------------------------------|
| POST   | `/control/app/registration/link` | Link device to connector           |
| POST   | `/control/app/centrifugo/token`  | Get Centrifugo subscription token  |
| POST   | `/control/app/tools/result`      | Submit tool result from device     |
| POST   | `/control/app/trigger/new`       | Submit trigger from device         |

### Agent API (API Key — `X-Api-Key`)

> For full request/response schemas see [control-api-agent-app.md](control-api-agent-app.md).

| Method | Path                                              | Description                          |
|--------|---------------------------------------------------|--------------------------------------|
| GET    | `/control/agent/settings`                          | Get agent settings (from API key)    |
| POST   | `/control/agent/centrifugo/token`                  | Get Centrifugo token for agent       |
| POST   | `/control/agent/tool/call/{connectorId}`           | Push tool_use to connector/control    |
| POST   | `/control/agent/tool/check/{connectorId}`          | Check tool_use permission (no push)  |
| POST   | `/control/agent/tool/result`                       | Save tool_use result from agent      |
| GET    | `/control/agent/connectors/`                       | List connected connectors            |
| GET    | `/control/agent/connectors/triggers/`              | Get all connector triggers           |
| GET    | `/control/agent/connectors/triggers/{connectorId}` | Get connector triggers               |
| GET    | `/control/agent/connectors/tools/`                 | List all connector tools (stub)      |
| GET    | `/control/agent/connectors/tools/{connectorId}`    | Get connector tools                  |
| GET    | `/control/agent/skills/`                            | List agent's skills (paginated)      |
| GET    | `/control/agent/skills/{skillPubId}.zip`            | Download skill files as ZIP          |

### App Management (JWT)

| Method | Path                                            | Description                      |
|--------|-------------------------------------------------|----------------------------------|
| GET    | `/control/manage/apps/`                          | List all apps                    |
| POST   | `/control/manage/apps/`                          | Create new app                   |
| GET    | `/control/manage/apps/{appId}`                   | Get specific app                 |
| PUT    | `/control/manage/apps/{appId}`                   | Update app                       |
| DELETE | `/control/manage/apps/{appId}`                   | Delete app (soft)                |
| POST   | `/control/manage/apps/{appId}/regenerate`        | Regenerate auth key              |
| GET    | `/control/manage/apps/{appId}/detail`            | Get app detail with device info  |
| POST   | `/control/manage/apps/{appId}/disconnect`        | Disconnect device from app       |

### Connector Catalog (JWT)

> For full request/response schemas see [control-api-manage-connectors.md](control-api-manage-connectors.md).

| Method | Path                                 | Description                                                                   |
|--------|--------------------------------------|-------------------------------------------------------------------------------|
| GET    | `/control/manage/connectors/`         | List connectors (paginated, filter by `type`, search by `name`/`description`) |
| GET    | `/control/manage/connectors/{code}`   | Get connector by code (includes `integrationMeta` when `type=INTEGRATION`)    |

### Skill Management (JWT)

> For full request/response schemas see [control-api-manage-skills.md](control-api-manage-skills.md).

| Method | Path                                            | Description                                                |
|--------|-------------------------------------------------|------------------------------------------------------------|
| GET    | `/control/manage/skills/`                        | List own skills (search, connector filter, pagination)     |
| GET    | `/control/manage/skills/public/`                 | List public non-featured skills                             |
| GET    | `/control/manage/skills/featured/`               | List featured skills                                        |
| GET    | `/control/manage/skills/{pubId}`                 | Get skill detail with `SKILL.md` content                    |
| GET    | `/control/manage/skills/{pubId}/agents/`         | List my agents that use this skill (paginated, search)      |
| POST   | `/control/manage/skills/`                        | Create skill from JSON                                      |
| POST   | `/control/manage/skills/upload`                  | Create skill from uploaded `SKILL.md` file                  |
| PUT    | `/control/manage/skills/{pubId}`                 | Update skill (bumps version)                                |
| DELETE | `/control/manage/skills/{pubId}`                 | Soft-delete skill                                           |
| POST   | `/control/manage/skills/{pubId}/clone`           | Clone a public/featured skill into the user's collection    |

### Tool Management (JWT)

| Method | Path                        | Description              |
|--------|-----------------------------|--------------------------|
| GET    | `/control/manage/tools/`     | List all device tools    |

### Trigger Management (JWT)

| Method | Path                            | Description                        |
|--------|---------------------------------|------------------------------------|
| GET    | `/control/manage/triggers/`      | List all device triggers           |

### Trigger Logs (JWT)

| Method | Path                              | Description                                     |
|--------|-----------------------------------|-------------------------------------------------|
| GET    | `/control/manage/trigger-logs/`    | List trigger logs (filter by deviceId, connectorPubId) |

### Webhook Delivery Logs (JWT)

| Method | Path                                    | Description                                          |
|--------|-----------------------------------------|------------------------------------------------------|
| GET    | `/control/manage/webhook-deliveries/`    | List webhook delivery logs (optional `?apiKeyPubId`, pagination) |

### Agent Management (JWT)

| Method | Path                                       | Description                   |
|--------|--------------------------------------------|-------------------------------|
| GET    | `/control/manage/agents/`                   | List agents (paginated; optional `?agenticTeamPubId=`, `?search=` by name/description). Each item includes `skills: [{pubId, name}]` for quick navigation to the skill page |
| POST   | `/control/manage/agents/`                   | Create agent                  |
| GET    | `/control/manage/agents/{apiKeyPubId}`      | Get agent (includes `skills: [{pubId, name}]`) |
| PUT    | `/control/manage/agents/{apiKeyPubId}`      | Update agent                  |
| DELETE | `/control/manage/agents/{apiKeyPubId}`      | Delete agent                  |

### Agentic Teams (JWT)

| Method | Path                                           | Description                   |
|--------|-------------------------------------------------|-------------------------------|
| GET    | `/control/manage/agentic-teams/`                | List agentic teams            |
| POST   | `/control/manage/agentic-teams/`                | Create agentic team           |
| GET    | `/control/manage/agentic-teams/{pubId}`         | Get agentic team              |
| PUT    | `/control/manage/agentic-teams/{pubId}`         | Update agentic team           |
| DELETE | `/control/manage/agentic-teams/{pubId}`         | Delete agentic team           |

### Board Management (JWT)

| Method | Path                                                   | Description                   |
|--------|--------------------------------------------------------|-------------------------------|
| GET    | `/control/manage/boards/`                               | List boards                   |
| POST   | `/control/manage/boards/`                               | Create board                  |
| GET    | `/control/manage/boards/{pubId}`                        | Get board                     |
| GET    | `/control/manage/boards/{boardPubId}/tasks/`            | Get board tasks by status     |
| POST   | `/control/manage/boards/{boardPubId}/tasks/`            | Create board task             |
| PATCH  | `/control/manage/boards/tasks/{taskPubId}/status`       | Update task status            |
| GET    | `/control/manage/boards/tasks/{taskPubId}/comments/`    | Get task comments             |
| POST   | `/control/manage/boards/tasks/{taskPubId}/comments/`    | Create task comment           |

### Tool Use Logs (JWT)

| Method | Path                            | Description                        |
|--------|---------------------------------|------------------------------------|
| GET    | `/control/manage/tool-use-logs/` | List tool use logs (filter by apiKeyPubId) |

### Integration Management (JWT)

> For full request/response schemas see [control-api-manage-integrations.md](control-api-manage-integrations.md).

| Method | Path                                                             | Description                                                    |
|--------|------------------------------------------------------------------|----------------------------------------------------------------|
| GET    | `/control/manage/integrations/credentials/`                       | List integration credentials (optional `?connectorCode=`)      |
| POST   | `/control/manage/integrations/credentials/`                       | Create integration credentials                                 |
| GET    | `/control/manage/integrations/credentials/{credentialId}`         | Get integration credentials details                            |
| PATCH  | `/control/manage/integrations/credentials/{credentialId}/`        | Update integration settings (enabled, name)                    |
| PUT    | `/control/manage/integrations/credentials/{credentialId}/secret`  | Update integration secret (credential values)                  |
| DELETE | `/control/manage/integrations/credentials/{credentialId}`         | Delete integration credentials                                 |
| GET    | `/control/manage/integrations/tools/?connectorCode={code}`        | List predefined tools exposed by an integration connector      |
| GET    | `/control/manage/integrations/triggers/?connectorCode={code}`     | List predefined triggers exposed by an integration connector   |

### Integration Webhooks (Public)

| Method | Path                                                | Description                     |
|--------|-----------------------------------------------------|---------------------------------|
| POST   | `/control/webhook/integration/{integrationPubId}`    | Receive inbound webhook events  |

### Public

| Method | Path                  | Description                 |
|--------|-----------------------|-----------------------------|
| GET    | `/control/`            | Application info and uptime |
| GET    | `/control/favicon.ico` | Empty favicon               |

## Centrifugo Integration

control-api integrates with Centrifugo for real-time messaging:
- **Device channels** (`device:{deviceId}`) — push tool_use requests to devices
- **Agent channels** (`agent:{apiKeyPubId}`) — deliver tool_result and trigger events to agents
- Connection and subscription token generation (ES256 JWT)

## AI Agent Flow

1. Agent authenticates with API Key (`X-Api-Key` header)
2. Gets settings via `GET /agent/settings` (prompt, available tools, triggers)
3. Gets Centrifugo token via `POST /agent/centrifugo/token` for channel `agent:{apiKeyPubId}`
4. Subscribes to agent channel for real-time events
5. Calls `POST /agent/tool/call/{connectorId}` to invoke a tool on a connector/control
   - Tool authorization checked against `agent_tools` table
   - `tool_use_log` entry created
6. Device executes tool and sends result via `POST /app/tools/result`
   - `tool_use_log` updated with result
   - Result published to agent's Centrifugo channel
7. Device triggers (`POST /app/trigger/new`) are routed to subscribed agents:
   - `triggers_to=centrifugo` — published to agent's Centrifugo channel
   - `triggers_to=webhook` — delivered directly by control-api to the webhook URL configured in agent
   - `triggers_to=ignore` — not routed (default)
   - `triggers_allow_all=true` — agent receives all triggers without explicit subscription

## Integration Flow (Telegram, etc.)

1. User creates integration via `POST /manage/integrations/credentials/` with platform token
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

Migrations: `services/control-api/src/main/resources/db/changelog/`
