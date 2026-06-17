# control-api — Connector Task Management Endpoints

Detailed API specification for the `/manage/connector-tasks/**` endpoint group in control-api. Aimed at frontend developers integrating the connector task management UI.

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

A **connector task** is a scheduled background invocation of a connector tool. The scheduler uses a pull-based approach: rows are claimed with `FOR UPDATE SKIP LOCKED`, so each row is processed by exactly one node at a time.

### `kind` — who created the task and how it is managed

| `kind` | Origin | Deletable via API |
|--------|--------|-------------------|
| `SYSTEM` | Declared by the connector (`getTasks()`); the reconcile-sync owns the row — upsert/delete by business key `(connector_code, identity, task_name)`. `agentId` is always `null`. | No — pause it, or delete the integration |
| `AGENT` | Scheduled by an agent at runtime (e.g. `time.schedule`). `agentId` is the initiating agent (also the delivery target for `time.fire`). | Yes |
| `USER` | Created by the user via manage-API. Reserved — task creation is not yet implemented. `agentId` is the target agent if the task is addressed. | Yes |

### `status` vs `pausedAt` — two orthogonal dimensions

`status` is the scheduler's machine state:

| `status` | Meaning |
|----------|---------|
| `PENDING` | In the queue, waiting for `nextRunAt` |
| `RUNNING` | A node has claimed the row and is executing it. `lease_until` bounds the claim; expired leases are reclaimed for crash-recovery |
| `COMPLETED` | A `ONETIME` task successfully completed; the row is never picked up again. (An upsert on business key can reset it to `PENDING`.) |

`pausedAt` is the **user's pause flag** — orthogonal to `status`. While `pausedAt` is set, the scheduler skips the row. A currently `RUNNING` iteration finishes normally; the task is simply not picked up again afterward. Pause survives connector re-syncs.

### `taskType` — schedule type

| `taskType` | Meaning | `taskConfig` shape |
|------------|---------|-------------------|
| `PERIODIC` | Repeats on a fixed interval | `{ "intervalSeconds": <long> }` |
| `CRON` | Next tick of a cron expression (Spring 6-field format, with seconds) | `{ "cron": "<expression>", "zone": "<IANA TZ>" }` |
| `ONETIME` | Fires once, then `COMPLETED` | (empty or connector-specific) |

`taskArgs` contains the arguments passed to the tool on each run. For `time.fire` this is `{ "prompt": "<text>" }`.

---

## Endpoint summary

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/control/manage/connector-tasks/` | List the user's tasks (paginated, filterable) |
| POST | `/control/manage/connector-tasks/{id}/pause` | Pause a task |
| POST | `/control/manage/connector-tasks/{id}/resume` | Resume a paused task |
| DELETE | `/control/manage/connector-tasks/{id}` | Hard-delete a `USER` or `AGENT` task |

---

## Shared schemas

### `ConnectorTaskResponse`

Returned in the list endpoint and also as the item schema for reference in per-task operations.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `uuid` | no | Task identifier — use in all subsequent calls |
| `kind` | `string` (`SYSTEM` \| `USER` \| `AGENT`) | no | Who created the task and how it is managed |
| `connectorCode` | `string` | no | Connector that owns this task (e.g. `"time"`) |
| `identity` | `string` | yes | Connector instance identity (integration credentials ID, board pubId, etc.) |
| `agentId` | `uuid` | yes | Initiating or target agent ID; `null` for `SYSTEM` tasks |
| `taskName` | `string` | no | Tool name dispatched to the connector (e.g. `"time.fire"`) |
| `taskType` | `string` (`PERIODIC` \| `CRON` \| `ONETIME`) | no | Schedule type |
| `taskConfig` | `object` | yes | Schedule parameters — see `taskType` table above |
| `taskArgs` | `object` | yes | Arguments passed to the tool on each run |
| `status` | `string` (`PENDING` \| `RUNNING` \| `COMPLETED`) | no | Scheduler's machine state |
| `nextRunAt` | `datetime` (`yyyy-MM-dd'T'HH:mm:ss`) | yes | Next scheduled run time; `null` for `COMPLETED` rows |
| `pausedAt` | `datetime` (`yyyy-MM-dd'T'HH:mm:ss`) | yes | When the task was paused by the user; `null` if active |
| `lastError` | `string` | yes | Error message from the latest iteration; `null` on success |
| `createdAt` | `datetime` (`yyyy-MM-dd'T'HH:mm:ss`) | no | Task creation timestamp |

### Spring `Page<T>` envelope

The list endpoint returns a standard Spring `Page<T>`:

```json
{
  "response": {
    "content": [ /* ConnectorTaskResponse[] */ ],
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

The frontend should rely on `totalElements`, `totalPages`, `number` (current page), `first`, and `last`. `MAX_PAGE_SIZE` on the backend is **100** — values above are silently clamped.

---

## Endpoints

### GET `/control/manage/connector-tasks/`

List the **current user's** connector tasks (all kinds), sorted by `nextRunAt` ascending. `COMPLETED` rows have `nextRunAt = null` and sort last (database NULLs-last behaviour).

**Query parameters:**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `connectorCode` | `string` | no | — | Filter by connector code (exact match). |
| `kind` | `string` | no | — | Filter by kind: `SYSTEM`, `USER`, or `AGENT`. |
| `page` | `int` | no | `0` | Zero-based page index. |
| `size` | `int` | no | `20` | Page size (max `100`). |

**Response `200`:** `Page<ConnectorTaskResponse>`

```json
{
  "response": {
    "content": [
      {
        "id": "019eb28d-0000-7c31-a4f0-aabbccddeeff",
        "kind": "AGENT",
        "connectorCode": "time",
        "identity": null,
        "agentId": "0193b8e3-ad77-7c31-a4f0-8e7c9d2f1a77",
        "taskName": "time.fire",
        "taskType": "PERIODIC",
        "taskConfig": { "intervalSeconds": 1800 },
        "taskArgs": { "prompt": "Summarise open PRs and post to the daily thread" },
        "status": "PENDING",
        "nextRunAt": "2026-06-11T16:00:00",
        "pausedAt": null,
        "lastError": null,
        "createdAt": "2026-06-11T12:00:00"
      },
      {
        "id": "019eb290-0000-7c31-a4f0-112233445566",
        "kind": "SYSTEM",
        "connectorCode": "telegram",
        "identity": "0193b8e3-1111-7c31-a4f0-556677889900",
        "agentId": null,
        "taskName": "telegram.long_poll",
        "taskType": "PERIODIC",
        "taskConfig": { "intervalSeconds": 0 },
        "taskArgs": {},
        "status": "PENDING",
        "nextRunAt": "2026-06-11T18:00:00",
        "pausedAt": "2026-06-11T10:30:00",
        "lastError": null,
        "createdAt": "2026-06-01T09:00:00"
      }
    ],
    "pageable": {
      "pageNumber": 0,
      "pageSize": 20,
      "sort": { "sorted": true, "unsorted": false, "empty": false },
      "offset": 0,
      "paged": true,
      "unpaged": false
    },
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
GET /control/manage/connector-tasks/
GET /control/manage/connector-tasks/?connectorCode=time
GET /control/manage/connector-tasks/?kind=AGENT
GET /control/manage/connector-tasks/?connectorCode=telegram&kind=SYSTEM&page=0&size=10
```

---

### POST `/control/manage/connector-tasks/{id}/pause`

Sets `pausedAt` on the task. While `pausedAt` is set the scheduler skips this row. A currently `RUNNING` iteration finishes normally and then the task is not picked up again.

Idempotent: pausing a task that is already paused is a no-op (returns `200` without error).

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `id` | `uuid` | Task identifier |

**Request body:** none.

**Response `200`:** empty success envelope.

```json
{ "response": null }
```

**Errors:**

| Status | Body | Condition |
|--------|------|-----------|
| 400 | `{ "error": { "message": "Task is already completed" } }` | Task `status` is `COMPLETED` — completed tasks cannot be paused |
| 404 | `{ "error": { "message": "Task not found" } }` | Task does not exist or belongs to another user (ownership is not revealed) |

---

### POST `/control/manage/connector-tasks/{id}/resume`

Clears `pausedAt` and recomputes `nextRunAt` from "now" — the task does **not** catch up on runs missed while paused.

Recalculation rules:

| `taskType` | New `nextRunAt` |
|------------|-----------------|
| `PERIODIC` | `now + intervalSeconds` |
| `CRON` | Next tick of the cron expression from now |
| `ONETIME` | Unchanged (original time). If the original time is already past, the task fires on the next scheduler cycle. |

Idempotent: resuming a task that is not paused (`pausedAt == null`) is a no-op (returns `200` without error).

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `id` | `uuid` | Task identifier |

**Request body:** none.

**Response `200`:** empty success envelope.

```json
{ "response": null }
```

**Errors:**

| Status | Body | Condition |
|--------|------|-----------|
| 400 | `{ "error": { "message": "Task is already completed" } }` | Task `status` is `COMPLETED` — completed tasks cannot be resumed |
| 404 | `{ "error": { "message": "Task not found" } }` | Task does not exist or belongs to another user |

---

### DELETE `/control/manage/connector-tasks/{id}`

Hard-deletes a `USER` or `AGENT` task. If the task is currently `RUNNING`, the in-flight iteration finishes normally; the task is not rescheduled afterward.

`SYSTEM` tasks cannot be deleted via this API — the reconcile-sync would recreate the row on the next connector event. Pause the task, or delete the integration instead.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `id` | `uuid` | Task identifier |

**Request body:** none.

**Response `200`:** empty success envelope.

```json
{ "response": null }
```

**Errors:**

| Status | Body | Condition |
|--------|------|-----------|
| 400 | `{ "error": { "message": "Declarative connector task cannot be deleted: pause it or delete the integration" } }` | Task `kind` is `SYSTEM` |
| 404 | `{ "error": { "message": "Task not found" } }` | Task does not exist or belongs to another user |

---

## UI action matrix

This table shows which actions are available based on the task's current state. Read `pausedAt` and `status` from the `ConnectorTaskResponse`.

| Action | Available when | Notes |
|--------|---------------|-------|
| **Pause** | `status != COMPLETED` AND `pausedAt == null` | Greys out for completed tasks and already-paused tasks |
| **Resume** | `pausedAt != null` AND `status != COMPLETED` | Only relevant when the task is paused; completed tasks are terminal |
| **Delete** | `kind != SYSTEM` | Available for `AGENT` and `USER` tasks regardless of `status` or `pausedAt` |

Note: `status` and `pausedAt` are **orthogonal** — a task can be `RUNNING` and paused at the same time (current iteration finishes, then the task is not picked up again). Do not conflate them:

- `status` — the scheduler's current machine state (`PENDING` / `RUNNING` / `COMPLETED`).
- `pausedAt` — a user-controlled flag that tells the scheduler to skip this row until cleared.

---

## Related behaviour

- Deleting an **agent** deletes all tasks bound to it (`agentId`).
- Deleting an **integration** deletes all tasks of its identity, including dynamic ones.
- The agent tool `time.cancel_scheduled` removes only tasks the agent created itself (`kind = AGENT`); user-created tasks targeting the agent can be removed only through this API.
