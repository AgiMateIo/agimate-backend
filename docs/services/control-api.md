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
| POST   | `/control/agent/tool/call/{connectorId}`           | Push tool_call to connector/control    |
| POST   | `/control/agent/tool/check/{connectorId}`          | Check tool_call permission (no push)  |
| POST   | `/control/agent/tool/result`                       | Save tool_call result from agent      |
| GET    | `/control/agent/connectors/`                       | List connected connectors            |
| GET    | `/control/agent/connectors/triggers/`              | Get all connector triggers           |
| GET    | `/control/agent/connectors/triggers/{connectorId}` | Get connector triggers               |
| GET    | `/control/agent/connectors/tools/`                 | List all connector tools (stub)      |
| GET    | `/control/agent/connectors/tools/{connectorId}`    | Get connector tools                  |
| GET    | `/control/agent/skills/`                            | List agent's skills (paginated)      |

### App Management (JWT)

| Method | Path                                            | Description                      |
|--------|-------------------------------------------------|----------------------------------|
| GET    | `/control/manage/apps/`                          | List all apps                    |
| POST   | `/control/manage/apps/`                          | Create new app                   |
| GET    | `/control/manage/apps/{appId}`                   | Get specific app                 |
| PUT    | `/control/manage/apps/{appId}`                   | Update app                       |
| DELETE | `/control/manage/apps/{appId}`                   | Delete app (soft)                |
| POST   | `/control/manage/apps/{appId}/regenerate`        | Regenerate auth key              |
| GET    | `/control/manage/apps/{appId}/tools/`            | List the app's tools (was `/manage/app-tools/{appId}`)    |
| GET    | `/control/manage/apps/{appId}/triggers/`         | List the app's triggers (was `/manage/app-triggers/{appId}`) |

### Connector Catalog (JWT)

> For full request/response schemas see [control-api-manage-connectors.md](control-api-manage-connectors.md).

| Method | Path                                 | Description                                                                   |
|--------|--------------------------------------|-------------------------------------------------------------------------------|
| GET    | `/control/manage/connectors/`               | List connectors (paginated, filter by `type`, search by `name`/`description`) |
| GET    | `/control/manage/connectors/{code}`         | Get connector by code (includes `integrationMeta` when `type=INTEGRATION`)    |
| GET    | `/control/manage/connectors/{code}/tools/`           | Catalog (type-level) tools (STATIC connectors; empty for DYNAMIC)    |
| GET    | `/control/manage/connectors/{code}/tools/{toolName}` | Parameter schema of a single catalog tool                           |
| GET    | `/control/manage/connectors/{code}/triggers/`        | Catalog (type-level) triggers (integration connectors)              |

### Connector Jobs (JWT)

> For full request/response schemas see [control-api-manage-connector-jobs.md](control-api-manage-connector-jobs.md).

| Method | Path                                              | Description                                                  |
|--------|---------------------------------------------------|--------------------------------------------------------------|
| GET    | `/control/manage/connector-jobs/`                 | List background connector jobs (filter by `connectorCode`, `kind`) |
| POST   | `/control/manage/connector-jobs/{id}/pause`       | Pause a job (scheduler skips it until resumed)               |
| POST   | `/control/manage/connector-jobs/{id}/resume`      | Resume a paused job (`nextRunAt` recomputed from now)        |
| DELETE | `/control/manage/connector-jobs/{id}`             | Delete a USER/AGENT job (SYSTEM jobs are sync-managed)       |

### Skill Management (JWT)

> For full request/response schemas see [control-api-manage-skills.md](control-api-manage-skills.md).

| Method | Path                                            | Description                                                |
|--------|-------------------------------------------------|------------------------------------------------------------|
| GET    | `/control/manage/skills/`                        | List own skills (search, connector filter, pagination)     |
| GET    | `/control/manage/skills/public/`                 | List ALL public skills                                      |
| GET    | `/control/manage/skills/{id}`                    | Get skill detail with SKILL.md body (`mdContent`)           |
| GET    | `/control/manage/skills/{id}/agents/`            | List my agents that use this skill (paginated, search)      |
| POST   | `/control/manage/skills/`                        | Create skill from JSON                                      |
| POST   | `/control/manage/skills/upload`                  | Create skill from uploaded SKILL.md file                    |
| PUT    | `/control/manage/skills/{id}`                    | Update skill (bumps version)                                |
| DELETE | `/control/manage/skills/{id}`                    | Soft-delete skill                                           |

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
| POST   | `/control/manage/agents/`                   | Create agent (optional `skillIds` — bound in the same transaction; optional `presetCode` — funnel analytics, must exist) |
| GET    | `/control/manage/agents/{apiKeyPubId}`      | Get agent (includes `skills: [{pubId, name}]`) |
| PUT    | `/control/manage/agents/{apiKeyPubId}`      | Update agent                  |
| DELETE | `/control/manage/agents/{apiKeyPubId}`      | Delete agent                  |

### Agent Presets (JWT)

Role presets for the agent creation wizard. A preset is a pure prefill: the frontend fills the
wizard's editable fields from it (instructions, skill set) and the final values arrive via the
regular create-agent request. System presets are seeded from classpath (`presets/<code>/PRESET.md`,
same pattern as system skills), idempotently by `code`. Presets can also be created/edited by an
**ADMIN** through the API (see [control-api-manage-agent-presets.md](control-api-manage-agent-presets.md)).

| Method | Path                              | Description                                                  |
|--------|-----------------------------------|--------------------------------------------------------------|
| GET    | `/control/manage/agent-presets/`  | List enabled presets: `code`, `name`, `description`, full `instructions`, resolved `skills: [{id, name, description}]`, derived `connectorCodes`, plus raw `skillNames`, `sortOrder`, `enabled` |
| GET    | `/control/manage/agent-presets/all/` | **ADMIN** — list all presets including disabled |
| POST   | `/control/manage/agent-presets/`  | **ADMIN** — create a preset |
| PATCH  | `/control/manage/agent-presets/{id}` | **ADMIN** — partial update (`code` immutable) |

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
| GET    | `/control/manage/boards/{boardId}`                              | Get board                     |
| GET    | `/control/manage/boards/{boardId}/tasks/`                       | Get board tasks by status     |
| POST   | `/control/manage/boards/{boardId}/tasks/`                       | Create board task             |
| PATCH  | `/control/manage/boards/{boardId}/tasks/{taskId}/status`        | Update task status            |
| GET    | `/control/manage/boards/{boardId}/tasks/{taskId}/comments/`     | Get task comments             |
| POST   | `/control/manage/boards/{boardId}/tasks/{taskId}/comments/`     | Create task comment           |

### Tool Call Logs (JWT)

| Method | Path                            | Description                        |
|--------|---------------------------------|------------------------------------|
| GET    | `/control/manage/tool-call-logs/` | List tool call logs (filter by apiKeyPubId) |

### Connections Management (JWT)

> For full request/response schemas see [control-api-manage-connections.md](control-api-manage-connections.md).

| Method | Path                                                  | Description                                                       |
|--------|-------------------------------------------------------|------------------------------------------------------------------|
| GET    | `/control/manage/connections/`                        | List the user's connections (filters `?connectorCode=&scope=&enabled=`) |
| POST   | `/control/manage/connections/`                        | Create a connection (credentials; integration connectors only)   |
| GET    | `/control/manage/connections/{connectionId}`          | Get connection details                                           |
| PATCH  | `/control/manage/connections/{connectionId}`          | Update connection settings (enabled, name)                       |
| PUT    | `/control/manage/connections/{connectionId}/secret`   | Update connection secret (credential values)                     |
| DELETE | `/control/manage/connections/{connectionId}`          | Delete a connection                                             |
| GET    | `/control/manage/connections/{connectionId}/tools/`   | List tools of a connection instance (SPI)                        |
| POST   | `/control/manage/connections/{connectionId}/test`     | Validate credentials + (MCP) reload tools                        |

Predefined tools/triggers of a connector **type** moved to the catalog:

| Method | Path                                          | Description                                            |
|--------|-----------------------------------------------|--------------------------------------------------------|
| GET    | `/control/manage/connectors/{code}/tools/`    | Predefined tools of an integration connector type      |
| GET    | `/control/manage/connectors/{code}/triggers/` | Predefined triggers of an integration connector type   |

### Connection Webhooks (Public)

| Method | Path                                  | Description                                                        |
|--------|---------------------------------------|--------------------------------------------------------------------|
| POST   | `/control/webhook/{connectionId}`     | Receive inbound webhook events for a connection (integration types) |

### Public

| Method | Path                  | Description                 |
|--------|-----------------------|-----------------------------|
| GET    | `/control/`            | Application info and uptime |
| GET    | `/control/favicon.ico` | Empty favicon               |

## Centrifugo Integration

control-api integrates with Centrifugo for real-time messaging:
- **Device channels** (`device:{deviceId}`) — push tool_call requests to devices
- **Agent channels** (`agent:{apiKeyPubId}`) — deliver tool_result and trigger events to agents
- Connection and subscription token generation (ES256 JWT)

## AI Agent Flow

1. Agent authenticates with API Key (`X-Api-Key` header)
2. Gets settings via `GET /agent/settings` (instructions, available tools, triggers)
3. Gets Centrifugo token via `POST /agent/centrifugo/token` for channel `agent:{apiKeyPubId}`
4. Subscribes to agent channel for real-time events
5. Calls `POST /agent/tool/call/{connectorId}` to invoke a tool on a connector/control
   - Tool authorization checked against `agent_tools` table
   - `tool_call_log` entry created
6. Device executes tool and sends result via `POST /app/tools/result`
   - `tool_call_log` updated with result
   - Result published to agent's Centrifugo channel
7. Device triggers (`POST /app/trigger/new`) are routed to subscribed agents:
   - `triggers_to=centrifugo` — published to agent's Centrifugo channel
   - `triggers_to=webhook` — delivered directly by control-api to the webhook URL configured in agent
   - `triggers_to=ignore` — not routed (default)
   - `triggers_allow_all=true` — agent receives all triggers without explicit subscription

## Integration Flow (Telegram, etc.)

1. User creates a connection via `POST /manage/connections/` with platform token
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
- `tool_call_logs` — Tool invocation logs (request + result)
- `agents` — Agent configuration (instructions, triggers_allow_all, triggers_to, webhook_url, webhook_auth_header)
- `agent_presets` — Role presets for the creation wizard (code, instructions prefill, skill_names)
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
