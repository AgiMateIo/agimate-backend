# control-api: Generic Worker gRPC Protocol

PoC implementation of the gRPC contract described in [`agimate-worker-protocol-spec.md`](../agimate-worker-protocol-spec.md).
The protocol lets Generic Workers — durable executors of agentic workflows running inside DBOS — fetch agent specs/skills/LLM
credentials from the backend and execute tools through the Tool Gateway.

## Surface

- Transport: gRPC over TLS (HTTP/2). Plaintext is allowed only for local development (`grpc.server.security.enabled=false`).
- Single port: `9091` (configurable via `grpc.server.port`).
- Authentication: pool-level `worker_pool_key` (Bearer). mTLS and per-workflow JWT are out of scope for PoC.

### Services (proto package `ru.agimate.agentworker`)

| Service                 | RPCs (PoC)                                                                                        | Status   |
|-------------------------|---------------------------------------------------------------------------------------------------|----------|
| `WorkerControl`         | `HealthCheck`                                                                                     | done     |
| `AgentContext`          | `GetAgentSpec`, `GetSkills`, `GetSkill`, `GetTeamContext`, `GetLlmCredentials`, `GetConnections`, `GetConnectionTools`, `GetMemory`, `GetMemoryNotes` | done     |
| `ToolGateway`           | `ExecuteTool` (sync). `ExecuteToolStream/Batch/Async` reserved → return `UNIMPLEMENTED`           | partial  |
| `AgentSessionMessages`  | `Append`, `GetHistory`                                                                            | done     |
| `AgentRunRegistry`      | `RegisterRun`, `GetActiveRun`, `ReleaseRun`                                                       | done     |
| `WorkflowReporting`     | —                                                                                                 | post-PoC |

Source proto files: `services/libs/agentworker-proto/src/main/proto/agentworker/`.

## Authentication

Each RPC must include:

- `authorization: Bearer <wrkp...>` — the full worker pool key (64 chars, prefix `wrkp`).
- `x-worker-instance: <uuid>` — generated once per worker process, used for audit/tracing.

The server validates the bearer token in `WorkerPoolAuthInterceptor`:

1. Parse the key with `AppKeyUtils.parse()` (positional format `prefix(4)+keyId(12)+payload(48)`).
2. Verify CRC32 checksum embedded in the payload.
3. Look up the pool by `keyId` in the in-memory `WorkerPoolRegistry`.
4. Verify SHA-256 of the secret matches the stored hash.

Failure → `UNAUTHENTICATED`. Success → `WorkerPoolContext(poolId, workerInstanceId)` is attached to the gRPC `Context` and is
available downstream via `WorkerPoolContextHolder.current()`.

### Why config, not DB

Per the protocol spec §1.4, worker identity must not depend on backend DB availability. The registry is loaded from
`worker-pools.authkeys` at startup and never queries the DB.

## Pool key configuration

Each pool is one **authkey** string (80 chars) in the config:

```
{prefix(4)}{keyId(12)}{keyHashHex(64)}
```

This is **not the worker's full key**. The worker is given the full key (`prefix+keyId+payload`, 64 chars, contains the
plaintext secret); the backend stores only `prefix+keyId+sha256(secret)hex`. Looking up by `keyId` and verifying
`sha256(secret)` is enough to authenticate without ever holding the secret.

Example (`application.yaml`):

```yaml
worker-pools:
  authkeys:
    - wrkpaf4bvIRmNRDt4172e9b5bf81ca8d7f510bfe8ff7e33f13bd57d57e8b7ce6c0b02510aaeba59d
```

In production, supply via env vars: `WORKER_POOLS_AUTHKEYS_0`, `WORKER_POOLS_AUTHKEYS_1`, ...

## Generating a worker pool key

Use the gated JUnit test (no CLI runner — keeps the magic out of production):

```bash
cd services
./gradlew :control-api:test --tests "*WorkerAuthkeyGeneratorTest" -Dgenerate.worker.authkey=true --rerun-tasks
cat services/control-api/build/test-results/test/TEST-ru.agimate.controlapi.grpc.auth.WorkerAuthkeyGeneratorTest.xml
```

Output:

```
=== Worker Pool Authkey Generated ===
Full key (give to worker, set as authorization Bearer): wrkp<keyId>...<payload>
Authkey  (put in WORKER_POOLS_AUTHKEYS_0):              wrkp<keyId><sha256-hex>
KeyId   (poolId on PoC):                                <keyId>
=====================================
```

Distribute the **Full key** to the worker (mounted secret / env var). Add the **Authkey** to the backend config.
Rotation: add a new authkey, redeploy backend, then redeploy workers with the new full key, then drop the old authkey.

## Local development

In `application.yaml` defaults the gRPC server is **disabled**. To turn it on locally without TLS:

```bash
GRPC_SERVER_ENABLED=true \
GRPC_SERVER_SECURITY_ENABLED=false \
WORKER_POOLS_AUTHKEYS_0=<authkey from generator> \
./gradlew :control-api:bootRun
```

Smoke-test with `grpcurl` (install: `brew install grpcurl`):

```bash
TOKEN="<full key>"
INSTANCE=$(uuidgen)

grpcurl -plaintext \
  -H "authorization: Bearer ${TOKEN}" \
  -H "x-worker-instance: ${INSTANCE}" \
  -d '{}' \
  localhost:9091 \
  ru.agimate.agentworker.WorkerControl/HealthCheck
```

Without `authorization` header → `UNAUTHENTICATED`. With a tampered token → `UNAUTHENTICATED`.

## Skills (`AgentContext.GetSkills` / `GetSkill`)

### `GetSkills(workflow_id, agent_id)` → `GetSkillsResponse`

Returns all skills bound to the agent as a list of `SkillRef`:

| Field | Type | Description |
|-------|------|-------------|
| `skill_id` | `string` | Skill UUID |
| `name` | `string` | Skill display name |
| `description` | `string` | Skill description |
| `connector_codes` | `repeated string` | Connector codes required by the skill (e.g. `time`, `board`, `mcp`) |

### `GetSkill(workflow_id, skill_id, version)` → `SkillSpec`

Returns the full spec for a single skill:

| Field | Type | Description |
|-------|------|-------------|
| `skill_id` | `string` | Skill UUID |
| `version` | `string` | Skill version at time of fetch |
| `name` | `string` | Skill display name |
| `description` | `string` | Skill description |
| `definition_json` | `bytes` | Reserved (JSON definition, not used in current PoC) |
| `skill_md` | `string` | SKILL.md **body without frontmatter** — the markdown instructions for the agent |

Note: `skill_md` contains only the body text. Name, description, and connector codes are separate fields; the frontmatter is not re-emitted.

## Tool execution

`ToolGateway.ExecuteTool` wraps the existing `AgentToolCallService` (idempotency via `tool_call_id`, ABAC via
`ToolPolicyDbEvaluatorService`, audit via `ToolCallLogService`, delivery via `ConnectorService`).

Errors:

- `PERMISSION_DENIED` — ABAC denied. Per spec §3.6 this is a valid tool result, not a network error — the worker should
  feed it back to the LLM as a tool response.
- `ABORTED` — same `tool_call_id` was reused with different input (idempotency conflict).
- `INVALID_ARGUMENT` — missing `tool_call_id`/`connector_code`/`tool_name`/UUID parsing.
- `UNAUTHENTICATED` — bad pool key (handled by interceptor before the call ever reaches the service).

### Tool discovery (`AgentContext.GetConnections` → `GetConnectionTools`)

Two steps, both keyed on `connections.id`:

1. **`GetConnections(agent_id)` → `[ConnectionRef{id, connector_code, namespace, name}]`** — the connector
   instances available to the agent. Source: active `agent_connections` bindings joined to `connections`
   (enabled, not soft-deleted). This is the connector-level ABAC gate — only bound instances are returned.
2. **`GetConnectionTools(connection_id)` → `[ConnectorToolSpec]`** — every tool of one instance. The backend
   resolves `connector_code` from the connection, then by `tool_binding`: **STATIC** connectors (telegram,
   time, board, persist-memory) derive tools from `@Tool` methods by reflection; **DYNAMIC** connectors
   (`mcp`, device `app`) read the per-instance set from `connection_tools` (synced from `tools/list` /
   device link, no remote call on this path). Each `ConnectorToolSpec` echoes `connection_id` (= `connection_id`
   on `ExecuteTool`) and `namespace`; the worker builds the LLM-facing name as `{namespace}.{name}`.

**Naming.** Stored tool/trigger names are **bare local identifiers** (`schedule`, `get_tasks`,
`message_received`, `consolidate`) — no connector prefix. Global uniqueness for the LLM comes from the
`namespace` the backend derives per instance: `connector_code` for context singletons (time/board/persist-memory
— an agent has exactly one) and `full_code` for multi-instance connectors (`mcp_context7`, `telegram_<bot>`).
So the agent sees `time.schedule`, `persist-memory.save_memory_note`, `mcp_context7.resolve-library-id`. On
`ExecuteTool` the worker sends the **bare** `name` back (+ `connector_code`, `connection_id`) — namespace is
presentation-only, wire routing unchanged.

`GetConnectionTools` returns **all** tools of the instance — per-tool `DENY`/`ALLOW` policies
(`agent_connection_policies`) are **not** applied at listing time; they are enforced on `ExecuteTool`
(`PERMISSION_DENIED`). The worker may surface denied tools to the LLM; the call is rejected at execution.

## Active-run registry (`AgentRunRegistry`)

Backed by the `trigger_log_agents` table — each row is an agent run, and its `pub_id` is the canonical
**`run_id` == DBOS `workflow_id`**. There is no separate `workflow_id` field: to STEER/INTERRUPT the active
run, the worker addresses the workflow whose id equals `run_id`.

Lifecycle (`status` column, orthogonal to `result`/`error`):
`ENQUEUED` (created by the backend at trigger routing) → `RUNNING` (worker acquired the session slot) →
`DONE` (released) / `FAILED` / `CANCELLED` (pre-empted by INTERRUPT).

- `RegisterRun(session_pub_id, run_id, ttl_seconds)` — flips the run to `RUNNING`, sets `expires_at = now + ttl`
  (server default ~3600s, no heartbeat). Returns the `ActiveRun`. The claim is a conditional UPDATE
  (`NOT EXISTS` another RUNNING holder) — a busy slot is a regular outcome, not a constraint violation. On a
  busy slot the server evicts a holder that provably no longer needs it — expired lease (the partial unique
  index ignores `expires_at`, so the claim is where TTL takeover actually happens) or dead DBOS workflow
  (`run_id` == workflow id; terminal state or missing record — e.g. the run errored during a control-api
  outage and never released) — marks it `FAILED` and retries once; only a live holder yields `ABORTED`.
- `GetActiveRun(session_pub_id)` — the single live writer for the session; expired `RUNNING` rows count as
  inactive (`active=false`). No sweeper needed.
- `ReleaseRun(session_pub_id, run_id)` — release-own: only the run holding the slot can release it, so a late
  Release from a pre-empted run is a no-op (`released=false`).

**Single-writer invariant**: at most one `RUNNING` run per session, enforced by a partial unique index
`uq_trigger_log_agents_active_session ON (session_pub_id) WHERE status = 'RUNNING'`. The index is the
invariant backstop, not the detection mechanism: the claim's `NOT EXISTS` makes a busy slot an explicit
outcome, and the index only trips on a true race (two concurrent claims both passing `NOT EXISTS` under
READ COMMITTED — the loser gets `ABORTED`). An INTERRUPT take-over must first move the pre-empted run out
of `RUNNING` (`CANCELLED`) before the new run's `RegisterRun`; otherwise `RegisterRun` is rejected with
`ABORTED`.

`AgentSessionMessages.Append` is hardened independently: the insert into `channel_session_messages` uses
`ON CONFLICT (session_id, turn_idx) DO NOTHING` and returns the actual `turn_idx` values, so a DBOS-replay /
retry of the same run is idempotent without poisoning the transaction.

## What's intentionally out of scope (PoC)

- mTLS, per-workflow JWT (`x-workflow-token`), Worker Registration with capability negotiation — deferred to phases 1–3.
- LLM Gateway (option B): for PoC, `AgentContext.GetLlmCredentials` returns a decrypted API key directly (option A).
- `ExecuteToolStream/Batch/Async` — `UNIMPLEMENTED` placeholders.
- Knowledge Base RPC — stub (`UNIMPLEMENTED`); KB schema does not exist yet.
- `WorkflowReporting` (logs/status/traces/SubmitResult) — deferred.
