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
| `APP_CONTENT_LANGUAGE`  | System content language of the installation: `ru` (default) or `en`. See [System content language](#system-content-language) |
| `APP_SECRETS_ENCRYPTION_KEY` | KEK for the envelope-encrypted `secrets` store (AES-256, Base64, 32 bytes). Required outside `local`/`test` profiles — startup fails without it |
| `APP_INTEGRATION_WEBHOOK_BASE_URL` | Public URL for webhook callbacks |
| `INBOUND_RATE_LIMIT_ENABLED` | Inbound rate limiting for device/webhook traffic (default `true`) |
| `INBOUND_RATE_LIMIT_TRIGGERS_PER_MINUTE` | Trigger events per minute per connection — `/app/trigger/new` + `/webhook/*` (default `120`, `<=0` disables) |
| `INBOUND_RATE_LIMIT_TOOL_RESULTS_PER_MINUTE` | Tool results per minute per connection — `/app/tools/result` (default `120`, `<=0` disables) |
| `INBOUND_RATE_LIMIT_FILE_UPLOADS_PER_MINUTE` | File uploads per minute per connection — `/app/files` (default `30`, `<=0` disables) |
| `APP_FILES_BACKEND` | Connector file layer blob store: `local` (disk, default; root — `APP_FILES_LOCAL_DIR`, empty = `~/.agimate/files`) or `s3` (`docs/connectors/files.md`) |
| `APP_FILES_BUCKET` / `APP_FILES_ENDPOINT` / `APP_FILES_REGION` / `APP_FILES_ACCESS_KEY` / `APP_FILES_SECRET_KEY` | s3 backend only; empty endpoint = AWS, empty keys = AWS credentials chain |

## System content language

`APP_CONTENT_LANGUAGE` (property `app.content.language`, enum `ContentLanguage`: `ru` | `en`) selects
the language of the content the platform ships: agent presets, system skills, the connector catalog
and the trusted instruction blocks the platform injects into agent prompts. It is **not** the language
agents reply in — that follows the user and is stated in the instructions themselves. A typo in the
value fails startup (the property binds to an enum).

Content lives per language in the classpath, and `SeedContentLocator` is the only place that knows
the layout:

```
resources/seed/<lang>/presets/<code>/PRESET.md      # seeded by SystemPresetBootstrap
resources/seed/<lang>/skills/<code>/SKILL.md        # seeded by SystemSkillBootstrap
resources/seed/<lang>/connectors.properties         # connector catalog name/description
resources/seed/<lang>/prompt.properties             # trusted prompt blocks (behaviour, not captions)
```

Adding a language = a new `ContentLanguage` constant plus a copy of the directory. Only `title`,
`description` and the body are translated: `name`, `skills`, `connectors` and `sortOrder` are machine
keys and must be byte-identical across languages — a translated slug silently breaks the
preset→skill and skill→connector links, which is what `SeedContentParityTest` guards. A file missing
for the selected language falls back to `ru` with a warning rather than failing the seed.

**Two different lifecycles:**

- **Presets and skills — the language is fixed by the first seeding.** Both bootstraps are
  seed-only-if-missing, keyed by the language-independent `name`, and the database holds one language
  at a time. Changing `APP_CONTENT_LANGUAGE` on an already-seeded environment therefore translates
  nothing: it is a choice for a fresh installation. To switch in development, delete the system rows
  (`skills` where `user_id = '00000000-0000-0000-0000-000000000000'`, and `agent_presets`) and
  restart. Existing agents never follow a switch in any case — preset `instructions` are copied into
  the agent at creation, and skills are bound by ID.
- **Connector catalog — follows the property.** `ConnectorBootstrap` upserts `connectors` rows on
  every start, so `name`/`description` move to the new language after a restart, with no migration.
  Russian stays in the code (`connectorName()`/`connectorDescription()`) as the last-resort fallback,
  which is why there is deliberately no `seed/ru/connectors.properties`; `ConnectorTextsTest`
  enforces that every registered code has a translation in every other language.
- **Prompt blocks — follow the property.** Resolved per run in `RunContextService`, so a restart is
  enough. Keys live in `PromptTexts`: `run.trigger.guidance` (autonomous event handling),
  `run.tool-call.guidance` (never imitate a tool call in text), `run.attachment.guidance`
  (the `[[attach:]]` convention), and `connector.<code>.<trigger>.guidance` with a fallback to
  `connector.<code>.guidance` for `ContextDirectives.guidance`. These are **behaviour, not captions** —
  a bad translation changes what agents do — which is why they sit in a bundle separate from the
  connector catalog: different reader, different cost of error. `PromptTextsTest` enforces
  completeness; the Russian source stays in `RunContextService`/`ContextDirectives` as the fallback.

**The tool layer needs no bundle by convention** — tool descriptions (`@Tool`), parameter descriptions
(`@ToolParam`, including example values inside them) and trigger descriptions
(`TriggerSpec.description`) are written in English, because the model reads them and no translation
bundle covers them. `@Tool(title)` is not used at all: display titles for tools are an open UI
question, and having them on one connector only made listings uneven. See
`docs/connectors/architecture.md`.

The one path by which a Russian tool text could reach a user is the platform connector relaying
`get_connector` output; the English `platform` skill instructs the agent to retell rather than quote.

## Inbound Rate Limiting

Trigger and tool-result ingestion from external sources is rate-limited per connection (token bucket, in-memory, burst = the per-minute limit). The key is `connectionId` — for device apps `app.id == connection.id`, for webhooks it is the path parameter, so all inbound surfaces share one mechanism:

- `/app/trigger/new`, `/app/tools/result` — over-limit requests get **429** `{ "error": { "message": "..." } }`; the device should back off.
- `/webhook/{connectionId}` — over-limit requests are dropped silently with **200** `ok` (the source is unauthenticated, and webhook platforms endlessly retry non-2xx responses). The drop is logged.

## API reference

Paths, request and response schemas are generated from the code. See Swagger at
**`/control/docs/ui`** (`develop` profile) — the auth contour for each prefix is in the
[Authentication](#authentication) table above.

Common error envelope for every group:

| Status | Meaning |
|--------|---------|
| 401 | Missing or invalid credential |
| 403 | Authenticated but not authorized |
| 429 | Inbound rate limit exceeded (see above) |

## Centrifugo integration

Real-time delivery to devices and agents. Channels are namespaced (`app`, `agent`, `user`,
`webchat` — see `ops/centrifugo/config.json`); all of them are server-side only, clients may
neither subscribe nor publish on their own.

Client connection and subscription tokens are ES256 JWTs signed by control-api with
`CENTRIFUGO_PRIVATEKEY`; Centrifugo verifies them with the matching public key in
`client.token.ecdsa_public_key`. This pair is independent of the user-JWT one.

## Tool invocation

A tool call never executes inside the request that asked for it — the caller gets an id and the
result arrives asynchronously. This is what lets a tool run on a device the platform does not
control, and it is why every tool call has a log row:

1. The agent asks control-api to invoke a tool on a connection.
2. control-api authorizes it against ABAC (`ConnectionAccessEvaluator`, `PolicyKind.TOOL`),
   writes a `tool_call_logs` row and returns its id.
3. Execution is dispatched by connector kind — internal connectors run in-process, integrations
   call the platform API, device connectors get a Centrifugo push.
4. The executor reports the result back; the log row is completed and the result is delivered to
   the agent over its channel.

Backend-side jobs follow the same shape through `connector_jobs`, claimed with
`FOR UPDATE SKIP LOCKED` (`docs/connectors/architecture.md`).

## Inbound triggers

Inbound events — a device trigger, a platform webhook, a message in a channel — converge on one
path: `TriggerRouterService` decides **which** agents get the event (bindings plus ABAC with
`PolicyKind.TRIGGER` and an optional `params_filter`), and the channel decides **how** the
conversation is conducted (message extraction, chat filtering, reply). Policy and channel are
deliberately separate layers; see
[agent-channels-integration.md](agent-channels-integration.md).

## Database

Migrations: `services/control-api/src/main/resources/db/changelog/`.

| Area | Tables |
|---|---|
| Agents | `agents`, `agent_presets`, `agent_skills`, `agent_llms`, `agentic_teams`, `skills` |
| Runs | `agent_runs`, `agent_run_turns`, `trigger_logs`, `tool_call_logs`, `webhook_delivery_logs` |
| Connections | `connectors`, `connections`, `connection_tools`, `connection_triggers`, `agent_connections`, `agent_connection_policies`, `connector_jobs` |
| Channels | `channels`, `channel_sessions`, `channel_session_messages`, `webchat_messages` |
| Apps and files | `apps`, `files`, `secrets` |
| LLM | `llm_providers`, `llm_provider_models`, `llm_model_defaults`, `llm_quotas`, `llm_usage_counters`, `llm_usage_log` |
| Connector data | `persistent_memory_hot`, `persistent_memory_cold`, `sheets`, `sheet_rows`, `boards`, `board_tasks`, `board_task_comments` |
