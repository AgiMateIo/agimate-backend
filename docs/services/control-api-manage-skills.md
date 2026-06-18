# control-api — Skill Management Endpoints

Detailed API specification for the `/manage/skills/**` endpoint group in control-api. Aimed at frontend developers integrating the Skill catalog into the UI.

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

A **skill** is a packaged capability that can be bound to one or more agents. Its content lives in a `SKILL.md` file with a YAML frontmatter (`name`, `description`) followed by markdown.

Skill flavours:

| Flag combination | Meaning |
|------------------|---------|
| `isPublic = false` | Private skill — visible only to its owner |
| `isPublic = true`, `isFeatured = false` | Public skill — discoverable by other users in `/public/` |
| `isPublic = true`, `isFeatured = true` | Featured skill — curated, surfaced in `/featured/` |
| `parentPubId != null` | Clone — created from another skill via `POST /{pubId}/clone` |

**Featured-clone rule:** if a skill is a clone of a *featured* skill (`parentPubId` references a featured skill), it is read-only — the user cannot edit or delete it (returns `403`).

**Versioning:** every successful `PUT /{pubId}` increments `version`. Agent-skill bindings remember `installedSkillVersion`, which lets the UI flag "needs reinstall" once a new version exists.

---

## Endpoint summary

| Method | Path                                            | Purpose                                                              |
|--------|-------------------------------------------------|----------------------------------------------------------------------|
| GET    | `/control/manage/skills/`                        | List my own skills (paginated, search, filter by connector)          |
| GET    | `/control/manage/skills/public/`                 | List public non-featured skills                                       |
| GET    | `/control/manage/skills/featured/`               | List featured skills                                                  |
| GET    | `/control/manage/skills/{pubId}`                 | Get skill details + `SKILL.md` content                                |
| GET    | `/control/manage/skills/{pubId}/agents/`         | List my agents that use this skill (paginated, search by name/prompt) |
| POST   | `/control/manage/skills/`                        | Create skill from JSON payload                                        |
| POST   | `/control/manage/skills/upload`                  | Create skill by uploading a `SKILL.md` file                           |
| PUT    | `/control/manage/skills/{pubId}`                 | Update skill (bumps `version`)                                        |
| DELETE | `/control/manage/skills/{pubId}`                 | Soft-delete skill                                                     |
| POST   | `/control/manage/skills/{pubId}/clone`           | Clone a public/featured skill into the user's collection              |

---

## Shared schemas

### `SkillResponse`

Returned in list endpoints and on create/update/clone.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `uuid` | no | Skill `pubId` — use this in all subsequent calls |
| `name` | `string` | no | Display name from `SKILL.md` frontmatter |
| `description` | `string` | yes | Description from `SKILL.md` frontmatter |
| `version` | `int` | no | Increments on every `PUT /{pubId}` |
| `isPublic` | `bool` | no | Visible to other users in `/public/` |
| `isFeatured` | `bool` | no | Curated; surfaced in `/featured/` (read-only for clones) |
| `userId` | `uuid` | no | Owner of the skill |
| `parentPubId` | `uuid` | yes | Source skill if this is a clone |
| `createdAt` | `datetime` (`yyyy-MM-dd'T'HH:mm:ss`) | no | Creation timestamp |
| `updatedAt` | `datetime` (`yyyy-MM-dd'T'HH:mm:ss`) | no | Last update timestamp |
| `myCopyId` | `uuid` | yes | In `/public/` and `/featured/` lists: `pubId` of the current user's clone of this skill, if any. `null` everywhere else. Use this to render "Open my copy" vs "Clone" buttons. |

### `SkillDetailResponse`

Same as `SkillResponse` but **without** `myCopyId` and **with** the raw markdown body:

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `skillMd` | `string` | no | Full `SKILL.md` content with frontmatter |

(All other fields identical to `SkillResponse`.)

### `AgentSummaryResponse`

Lightweight agent representation used by `GET /{pubId}/agents/`.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `uuid` | no | Agent `pubId` |
| `name` | `string` | no | Agent display name |
| `instructions` | `string` | yes | Agent system instructions |
| `enabled` | `bool` | no | Whether the agent is currently enabled |

### Spring `Page<T>` envelope

All paginated endpoints return a standard Spring `Page<T>`:

```json
{
  "response": {
    "content": [ /* T[] */ ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "sorted": true, "unsorted": false, "empty": false },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
    "totalElements": 42,
    "totalPages": 3,
    "last": false,
    "first": true,
    "numberOfElements": 20,
    "size": 20,
    "number": 0,
    "sort": { "sorted": true, "unsorted": false, "empty": false },
    "empty": false
  }
}
```

The frontend should rely on `totalElements`, `totalPages`, `number` (current page), `first` and `last`. `MAX_PAGE_SIZE` on the backend is **100** — values above are silently clamped.

---

## Endpoints

### GET `/control/manage/skills/`

List the **current user's own** skills.

**Query parameters:**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `search` | `string` | no | — | Case-insensitive substring match against `name` or `description`. |
| `connectorCode` | `string` | no | — | Show only skills bound to the given connector code. |
| `page` | `int` | no | `0` | Zero-based page index. |
| `size` | `int` | no | `20` | Page size (max `100`). |

Sorted by `createdAt` descending.

**Response `200`:** `Page<SkillResponse>` (`myCopyId` is always `null` in this list).

---

### GET `/control/manage/skills/public/`

List **public, non-featured** skills authored by other users.

Query parameters: same as above (`search`, `connectorCode`, `page`, `size`).

Sorted by `createdAt` descending.

**Response `200`:** `Page<SkillResponse>`. For each public skill, `myCopyId` is set to the `pubId` of the user's existing clone (if any), so the UI can offer "Open my copy" instead of "Clone".

---

### GET `/control/manage/skills/featured/`

List **featured** skills (`isPublic = true`, `isFeatured = true`).

Query parameters: same as `/`.

Sorted by `createdAt` descending.

**Response `200`:** `Page<SkillResponse>`, with `myCopyId` populated where applicable. Featured clones cannot be edited or deleted later.

---

### GET `/control/manage/skills/{pubId}`

Returns full skill details including the raw `SKILL.md` body.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `pubId` | `uuid` | Skill identifier |

Access rule: the skill must be either owned by the caller **or** marked `isPublic`. Otherwise `403`.

**Response `200`:**
```json
{
  "response": {
    "id": "0193b8e3-ad77-7c31-a4f0-8e7c9d2f1a77",
    "name": "Daily Standup",
    "description": "Generates daily standup summaries",
    "version": 3,
    "isPublic": false,
    "isFeatured": false,
    "userId": "0193b8e3-9999-7c31-a4f0-111122223333",
    "parentPubId": null,
    "skillMd": "---\nname: Daily Standup\ndescription: Generates daily standup summaries\n---\n\n# Instructions\n...",
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

### GET `/control/manage/skills/{pubId}/agents/`

Returns the **current user's agents** that have this skill bound. Useful for the "Used by" panel on the skill detail page.

Access rule: the skill must exist and be either owned by the caller or public (same as `getSkillDetail`). The returned agents are always scoped to `userId = caller`, so even on a public skill page the user only sees *their own* agents.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `pubId` | `uuid` | Skill identifier |

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
      },
      {
        "id": "0193b900-1111-7c31-a4f0-aaaa00000002",
        "name": "QA Reporter",
        "instructions": "Summarize failing tests...",
        "enabled": false
      }
    ],
    "pageable": { "pageNumber": 0, "pageSize": 20, "offset": 0, "paged": true, "unpaged": false, "sort": { "sorted": true, "unsorted": false, "empty": false } },
    "totalElements": 2,
    "totalPages": 1,
    "last": true,
    "first": true,
    "numberOfElements": 2,
    "size": 20,
    "number": 0,
    "sort": { "sorted": true, "unsorted": false, "empty": false },
    "empty": false
  }
}
```

**Examples:**

```
GET /control/manage/skills/0193b8e3-ad77-7c31-a4f0-8e7c9d2f1a77/agents/
GET /control/manage/skills/0193b8e3-ad77-7c31-a4f0-8e7c9d2f1a77/agents/?search=standup
GET /control/manage/skills/0193b8e3-ad77-7c31-a4f0-8e7c9d2f1a77/agents/?page=1&size=10
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
  "skillMd": "---\nname: My Skill\ndescription: Does the thing\n---\n\n# Steps\n1. ...",
  "isPublic": false
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `skillMd` | `string` | yes (`@NotBlank`) | Full `SKILL.md` content. Must start with `---`-delimited YAML frontmatter containing at least `name`. `description` is optional. |
| `isPublic` | `bool` | no (default `false`) | Whether the skill is published publicly |

The backend parses the frontmatter (`name`, `description`) and uses it as the canonical name/description — there are no separate fields in the request.

**Response `200`:** `SkillResponse` of the created skill.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | `skillMd` is empty, missing frontmatter, missing `name` field, or YAML is invalid |
| 409 | A skill with this `name` already exists for the user (also returned by DB-level uniqueness violation) |

---

### POST `/control/manage/skills/upload`

Same as `POST /` but accepts a `multipart/form-data` upload of the `SKILL.md` file. Convenient for "Upload SKILL.md" buttons in the UI.

**Form fields:**

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `file` | `file` | yes | A `SKILL.md` file (UTF-8 text) |
| `isPublic` | `bool` | no (default `false`) | Whether the skill is published publicly |

**Content-Type:** `multipart/form-data`.

**Response `200`:** `SkillResponse`.

**Errors:** same as `POST /`, plus:

| Status | Condition |
|--------|-----------|
| 400 | `Failed to read uploaded file` (I/O error reading the multipart part) |

---

### PUT `/control/manage/skills/{pubId}`

Update an existing skill. Bumps `version` by 1, persists the new `SKILL.md`, and re-parses `name` / `description` from the frontmatter.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `pubId` | `uuid` | Skill identifier |

**Request body:**

```json
{
  "skillMd": "---\nname: My Skill\ndescription: Updated description\n---\n\n# Steps\n...",
  "isPublic": true
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `skillMd` | `string` | yes (`@NotBlank`) | Full `SKILL.md` content with frontmatter |
| `isPublic` | `bool` | no | Whether the skill should be public after update (defaults to `false` if omitted) |

**Response `200`:** `SkillResponse` with the new `version`.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | Invalid `SKILL.md` (see `POST /`) |
| 403 | Caller is not the owner, or the skill is a featured-skill clone (read-only) |
| 404 | Skill not found or soft-deleted |
| 409 | Renaming would collide with an existing skill name in the user's collection |

---

### DELETE `/control/manage/skills/{pubId}`

Soft-deletes the skill and removes its files from storage.

**Response `200`:** empty success envelope:
```json
{ "response": null }
```

**Errors:**

| Status | Condition |
|--------|-----------|
| 403 | Caller is not the owner, or the skill is a featured-skill clone (read-only) |
| 404 | Skill not found or already deleted |

---

### POST `/control/manage/skills/{pubId}/clone`

Clones a **public** or **featured** skill into the caller's own collection. Cannot be used on your own skill.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `pubId` | `uuid` | Source skill identifier |

**Request body:** none.

**Response `200`:** `SkillResponse` for the new clone, with `parentPubId` set to the source skill's `pubId`.

Notes:
- Featured-skill clones do **not** copy `SKILL.md` content (the file is read on-demand from the source). They are also read-only — `PUT` and `DELETE` will return `403`.
- Non-featured public clones get a full copy of the `SKILL.md` files and are fully editable.

**Errors:**

| Status | Condition | Body |
|--------|-----------|------|
| 400 | Cloning your own skill | `{ "error": { "message": "Cannot clone your own skill" } }` |
| 403 | Source skill is private and not owned by the caller | standard 403 |
| 404 | Source skill not found | standard 404 |
| 409 | A skill with the same name already exists in the user's collection — the response body includes `existingSkillId` so the UI can offer "Open existing" | `{ "error": { "message": "Skill with name '...' already exists in your collection", "existingSkillId": "<uuid>" } }` |

---

## Frontend recipes

### Skill detail page — "Used by N agents" panel

```
GET /control/manage/skills/{skillPubId}/agents/?page=0&size=10
```

Render `totalElements` next to the section header. If `totalElements === 0`, show an empty state with a CTA to "Bind to an agent" (which calls `POST /control/manage/agents/{agentPubId}/skills/`). Use the `search` query param for the in-panel search input — debounce input and keep `page=0` while typing.

### "Public skills" tab — show "Open my copy" vs "Clone"

```
GET /control/manage/skills/public/?search=...&page=...&size=...
```

For each item in `content[]`:
- if `myCopyId != null` → render "Open my copy" linking to `/skills/{myCopyId}`
- else → render "Clone" calling `POST /control/manage/skills/{id}/clone`

### Detecting "needs reinstall" on a bound skill

The needs-reinstall flag is exposed in the *agent skills* list (`GET /control/manage/agents/{agentPubId}/skills/`), not here. Compare `installedSkillVersion` against the skill's current `version` (also returned in `SkillResponse`) if you need to compute it client-side.
