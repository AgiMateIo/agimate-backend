# agent-worker

Java port of the `pydantic-dbos-agent` Python worker. A headless (non-web) Spring Boot
service that consumes work from DBOS queues, runs an agent turn-loop against an LLM (Spring AI)
and backend tools, and talks to control-api over gRPC. control-api is the **producer** that
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
| `MessageCodec` | (De)serialize messages to the `message_json` bytes persisted as history + timeline/progress text projections. |
| `ToolRegistry` | Sanitized LLM name ↔ backend `(connector_code, name, identity)`; `{namespace}.{name}` naming; schema parsing. |
| `PromptBuilder` | System prompt (agent/team/skills/memory), untrusted-trigger wrapping, skill selection, memory notes. |
| `SimpleAgent` | The manual turn-loop (LLM call + tool dispatch injected; optional steering checkpoint). |
| `AgentRunner` | Assemble the message list, map terminal failures to `AgentRunAborted`. |

### `llm/` — Spring AI (OpenAI)
`ModelFactory` builds an `OpenAiChatModel` **per call** from backend `LlmCredentials`.
`LlmMessageMapper` converts to/from Spring AI messages and exposes tool definitions as no-op
`ToolCallback`s — the loop is driven **manually** (no ToolCallingAdvisor), so tool calls come
back to us to dispatch on a separate queue instead of Spring AI auto-executing them.

### `workers/` — DBOS surface
| Workflow | Queue | Role |
|---|---|---|
| `AgentWorkflow.startAgent` | `agent_runs` | **Router**: atomic `RegisterRun` claim → enqueue the run; on a busy session applies the policy (queue/steer/interrupt). |
| `AgentRunWorkflow.runAgent` | `agent_exec` | **Run stage** (partitioned by session, concurrency=1 → one writer per session): register/release the slot, route channel vs trigger, drive `AgentRunCore`. |
| `LlmCallWorkflow.llmCall` | `llm_calls` | One model request; credentials fetched inline (never checkpointed). |
| `ToolCallWorkflow.toolCall` | `tool_calls` | One backend tool call (`ExecuteToolAsync` + poll `GetToolResult`); never raises. |

The package root is what DBOS sees: the four workflow pairs, `Queues`, the router↔run
`ControlSignal` and the claim helper. The run-body machinery lives in `workers/run`:
`AgentRunCore` holds the invariant run body — a `prepare_context` step (spec/team/skills → prompt
+ `ToolRegistry`), history restore/append steps, the loop (via `AgentDispatcher` binding the
LLM/tool queues), and failure reporting; `OutboundPublisher` routes progress/answer/error to
channels by role with deterministic `message_id`s for idempotent replays.

### Producer contract (shared code, not config)
The queue/class/workflow/instance names (`agent_runs`/`AgentWorkflow`/`start_agent`/`default`)
and the router workflow-id scheme live in **`ru.agimate.agentworker.WorkerProtocol`**
(`libs/agentworker-proto`), compiled into both control-api and the worker — the contract cannot
drift. In Java DBOS the names bind via `@WorkflowClassName` + `@Workflow(name=...)` +
`registerProxy(iface, impl, instance)`; serialization is `PORTABLE`. control-api enqueues the
router under `WorkerProtocol.routerWorkflowId(runId)` (`runId + ":router"`) so the bare `run_id`
is free for the run-stage workflow (`run_id ==` that workflow's DBOS id, which steering
addresses).

### Channels & session
The enqueued `Channels` envelope has three roles (`prompt`/`progress`/`answer`), each resolved
with a fallback. The presence of `channels.prompt` — not the always-`"trigger"` `type` field —
is the channel-vs-trigger discriminator. The single-writer/history session key is resolved
**once by control-api** (prompt channel's session, else answer's) and shipped as the explicit
`AgentMessage.sessionId` field — the worker does not re-derive it (a channel-based fallback
remains only for messages enqueued before the field existed).

### Steering (session.on-active-message)
- **queue** (default): a message into an active session waits on the partitioned run queue and
  runs after the current run releases the slot — fixes the `turn_idx` race via single-writer.
- **steer**: the new message is delivered to the active run's DBOS `control` mailbox and folded
  in at the next turn boundary; no new run starts.
- **interrupt**: the active run is asked to stop gracefully (`AgentInterrupted`, no hard cancel)
  and a new run is enqueued behind it.

## Configuration
Bound from `application.yaml` under `agent.*`; every value is overridable via env (relaxed
binding, e.g. `AGENT_GRPC_TARGET`, `AGENT_DBOS_DATABASE_URL`). See `.env.example`. Key sections:
`grpc` (target/tls/auth-token), `agent` (id/workflow-id), `concurrency` (agent-runs/llm/tool),
`session` (on-active-message), `dbos` (system database — must match control-api's).

### gRPC client resilience
`AgentWorkerClient` retries `UNAVAILABLE` at the transport level with exponential backoff
(~63s budget) — a routine control-api restart is waited out instead of killing the run. This
sits below DBOS step retries and also covers the non-step call sites (inline LLM credentials
fetch, `OutboundPublisher` sends). `ABORTED` is a business outcome and never retried; other
statuses fail fast as `ControlApiCallException` (serializable, unlike the raw gRPC exception).

## Run
```bash
cd services
./gradlew :agent-worker:bootRun     # needs the shared DBOS Postgres reachable
./gradlew :agent-worker:test        # pure-logic unit tests
```
The gRPC/proto stubs are the shared `:libs:agentworker-proto` module (also used by control-api).
