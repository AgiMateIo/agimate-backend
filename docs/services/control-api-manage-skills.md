# control-api — Skill Management Endpoints

Detailed API specification for the `/manage/skills/**` and `/manage/agents/{agentId}/skills/**` endpoint groups in control-api. Aimed at frontend developers integrating the Skill catalog into the UI.

> All paths below are relative to the context path `/control`.

## Authentication

| Group | Mechanism | Header |
|-------|-----------|--------|
| `/manage/**` | **JWT** | `Authorization: Bearer <jwt>` |

**Common error responses (apply to every endpoint):**

| Status | Body | Meaning |
|--------|------|---------|
| 401 | `{ "error": { "message": "Authentication credentials not found or invalid" } }` | Missing or invalid JWT |
| 403 | `{ "error": { "message": "Access denied. Insufficient permissions." } }` | Authenticated but not authorized for the resource |

The unified envelopes:

- Success: `{ "response": <T> }`
- Error: `{ "error": { "message": "<text>" } }`

---

## Domain model

A **skill** is a packaged capability that can be bound to one or more agents. Its content lives in a single `skills` table row: `md_content` holds the SKILL.md body (markdown only, without frontmatter), and `connector_codes` is a Postgres `text[]` of the connector codes the skill requires (parsed from the SKILL.md frontmatter at create/update time).

**SKILL.md format** — the `skillMd` field in create/update requests must be a complete SKILL.md with YAML frontmatter:

```markdown
---
name: My Skill
description: Does the thing
connectors:
  - time
  - board
---

# Instructions

...
```

Supported frontmatter fields:

| Field | Required | Description |
|-------|----------|-------------|
| `name` | yes | Display name (must be unique per user) |
| `description` | no | Short description |
| `connectors` | no | List of connector codes the skill requires (e.g. `time`, `board`, `mcp`) |

The backend parses the frontmatter, stores `name`, `description`, `connector_codes`, and the body (everything after the closing `---`) separately. The request DTOs only carry `skillMd` (the full file) and `isPublic` — there are no separate fields for name or connectors.

**Visibility:**

| `isPublic` | Meaning |
|------------|---------|
| `false` | Private — visible only to the owner |
| `true` | Public — discoverable by any user via `/public/` |

System skills (time, board, persist-memory) are owned by the synthetic system user, seeded as `isPublic = true`, and can be bound directly by any user without copying. They are marked `system: true` in `SkillResponse`.

**System skills are referenced by ID, not copied** — bound agents resolve the skill body on read, so editing a system skill's `md_content` immediately changes behaviour for **every** agent that has it bound. This is why ADMIN edits bump `version` (surfacing "needs reinstall" downstream) and why rename/hard-delete are restricted (see [System skills — ADMIN](#system-skills--admin)).

**No cloning** — there is no clone endpoint. A user can bind any own or public skill to an agent directly.

**Versioning:** every successful `PUT /{id}` increments `version`. Agent-skill bindings record the installed version, which enables the "needs reinstall" flag when the skill is later updated.

---

## Endpoint summary

### Skill CRUD — `/control/manage/skills`

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/control/manage/skills/` | List my own skills (paginated, search, filter by connector) |
| GET | `/control/manage/skills/public/` | List ALL public skills |
| GET | `/control/manage/skills/{id}` | Get skill details + SKILL.md body |
| GET | `/control/manage/skills/{id}/agents/` | List my agents that use this skill (paginated, search) |
| POST | `/control/manage/skills/` | Create skill from JSON payload |
| POST | `/control/manage/skills/system` | **ADMIN** — create a system (platform) skill |
| POST | `/control/manage/skills/upload` | Create skill by uploading a SKILL.md file |
| PUT | `/control/manage/skills/{id}` | Update skill (bumps `version`); ADMIN may edit system skills |
| DELETE | `/control/manage/skills/{id}` | Soft-delete skill (system skills restricted) |

### Agent-skill bindings — `/control/manage/agents/{agentId}/skills`

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/control/manage/agents/{agentId}/skills/` | List skills bound to an agent |
| POST | `/control/manage/agents/{agentId}/skills/` | Bind an own or public skill to an agent |
| DELETE | `/control/manage/agents/{agentId}/skills/{skillId}` | Unbind a skill from an agent |
| GET | `/control/manage/agents/{agentId}/skills/{skillId}/policy-diff` | Preview policy changes |
| POST | `/control/manage/agents/{agentId}/skills/sync-policies` | Re-sync all skill-sourced policies |

---

## Shared schemas

### `SkillResponse`

Returned by list endpoints and on create/update.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `uuid` | no | Skill ID — use in all subsequent calls |
| `name` | `string` | no | Display name from SKILL.md frontmatter |
| `description` | `string` | yes | Description from SKILL.md frontmatter |
| `connectorCodes` | `string[]` | no | Connector codes required by the skill (empty array if none) |
| `version` | `int` | no | Increments on every `PUT /{id}` |
| `isPublic` | `bool` | no | Whether the skill is visible to all users |
| `userId` | `uuid` | no | Owner of the skill |
| `system` | `bool` | no | `true` for a platform-owned system skill (owner = synthetic system user). Editable only by ADMIN; rename and hard-delete are restricted. |
| `createdAt` | `datetime` (`yyyy-MM-dd'T'HH:mm:ss`) | no | Creation timestamp |
| `updatedAt` | `datetime` (`yyyy-MM-dd'T'HH:mm:ss`) | no | Last update timestamp |

### `SkillDetailResponse`

Same as `SkillResponse` plus the SKILL.md body:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `mdContent` | `string` | no | SKILL.md body **without** frontmatter |

(All other fields identical to `SkillResponse`.)

### `AgentSkillResponse`

Returned by the `/manage/agents/{agentId}/skills/` list and bind endpoints.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `uuid` | no | Binding ID |
| `agentId` | `uuid` | no | Agent ID |
| `skillId` | `uuid` | no | Skill ID |
| `skillName` | `string` | yes | Skill name (`null` if skill was soft-deleted) |
| `connectors` | `SkillConnectorStatus[]` | no | One entry per connector code required by the skill |
| `needsReinstall` | `bool` | no | `true` if skill version advanced since binding was created |
| `createdAt` | `datetime` | no | When the binding was created |
| `updatedAt` | `datetime` | no | When the binding was last updated |

### `SkillConnectorStatus`

Nested in `AgentSkillResponse.connectors`.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `connectorCode` | `string` | no | Connector code required by the skill |
| `connectionId` | `uuid` | yes | Active connection of this connector type bound to the agent. `null` means the agent has no active connection for this connector — the frontend should prompt the user to connect it. |

### `AgentSummaryResponse`

Lightweight agent representation used by `GET /{id}/agents/`.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `uuid` | no | Agent ID |
| `name` | `string` | no | Agent display name |
| `instructions` | `string` | yes | Agent system instructions |
| `enabled` | `bool` | no | Whether the agent is currently enabled |

### Spring `Page<T>` envelope

All paginated endpoints return a standard Spring `Page<T>`:

```json
{
  "response": {
    "content": [ ],
    "totalElements": 42,
    "totalPages": 3,
    "size": 20,
    "number": 0
  }
}
```

The frontend relies on `totalElements`, `totalPages`, and `number` (current page); "is first/last page" is derived client-side (`number === 0`, `number >= totalPages - 1`). `MAX_PAGE_SIZE` on the backend is **100** — values above are silently clamped.

---

## Endpoints — Skill CRUD

### GET `/control/manage/skills/`

List the **current user's own** skills.

**Query parameters:**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `search` | `string` | no | — | Case-insensitive substring match against `name` or `description`. |
| `connectorCode` | `string` | no | — | Show only skills that require the given connector code. |
| `page` | `int` | no | `0` | Zero-based page index. |
| `size` | `int` | no | `20` | Page size (max `100`). |

Sorted by `createdAt` descending.

**Response `200`:** `Page<SkillResponse>`.

---

### GET `/control/manage/skills/public/`

List **all public** skills (any user's, `isPublic = true`).

Query parameters: same as above (`search`, `connectorCode`, `page`, `size`).

Sorted by `createdAt` descending.

**Response `200`:** `Page<SkillResponse>`.

---

### GET `/control/manage/skills/{id}`

Returns full skill details including the SKILL.md body.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `id` | `uuid` | Skill identifier |

Access rule: the skill must be either owned by the caller **or** marked `isPublic`. Otherwise `403`.

**Response `200`:**
```json
{
  "response": {
    "id": "0193b8e3-ad77-7c31-a4f0-8e7c9d2f1a77",
    "name": "Daily Standup",
    "description": "Generates daily standup summaries",
    "connectorCodes": ["board"],
    "version": 3,
    "isPublic": false,
    "userId": "0193b8e3-9999-7c31-a4f0-111122223333",
    "mdContent": "# Instructions\n\nYou are a standup facilitator...",
    "createdAt": "2026-04-01T12:00:00",
    "updatedAt": "2026-04-05T09:23:11"
  }
}
```

**Errors:**

| Status | Condition |
|--------|-----------|
| 403 | Skill belongs to another user and is not public |
| 404 | Skill not found or soft-deleted |

---

### GET `/control/manage/skills/{id}/agents/`

Returns the **current user's agents** that have this skill bound. Useful for the "Used by" panel on the skill detail page.

Access rule: the skill must exist and be either owned by the caller or public. The returned agents are always scoped to `userId = caller`, so even on a public skill page the user only sees their own agents.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `id` | `uuid` | Skill identifier |

**Query parameters:**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `search` | `string` | no | — | Case-insensitive substring match against agent `name` or `instructions`. Blank values are ignored. |
| `page` | `int` | no | `0` | Zero-based page index. |
| `size` | `int` | no | `20` | Page size (max `100`). |

Sorted by agent `name` ascending.

**Response `200`:**
```json
{
  "response": {
    "content": [
      {
        "id": "0193b900-1111-7c31-a4f0-aaaa00000001",
        "name": "Standup Bot",
        "instructions": "You are a standup facilitator...",
        "enabled": true
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "size": 20,
    "number": 0
  }
}
```

**Errors:**

| Status | Condition |
|--------|-----------|
| 403 | Skill belongs to another user and is not public |
| 404 | Skill not found or soft-deleted |

---

### POST `/control/manage/skills/`

Create a skill from a JSON payload.

**Request body:**

```json
{
  "skillMd": "---\nname: My Skill\ndescription: Does the thing\nconnectors:\n  - time\n  - board\n---\n\n# Steps\n1. ...",
  "isPublic": false
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `skillMd` | `string` | yes (`@NotBlank`) | Full SKILL.md content including frontmatter. Must start with `---`-delimited YAML containing at least `name`. `description` and `connectors` are optional. |
| `isPublic` | `bool` | no (default `false`) | Whether the skill is published publicly. |

The backend parses the frontmatter (`name`, `description`, `connectors`) and stores them as separate columns. The `mdContent` stored in the DB is the body only (after the closing `---`).

**Response `200`:** `SkillResponse` of the created skill.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | `skillMd` is empty, missing frontmatter, missing `name` field, or YAML is invalid |
| 400 | `Unknown connector code(s): …` — frontmatter `connectors` lists a code not present in the connector registry |
| 409 | A skill with this `name` already exists for the user |

---

### POST `/control/manage/skills/system`

**ADMIN only.** Create a **system (platform) skill**: owner is forced to the synthetic system user and `isPublic` is forced to `true` (so any user can bind it without copying). Body is the same `CreateSkillRequest` as `POST /` — the `isPublic` field is ignored.

**Response `200`:** `SkillResponse` with `system: true`, `isPublic: true`.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | Invalid SKILL.md or unknown connector code(s) (see `POST /`) |
| 403 | Caller is not ADMIN |
| 409 | A system skill with this `name` already exists |

---

### POST `/control/manage/skills/upload`

Same as `POST /` but accepts a `multipart/form-data` upload of a SKILL.md file. Convenient for "Upload SKILL.md" buttons in the UI.

**Form fields:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `file` | `file` | yes | A SKILL.md file (UTF-8 text) |
| `isPublic` | `bool` | no (default `false`) | Whether the skill is published publicly. |

**Content-Type:** `multipart/form-data`.

**Response `200`:** `SkillResponse`.

**Errors:** same as `POST /`, plus:

| Status | Condition |
|--------|-----------|
| 400 | `Failed to read uploaded file` (I/O error reading the multipart part) |

---

### PUT `/control/manage/skills/{id}`

Update an existing skill. Bumps `version` by 1, re-parses `name`, `description`, and `connectors` from the new frontmatter, and replaces the stored `mdContent`.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `id` | `uuid` | Skill identifier |

**Request body:**

```json
{
  "skillMd": "---\nname: My Skill\ndescription: Updated description\nconnectors:\n  - board\n---\n\n# Steps\n...",
  "isPublic": true
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `skillMd` | `string` | yes (`@NotBlank`) | Full SKILL.md content with frontmatter |
| `isPublic` | `bool` | no | Whether the skill should be public after update (defaults to `false` if omitted) |

**Response `200`:** `SkillResponse` with the new `version`.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | Invalid SKILL.md or unknown connector code(s) (see `POST /`) |
| 400 | Attempt to rename a system skill (its name is the reference key for the seeder and preset `skill_names`) |
| 403 | Caller is not the owner (and not an ADMIN editing a system skill) |
| 404 | Skill not found or soft-deleted |
| 409 | Renaming would collide with an existing skill name in the owner's collection |

> **ADMIN + system skills:** an ADMIN may `PUT` a system skill (edits its body/connectors/`isPublic`, bumps `version`) but **cannot rename it** (`400`). To retire a system skill without deleting, an ADMIN sets `isPublic: false` — it stops being offered to new agents while existing bindings keep working.

---

### DELETE `/control/manage/skills/{id}`

Soft-deletes the skill and removes all its agent bindings (`agent_skills`), including bindings made
by other users while the skill was public. No policy recompute is needed: skill policies are
add-only, unbinding never revokes connector bindings.

**Response `200`:** empty success envelope:
```json
{ "response": null }
```

**Errors:**

| Status | Condition |
|--------|-----------|
| 403 | Caller is not the owner (and not an ADMIN acting on a system skill) |
| 404 | Skill not found or already deleted |
| 409 | System skill is still bound to agents or referenced by an agent preset — retire it via `isPublic: false` instead |

> A system skill can only be hard-deleted by an ADMIN once nothing references it (no `agent_skills` bindings and no preset `skill_names` entry). Otherwise use `PUT … { "isPublic": false }` to retire it.

---

## Endpoints — Agent-skill bindings

### GET `/control/manage/agents/{agentId}/skills/`

List skills currently bound to the agent. Each entry includes the connector requirements and whether each connector has an active connection on this agent.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `agentId` | `uuid` | Agent identifier |

**Query parameters:**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `page` | `int` | no | `0` | Zero-based page index. |
| `size` | `int` | no | `20` | Page size (max `100`). |

**Response `200`:**
```json
{
  "response": {
    "content": [
      {
        "id": "0194a111-0001-7c31-a4f0-000000000001",
        "agentId": "0193b900-1111-7c31-a4f0-aaaa00000001",
        "skillId": "0193b8e3-ad77-7c31-a4f0-8e7c9d2f1a77",
        "skillName": "Daily Standup",
        "connectors": [
          { "connectorCode": "board", "connectionId": "0194a000-bbbb-7c31-a4f0-cccc00000001" },
          { "connectorCode": "time", "connectionId": null }
        ],
        "needsReinstall": false,
        "createdAt": "2026-05-10T10:00:00",
        "updatedAt": "2026-05-10T10:00:00"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "size": 20,
    "number": 0
  }
}
```

`connectionId = null` for a connector means the agent has no active connection of that type — the frontend should render a "Connect" prompt for it.

**Errors:**

| Status | Condition |
|--------|-----------|
| 403 | Agent does not belong to the caller |
| 404 | Agent not found |

---

### POST `/control/manage/agents/{agentId}/skills/`

Bind an own or public skill to the agent.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `agentId` | `uuid` | Agent identifier |

**Request body:**

```json
{
  "skillId": "0193b8e3-ad77-7c31-a4f0-8e7c9d2f1a77"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `skillId` | `uuid` | yes | ID of the skill to bind. Must be owned by the caller or public. |

**Response `200`:** `AgentSkillResponse` for the new binding.

**Errors:**

| Status | Condition |
|--------|-----------|
| 403 | Skill is private and not owned by the caller, or agent does not belong to the caller |
| 404 | Agent or skill not found |
| 409 | Skill is already bound to this agent |

---

### DELETE `/control/manage/agents/{agentId}/skills/{skillId}`

Unbind a skill from the agent. Connector bindings created when the skill was added are **not** revoked — the agent retains its existing connections.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `agentId` | `uuid` | Agent identifier |
| `skillId` | `uuid` | Skill identifier (the skill's ID, not the binding ID) |

**Response `200`:**
```json
{ "response": null }
```

**Errors:**

| Status | Condition |
|--------|-----------|
| 403 | Agent does not belong to the caller |
| 404 | Binding not found |

---

### GET `/control/manage/agents/{agentId}/skills/{skillId}/policy-diff`

Preview what ABAC policies would be added or removed for an `add`, `remove`, or `sync` action — without applying any changes.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `agentId` | `uuid` | Agent identifier |
| `skillId` | `uuid` | Skill identifier |

**Query parameters:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `action` | `string` | yes | One of `add`, `remove`, `sync` |

**Response `200`:** `PolicyDiffResponse` — shape varies by action; see ABAC docs for detail.

---

### POST `/control/manage/agents/{agentId}/skills/sync-policies`

Re-sync all skill-sourced ABAC policies for the agent. Idempotent — safe to call after binding/unbinding multiple skills in a row.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `agentId` | `uuid` | Agent identifier |

**Request body:** none.

**Response `200`:**
```json
{ "response": null }
```

---

## Frontend recipes

### Skill detail page — "Used by N agents" panel

```
GET /control/manage/skills/{skillId}/agents/?page=0&size=10
```

Render `totalElements` next to the section header. If `totalElements === 0`, show an empty state with a CTA to "Bind to an agent" (which calls `POST /control/manage/agents/{agentId}/skills/`). Use the `search` query param for the in-panel search input — debounce input and keep `page=0` while typing.

### Binding a public skill to an agent

```
POST /control/manage/agents/{agentId}/skills/
{ "skillId": "<public or own skill id>" }
```

No cloning step is needed. Any public skill (including system skills like time/board/persist-memory) can be bound directly.

### Detecting missing connector connections after bind

After `POST /control/manage/agents/{agentId}/skills/` (or on the `GET /` list), inspect each entry in `connectors`. For any `{ connectorCode: "X", connectionId: null }`, prompt the user to set up a connection for connector `X`. The connector catalog endpoint (`GET /control/manage/connectors/`) provides the human-readable name and configuration details.

### Detecting "needs reinstall" on a bound skill

`needsReinstall = true` means the skill was updated since the agent last had its policies synced. Call `POST /control/manage/agents/{agentId}/skills/sync-policies` to bring policies up to date.
