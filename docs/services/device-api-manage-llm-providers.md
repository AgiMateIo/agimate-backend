# device-api — LLM Provider Endpoints

API specification for the `/manage/llm-providers/**` and `/manage/agents/{agentPubId}/llms/**` endpoint groups, plus the agent-runtime endpoint `/agent/llm`.

LLM providers are per-user. Agents can bind to multiple providers/models under arbitrary labels (`main_model`, `for_light_task`, `visual_task`, …). API keys are encrypted at rest (AES-GCM via `app.integration.encryption-key`) and only returned, decrypted, to the authenticated agent itself via `GET /agent/llm`.

> All paths below are relative to the context path `/device`.

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

### `GET /device/manage/llm-providers/`

List the current user's providers.

### `POST /device/manage/llm-providers/`

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

### `GET /device/manage/llm-providers/{pubId}`

### `PATCH /device/manage/llm-providers/{pubId}`

Partial update. All fields optional. If `apiKey` is absent or blank, the existing encrypted key is left untouched. If present, it is re-encrypted and `apiKeyMask` is recomputed.

### `DELETE /device/manage/llm-providers/{pubId}`

Cascades to `agent_llms` (bindings disappear from `GET /agent/llm`).

### `POST /device/manage/llm-providers/{pubId}/refresh-models`

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
| `llmProviderPubId` | UUID | Bound provider |
| `llmProviderName` | string | Provider's display name |
| `providerType` | enum | Provider type |

### `GET /device/manage/agents/{agentPubId}/llms/`

### `POST /device/manage/agents/{agentPubId}/llms/`

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

### `PUT /device/manage/agents/{agentPubId}/llms/{name}`

Replaces an existing binding's `llmProviderPubId` and `model`. Body uses the same shape as `POST` minus `name`.

### `DELETE /device/manage/agents/{agentPubId}/llms/{name}`

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

### `GET /device/agent/llm`

Returns all bindings of the authenticated agent whose providers are `enabled=true`. Disabled providers are silently excluded.

### `GET /device/agent/llm/{name}`

Returns the single binding identified by its label. `404` if the binding does not exist or its provider is disabled.

---

## Notes

- Agents are responsible for calling the LLM themselves; the backend does not proxy LLM traffic.
- Encryption key (`app.integration.encryption-key`) is shared with `IntegrationCredentials` — rotating it requires re-encrypting every row in both tables.
- Deleting a provider cascades to all agent bindings via `ON DELETE CASCADE` on `agent_llms.llm_provider_pub_id`.
- Deleting an agent cascades to its bindings via `ON DELETE CASCADE` on `agent_llms.agent_pub_id`.
