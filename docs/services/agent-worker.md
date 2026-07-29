# agent-worker

Java port of the `pydantic-dbos-agent` Python worker. A headless Spring Boot service that
consumes work from DBOS queues, runs an agent turn-loop against an LLM (Spring AI)
and backend tools, and talks to control-api over gRPC. Its only HTTP surface is
`/actuator/health` on port 8089 — nothing calls the worker, the health check aside. control-api is the **producer** that
enqueues onto the shared DBOS system database; the worker is the **consumer**.

Module: `services/agent-worker`. Entry point: `AgentWorkerApplication`.

## Architecture

Two layers, kept deliberately separate (mirrors the Python worker):

- **Pure logic** (`agent/`, `dto/`, `llm/`) — no DBOS, no transport; unit-tested.
- **DBOS surface** (`workers/`, `config/DbosRuntime`) — durable workflows/steps + queue wiring.

### `agent/` — pure logic
Vocabulary types live in `agent/model`, the loop's exceptions in `agent/error`.

| Type | Responsibility |
|---|---|
| `model/AgentChatMessage` | The worker's own message model (greenfield history — not pydantic-ai). |
| `model/ToolDef` | A tool definition as the LLM sees it (sanitized name + JSON Schema). |
| `MessageCodec` | Typed channel-facing progress lines (`ProgressLine{type, text}`) for `SaveMessage`; history persistence is text-only since v2 (raw transcript lives in DBOS checkpoints). Also exposes shared `AgentChatMessage`→proto `tool_calls`/`tool_results` converters reused by the turn ledger. |
| `workers/run/TurnLog` | Canonical full-fidelity turn ledger writer (`SaveTurn` → `agent_run_turns`): one record per assistant/tool `AgentChatMessage`, uncapped, all runs. Assistant turns also carry LLM provenance (`finish_reason`/`model`/`call_id`) via `LlmMeta` — `call_id` joins to `llm_usage_log`; tool turns leave it null. Plain idempotent call (not a durable step) — a turn is a projection of already-durable child-workflow results; replay dedupes on `(run_id, turn_index)`. Best-effort. |
| `ToolRegistry` | Sanitized LLM name ↔ backend `(connector_code, name, connection_id, openWorld)`; `{namespace}.{name}` naming; schema parsing. |
| `context/ContextBuilder` | Pure renderer of backend-assembled blocks: tags (`<name attrs>`), untrusted wrapping with preamble, ephemeral user-suffix split. The assembly policy lives server-side (`ContextSpec` in control-api). When the run has open-world tools it appends a system paragraph pinning tool output as data. |
| `context/ContextMaterials` | The `GetRunContext` payload as fetched (ordered blocks + tools), consumed by `ContextBuilder`. |
| `SimpleAgent` | The manual turn-loop (LLM call + tool dispatch injected). Loop events go out through one injected `RunObserver` — `onStart` (turn-1 prompt snapshot), `onMessages` (each turn), `onUsage` (per-call tokens); default no-ops, wired by `AgentRunCore`. |
| `AgentRunner` | Assemble the message list, map terminal failures to `AgentRunAborted`. |

### `llm/` — Spring AI (OpenAI)
`ModelFactory` builds an `OpenAiChatModel` **per call** from backend `LlmCredentials`.
`LlmMessageMapper` converts to/from Spring AI messages and exposes tool definitions as no-op
`ToolCallback`s — the loop is driven **manually** (no ToolCallingAdvisor), so tool calls come
back to us to dispatch on a separate queue instead of Spring AI auto-executing them.

### `workers/` — DBOS surface
| Workflow | Queue | Role |
|---|---|---|
| `AgentRunWorkflow.runAgent` | `agent_exec` | **Entry point + run stage**: enqueued directly by control-api (`workflow_id == runId`, partitioned by session, concurrency=1 → one writer per session); drives `AgentRunCore` (the run body is uniform — dialogue vs trigger is server-side policy). |
| `LlmCallWorkflow.llmCall` | `llm_calls` | One model request; credentials fetched inline (never checkpointed). Returns token usage on its `Result` — the child only counts; the loop surfaces it and the run wiring emits `ReportLlmUsage`. |
| `ToolCallWorkflow.toolCall` | `tool_calls` | One backend tool call (`ExecuteToolAsync` + poll `GetToolResult`); never raises. |

The package root is what DBOS sees: the three workflow pairs and `Queues`. The run-body machinery lives in `workers/run`:
`AgentRunCore` holds the invariant run body — a `prepare_context` step
(`ContextMaterialsFetcher`: one `GetRunContext(agent_id, run_id)` call → pure
`ContextBuilder.build` render → `PreparedContext`), the loop, and failure reporting — delegating the
distinct concerns to collaborators: `MessageLog` (the run's single writer of dialogue events —
inbound ack, progress, answer, error — one `save_message` durable step per event with a
deterministic per-run `seq`, so replays dedupe backend-side) and
`LlmCallDispatcher`/`ToolCallDispatcher` binding the LLM/tool queues (shared
`WorkflowHandles` await). `LlmCallDispatcher` is a pure data-returner: token usage and the
truncation `incompleteReason` ride up on the `LlmReply`. `SimpleAgent` surfaces all loop events
through one injected `RunObserver` — `onStart` (the turn-1 message list, before the first call),
`onMessages` (each turn), `onUsage` (per-call tokens, before aborting a truncated turn so they still
count); `AgentRunCore` implements it and projects each into a backend side-record — `SavePrompt` →
`agent_runs.prompt` (start snapshot, first-write-wins), `SaveTurn` → `agent_run_turns`, and
`ReportLlmUsage`. The parent is the sole writer of backend side-records, symmetric with
`SaveMessage`. Output of tools with MCP `openWorldHint=true` (external-world
content — mail, tickets, web; a prompt-injection channel) is wrapped by the dispatcher in
`<untrusted_tool_output>` with the closing tag neutralized inside the payload; the wrapper's
semantics are pinned by the `ContextBuilder` system paragraph. History arrives pre-assembled in `PreparedContext.history`
(backend window/filter, completed runs only); delivery and persistence are backend projections
of `SaveMessage` — the worker no longer routes channels. `PreparedContext` stays in `workers/run` — its FQCN is pinned by the DBOS
checkpoint (in-flight runs replay the serialized step result across deploys). See
[agent-context-design.md](../architecture/agents-and-runs.md) for the context-assembly design.

### Producer contract (shared code, not config)
The queue/class/workflow/instance names (`agent_exec`/`AgentRunWorkflow`/`run_agent`/`default`)
live in **`ru.agimate.agentworker.WorkerProtocol`** (`libs/agentworker-proto`), compiled into
both control-api and the worker — the contract cannot drift. In Java DBOS the names bind via
`@WorkflowClassName` + `@Workflow(name=...)` + `registerProxy(iface, impl, instance)`;
serialization is `PORTABLE`. control-api enqueues the run-stage workflow directly:
`workflow_id == runId` (delivery dedupes on it), partition key — the run's `sessionId`
(direct run — its own `runId`).

### Session serialization (протокол v2, без steering и без registry)
Воркер не знает `sessionId` — партицию задаёт продюсер при enqueue. Партиционированная
очередь (concurrency=1 → один исполняющийся ран на сессию) — единственный механизм
single-writer'а и контрактное требование к транспорту; регистрационного хэндшейка
(RegisterRun/ReleaseRun) нет. Жизненный цикл рана — серверная проекция потока `SaveMessage`;
признак жизни — RPC рана (молча умерший ран добирает серверный сборщик). Steering
(steer/interrupt в живой ран) удалён — вернётся отдельным дизайном, если понадобится.

## Configuration
Bound from `application.yaml` under `agent.*`; every value is overridable via env (relaxed
binding, e.g. `AGENT_GRPC_TARGET`, `AGENT_DBOS_DATABASE_URL`). See `.env.example`. Key sections:
`grpc` (target/tls/auth-token), `agent` (id/workflow-id), `concurrency` (agent-runs/llm/tool),
`session` (run-ttl-seconds), `tool` (poll-timeout — дефолтный бюджет ожидания результата тул-вызова;
спек тула может заявить свой `timeout_seconds` (кламп 30 мин) — тогда он побеждает; таймаут
не отменяет джобу на бэке, модель получает явное «could still complete»; max-output-chars — потолок
вывода одного тула: гигантский вывод раздувает контекст всех последующих turns и DBOS-чекпоинты,
поэтому обрезается с явной пометкой ещё внутри durable-шага), `response` (`language` —
язык пользовательских нотисов), `dbos` (system database — must match control-api's;
`retention` — сколько хранить завершённые воркфлоу с чекпоинтами, 0 отключает).

### User-facing notices (i18n)
Пользовательские нотисы (max-turns, ошибка/квота модели, «модель не настроена», обрезка/фильтр
ответа, инфра-сбой) — `resources/messages_<lang>.properties`, резолвятся через Spring
`MessageSource`. Язык выбирается
`agent.response.language` (BCP-47, дефолт `en`; в комплекте `en` и `ru`). Неизвестный язык падает
в базовый бандл (`messages.properties`, английский) — `spring.messages.fallback-to-system-locale:
false`, поэтому JVM-локаль не влияет. Per-deploy: один язык на воркер; `ResponseTemplates`
резолвит локаль один раз и отдаёт нотисы `AgentRunner`/`AgentRunCore`. Model-facing тексты
(коррекция имитации, guidance в системном промпте) здесь **не** локализуются — их ось иная (язык
диалога). Per-agent локаль (из рана) — на будущее.

Текст сервера уходит пользователю дословно только там, где сервер пишет его для пользователя —
это квота (`GetLlmCredentials` → `RESOURCE_EXHAUSTED`). Отказ той же RPC с `NOT_FOUND` или
`FAILED_PRECONDITION` (нет привязки и нет платформенного провайдера, привязанная модель пропала из
листинга, провайдер выключен) — это «агенту не настроена модель»: сообщение сервера тут техническое
(uuid'ы агента и провайдера), поэтому воркер подменяет его нотисом `notice.no-model`, который зовёт
владельца в настройки, вместо общего «ошибка при обращении к модели, попробуй ещё раз».

### DBOS retention
`DbosRetentionJob` периодически (раз в 6 часов, батчами по 5000) удаляет завершённые воркфлоу
старше `agent.dbos.retention` (дефолт 7d) через public admin API библиотеки
(`listWorkflows` с фильтром терминальных статусов + `deleteWorkflows`); чекпоинты и прочие
дочерние таблицы чистятся каскадом. Семантика совпадает со встроенным bulk-GC
(`WorkflowDAO.garbageCollect`), который наружу выведен только через неаутентифицированный
deprecated admin-server и поэтому не используется. PENDING/ENQUEUED/DELAYED не трогаются.
Чекпоинты — операционный дубль: бизнес-данные рана уходят в control-api синхронно, ретеншн
ограничивает только окно расследований и ручного recovery.

The worker owns the DBOS system-schema migrations (`withMigrate(true)` in `DbosRuntime`): on a
`dev.dbos:transact` upgrade start the worker before control-api, whose `DBOSClient` does not
migrate.

### gRPC client resilience
`AgentWorkerClient` retries `UNAVAILABLE` at the transport level with exponential backoff
(~63s budget) — a routine control-api restart is waited out instead of killing the run. This
sits below DBOS step retries and also covers the non-step call sites (inline LLM credentials
fetch). `ABORTED` is a business outcome and never retried; other
statuses fail fast as `ControlApiCallException` (serializable, unlike the raw gRPC exception).

Слои ретраев не умножаются: step-ретраи (`register_run`/`release_run`/`save_message`,
maxAttempts=3) через `ControlApiCallException.retriableInStep` **не** ретраят `UNAVAILABLE` —
его бюджет целиком принадлежит клиентскому слою (иначе каждый step-attempt ждал бы все ~63s
заново). Step-ретраи покрывают остальные transient-коды (DEADLINE_EXCEEDED, INTERNAL, …).

## Run
```bash
cd services
./gradlew :agent-worker:bootRun     # needs the shared DBOS Postgres reachable
./gradlew :agent-worker:test        # pure-logic unit tests
```
The gRPC/proto stubs are the shared `:libs:agentworker-proto` module (also used by control-api).
