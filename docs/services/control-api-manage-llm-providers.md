# control-api — LLM Provider Endpoints

API specification for the `/manage/llm-providers/**` and `/manage/agents/{agentPubId}/llms/**` endpoint groups, plus the agent-runtime endpoint `/agent/llm`.

LLM providers are per-user. Agents can bind to multiple providers/models under arbitrary labels (`main_model`, `for_light_task`, `visual_task`, …). API keys are encrypted at rest (envelope AES-256-GCM in the `secrets` store, KEK = `app.secrets.encryption-key`) and only returned, decrypted, to the authenticated agent itself via `GET /agent/llm`.

Besides user providers there is a single **platform provider** — a system-owned `llm_providers` row that serves as an implicit fallback for agents without any binding (see [Platform provider](#platform-provider)).

> All paths below are relative to the context path `/control`.

## Authentication

| Group | Mechanism | Header |
|-------|-----------|--------|
| `/manage/llm-providers/**`, `/manage/agents/{agentPubId}/llms/**` | **JWT** | `Authorization: Bearer <jwt>` |
| `/agent/llm/**` | **API key** | `x-api-key: agnt<keyId><secret>` |

## Provider types

| `providerType` | `baseUrl` | Notes |
|---|---|---|
| `OPENAI` | optional | Defaults to `https://api.openai.com`. Models discovered via `GET /v1/models`. |
| `ANTHROPIC` | optional | Defaults to `https://api.anthropic.com`. `GET /v1/models` with `anthropic-version: 2023-06-01`. |
| `GEMINI` | optional | Defaults to `https://generativelanguage.googleapis.com`. `GET /v1beta/models?key=…`. |
| `OPENAI_COMPATIBLE` | **required** | Any OpenAI-compatible endpoint (Azure OpenAI, OpenRouter, vLLM, etc.). `GET /v1/models` with `Bearer` auth. |

---

## LLM Providers

### `LlmProviderResponse`

| Field | Type | Description |
|---|---|---|
| `pubId` | UUID | Public ID |
| `name` | string | Human-readable name (unique per user) |
| `providerType` | enum | One of `OPENAI`, `ANTHROPIC`, `GEMINI`, `OPENAI_COMPATIBLE` |
| `baseUrl` | string? | Custom endpoint, null = provider default |
| `apiKeyMask` | string | Masked key (e.g. `sk-...AbCd`); the real key is never returned |
| `extraBody` | object? | Provider-level extra chat/completions body fields (e.g. OpenRouter `provider` routing). Deep-merged with per-model `extraBody` (model wins) and sent to the worker with LLM credentials. **Not a secret store.** |
| `modelsRefreshedAt` | datetime? | When the model registry was last refreshed from the provider listing |
| `enabled` | boolean | Disabled providers are filtered out from `GET /agent/llm` |
| `platform` | boolean | `true` for the system-owned platform (free-tier) row. Visible to ADMIN only; rename/delete are rejected. |
| `createdAt` | datetime | |

### `GET /control/manage/llm-providers/`

List the current user's providers.

### `POST /control/manage/llm-providers/`

Create a provider. Body:

```json
{
  "name": "Production OpenAI",
  "providerType": "OPENAI",
  "baseUrl": null,
  "apiKey": "sk-...",
  "enabled": true
}
```

Validation:
- `name` unique per user → `409` on conflict.
- `apiKey` non-blank → required.
- `OPENAI_COMPATIBLE` requires `baseUrl` → `400` otherwise.

### `GET /control/manage/llm-providers/{pubId}`

### `PATCH /control/manage/llm-providers/{pubId}`

Partial update. All fields optional. If `apiKey` is absent or blank, the existing encrypted key is left untouched. If present, it is re-encrypted and `apiKeyMask` is recomputed.

### `DELETE /control/manage/llm-providers/{pubId}`

Cascades to `agent_llms` (bindings disappear from `GET /agent/llm`).

### `POST /control/manage/llm-providers/{pubId}/refresh-models`

Synchronously calls the provider listing and **upserts the model registry** (`llm_provider_models`): listed models get metadata + `lastSeenAt` + `status=AVAILABLE`; models that disappeared from the listing are kept with `status=UNAVAILABLE` (they may carry config and agent bindings). Guard: an empty listing leaves statuses untouched. Connect timeout 5 s, read timeout 10 s. Provider errors → `400` with the provider's status code echoed.

Response:

```json
{
  "response": {
    "models": [ { "model": "gpt-4o", "status": "AVAILABLE", "...": "..." } ],
    "refreshedAt": "2026-05-07T12:34:56"
  }
}
```

## Model registry

Per-provider model registry rows (`llm_provider_models`, unique `(provider, model)`): discovery metadata, availability lifecycle and per-model `extraBody` override. `status` is **advisory** — listings can be incomplete, so an `UNAVAILABLE` model still binds and still gets credentials; the UI uses it for warnings only.

Models whose parameters the provider does not report (e.g. bare-id listings from OpenAI/Anthropic) are backfilled at refresh time from a curated **model defaults** table (discovered values always win). Absence of a default row just leaves the fields `null` — same as before.

### `LlmProviderModelResponse`

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Registry row id |
| `model` | string | Provider-specific model id (e.g. `moonshotai/kimi-k2.5`) |
| `displayName` | string? | From the listing |
| `contextWindow` | int? | Context window in tokens (from the listing or model defaults) |
| `maxOutputTokens` | int? | Max output tokens (`top_provider.max_completion_tokens`) |
| `inputModalities` | string[]? | e.g. `["text","image"]` — `image` means the model has vision |
| `outputModalities` | string[]? | e.g. `["image"]` / `["audio"]` — basis for model-as-tool routing |
| `supportedParameters` | string[]? | e.g. `["tools","reasoning"]` |
| `extraBody` | object? | Per-model extra chat/completions body fields; deep-merged over the provider-level `extraBody`, model wins, arrays replaced whole |
| `status` | enum | `AVAILABLE` / `UNAVAILABLE` (advisory, per the last successful refresh) |
| `firstSeenAt` | datetime? | `null` — never listed (config added manually before refresh) |
| `lastSeenAt` | datetime? | Last time seen in the listing |

### `GET /control/manage/llm-providers/{pubId}/models/`

List the registry.

### `PUT /control/manage/llm-providers/{pubId}/models/extra-body`

Set or clear per-model `extraBody`. The model id goes in the body (it may contain slashes). Upserts the row — config may be added for a model the provider hasn't listed yet (`firstSeenAt=null`, `UNAVAILABLE`). `extraBody: null` clears the override; serialized size is capped at 16 KB.

```json
{
  "model": "moonshotai/kimi-k2.5",
  "extraBody": { "provider": { "only": ["moonshotai"], "require_parameters": true } }
}
```

---

## Agent ↔ LLM bindings

### `AgentLlmResponse` (no API keys)

| Field | Type | Description |
|---|---|---|
| `name` | string | Binding label (unique per agent) |
| `model` | string | Model name |
| `llmProviderId` | UUID? | Bound provider; `null` for the platform fallback entry |
| `llmProviderName` | string | Provider's display name |
| `providerType` | enum | Provider type |
| `source` | enum | `USER` — explicit `agent_llms` row; `PLATFORM` — synthetic fallback entry |

When an agent has no bindings and the platform provider is usable (enabled + `default_model` set), listing endpoints return a single synthetic entry with `source=PLATFORM` showing the effective model. It is not a DB row: it cannot be updated or deleted, and `llmProviderId` is `null`.

### `GET /control/manage/agents/{agentPubId}/llms/`

### `POST /control/manage/agents/{agentPubId}/llms/`

Body:

```json
{
  "name": "main_model",
  "llmProviderPubId": "018f...",
  "model": "gpt-4o"
}
```

Validation:
- `name` unique per agent → `409` on conflict.
- Provider must belong to the same user → `404` otherwise.
- If the provider's model registry is non-empty, `model` must be a registry row (any status — `UNAVAILABLE` is advisory) → `400` otherwise. If the registry is empty (never refreshed), the model is accepted with a server-side warning.

### `PUT /control/manage/agents/{agentPubId}/llms/{name}`

Replaces an existing binding's `llmProviderPubId` and `model`. Body uses the same shape as `POST` minus `name`.

### `DELETE /control/manage/agents/{agentPubId}/llms/{name}`

---

## Agent runtime

### `AgentLlmRuntimeResponse`

| Field | Type | Description |
|---|---|---|
| `name` | string | Binding label |
| `providerType` | enum | |
| `baseUrl` | string? | Null = provider default |
| `model` | string | |
| `apiKey` | string | **Decrypted** — agents must keep it in memory and never log it |

### `GET /control/agent/llm`

Returns all bindings of the authenticated agent whose providers are `enabled=true`. Disabled providers are silently excluded.

### `GET /control/agent/llm/{name}`

Returns the single binding identified by its label. `404` if the binding does not exist or its provider is disabled.

---

## Platform provider

A single system-owned `llm_providers` row (owner = synthetic system user, name `platform`) used as an implicit fallback: when an agent has **no** `agent_llms` bindings, the worker's `GetLlmCredentials` gRPC issues the platform provider's credentials with its `default_model`. A personal binding always wins over the fallback.

- **Created and managed entirely by an ADMIN via the API** — there is no startup seeding and no `app.platform-llm.*` environment variables. On a fresh install the platform provider simply does not exist until an ADMIN creates it with `POST /manage/llm-providers/platform` (see below).
- The **DB is the sole source of truth**: key rotation and model changes are done via `PATCH /{id}` and persist across restarts.
- Created with `enabled=false`; enabling the free-tier is a deliberate action performed by an **ADMIN**, typically after quotas are configured.
- Invisible to regular users in `/manage/llm-providers/**` and not addressable in binding requests (`404` — the provider belongs to the system user).
- `default_model` exists on every provider (`Create`/`Update` requests accept it; for user providers it is a UI preselect); on the platform row it is the fallback model and is required for the fallback to work.

### ADMIN management

Users with role `ADMIN` (`users.role` in user-api, carried in the JWT `roles` claim) manage the platform provider **through the same endpoints**:

- `POST /manage/llm-providers/platform` — create the platform row. Body is a dedicated `CreatePlatformLlmProviderRequest` — `{ providerType, baseUrl?, apiKey, defaultModel? }` — with **no `name` and no `enabled`**: the name is forced to `platform`, the owner is the system user, and the row is always created `enabled=false`. `providerType` + `apiKey` are required; `OPENAI_COMPATIBLE` also needs `baseUrl`. `409` if it already exists.
- `GET /manage/llm-providers/` — the platform row is appended to the admin's own providers, marked `platform: true` in `LlmProviderResponse`.
- `GET`/`PATCH /{id}`, `POST /{id}/refresh-models` — allowed on the platform row for admins. Enabling the free-tier = `PATCH {"enabled": true}`; key rotation = `PATCH {"apiKey": "..."}`.
- `POST`/`PATCH`/`DELETE /manage/llm-providers/{id}/quotas/**` — admins manage the free-tier quota on the platform row (typically `subjectKind: USER` for a per-user cap, optionally `subjectKind: TOTAL` for a system-wide cap).
- Restrictions on the platform row: **rename rejected** (`400` — the name `platform` is the fallback lookup key) and **delete rejected** (`400` — disable via `enabled` instead).

The typical free-tier bring-up from the UI: `POST /platform` (create disabled) → `POST …/quotas/` (set per-user / system caps) → `PATCH /{id} {"enabled": true}`.
- Admins do **not** get access to other users' providers — only their own plus the platform row.
- Assigning the ADMIN role: `UPDATE users SET role = 'ADMIN' WHERE id = ...` in the user-api DB (no UI yet).

---

## Usage accounting

Every successful LLM call made by the managed worker is reported back over gRPC (`ReportLlmUsage`, best-effort) and recorded in two tables:

- `llm_usage_log` — per-call journal (audit/debug): run, agent, user, provider, model, input/output/cache tokens. Idempotent by `call_id` (the DBOS workflow id of the LLM call), so worker replays never double-count.
- `llm_usage_counters` — aggregates per `(provider, subject, calendar window UTC)` used for quota enforcement and "remaining" displays. Subjects: `USER` (per user), `AGENT` (per agent), `TOTAL` (whole provider); windows: `DAY`, `MONTH`. All six counters are incremented in the same transaction as the journal insert; the token metric is `input + output + cache_write` (cache reads are not counted).

Counters exist for BYOK providers too — usage stats are collected from day one regardless of whether a quota is configured.

---

## Quotas

Token quotas are declared per provider in `llm_quotas` and checked in `GetLlmCredentials` — i.e. before **every** LLM call (overshoot is bounded by one call). A provider without quotas is unlimited. Exceeding a quota fails the call with gRPC `RESOURCE_EXHAUSTED`; the human-readable message reaches the user as the run's ERROR message.

Quota key: `(provider, subjectKind, window)` — one quota per combination.

| `subjectKind` | Meaning | Typical use |
|---|---|---|
| `USER` | limit per each user | platform free-tier («каждому пользователю N в день») |
| `AGENT` | limit per each agent | BYOK: a runaway agent can't burn the key |
| `TOTAL` | limit for the whole provider | BYOK: wallet ceiling; on the **platform** provider — a system-wide cap across all users combined |

Windows are calendar UTC: `DAY`, `MONTH`. Metric: `input + output + cache_write` tokens.

### `GET /control/manage/llm-providers/{providerId}/quotas/`

### `POST /control/manage/llm-providers/{providerId}/quotas/`

```json
{ "subjectKind": "TOTAL", "window": "DAY", "limitTokens": 100000 }
```

`409` on duplicate `(subjectKind, window)`; `limitTokens >= 1`. Access is gated by `requireOwnedOrPlatformAdmin`: the provider must belong to the caller, **or** be the platform provider and the caller an ADMIN.

### `PATCH /control/manage/llm-providers/{providerId}/quotas/{quotaId}`

Change an existing quota's token limit (atomic — no delete+recreate gap). `subjectKind` and `window` are immutable (they form the quota's business key); to change the subject/window, delete and create a new quota.

```json
{ "limitTokens": 250000 }
```

`limitTokens >= 1`; `404` if the quota does not exist on the provider. Same access gate as above.

### `DELETE /control/manage/llm-providers/{providerId}/quotas/{quotaId}`

---

## Usage view

### `GET /control/manage/llm-usage/`

Usage and remaining quota per provider for the current calendar windows. Perspective depends on provider type: own (BYOK) providers show whole-provider usage (`TOTAL` subject), the platform provider shows the current user's usage (`USER` subject, `llmProviderId: null`).

```json
{
  "response": [
    {
      "llmProviderId": "018f...",
      "providerName": "my-openrouter",
      "source": "USER",
      "windows": [
        { "window": "DAY", "windowStart": "2026-07-13", "usedTokens": 300,
          "requests": 7, "limitTokens": 1000, "remainingTokens": 700 },
        { "window": "MONTH", "windowStart": "2026-07-01", "usedTokens": 4500,
          "requests": 120, "limitTokens": null, "remainingTokens": null }
      ]
    }
  ]
}
```

---

## Notes

- Agents are responsible for calling the LLM themselves; the backend does not proxy LLM traffic.
- The KEK (`app.secrets.encryption-key`) is shared by the whole `secrets` store — rotating it requires re-wrapping the DEK of every `secrets` row.
- Deleting a provider cascades to all agent bindings via `ON DELETE CASCADE` on `agent_llms.llm_provider_pub_id`.
- Deleting an agent cascades to its bindings via `ON DELETE CASCADE` on `agent_llms.agent_pub_id`.
