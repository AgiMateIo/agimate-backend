# control-api — Connector Job Management Endpoints

Detailed API specification for the `/manage/connector-jobs/**` endpoint group in control-api. Aimed at frontend developers integrating the connector job management UI.

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

A **connector job** is a scheduled background invocation of a connector tool. The scheduler uses a pull-based approach: rows are claimed with `FOR UPDATE SKIP LOCKED`, so each row is processed by exactly one node at a time.

### `kind` — who created the job and how it is managed

| `kind` | Origin | Deletable via API |
|--------|--------|-------------------|
| `SYSTEM` | Declared by the connector (`getJobs()`); the reconcile-sync owns the row — upsert/delete by business key `(connector_code, identity, name)`. `agentId` is always `null`. | No — pause it, or delete the integration |
| `AGENT` | Scheduled by an agent at runtime (e.g. `time.schedule`). `agentId` is the initiating agent (also the delivery target for `time.fire`). | Yes |
| `USER` | Created by the user via manage-API. Reserved — job creation is not yet implemented. `agentId` is the target agent if the job is addressed. | Yes |

### `status` vs `pausedAt` — two orthogonal dimensions

`status` is the scheduler's machine state:

| `status` | Meaning |
|----------|---------|
| `PENDING` | In the queue, waiting for `nextRunAt` |
| `RUNNING` | A node has claimed the row and is executing it. `lease_until` bounds the claim; expired leases are reclaimed for crash-recovery |
| `COMPLETED` | A `ONETIME` job successfully completed; the row is never picked up again. (An upsert on business key can reset it to `PENDING`.) |

`pausedAt` is the **user's pause flag** — orthogonal to `status`. While `pausedAt` is set, the scheduler skips the row. A currently `RUNNING` iteration finishes normally; the job is simply not picked up again afterward. Pause survives connector re-syncs.

### `type` — schedule type

| `type` | Meaning | `config` shape |
|--------|---------|----------------|
| `PERIODIC` | Repeats on a fixed interval | `{ "intervalSeconds": <long> }` |
| `CRON` | Next tick of a cron expression (Spring 6-field format, with seconds) | `{ "cron": "<expression>", "zone": "<IANA TZ>" }` |
| `ONETIME` | Fires once, then `COMPLETED` | (empty or connector-specific) |

`args` contains the arguments passed to the tool on each run. For `time.fire` this is `{ "prompt": "<text>" }`.

---

## Endpoint summary

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/control/manage/connector-jobs/` | List the user's jobs (paginated, filterable) |
| POST | `/control/manage/connector-jobs/{id}/pause` | Pause a job |
| POST | `/control/manage/connector-jobs/{id}/resume` | Resume a paused job |
| POST | `/control/manage/connector-jobs/{id}/run-now` | Run a `PENDING` job immediately (scheduler picks it up within ~1s) |
| DELETE | `/control/manage/connector-jobs/{id}` | Hard-delete a `USER` or `AGENT` job |

---

## Shared schemas

### `ConnectorJobResponse`

Returned in the list endpoint and also as the item schema for reference in per-job operations.

| Field | Type | Nullable | Description |
|-------|------|----------|-------------|
| `id` | `uuid` | no | Job identifier — use in all subsequent calls |
| `kind` | `string` (`SYSTEM` \| `USER` \| `AGENT`) | no | Who created the job and how it is managed |
| `connectorCode` | `string` | no | Connector that owns this job (e.g. `"time"`) |
| `identity` | `string` | yes | Connector instance identity (integration credentials ID, board pubId, etc.) |
| `agentId` | `uuid` | yes | Initiating or target agent ID; `null` for `SYSTEM` jobs |
| `name` | `string` | no | Tool name dispatched to the connector (e.g. `"time.fire"`) |
| `type` | `string` (`PERIODIC` \| `CRON` \| `ONETIME`) | no | Schedule type |
| `config` | `object` | yes | Schedule parameters — see `type` table above |
| `args` | `object` | yes | Arguments passed to the tool on each run |
| `status` | `string` (`PENDING` \| `RUNNING` \| `COMPLETED`) | no | Scheduler's machine state |
| `nextRunAt` | `datetime` (`yyyy-MM-dd'T'HH:mm:ss`) | yes | Next scheduled run time; `null` for `COMPLETED` rows |
| `pausedAt` | `datetime` (`yyyy-MM-dd'T'HH:mm:ss`) | yes | When the job was paused by the user; `null` if active |
| `lastError` | `string` | yes | Error message from the latest iteration; `null` on success |
| `createdAt` | `datetime` (`yyyy-MM-dd'T'HH:mm:ss`) | no | Job creation timestamp |

### Spring `Page<T>` envelope

The list endpoint returns a standard Spring `Page<T>`:

```json
{
  "response": {
    "content": [ /* ConnectorJobResponse[] */ ],
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

### GET `/control/manage/connector-jobs/`

List the **current user's** connector jobs (all kinds), sorted by `nextRunAt` ascending. `COMPLETED` rows have `nextRunAt = null` and sort last (database NULLs-last behaviour).

**Query parameters:**

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `connectorCode` | `string` | no | — | Filter by connector code (exact match). |
| `kind` | `string` | no | — | Filter by kind: `SYSTEM`, `USER`, or `AGENT`. |
| `page` | `int` | no | `0` | Zero-based page index. |
| `size` | `int` | no | `20` | Page size (max `100`). |

**Response `200`:** `Page<ConnectorJobResponse>`

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
        "name": "time.fire",
        "type": "PERIODIC",
        "config": { "intervalSeconds": 1800 },
        "args": { "prompt": "Summarise open PRs and post to the daily thread" },
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
        "name": "telegram.long_poll",
        "type": "PERIODIC",
        "config": { "intervalSeconds": 0 },
        "args": {},
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
GET /control/manage/connector-jobs/
GET /control/manage/connector-jobs/?connectorCode=time
GET /control/manage/connector-jobs/?kind=AGENT
GET /control/manage/connector-jobs/?connectorCode=telegram&kind=SYSTEM&page=0&size=10
```

---

### POST `/control/manage/connector-jobs/{id}/pause`

Sets `pausedAt` on the job. While `pausedAt` is set the scheduler skips this row. A currently `RUNNING` iteration finishes normally and then the job is not picked up again.

Idempotent: pausing a job that is already paused is a no-op (returns `200` without error).

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `id` | `uuid` | Job identifier |

**Request body:** none.

**Response `200`:** empty success envelope.

```json
{ "response": null }
```

**Errors:**

| Status | Body | Condition |
|--------|------|-----------|
| 400 | `{ "error": { "message": "Job is already completed" } }` | Job `status` is `COMPLETED` — completed jobs cannot be paused |
| 404 | `{ "error": { "message": "Job not found" } }` | Job does not exist or belongs to another user (ownership is not revealed) |

---

### POST `/control/manage/connector-jobs/{id}/resume`

Clears `pausedAt` and recomputes `nextRunAt` from "now" — the job does **not** catch up on runs missed while paused.

Recalculation rules:

| `type` | New `nextRunAt` |
|--------|-----------------|
| `PERIODIC` | `now + intervalSeconds` |
| `CRON` | Next tick of the cron expression from now |
| `ONETIME` | Unchanged (original time). If the original time is already past, the job fires on the next scheduler cycle. |

Idempotent: resuming a job that is not paused (`pausedAt == null`) is a no-op (returns `200` without error).

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `id` | `uuid` | Job identifier |

**Request body:** none.

**Response `200`:** empty success envelope.

```json
{ "response": null }
```

**Errors:**

| Status | Body | Condition |
|--------|------|-----------|
| 400 | `{ "error": { "message": "Job is already completed" } }` | Job `status` is `COMPLETED` — completed jobs cannot be resumed |
| 404 | `{ "error": { "message": "Job not found" } }` | Job does not exist or belongs to another user |

---

### POST `/control/manage/connector-jobs/{id}/run-now`

Triggers an immediate run of a `PENDING` job by moving `nextRunAt` to "now". The pull-based scheduler claims the row on its next tick (within ~1s) and runs it through the normal claim/lease/retry path — there is no separate execution path and no risk of double execution.

This is **fire-and-forget**: the call returns as soon as the row is nudged, not when the run finishes. Observe progress by re-reading the job — `status` moves `PENDING → RUNNING → PENDING` (or `COMPLETED` for a `ONETIME` job).

Works for any `kind` (including `SYSTEM`) — e.g. to trigger a declarative daily job on demand. The schedule cadence is preserved: after the manual run completes, `nextRunAt` is recomputed as usual (next cron tick / `now + intervalSeconds`).

Only a job that is `PENDING` and not paused can be run. A paused job must be resumed first; a `RUNNING` job is already executing; a `COMPLETED` `ONETIME` job is terminal.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `id` | `uuid` | Job identifier |

**Request body:** none.

**Response `200`:** empty success envelope.

```json
{ "response": null }
```

**Errors:**

| Status | Body | Condition |
|--------|------|-----------|
| 400 | `{ "error": { "message": "Job is already completed" } }` | Job `status` is `COMPLETED` |
| 400 | `{ "error": { "message": "Job is paused: resume it first" } }` | `pausedAt` is set — resume before running |
| 400 | `{ "error": { "message": "Job is already running" } }` | Job `status` is `RUNNING` (or the scheduler claimed it concurrently) |
| 404 | `{ "error": { "message": "Job not found" } }` | Job does not exist or belongs to another user |

---

### DELETE `/control/manage/connector-jobs/{id}`

Hard-deletes a `USER` or `AGENT` job. If the job is currently `RUNNING`, the in-flight iteration finishes normally; the job is not rescheduled afterward.

`SYSTEM` jobs cannot be deleted via this API — the reconcile-sync would recreate the row on the next connector event. Pause the job, or delete the integration instead.

**Path parameters:**

| Name | Type | Description |
|------|------|-------------|
| `id` | `uuid` | Job identifier |

**Request body:** none.

**Response `200`:** empty success envelope.

```json
{ "response": null }
```

**Errors:**

| Status | Body | Condition |
|--------|------|-----------|
| 400 | `{ "error": { "message": "Declarative connector job cannot be deleted: pause it or delete the integration" } }` | Job `kind` is `SYSTEM` |
| 404 | `{ "error": { "message": "Job not found" } }` | Job does not exist or belongs to another user |

---

## UI action matrix

This table shows which actions are available based on the job's current state. Read `pausedAt` and `status` from the `ConnectorJobResponse`.

| Action | Available when | Notes |
|--------|---------------|-------|
| **Pause** | `status != COMPLETED` AND `pausedAt == null` | Greys out for completed jobs and already-paused jobs |
| **Resume** | `pausedAt != null` AND `status != COMPLETED` | Only relevant when the job is paused; completed jobs are terminal |
| **Run now** | `status == PENDING` AND `pausedAt == null` | Greys out while `RUNNING`, `COMPLETED`, or paused. Fire-and-forget — refresh to see `status` change |
| **Delete** | `kind != SYSTEM` | Available for `AGENT` and `USER` jobs regardless of `status` or `pausedAt` |

Note: `status` and `pausedAt` are **orthogonal** — a job can be `RUNNING` and paused at the same time (current iteration finishes, then the job is not picked up again). Do not conflate them:

- `status` — the scheduler's current machine state (`PENDING` / `RUNNING` / `COMPLETED`).
- `pausedAt` — a user-controlled flag that tells the scheduler to skip this row until cleared.

---

## Related behaviour

- Deleting an **agent** deletes all jobs bound to it (`agentId`).
- Deleting an **integration** deletes all jobs of its identity, including dynamic ones.
- The agent tool `time.cancel_scheduled` removes only jobs the agent created itself (`kind = AGENT`); user-created jobs targeting the agent can be removed only through this API.
