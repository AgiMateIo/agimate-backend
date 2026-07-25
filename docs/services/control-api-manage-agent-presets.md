# control-api — Agent Preset Endpoints

API specification for the `/manage/agent-presets/**` endpoint group. Presets are the role templates
offered by the agent-creation wizard.

> All paths below are relative to the context path `/control`.

## Authentication & access model

| Group | Mechanism | Header |
|-------|-----------|--------|
| `/manage/agent-presets/**` | **JWT** | `Authorization: Bearer <jwt>` |

The gallery listing (`GET /`) is available to any authenticated `USER`. Every mutating endpoint and
the "list all" endpoint require role **ADMIN** (`users.role` in user-api, carried in the JWT). The
role is enforced with `@PreAuthorize("hasRole('ADMIN')")`; a non-admin gets `403`.

## Domain model

A **preset** is a pure **prefill** for the agent-creation wizard, not a live link:

- `instructions` are **copied** into `agents.instructions` on creation and edited freely afterwards.
- `skillNames` reference **system skills** by their `name` (stable machine code / slug, e.g. `board`, `time`);
  they are resolved to system-skill IDs when the gallery is listed, and the frontend passes the resulting
  `skillIds` in the create-agent request.
- `agents.preset_name` records which preset a wizard run started from (funnel analytics only, no FK) —
  the create-agent request field is `presetName`, referencing the preset's `name` (slug).

**Consequence:** editing a preset affects only **future** agents created from it — existing agents are
untouched (unlike system skills, which are referenced by ID and change behaviour live).

`name` is a stable kebab-case slug and the idempotency key for the classpath seeder; it is **immutable**
after creation (no rename endpoint). "Deleting" a preset is done by disabling it (`enabled: false`) —
there is no `DELETE`, because `name` is referenced by analytics.

## `AgentPresetResponse`

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Preset ID |
| `name` | string | Stable slug, e.g. `personal-assistant` (immutable) |
| `title` | string | Display title |
| `description` | string? | Gallery card description |
| `instructions` | string | Prefill for agent instructions |
| `skills` | `PresetSkill[]` | Resolved system skills (`{id, name, title, description}`); names that no longer resolve are silently dropped |
| `connectorCodes` | string[] | Union of the resolved skills' connector codes (display hint) |
| `skillNames` | string[] | Raw skill names as stored (for the admin editing form; unresolved) |
| `sortOrder` | int | Gallery sort order, ascending |
| `enabled` | boolean | Whether the preset is offered in the gallery |

### `PresetSkill`

| Field | Type | Description |
|---|---|---|
| `id` | UUID | Skill ID |
| `name` | string | Skill's stable machine code / slug |
| `title` | string | Skill's human-readable display name (falls back to `name` if unset) |
| `description` | string? | Skill description |

## `GET /control/manage/agent-presets/`

List **enabled** presets ordered by `sortOrder`, then `name`. Available to any authenticated user —
this is what the wizard gallery reads.

## `GET /control/manage/agent-presets/all/`

**ADMIN.** Same shape, but includes disabled presets — for the admin management table.

## `POST /control/manage/agent-presets/`

**ADMIN.** Create a preset. Body:

```json
{
  "name": "sales-assistant",
  "title": "Sales assistant",
  "description": "Helps qualify leads",
  "instructions": "You are a sales assistant...",
  "skillNames": ["time", "persist-memory"],
  "sortOrder": 10
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | Lowercase kebab-case slug (`[a-z0-9]+(-[a-z0-9]+)*`), immutable |
| `title` | string | yes | |
| `description` | string | no | |
| `instructions` | string | yes | |
| `skillNames` | string[] | no | Each must be an existing **system** skill `name` (slug) |
| `sortOrder` | int | no (default `0`) | |

Created with `enabled = true`.

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | `name` is not a valid slug, or `skillNames` contains a name that is not an existing system skill (`Unknown system skill(s): …`) |
| 403 | Caller is not ADMIN |
| 409 | A preset with this `name` already exists |

## `PATCH /control/manage/agent-presets/{id}`

**ADMIN.** Partial update — every field is optional, `null` means "leave unchanged". `name` cannot be
changed (it is not part of the request — it is the immutable slug / analytics key). Passing `skillNames`
**replaces** the whole list (and is re-validated against system skills).

```json
{ "enabled": false }
```

| Field | Type | Notes |
|---|---|---|
| `title` | string? | |
| `description` | string? | |
| `instructions` | string? | |
| `skillNames` | string[]? | Replaces the list; each must be an existing system skill `name` (slug) |
| `sortOrder` | int? | |
| `enabled` | boolean? | `false` retires the preset from the gallery |

**Errors:**

| Status | Condition |
|--------|-----------|
| 400 | `skillNames` contains a name that is not an existing system skill |
| 403 | Caller is not ADMIN |
| 404 | Preset not found |

## Notes

- The seeder (`SystemPresetBootstrap`) is **seed-only-if-missing** by `name`: once a preset exists,
  the classpath `PRESET.md` is no longer the source of truth, so admin edits are not clobbered by the
  next deploy.
- Presets are read from `resources/seed/<lang>/presets/<code>/PRESET.md`, where `<lang>` comes from
  `APP_CONTENT_LANGUAGE` (see [control-api.md](control-api.md#system-content-language)). Because the
  key `name` is language-independent and the seeder never overwrites, the database holds **one**
  language: switching the setting on a seeded environment does not retranslate the gallery.
- Skill references use the same semantics as system skills: a preset pointing at a system skill that
  was later deleted simply drops that skill from the resolved `skills` list (a warning is logged),
  while `skillNames` still shows the raw stored value.
