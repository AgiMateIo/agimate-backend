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
- **DBOS surface** (`workers/`, `config/DbosRuntime`) — the run workflow, its durable steps, queue wiring.

### `agent/` — pure logic
Vocabulary types live in `agent/model`, the loop's exceptions in `agent/error`.

| Type | Responsibility |
|---|---|
| `model/AgentChatMessage` | The worker's own message model (greenfield history — not pydantic-ai). |
| `model/ToolDef` | A tool definition as the LLM sees it (sanitized name + JSON Schema). |
| `MessageCodec` | Typed channel-facing progress lines (`ProgressLine{type, text}`) for `SaveMessage`; a tool turn's lines also carry its structural `ToolTurn`. Also exposes shared `AgentChatMessage`→proto `tool_calls`/`tool_results` converters reused by the turn ledger. |
| `workers/run/TurnLog` | Canonical full-fidelity turn ledger writer (`SaveTurn` → `agent_run_turns`): one record per inbound/assistant/tool `AgentChatMessage`, uncapped, all runs; one counter per run shared by its three writers (`AgentRunner` for turn 0, the `llm_call` step for the assistant, `BackendRunRecorder`/`SteeringAbsorber` for the rest). `turn_index` 0 is the inbound turn without its ephemeral prefix (the persistent part — later runs read the ledger back as history); the system prompt is never a turn (it lives in `agent_runs.prompt`). Assistant turns also carry LLM provenance (`finish_reason`/`model`/`call_id`) and the reasoning text (`thinking_text`) via `LlmMeta` — `call_id` joins to `llm_usage_log`; tool turns leave both null. The assistant turn is written inside the `llm_call` step before its checkpoint commits (a replay reads it back with `GetTurn`, `resumeAfter` re-syncs the counter); the other turns are plain idempotent calls, deduped on `(run_id, turn_index)`. Best-effort. |
| `ToolRegistry` | Sanitized LLM name ↔ backend `(connector_code, name, connection_id, openWorld)`; `{namespace}.{name}` naming; schema parsing. |
| `context/ContextBuilder` | Pure renderer of backend-assembled blocks: tags (`<name attrs>`), untrusted wrapping with preamble, ephemeral user-suffix split. The assembly policy lives server-side (`ContextSpec` in control-api). When the run has open-world tools it appends a system paragraph pinning tool output as data. |
| `context/ContextMaterials` | The `GetRunContext` payload as fetched (ordered blocks + tools), consumed by `ContextBuilder`. |
| `AgiMateAgent` | The manual turn-loop (LLM call + tool dispatch injected). Loop events go out through one injected `RunRecorder` — `onStart` (turn-1 prompt snapshot), `onMessages` (each turn), `onUsage` (per-call tokens), `pollSteering` (seam absorption of the session's queued messages; resets the turn budget, max 5 resets per run); default no-ops, wired by `AgentRunner`. |
| `AgentRunner` | Assemble the message list, map terminal failures to `AgentRunAborted`. |

### `llm/` — Spring AI (OpenAI)
`ModelFactory` builds an `OpenAiChatModel` **per call** from backend `LlmCredentials`.
`LlmMessageMapper` converts to/from Spring AI messages and exposes tool definitions as no-op
`ToolCallback`s — the loop is driven **manually** (no ToolCallingAdvisor), so tool calls come
back to us to dispatch on a separate queue instead of Spring AI auto-executing them.

### `workers/` — DBOS surface
One workflow, `AgentRunWorkflow.runAgent` on the `agent_exec` queue: enqueued directly by control-api
(`workflow_id == runId`, partitioned by session, concurrency=1 → one writer per session); drives
`AgentRunner`, which owns the whole run lifecycle — the workflow class keeps only the annotation and
the log tag (the run body is uniform — dialogue vs trigger is server-side policy). Everything
else is a durable step of that workflow, and every checkpoint holds identifiers, not the dialogue
([decisions/dbos-ids-only.md](../decisions/dbos-ids-only.md)):

| Step | Body | Checkpoint |
|---|---|---|
| `save_message` | `ChannelMessageLog` — one per dialogue event | `duplicate`/`cancelled`/`steered` flags |
| `llm_call` | `LlmCall` — credentials inline, the provider request under a per-worker semaphore (`concurrency.llm`), retries on 429/5xx; then `SaveTurn` of the assistant turn | `call_id` (`runId-n`), `turn_index`, `finish_reason`, model, token counts, or the provider's failure |
| `tool_calls` | `ToolCallStep` — `ExecuteToolAsync` for every call of the turn, then a round-robin poll of `GetToolResult`; a call still pending at `detach-after` is detached (`DetachTool`), the model gets an interim task handle and the result returns later as a `tool_completed` trigger | id + status per call (SUCCESS/ERROR/DETACHED/TIMEOUT/ABANDONED/FAILED) |
| `report_failure` | `SendMessage` with the run's outcome | `true` |

The reply of a step lives in run memory; a crash replay re-reads it by id (`GetTurn`,
`GetToolResult`) and regenerates the worker's own notices. The backend executes tools
concurrently from the moment they are issued, so one polling loop is as parallel as one
workflow per call used to be.

The package root is what DBOS sees: the run workflow pair and `Queues`. The run-body machinery lives in `workers/run`:
`AgentRunner` holds the invariant run body — the context fetch
(`ContextMaterialsFetcher`: one `GetRunContext(agent_id, run_id)` call → pure
`ContextBuilder.build` render → `PreparedContext`; deliberately **not** a durable step, so the
assembled dialogue never lands in the DBOS system database), the loop, and failure reporting — delegating the
distinct concerns to collaborators: `ChannelMessageLog` (the run's single writer of dialogue events —
inbound ack, progress, answer, error — one `save_message` durable step per event with a
deterministic per-run `seq`, so replays dedupe backend-side) and
`LlmCallDispatcher`/`ToolCallDispatcher` wrapping `LlmCall`/`ToolCallStep` into the
`llm_call`/`tool_calls` steps and owning the replay branch. `LlmCallDispatcher` is a pure
data-returner: token usage and the truncation `incompleteReason` ride up on the `LlmReply`. `AgiMateAgent` surfaces all loop events
through one injected `RunRecorder` — `onStart` (the turn-1 message list, before the first call),
`onMessages` (each turn), `onUsage` (per-call tokens, before aborting a truncated turn so they still
count) — and asks it two questions (`cancelRequested`, `pollSteering`). `BackendRunRecorder` is the run's
implementation: it projects each event into a backend side-record — `SavePrompt` →
`agent_runs.prompt` (start snapshot, first-write-wins), `SaveTurn` → `agent_run_turns` (via
`TurnLog`; the assistant turn excepted — the `llm_call` step writes it), the channel's progress
lines (via `ChannelMessageLog`) and `ReportLlmUsage` — and answers the
questions off records the run makes anyway (`ChannelMessageLog`'s cancel flag, `SteeringAbsorber`). The
parent is the sole writer of backend side-records, symmetric with `SaveMessage`. Output of tools with MCP `openWorldHint=true` (external-world
content — mail, tickets, web; a prompt-injection channel) is wrapped by the dispatcher in
`<untrusted_tool_output>` with the closing tag neutralized inside the payload; the wrapper's
semantics are pinned by the `ContextBuilder` system paragraph. History arrives pre-assembled in `PreparedContext.history`
(backend window/filter, completed runs only); delivery and persistence are backend projections
of `SaveMessage` — the worker no longer routes channels. See
[agent-context-design.md](../architecture/agents-and-runs.md) for the context-assembly design.

### Продолжать или закончить
Решает `finish_reason` провайдера, а не форма сообщения: `TOOL_CALLS` — модель в середине работы,
цикл идёт дальше; `STOP` — ход и есть ответ. Диалекты нормализуются в `LlmCallDispatcher` (на проводе
OpenAI шлёт `tool_calls`, Spring AI отдаёт имя enum'а SDK — `TOOL_CALLS`), цикл видит уже готовый
`Completion`. Чужое или отсутствующее значение (`end_turn`, `eos`, null) — `UNKNOWN`, и тогда решает
форма сообщения, как было раньше: есть вызовы — продолжаем, нет — финал.

`TOOL_CALLS` без единого распарсенного вызова (шлюз срезал, аргументы не разобрались) финалом не
считается: ход остаётся в диалоге, модель переспрашивается, потолок ходов с мягкой посадкой закрывает
вырожденный случай.

### Гварды цикла
Ход без tool call'ов — ещё не финал. Вырожденный «финал» перехватывается и не доезжает до
пользователя:

- **пустой текст** — reasoning-модели за OpenAI-совместимым шлюзом иногда тратят всю генерацию на
  `reasoning_content` и возвращают пустой `content` с `finish_reason: stop` (наблюдалось на
  `deepseek-v4-flash`: 858 output-токенов, текста нет). Сбоем это не помечено ничем, поэтому без
  guard'а ран завершался «успешно», а пользователь видел тишину: пустой ANSWER бэк не доставляет
  (`MessageLogService` режет blank), и в истории оставалась пустая строка. Проверка стоит **до**
  развилки по `finish_reason`: ход без текста и без вызовов пуст независимо от того, что заявил
  провайдер. Пустой ход выбрасывается
  из диалога (сигнала для модели он не несёт, а пустой assistant-content строгие шлюзы отклоняют),
  и **взамен него не дописывается ничего** — уходит тот же самый запрос, который его и породил.
  Это и есть проверяемая гипотеза: пустой ответ — икота провайдера, которую лечит новый бросок
  (температуру воркер не задаёт, так что повтор действительно другой). Корректирующее сообщение
  сделало бы запрос другим, ссылалось бы на ход, только что удалённый из контекста модели, и
  осталось бы в контексте до конца рана. Переспрос — **один**: он стоит пользователю ещё одного
  полного вызова. Дальше `EmptyAnswerExhausted` → нотис `notice.empty-answer`.

Выброшенные ходы эфемерны: ни в историю, ни в канал они не проецируются. Токены каждой попытки при
этом учитываются — `onUsage` идёт до решения о судьбе хода.

### Producer contract (shared code, not config)
The queue/class/workflow/instance names (`agent_exec`/`AgentRunWorkflow`/`run_agent`/`default`)
live in **`ru.agimate.agentworker.WorkerProtocol`** (`libs/agentworker-proto`), compiled into
both control-api and the worker — the contract cannot drift. In Java DBOS the names bind via
`@WorkflowClassName` + `@Workflow(name=...)` + `registerProxy(iface, impl, instance)`;
serialization is `PORTABLE`. control-api enqueues the run-stage workflow directly:
`workflow_id == runId` (delivery dedupes on it), partition key — the run's `sessionId`
(direct run — its own `runId`).

### Session serialization
Воркер не знает `sessionId` — партицию задаёт продюсер при enqueue. Партиционированная
очередь (concurrency=1 → один исполняющийся ран на сессию) — единственный механизм
single-writer'а и контрактное требование к транспорту; регистрационного хэндшейка
(RegisterRun/ReleaseRun) нет. Жизненный цикл рана — серверная проекция потока `SaveMessage`;
признак жизни — RPC рана (молча умерший ран добирает серверный сборщик). Стиринг — захват
сообщений младших ранов сессии на шве цикла (`ClaimSteering`/`MarkSteered`), см.
[worker-protocol.md](../contracts/worker-protocol.md), раздел «Стиринг».

## Configuration
Bound from `application.yaml` under `agent.*`; every value is overridable via env (relaxed
binding, e.g. `AGENT_GRPC_TARGET`, `AGENT_DBOS_DATABASE_URL`). See `.env.example`. Key sections:
`grpc` (target/tls/auth-token), `agent` (id/max-turns), `concurrency` (`llm` — the per-worker semaphore around provider requests),
`session` (run-ttl-seconds), `tool` (poll-timeout — дефолтный бюджет ожидания результата тул-вызова;
спек тула может заявить свой `timeout_seconds` (кламп 30 мин) — тогда он побеждает; таймаут
не отменяет джобу на бэке, модель получает явное «could still complete»; detach-after — grace
ожидания до детача (дефолт 10 с, ≤0 выключает): не уложившийся вызов отцепляется, модель получает
task handle, результат приедет триггером `tool_completed`; бюджеты остаются потолком блокирующего
фолбэка при недоступном `DetachTool`; max-output-chars — потолок
вывода одного тула: гигантский вывод раздувает контекст всех последующих turns, поэтому обрезается
с явной пометкой), `response` (`language` —
язык пользовательских нотисов), `dbos` (system database — must match control-api's;
`retention` — сколько хранить завершённые воркфлоу с чекпоинтами, 0 отключает).

### User-facing notices (i18n)
Пользовательские нотисы (max-turns, ошибка/квота модели, «модель не настроена», обрезка/фильтр
ответа, пустой ответ модели, инфра-сбой) — `resources/messages_<lang>.properties`, резолвятся через Spring
`MessageSource`. Язык выбирается
`agent.response.language` (BCP-47, дефолт `en`; в комплекте `en` и `ru`). Неизвестный язык падает
в базовый бандл (`messages.properties`, английский) — `spring.messages.fallback-to-system-locale:
false`, поэтому JVM-локаль не влияет. Per-deploy: один язык на воркер; `ResponseTemplates`
резолвит локаль один раз и отдаёт нотисы `AgentRunner`. Per-agent локаль (из рана) —
на будущее.

В том же бандле лежат тексты **не для пользователя, а для модели** — ключи `prompt.*`: нотис
мягкой посадки (`prompt.wrap-up`), преамбула недоверенного блока и два абзаца системного промпта
про вывод инструментов и отцепленные вызовы (`prompt.untrusted-preamble`,
`prompt.tool-output-guidance`, `prompt.detached-tool-guidance`), обрамление сообщения, поглощённого
стирингом (`prompt.steered-prefix`), и две подсказки про вложенные картинки (`prompt.image-visible`,
`prompt.image-not-visible`). Они уезжают в диалог, поэтому их ось — язык диалога, а не язык
инсталляции. Пока обе оси совпадают, это правильное место; когда разойдутся (per-agent локаль), их
придётся резолвить из рана. В коде текстов для модели не осталось: `AgiMateAgent`,
`ContextBuilder`, `SteeringAbsorber` и `LlmMessageMapper` получают их из `ResponseTemplates`.
Guidance системного промпта здесь **не** живёт вовсе: его собирает бэкенд.

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
