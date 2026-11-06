# Threat model — AgiMate backend

This file is input for Anthropic's OSS Scanner. It describes what the code is, where untrusted input
enters, which components matter, and how we would rate findings. It is guidance for finding real
bugs, not a claim that the listed risks are absent.

## What this project is

AgiMate is a self-hosted platform where a team builds AI agents that call tools through *connectors*.
A connector instance (`connection`) is a credential plus a set of tools, triggers and jobs: a Telegram
bot, an MCP server, a desktop or Android app, or an internal facility such as memory, schedules,
sheets, files, web search, boards and agent-to-agent messages. Agents belong to users and, through
teams, to groups.

The repository contains three Spring Boot services and two shared libraries:

- `services/user-api` — identity: OAuth2 (Google, Yandex, GitHub, VK ID), email/password, JWT
  access/refresh, sessions and push devices. It knows nothing about agents.
- `services/control-api` — the domain and the policy: agents, skills, presets, connectors,
  connections, channels, triggers, boards, LLM providers, quotas. It serves the dashboard
  (`/manage/**`), external agents (`/agent/**`), connected apps (`/app/**`), public webhooks and
  OAuth (`/webhook/**`, `/oauth/**`), file downloads (`/files/**`), an ACP WebSocket (`/acp`), an MCP
  server (`/mcp`) and the worker gRPC protocol (`:9091`). All ABAC decisions live here.
- `services/agent-worker` — a headless consumer of the DBOS queue: fetches the run context from
  control-api over gRPC, drives the LLM loop, calls tools through control-api and writes history. It
  owns no control-plane database.
- `services/libs/common` — shared security, JWT, response envelopes and outbound guards.
- `services/libs/agentworker-proto` — the protobuf/gRPC contract.

Stack: Java 25, Spring Boot 4, PostgreSQL 18, Liquibase, gRPC, Centrifugo, DBOS.

## Scope

Primary: `services/**` — the Java services and shared libraries.

Secondary: `clients/acp-bridge` (a small Node bridge for IDE clients).

Out of scope: `docs/**` (Russian design documentation; it describes behavior and is not executed),
`ops/**` (compose/helm/scripts for a local or cluster deployment), `ci/**`, Markdown and build files,
generated code under `build/`, test code as a production surface (tests are useful as reproducers,
but a bug that exists only in test code is not a finding), and `clients/acp-bridge/node_modules`.

Seed data under `services/*/src/main/resources/seed/**` is content inserted into prompts, skills and
catalogues. It is seed-only-if-missing: editing it does not change an already-seeded installation.
Treat it as content, not as a trusted instruction source; prompt injection through seed text is not a
finding unless it crosses a boundary below.

## Components that matter most

- `services/control-api/.../security/**` — authentication filters and the four ordered filter chains.
- `services/control-api/.../abac/**` — `ConnectionAccessEvaluator`, binding and policy resolution.
- `services/control-api/.../connectors/**` — SPI, credential store, MCP, Telegram, internal tools,
  async tool execution and `connector_jobs`.
- `services/control-api/.../service/trigger/**`, `.../service/channel/**`, `.../service/delivery/**` —
  trigger routing, channels, reply delivery.
- `services/control-api/.../service/secret/**`, `.../service/llm/**`, `.../storage/**` — secrets,
  LLM credentials, files.
- `services/libs/common/.../net/**` — SSRF, redirect and TLS-trust guards.
- `services/agent-worker/.../agent/context/**` — prompt rendering and untrusted-content wrapping.
- `services/user-api/.../security/**` — OAuth2, JWT and refresh-token handling.

## Trust boundaries and untrusted input

### 1. Identity provider — user-api

Untrusted: OAuth2 callback parameters, redirect targets, login and password-reset input, refresh
tokens, device tokens, headers.

What matters:
- Account takeover, OAuth state/redirect handling, token leakage, refresh rotation and revocation,
  cookie flags (`HttpOnly`, `Secure`, `SameSite`), CSRF on cookie-based flows.
- JWT is ES256; the access token is returned in the body, the refresh token in an `HttpOnly` cookie
  for browsers (native clients receive it in the body). Control-api verifies the public key and reads
  roles; `ADMIN` gates `/manage/admin/**`.
- control-api → user-api notifications use a shared `X-S2S-Key` on `/internal/notifications`; the
  endpoint must not be reachable without it.

### 2. Public surface of control-api (no user credential)

- `POST /webhook/{connectionId}` — unauthenticated by design. Authenticity is delegated to the
  connector: today only Telegram supports webhooks, and it checks
  `X-Telegram-Bot-Api-Secret-Token` against the per-connection webhook secret with a constant-time
  compare. Unknown, disabled and failed-validation requests return `200` and are dropped; the
  endpoint is rate-limited per connection. The raw body is untrusted and is parsed by the
  connector's `normalizeInbound`.
- `/oauth/token`, `/oauth/revoke`, `/.well-known/**` — the MCP server's OAuth authorization server.
  Codes and access/refresh tokens are prefixed `agmc`/`agma`/`agmr`; client identity is a Client ID
  Metadata Document; the consent page is a JWT-authenticated `/manage/mcp-oauth/authorize/*` call.
  The token and revocation endpoints are public but rate-limited.
- `GET /files/{fileId}?v&exp&sig` — download by HMAC-signed link. The signature is checked before
  the file is opened; ownership was checked when the link was issued. The link addresses one
  immutable version.
- `GET /public/catalog/**` — public catalogue pages: descriptions only, no user data.
- `/actuator/health/**`, `/`, `/error`, `/favicon.ico`, `/docs/**` (OpenAPI only on developer
  profiles).
- Any path not listed above falls through to `anyRequest().authenticated()`; a new public path must
  be added to `SecurityConfig` deliberately.

### 3. User dashboard — `/manage/**` (JWT USER/ADMIN)

This is the largest user-controlled surface. Values written here become model instructions, network
targets or file content:

- Agents: name, system prompt/spec, type and `webhook_url`; skills and their bodies; presets;
  starters.
- LLM providers: `base_url`, `extra_body`, API key, model lists and quotas. `base_url` is fetched by
  the backend.
- Connections: open `params` (JSONB) and encrypted `secrets`; MCP server URL; Telegram bot token;
  app-declared tools/triggers/skill.
- Channels: `messageField`, reply templates (`replyToolParams`), `input_filter`, `stopCommand`,
  reply connection and tool.
- Files, sheets, boards, memory entries and agent-to-agent requests.
- `PATCH` is tri-state: absent/null leaves a field, a value writes it, an empty string clears it.
  Admin endpoints live under `/manage/admin/**` and require `ADMIN`.

Invariants: every resource is scoped to the authenticated user or a team they belong to; no IDOR;
secrets are write-only (responses expose presence, not values); admin routes are behind the
`/manage/admin/**` prefix; user-controlled URLs never reach internal addresses (see §7). User-authored
prompts and skills are trusted configuration of that user's own agent — prompt injection from a user
into their own agent is not a vulnerability. Injection from external data is.

### 4. External agents and apps

- `/agent/**` — `X-Api-Key` (`agnt…`). The agent's type decides which surface the key opens
  (`CENTRIFUGO`, `WEBHOOK`, `MCP`, `GENERIC`); a valid key on a foreign surface is `403`.
  `/agent/llm/` returns metadata of active BYOK bindings only — never key material.
- `/app/**` — `X-App-Auth-Key` (`appk…`). A connected app registers/links, submits triggers, returns
  tool results, uploads files and gets a Centrifugo token. Apps are semi-trusted: they declare
  dynamic tools/triggers and a skill, and those descriptions reach the model. A malicious app must
  not escape its own connection/agent namespace or forge another connection's events/results.
- `/acp` — WebSocket for IDE clients, agent key. While an ACP session is alive it contributes
  session-scoped tools (filesystem, terminal, session MCP) to the agent's DIALOGUE prompt. The IDE
  is a trusted client of its own agent; model output and tool results remain untrusted.
- `/mcp` — MCP server over HTTP, OAuth access token (`agma…`) or agent key; exposes the agent's tools
  to external MCP clients. Per-agent rate limits; task polling uses the MCP task extension.

### 5. Worker protocol — gRPC :9091

Agent-worker authenticates with a pool authkey (`wrkp…`) from configuration, not the database; TLS is
a deployment option. The worker fetches run context by `(agent_id, run_id)`, executes the model loop
and reports turns/tool results. Untrusted data crossing this boundary: model output, tool results and
external content.

Invariants:
- One agent's worker cannot read another agent's context; ids are scoped.
- DBOS durable-step results are plain serializable values — never protobuf messages, never secrets,
  never user content. Checkpoints hold ids and statuses; replay re-fetches context by id
  (`GetRunContext`, `GetTurn`, `GetToolResult`). A secret or prompt in a DBOS checkpoint is a finding.
- The queue `agent_exec` is partitioned by `sessionId`; one writer per session is a transport
  property.

### 6. Agent and model

The model is untrusted. It can be manipulated by anything it reads: webhooks, connector results, web
pages fetched by `web_fetch`, MCP tool descriptions/results, app-declared tools, files, board
comments and another agent's report.

What the platform does:
- Trusted instructions reach the model only via the system prompt (authored by the agent owner or
  platform code). External event payloads are wrapped as untrusted data with a data-not-instructions
  preamble, and closing tags inside the payload are neutralized (`ContextBuilder`). Tool output is
  rendered under `untrusted_tool_output`.
- The model never sees connector secrets; tool calls go through control-api, where ABAC checks the
  specific connection and tool before execution. `params_filter` can restrict argument values.
- A prompt injection that only makes the agent say something wrong inside its own conversation is
  low severity. A prompt injection that causes a *permitted* tool call with attacker-chosen
  arguments (send a message, write a row, fetch a URL, exfiltrate a file) is the platform's central
  risk and is rated high or critical depending on what crosses.

### 7. Outbound HTTP — SSRF and egress

User-controlled addresses are fetched by the backend: agent `webhook_url`, LLM provider `base_url`,
MCP server URL, media endpoints and images in provider responses.

`PublicTargets` is the single guard: scheme `http(s)`, a host, no `userinfo`, and every resolved
address publicly routable. It blocks loopback, any-local, link-local (including `169.254.169.254`),
site-local, multicast, IPv6 ULA `fc00::/7`, `0.0.0.0/8` and CGNAT `100.64.0.0/10`. In control-api the
check is wired into the HTTP client's DNS resolver, so a second DNS answer cannot rebind the name
(DNS rebinding), and redirects are disabled; in agent-worker it is checked on the connected socket
before TLS. `requireSyntax` validates the shape on save.

An optional system HTTP proxy is configured by the operator with a host allowlist (`*.domain`; no
public-suffix wildcards). When a host is proxied, the guard checks the proxy, not the target: the
target is resolved by the proxy, which must not reach internal networks. The list is operator-owned;
a user cannot add to it. TLS trust is the platform's anchors plus optional operator-provided anchors
(`OutboundTrust`); there is no custom certificate-validation code.

### 8. Connectors and tool execution

The connector SPI is composition: an identity handler plus capability interfaces (`ToolProvider`,
`TriggerProvider`, `JobProvider`, `PromptBlockProvider`, `ViewProvider`), resolved by
`ConnectorRegistry`.

- Tool execution never runs inside the request that asked for it: `AgentToolCallService` → ABAC →
  `tool_call_logs` row → `ConnectorService.pushToConnector` → async executor. Every call has a log
  row.
- Internal connectors run in-process (memory, time, sessions, sheets, files, web search, boards,
  agents, media, skill/tool loaders). The `platform` connector is a meta-agent surface that reads and
  commands the platform itself; its tools must not act on another user's setup, and secrets are
  link-only. Integration connectors call external APIs (Telegram, MCP). APP connectors push to a
  connected device via Centrifugo and report back over `/app/tools/result`. LOOPBACK is executed by
  the calling agent (`/tool/check` + `/tool/result`).
- `connector_jobs` are claimed with `FOR UPDATE SKIP LOCKED`, leased and retried; agent-scheduled
  jobs (`time.schedule`) carry the initiator's context and channel/session snapshot. A user must not
  be able to create a job that runs with another user's connection.
- MCP client: user-supplied server; tool schemas are attacker-controlled; OAuth discovery from
  `WWW-Authenticate`/protected-resource metadata; tokens in `secrets`; a 401 moves the connection to
  `AUTH_EXPIRED`; `oauth_refresh` is a declared job. `tools/list` and `tools/call` go through the
  outbound guard.
- MCP server: external clients see only the agent's tools; OAuth consent is per user; per-agent rate
  limits.
- APP declarations: a malicious app can define tool descriptions that prompt-inject the model or
  schemas that confuse the tool layer. Report if it escapes its own connection/agent or forges
  another connection's trigger/result.

### 9. Channels, sessions and triggers

- A trigger is routed by ABAC: agents with an active binding to the connection, minus the actor,
  narrowed by `TriggerAudience` when present, minus agents without a push transport; then
  `AgentConnectionPolicy` (`kind=TRIGGER`, default-allow inside a binding, exact name >
  binding-wide > default) and `params_filter`.
- A channel is the "how" layer: `input_filter`, handler-specific message extraction, session
  resolution and the stop command. `generic` channels drive behavior from `config` (`triggers`,
  `messageField`, `replyConnectionId`/`replyToolName`, `replyToolParams` placeholders). A crafted
  trigger field must not redirect a reply to another chat or inject tool arguments.
- The reply address is snapshotted into the run (`ChannelInfo.address`) and delivery re-checks
  channel ownership. `[[attach:agf_…]]` markers create attachments; only `answer` carries them and
  only handlers that support outbound attachments.
- Sessions: `agent_sessions` scope `CHANNEL` or `CONNECTION`; `channel_session_messages` is the
  channel projection; history comes from the turn ledger. One writer per session is enforced by the
  DBOS partition.
- Known limitation: one session per bot, not per chat. A run from chat 1 that absorbs a steered
  message from chat 2 answers only to chat 1. Report only with a concrete cross-chat leak scenario
  (an attacker must be able to steer a session they do not own).
- ACP `contributesPromptTools` exposes the IDE's filesystem/terminal/session-MCP tools to any
  DIALOGUE run of that agent while the ACP session lives (roadmap `channelOnly`). This is accepted
  for now; report if it lets a non-ACP participant reach those tools.
- Subagents and the `agents` connector: messages and reports cross agent boundaries inside a team.
  Team membership, binding and circle checks must hold; a subagent report is untrusted content.

### 10. Secrets

- `secrets` uses envelope encryption: a random DEK per secret encrypts the data with AES-256-GCM;
  the DEK is encrypted by a KEK from configuration; AAD is `entity + owner_id`, so a row moved to
  another owner cannot be decrypted. Outbound connector credentials live here. The inbound app
  verifier is non-reversible and is not stored in `secrets`.
- `connections.params` is the open part; fields declared `SECRET`, derived credentials and OAuth
  state stay encrypted. A URL with `userinfo` or a query string stays secret even when its field is
  open.
- LLM keys are decrypted only in `LlmCredentialsResolver` and delivered to the worker per call; they
  never enter a DBOS checkpoint, a log line, an error body or the model context. `/agent/llm/`
  exposes metadata only; platform LLM configuration never leaves the control plane.
- Invariants: no secret in logs, error messages, HTTP responses, model context or DBOS checkpoints;
  no cross-owner decryption; no secret exfiltration through a tool result.

### 11. Files

The file layer uses `agf_…` references, immutable versions, TTL, local disk or S3, and HMAC-signed
download links. Upload endpoints: `/manage/files` (per user) and `/app/files` (per connection).
Attachments reach channels through `[[attach:agf_…]]` markers. Look for path traversal, local file
read/write, S3 key injection, signature bypass, content-type confusion, archive/image parser bugs
(PNG charts, xlsx import) and symlink attacks.

### 12. Realtime

Control-api issues Centrifugo connection and subscription tokens signed with its own ES256 key,
independent of the user JWT. Namespaces are `app`, `agent` and `user`; clients get one subscription
to `user:{userId}`, which carries all application events including webchat messages. Subscriptions
and publications are server-side only (`allow_subscribe_for_client` and
`allow_publish_for_client` are `false`). Push notifications are composed by control-api and sent to
user-api with `X-S2S-Key`. A user must not be able to subscribe to another user's channel or forge
a push.

### 13. Admin

`/manage/admin/**` requires `ADMIN`; the prefix is the only gate (there is deliberately no
`@PreAuthorize` in that section). It manages platform LLM chains and reads any user's LLM usage. An
admin endpoint outside the prefix, or a role a normal user can obtain, is a finding.

## Security invariants (what a finding should violate)

1. No credential — connector secret, LLM API key, OAuth token, worker authkey or S2S key — reaches
   the model, a log, an error body, a DBOS checkpoint or another user.
2. Every tool and trigger call is authorized for the specific connection and tool; no cross-user or
   cross-connection access.
3. No user-controlled URL reaches an internal, loopback, link-local or metadata address, including
   through DNS rebinding or redirects.
4. Every resource is owned by the caller (or their team); ids are not authorization.
5. Untrusted external content never becomes a trusted instruction to the model.
6. Channel replies go only to the address snapshotted for that run.
7. The public surface is exactly the set in `SecurityConfig`; everything else requires a credential.
8. Admin routes require `ADMIN`; a normal user cannot reach them.
9. Secrets are not recoverable from the database without the KEK.

## Severity rubric

- **Critical** — pre-auth RCE or auth bypass; cross-tenant data access or exfiltration; credential
  exfiltration (connector secret, LLM key, worker authkey, S2S key); SSRF to cloud metadata or
  internal services; arbitrary tool execution on another user's connection; remote prompt injection
  that performs a write or exfiltration across a user boundary.
- **High** — post-auth privilege escalation (user → admin); IDOR across users; stored XSS in a
  surface another user views; secret leakage through logs or errors; SSRF limited to public hosts;
  OAuth token theft, replay or redirect bypass; a reply redirected to another chat; a prompt
  injection that triggers a permitted but unintended write or exfiltration inside the victim's own
  agent.
- **Medium** — DoS of one user's agent; rate-limit bypass; metadata disclosure; prompt injection that
  only changes the agent's answer inside its own conversation; file type confusion without code
  execution; a race with a concrete but limited impact.
- **Low** — hardening, verbose errors, missing headers, theoretical races without a demonstrated
  impact.
- **Out of scope** — self-XSS; attacks requiring a compromised browser or host; a user attacking
  their own installation with admin access (unless cross-tenant); bugs in tests, docs or ops;
  dependency CVEs that are not reachable through our code; missing best practices without a concrete
  exploit.

## Reproducers and patches

- The build has no Spring context, no database and no Testcontainers. Unit tests are JUnit 5 +
  Mockito; a reproducer can be a unit test under `services/<module>/src/test/java/...` or a precise
  HTTP request/response sequence naming the controller and service path.
- The image builds and tests offline; `./gradlew :control-api:test --tests "…"` works from
  `/src/services`. The Gradle cache is warm.
- For crypto or signature findings, include test vectors. For parser findings, include the payload.
  For prompt injection, include the exact text and the tool call or effect it produces, and say
  whether it needs a malicious MCP server or app (lower) or is remotely deliverable through a webhook
  or connector result (higher).
- A patch should be minimal and carry a test; do not change unrelated behavior.

## Known limitations (do not report as bugs without a new impact)

- One session per bot, not per chat (see §9).
- ACP prompt tools are visible to any DIALOGUE run while the ACP session lives (roadmap
  `channelOnly`).
- The egress proxy's target guard is the proxy's responsibility; the allowlist is operator-owned.
- `app.net.allow-private-targets` (and the worker's `agent.net.allow-private-targets`) disables the
  outbound guard for local development; the default is `false`. Do not report the flag itself.
- gRPC TLS and database TLS are deployment options, not enforced by the code.
- Seed content is seed-only-if-missing.
- Reports are model-generated and unvalidated; false positives are expected. We review each one
  before changing code.
