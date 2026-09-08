# Agent Context Design

How the agent-worker assembles the LLM context for a run, and the design frame behind it.
The code seam is backend-side assembly (`RunContextService` + `ContextSpec` in control-api) →
one `GetRunContext` RPC → pure rendering in `agent/context/ContextBuilder` (`PreparedContext`);
this document records the
reasoning that the code must keep honoring as the context grows.

## The contradiction

The context must be **complete** (the agent should never act blind: skills, tools, memory,
environment, what happened before) and **minimal** (token cost, prompt-cache hits, attention
quality degrade with size). Resolving "complete AND minimal" is the organizing problem; every
feature below is one of its resolutions, not an independent knob.

## Resolution 1: three baskets

Every piece of candidate context falls into exactly one basket:

| Basket | Contract | Examples |
|---|---|---|
| **Core** | Always in the prompt, full text. Small and stable. | Agent spec + system prompt, memory, in-scope skill bodies, trigger guidance |
| **Table of contents** | Always in the prompt, but only as **O(1) aggregates** — counts, names, last-error, never full listings that grow with data. | Skills listing (metadata only), *(roadmap)* past-runs digest, environment manifest |
| **Retrievable** | Not in the prompt at all; the agent pulls details through tools when needed. | Connector tool calls today; *(roadmap)* `get_past_runs`, `get_tool_call_result`, deferred tools via tool_search |

The basket boundary is a **hard invariant**: nothing lands in the table of contents unless it
is O(1) in the size of the underlying data. If a summary needs a listing, it belongs in the
retrievable basket behind a tool. Retrieval through tool calls is DBOS-replay-safe for free —
tool results are checkpointed like any other tool call, so no new durability machinery is
needed when the roadmap items land.

## Resolution 2: policies by input type (server-side `ContextSpec`)

Different inputs need different contexts. A user message in a dialogue and an autonomous
system trigger are *not* the same run with a flag — they are different context policies,
declared in one place. Since stage 2 the policy lives **server-side**
(`controlapi/service/runcontext/ContextSpec`), where the data is — the worker receives ready
blocks and never branches on input kind:

| Policy | `DIALOGUE` | `SYSTEM_TRIGGER` |
|---|---|---|
| Skills | all listed, **all bodies** injected (skills define dialogue behavior too) | all listed, bodies of **matched** skills injected |
| Toolset | connectors of **all** skills | connectors of **matched** skills only |
| System prompt | base | base + trigger guidance («часто правильный исход — ничего не делать») |
| User turn | inbound text as a trusted block | event as an **untrusted** block (renderer wraps it) |

The preset is chosen by the route snapshot persisted at dispatch
(`agent_runs.channels`: prompt channel present → `DIALOGUE`). New input kinds
(e.g. inter-agent requests) become new enum constants with their own policy row — not new
conditionals inside the assembly.

## Composition invariants

- **Stable parts first.** Prompt-cache prefixes only pay off if the stable content (agent
  block, system prompt, memory) precedes the volatile content (notes, per-run sections).
  Keep the composition order cache-friendly as sections are added.
- **O(1) table of contents** (see baskets above).
- **Trust boundary.** Trusted instructions reach the model only via the system prompt;
  external event payloads are always wrapped as untrusted data in the user turn.
- **Checkpoints hold identifiers, not context.** `GetRunContext` is deliberately *not* a
  durable step: a crash replay re-fetches today's context instead of restoring a serialized
  one, so `PreparedContext` and `ToolRegistry` are free to change shape between deploys and
  even within a run (progressive disclosure grows the registry mid-run). What DBOS keeps is
  ids and statuses — the turn index of an LLM call, the outcome of a tool call — and the
  content is re-read from the backend (`GetTurn`, `GetToolResult`). See
  [`../decisions/dbos-ids-only.md`](../decisions/dbos-ids-only.md).

## Roadmap (priority order)

1. **Past-runs digest in the SYSTEM_TRIGGER core** — an O(1) aggregate over
   `agent_runs` (how many runs before, failed, active + the last error) plus
   introspection tools (`get_past_runs`, `get_tool_call_result`) for details. Cures the
   autonomous loop's amnesia: today `AgentRun` and `tool_call_logs` are invisible to
   the agent entirely.
2. **Environment manifest** — an O(1) table of contents of the agent's world (channels,
   activity, memory size, current time) in every profile.
3. **Deferred tools and skill bodies** — accepted as
   [`../decisions/progressive-disclosure.md`](../decisions/progressive-disclosure.md): an
   `EAGER|LAZY` axis on connectors, tools and skills, lazy tools listed by name and summary only,
   the `skill-loader`/`tool-deferral` connectors' `load_skill`/`describe_tools` disclosing on
   demand, the disclosed set derived from the history window by the backend-computed `llm_name`. A search over the catalog stays open there for
   catalogs of hundreds of tools.
4. **Token budget & history compaction** — per-part size accounting in `ContextBuilder`; a
   history builder appears together with compaction (deliberately not created empty today).

## Protocol (stage 2 — implemented)

Materials arrive in one atomic `GetRunContext(agent_id, run_id)` RPC: the backend
(`RunContextService`) assembles ordered `PromptBlock`s (system/user, trust and ephemerality
flags, connector blocks via `PromptBlockProvider`) and the scoped toolset;
`workers/run/ContextMaterialsFetcher` stays as the wire→`ContextMaterials` seam and
`agent/context/ContextBuilder` is a **pure renderer**: tags blocks (`<name attrs>`), wraps
untrusted ones with the data-not-instructions preamble (neutralizing closing tags inside the
payload), splits ephemeral user blocks (memory notes) into a non-persisted prefix prepended
ahead of the user's message. LLM
credentials deliberately stay a separate inline RPC — fetched per call, the api_key neither enters
a checkpoint nor outlives the call.

Stage 3 (`SaveMessage`, `SaveTurn`) makes the worker the single writer of both records: the
channel projection (`channel_session_messages`, the dialogue «as the user saw it») and the
canonical turn ledger (`agent_run_turns`, the model's own message list, tool turns included).
`GetRunContext.history` is assembled from the ledger, not the projection: finished runs whose
ledger is intact, a window counted in runs, the parts (`DIALOG`/`TOOLS`/`REASONING`) chosen by
the ContextSpec preset, tool turns handed back as native `tool_use`/`tool_result` pairs. Channel
delivery is a backend-side projection of the same record. See
[`../decisions/history-from-turn-ledger.md`](../decisions/history-from-turn-ledger.md) and
[`../contracts/worker-protocol.md`](../contracts/worker-protocol.md).
