# control-api — LLM Provider Endpoints

API specification for the `/manage/llm-providers/**` and `/manage/agents/{agentPubId}/llms/**` endpoint groups, plus the agent-runtime endpoint `/agent/llm`.

LLM providers are per-user. Agents can bind to multiple providers/models under arbitrary labels (`main_model`, `for_light_task`, `visual_task`, …). API keys are encrypted at rest (AES-GCM via `app.integration.encryption-key`) and only returned, decrypted, to the authenticated agent itself via `GET /agent/llm`.

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
| `availableModels` | string[]? | Models from the last refresh |
| `modelsRefreshedAt` | datetime? | When `availableModels` was refreshed |
| `enabled` | boolean | Disabled providers are filtered out from `GET /agent/llm` |
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

Synchronously calls the provider to fetch its models, replaces `availableModels`, updates `modelsRefreshedAt`. Connect timeout 5 s, read timeout 10 s. Provider errors → `400` with the provider's status code echoed.

Response:

```json
{
  "response": {
    "availableModels": ["gpt-4o", "gpt-4o-mini", "..."],
    "refreshedAt": "2026-05-07T12:34:56"
  }
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
- If the provider has a non-empty `availableModels`, `model` must be in that list → `400` otherwise. If the list is empty (never refreshed), the model is accepted with a server-side warning.

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

A single system-owned `llm_providers` row (owner = synthetic system user, name `platform`, always `OPENAI_COMPATIBLE`) used as an implicit fallback: when an agent has **no** `agent_llms` bindings, the worker's `GetLlmCredentials` gRPC issues the platform provider's credentials with its `default_model`. A personal binding always wins over the fallback.

- Seeded on startup by `PlatformLlmBootstrap` from `app.platform-llm.*` properties (`APP_PLATFORM_LLM_BASE_URL`, `APP_PLATFORM_LLM_API_KEY`, `APP_PLATFORM_LLM_DEFAULT_MODEL`). If any is missing, seeding is skipped and the feature is off.
- Created with `enabled=false`; enabling the free-tier is a deliberate runtime action in the DB (`llm_providers.enabled`). Bootstrap never touches `enabled` on subsequent starts — it only syncs `base_url`, `default_model` and the key.
- Invisible in `/manage/llm-providers/**` (those are filtered by the current user) and not addressable in binding requests (`404` — the provider belongs to the system user).
- The `default_model` column exists on every provider but is currently used only by the platform row.

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
| `TOTAL` | limit for the whole provider | BYOK: wallet ceiling |

Windows are calendar UTC: `DAY`, `MONTH`. Metric: `input + output + cache_write` tokens.

### `GET /control/manage/llm-providers/{providerId}/quotas/`

### `POST /control/manage/llm-providers/{providerId}/quotas/`

```json
{ "subjectKind": "TOTAL", "window": "DAY", "limitTokens": 100000 }
```

`409` on duplicate `(subjectKind, window)`; `limitTokens >= 1`. Provider must belong to the user — the platform provider is not addressable here (its quota is seeded by the platform bootstrap).

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
- Encryption key (`app.integration.encryption-key`) is shared with `IntegrationCredentials` — rotating it requires re-encrypting every row in both tables.
- Deleting a provider cascades to all agent bindings via `ON DELETE CASCADE` on `agent_llms.llm_provider_pub_id`.
- Deleting an agent cascades to its bindings via `ON DELETE CASCADE` on `agent_llms.agent_pub_id`.
