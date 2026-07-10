# Agent Context Design

How the agent-worker assembles the LLM context for a run, and the design frame behind it.
The code seam is `agent/context/` in `services/agent-worker` (`ContextProfile` →
`ContextBuilder.build(profile, materials)` → `PreparedContext`); this document records the
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
| Skills | all listed, **no bodies** | all listed, bodies of **matched** skills injected |
| Toolset | connectors of **all** skills | connectors of **matched** skills only |
| System prompt | base | base + trigger guidance («часто правильный исход — ничего не делать») |
| User turn | inbound text as a trusted block | event as an **untrusted** block (renderer wraps it) |

The preset is chosen by the route snapshot persisted at dispatch
(`trigger_log_agents.channels`: prompt channel present → `DIALOGUE`). New input kinds
(e.g. inter-agent requests) become new enum constants with their own policy row — not new
conditionals inside the assembly.

## Composition invariants

- **Stable parts first.** Prompt-cache prefixes only pay off if the stable content (agent
  block, system prompt, memory) precedes the volatile content (notes, per-run sections).
  Keep the composition order cache-friendly as sections are added.
- **O(1) table of contents** (see baskets above).
- **Trust boundary.** Trusted instructions reach the model only via the system prompt;
  external event payloads are always wrapped as untrusted data in the user turn.
- **Checkpoint shape is frozen.** `PreparedContext` (and `ToolRegistry`'s serialized parts)
  are DBOS durable-step results: FQCN and field shape must not change across deploys —
  in-flight runs replay the serialized result. Evolve the assembly *behind* the seam, not the
  seam's output type.

## Roadmap (priority order)

1. **Past-runs digest in the SYSTEM_TRIGGER core** — an O(1) aggregate over
   `trigger_log_agents` (how many runs before, failed, active + the last error) plus
   introspection tools (`get_past_runs`, `get_tool_call_result`) for details. Cures the
   autonomous loop's amnesia: today `TriggerLogAgent` and `tool_call_logs` are invisible to
   the agent entirely.
2. **Environment manifest** — an O(1) table of contents of the agent's world (channels,
   activity, memory size, current time) in every profile.
3. **tool_search / deferred tools** — the model gets a small active toolset + a search tool
   over the full catalog; extends `ToolRegistry` + `ContextBuilder` with an active/deferred
   split.
4. **Token budget & history compaction** — per-part size accounting in `ContextBuilder`; a
   history builder appears together with compaction (deliberately not created empty today).

## Protocol (stage 2 — implemented)

Materials arrive in one atomic `GetRunContext(agent_id, trigger_id)` RPC: the backend
(`RunContextService`) assembles ordered `PromptBlock`s (system/user, trust and ephemerality
flags, connector blocks via `PromptBlockProvider`) and the scoped toolset;
`workers/run/ContextMaterialsFetcher` stays as the wire→`ContextMaterials` seam and
`agent/context/ContextBuilder` is a **pure renderer**: tags blocks (`<name attrs>`), wraps
untrusted ones with the data-not-instructions preamble (neutralizing closing tags inside the
payload), splits ephemeral user blocks (memory notes) into a non-persisted suffix. LLM
credentials deliberately stay a separate inline RPC — the `GetRunContext` result is
checkpointed and must not carry the api_key. See
[`services/control-api-grpc-worker.md`](services/control-api-grpc-worker.md).
