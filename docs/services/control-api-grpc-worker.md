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
| `AgentContext`          | `GetRunContext` (весь контекст рана одним вызовом, включая историю), `GetLlmCredentials`, `GetFile` (содержимое вложения чанками), `ReportLlmUsage` | done     |
| `MessageLog`            | `SaveMessage` — единая запись событий диалога, доставка как её проекция; `SaveTurn` — канонический журнал ходов (`agent_run_turns`) | done     |
| `ToolGateway`           | `ExecuteToolAsync`, `GetToolResult` (несёт `run_id` — liveness рана)                          | done     |
| `WorkflowReporting`     | —                                                                                                 | post-PoC |

`AgentRunRegistry` удалён: single-writer держит партиционированная очередь, жизненный цикл
рана — проекция `SaveMessage` (см. «Run lifecycle» ниже).

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

## Run context (`AgentContext.GetRunContext`)

`GetRunContext(agent_id, run_id)` → `RunContext` — весь контекст рана одним вызовом
(`run_id` = `agent_runs.id` = DBOS workflow id). Сборка — `RunContextService`;
политика (`ContextSpec`: `DIALOGUE` при prompt-канале в снапшоте `agent_runs.channels`,
иначе `SYSTEM_TRIGGER`) целиком на бэке, воркер только рендерит блоки в присланном порядке.

`RunContext`:

| Field | Description |
|-------|-------------|
| `system_blocks` | Упорядоченные `PromptBlock` (stable-первые — prompt-cache): agent → инструкции → блоки `PromptBlockProvider`-коннекторов (memory) → team → skills-листинг → тела подошедших скиллов (SYSTEM_TRIGGER) → trigger guidance |
| `user_blocks`   | User-ход: user-блоки коннекторов (memory notes, `ephemeral=true` — не персистятся в историю) + основной промпт последним (диалоговый текст `trusted`, событие триггера `trusted=false` → воркер оборачивает как untrusted data) |
| `tools`         | `ConnectorToolSpec` уже отскоупленные (binding-гейт + скоуп скиллов): коннекторы **всех** скиллов агента в обоих `ContextSpec` — содержание делегированной триггером задачи не связано с коннектором события; по триггеру скоупятся только тела скиллов |
| `history`       | Сессионная история «как видел пользователь»: только завершённые раны (`completed=true` — сообщения текущего рана и упавших ранов не видны), окно 50, фильтр `historyDetail` (FULL/NO_REASONING/DIALOGUE_ONLY) из пресета `ContextSpec`; дореформенные REQUEST/RESPONSE маппятся на INBOUND/ANSWER. Tool-ходы (v2.1a): у PROGRESS/TOOL_CALL с `message_json` наружу идёт структурный `tool_turn` с вызовами, у следующей PROGRESS/TOOL_RESULT — с результатами (обе с капом JSON-полей до 4 KB, маркер `…[truncated]`); воркер сшивает пару. TEXT-преамбулы таких ранов скипаются (текст уже внутри `tool_turn`), легаси `🔧 name`-строки санитизируются в `[вызван инструмент name]` — текстовый паттерн вызова модель имитирует вместо реального tool call |
| `inbound_parts` | Вложения диалогового inbound текущего рана (`repeated FilePart{file_id, type, mime, size, name}`) — только `agf_`-ссылки, без байтов (безопасно для чекпоинта `prepare_context`). Пусто вне DIALOGUE и у старого control-api. Байты изображений воркер тянет `GetFile`'ом inline при `llm_call` и подаёт модели как `Media`; в историю не попадают (плейсхолдер — в тексте user-блока). См. docs/connectors/files.md, «Входящие вложения» |

## GetFile (`AgentContext.GetFile`)

`GetFile(file_id, agent_id)` → `stream FileChunk{data, mime, total_size}` — содержимое inbound-вложения
чанками (~128 KB, << 4 MB лимита gRPC-сообщения; первый чанк несёт `mime`+`total_size`). Ownership-гейт:
`file.user_id == agent.user_id`, иначе `NOT_FOUND` (существование чужих файлов не раскрывается).

Как `GetLlmCredentials`, вызывается **inline при `llm_call`**, не оборачивается в durable-шаг — байты в
DBOS-чекпоинт не попадают (`RunContext.inbound_parts` несёт только ссылки). Недоступный файл
(NOT_FOUND/сбой) воркер пропускает: текст сообщения уже содержит стаб, «зрение» деградирует, ран не
падает. Собирается воркером в `byte[]` с потолком 32 MB.

## SaveMessage (`MessageLog.SaveMessage`)

`SaveMessage(agent_id, run_id, seq, kind, progress_type, text)` — воркер единственный писатель
истории; бэк (`MessageLogService`) персистит строку `channel_session_messages` и доставляет её
проекцию в канал. Идемпотентность — UNIQUE `(run_id, seq)` (ON CONFLICT DO NOTHING); доставка
дедупится downstream детерминированным `message_id` от `(run_id, seq)`.

- `INBOUND` (seq=0, до prepare_context) — ack «агент получил»: текст пуст, канонику бэк берёт сам
  (`ChannelHandler.handleInput` от персистентного триггера / компактный JSON события), `trigger_input`
  заполняется из `trigger_log.input` (reply-context).
- `PROGRESS` (+`progress_type` THINKING/TOOL_CALL/TEXT/TOOL_RESULT) → progress-канал (если есть).
  Tool-ход дробится на две записи (v2.1a): у **TOOL_CALL** воркер шлёт `tool_turn{text, calls[{id,
  name, arguments_json}]}` **до** исполнения (`text` — канальная 🔧-проекция, доставляется сразу),
  у **TOOL_RESULT** — `tool_turn{results[{id, name, output_json, failed}]}` **после**, с пустым
  `text` (в канал не доставляется, история-only). Бэк кладёт обе в
  `channel_session_messages.message_json` (JSON-поля капаются до 32 KB) и отдаёт истории следующих
  ранов двумя соседними записями — воркер сшивает их в нативную пару tool_use/tool_result. Легаси
  TOOL_CALL с `calls+results` в одной записи по-прежнему читается.
- `ANSWER` → answer-канал (fallback prompt); в той же транзакции все сообщения рана помечаются
  `completed=true` — ран становится видимым истории. Direct-ран → `agent_runs.result`.
- `ERROR` → progress/answer/prompt-фолбэк; direct-ран → `agent_runs.error`. ERROR не
  завершает ран — его сообщения в историю не попадут.

`PromptBlock{name, source, content, attrs, trusted, ephemeral}` — `name`/`attrs` становятся XML-тегом
у рендерера (пустой `name` — сырой текст). LLM-креды в `RunContext` **не входят**: его результат
чекпоинтится воркером (`prepare_context`), api_key запрашивается отдельным `GetLlmCredentials`
inline на каждый `llm_call`.

## SaveTurn (`MessageLog.SaveTurn`) — канонический журнал ходов

`SaveTurn(agent_id, run_id, turn_index, role, text, thinking, tool_calls[], tool_results[],
finish_reason, model, call_id)` — full-fidelity журнал шагов рана в таблице `agent_run_turns`
(`AgentRunTurnService`). По одной строке на `AgentChatMessage` воркера: `ASSISTANT` несёт
`text`/`thinking`/`tool_calls`, `TOOL` — только `tool_results`. **Без капов** — в отличие от
капнутой канальной проекции `channel_session_messages` (SaveMessage), и пишется для **всех** ранов,
включая direct (`session_id` NULL, денорм из `agent_runs.session_id`).

- Идемпотентность — UNIQUE `(run_id, turn_index)` (ON CONFLICT DO NOTHING).
- В отличие от `SaveMessage`, у воркера это **не durable-шаг**: ход — идемпотентная проекция уже
  durable данных (результатов дочерних `llm_call`/`tool_call`), поэтому DBOS-replay переотправляет ту
  же пару и бэк дедуплицирует. Чекпоинт не добавляется → **drain перед деплоем не нужен**.
- Пишется рядом с каналом, не вместо: доставки нет, статус рана не проецирует.
- `finish_reason`/`model`/`call_id` — provenance LLM-хода (nullable): заполняются на `ASSISTANT`-ходах
  (для `TOOL` — NULL, LLM-вызова нет). `call_id` = id дочернего `llm_call`-воркфлоу = join-ключ к
  `llm_usage_log.call_id` (per-turn токены/стоимость без дублирования). `model` — из кредов вызова.
  Воркер везёт их на `LlmCallWorkflow.Result` (чекпоинтится однократно) → `LlmMeta` → `SaveTurn`;
  расширение `Result` — смена чекпоинта дочернего воркфлоу, деплой за drain.
- `session_id` денормализован без логики непрерывности — `AgentSession` отложен.
- **Асимметрия content vs accounting (важно).** Журнал ходов — канонический **транскрипт**, а не
  полный лог LLM-вызовов. Турн пишется только на **спроецированные** ходы; usage (`ReportLlmUsage`)
  же учитывается на **каждый** дошедший до модели вызов, включая те, что турна не дают: имитация
  вызова текстом (эфемерная коррекция) и truncation-обрыв (`length`/`content_filter`, ход прерывает
  ран). Токены таких вызовов потрачены и считаются, но записи в `agent_run_turns` у них нет. Значит
  join `llm_usage_log.call_id → agent_run_turns.call_id` может дать usage-строки **без** парного
  турна — это ожидаемо: учёт покрывает весь расход, журнал — только транскрипт. Не воспринимать
  `agent_run_turns` как полный перечень всех LLM-вызовов.

## Tool execution

`ToolGateway.ExecuteTool` wraps the existing `AgentToolCallService` (idempotency via `tool_call_id`, ABAC via
`ToolPolicyDbEvaluatorService`, audit via `ToolCallLogService`, delivery via `ConnectorService`).

BACKEND-locus тулы исполняются асинхронно на выделенном bounded-пуле `toolExecutor`
(`AsyncConfig`, 8..32 потоков, очередь 200, CallerRuns при переполнении): ack `ExecuteToolAsync`
возвращается сразу, долгий коннекторный вызов не держит gRPC-тред, результат воркер забирает
поллингом `GetToolResult` (PENDING → SUCCESS/ERROR).

Errors:

- `PERMISSION_DENIED` — ABAC denied. Per spec §3.6 this is a valid tool result, not a network error — the worker should
  feed it back to the LLM as a tool response.
- `ABORTED` — same `tool_call_id` was reused with different input (idempotency conflict).
- `INVALID_ARGUMENT` — missing `tool_call_id`/`connector_code`/`tool_name`/UUID parsing.
- `UNAUTHENTICATED` — bad pool key (handled by interceptor before the call ever reaches the service).

### Tool discovery (внутри `GetRunContext`)

Тулы приходят в `RunContext.tools` (отдельных discovery-RPC больше нет), ключ — `connections.id`:
активные `agent_connections`-binding'и (connector-level ABAC-гейт) фильтруются скоупом скиллов, затем
по `definition_binding`: **STATIC** коннекторы (telegram, time, board, persist-memory) отдают тулы рефлексией
`@Tool`-методов; **DYNAMIC** (`mcp`, device `app`) — per-instance набор из `connection_tools` (синк из
`tools/list` / device link, без удалённого вызова на этом пути). Каждый `ConnectorToolSpec` несёт
`connector_code`, `connection_id` (= `connection_id` на `ExecuteTool`) и `namespace`; воркер строит
LLM-имя как `{namespace}.{name}`.

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

## Run lifecycle (протокол v2, без registry)

Backed by the `agent_runs` table — each row is an agent run, and its id is the canonical
**`run_id` == DBOS `workflow_id`**. Регистрационного хэндшейка нет:
single-writer-per-session держит партиционированная очередь `agent_exec` (партицию задаёт
control-api при enqueue — `DbosTransport`, ключ = `session_id` рана; direct-ран — свой `run_id`).
Это контрактное требование к транспорту исполнения.

Lifecycle (`status` column, orthogonal to `result`/`error`) — a projection of the run's
`SaveMessage` stream, observability only: `ENQUEUED` (created at trigger routing; в очереди) →
`RUNNING` (первый `SaveMessage` — INBOUND-ack) → `DONE` (final ANSWER) / `FAILED` (ERROR, or
swept as stale). Terminal statuses are sticky — a replayed INBOUND never resurrects a finished run.

**Liveness** (`last_activity_at` + `RunActivityService`): каждый RPC рана — `SaveMessage`
(проекция обновляет метку в той же транзакции), `GetRunContext`, `ExecuteToolAsync`,
`GetToolResult` (несёт `run_id`) — продлевает `last_activity_at` (gRPC-фасады зовут
`RunActivityService.touch`, best-effort). Самый длинный легальный тихий участок — один LLM-вызов
с ретраями; ран, молчащий дольше `STALE_AFTER` (15 мин), добирает `@Scheduled`-сборщик:
`RUNNING` → `FAILED` с маркером в `error`. Никого не блокирует — следующий ран сессии стартует
по очереди независимо от статуса предыдущего.

`MessageLog.SaveMessage` is hardened independently: the insert into `channel_session_messages` uses
`ON CONFLICT (run_id, seq) DO NOTHING`, so a DBOS-replay / retry of the same run is idempotent without
poisoning the transaction.

## What's intentionally out of scope (PoC)

- mTLS, per-workflow JWT (`x-workflow-token`), Worker Registration with capability negotiation — deferred to phases 1–3.
- LLM Gateway (option B): for PoC, `AgentContext.GetLlmCredentials` returns a decrypted API key directly (option A).
- `ExecuteToolStream/Batch/Async` — `UNIMPLEMENTED` placeholders.
- Knowledge Base RPC — stub (`UNIMPLEMENTED`); KB schema does not exist yet.
- `WorkflowReporting` (logs/status/traces/SubmitResult) — deferred.
