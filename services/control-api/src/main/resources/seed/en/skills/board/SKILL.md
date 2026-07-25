---
name: board
title: Team Kanban board
description: Working with the agent team's Kanban board — creating tasks (EPIC/TASK/SUBTASK), moving them through statuses, keeping a comment log and reacting to board triggers.
connectors: [board]
---

# Skill: AgiMate Kanban Board

The Kanban board is the shared task space of your agent team: tasks are created here, picked up, moved through statuses and logged in comments. Board events arrive as triggers; actions go through the `board.*` tools (you already have their schemas).

## Tools: what to call when

- `get_tasks` — a compact view of the board (id/type/title/assignee, **no descriptions**), filtered by `status` and `assigneeAgentId` (`"me"` for your own). For choosing work and checking for duplicates.
- `get_task` — the task card: description, nearest `epic`, `parentTask`, `subtasks`, latest comments. **The main way to get context for a known ID.**
- `create_task` — a new task; `description` must contain the acceptance criteria.
- `edit_task` — a partial update (title/description/assignee/status); anything you don't pass stays as it was. `assigneeAgentId: "me"` takes the task for yourself.
- `get_comments` — the full log (the card only gives you the tail).
- `create_comment` — an entry in the task's log.

**Spend calls sparingly.** The trigger already carries a snapshot of the task (title, type, status, assignee) — if that's enough to decide, call nothing. If you need details, one `get_task`, not a dump of the whole board.

## Hierarchy

```
EPIC (no parent) → TASK (parent is an EPIC, or none) → SUBTASK (parent is a TASK, required)
```

## Statuses and the work cycle

```
BACKLOG → IN_PROGRESS → REVIEW → DONE
```

- New tasks are created in `BACKLOG`.
- `IN_PROGRESS` — picking a task up: `edit_task(assigneeAgentId: "me", status: "IN_PROGRESS")` in a single call.
- `REVIEW` — the result is ready; **a comment with the result is mandatory before the transition**.
- Review is done by the **task's creator** (`createdByAgentId`): criteria from the description met → `DONE`; not met → a comment with the notes and back to `IN_PROGRESS`.

**The claim rule:** you can only assign an owner to a free task (or reassign away from yourself). If a task is taken by someone else the server refuses; don't try to grab it — pick another one or leave a comment.

## Patterns

Picking up a task:
```
get_tasks(status: "BACKLOG") → get_task(taskId) → edit_task(taskId, assigneeAgentId: "me", status: "IN_PROGRESS")
→ create_comment("Taking this on. Plan: …") → work → create_comment("Result: …") → edit_task(status: "REVIEW")
```

Decomposing: `get_task` (description plus current subtasks) → `create_task(type: "SUBTASK", parentTaskId: …)` × N → a comment about the decomposition.

Found a new problem while working: `create_task(type: "TASK")` in BACKLOG → a comment on the original task pointing at the new one. Check for duplicates via `get_tasks` before creating it.

## Comments are both the log and the channel for results

Write a comment when you pick a task up, when you have an interim result, when you're blocked, when you finish, and when you make a decision. Specifics beat generalities:

- **Good:** `Checked prices with 3 suppliers. Best: Supplier A — $16/unit (MOQ 100).`
- **Bad:** `Checked the prices, all good.`

**Pass files as an `agf_` reference in the comment text** — only real ids from tool results. An invented reference is rejected by the server; if there's no file, record a blocker rather than a "result".

## Assigning owners

The team roster and roles are in the `team` block of your context. When creating a task, assign an agent by competence; if you're not sure, leave it unassigned (the whole team gets it and whoever is free picks it up) or assign yourself.

## Board triggers

Events arrive as a JSON block with self-describing fields. There are two:

- **`task_created`** — a new task: the full payload including `description`, `createdByAgentId`, `assigneeAgentId?`, `parentTaskId?`/`parentTaskTitle?`. Assigned to you means it's your work; unassigned means you may take it under the claim rule.
- **`task_changed`** — a task changed: a snapshot (`taskId`, `type`, `title`, `status`, `assigneeAgentId?`) plus `actorAgentId` (who did it) and the `change` discriminator:
  - `"status"` — a move across the board, `previousStatus` → `status`;
  - `"comment"` — a new comment: `commentId`, `comment`;
  - `"edited"` — a field update: `changedFields` (`title`/`description`/`assignee`), `previousAssigneeAgentId?`. If the status changed along with the edit, you get `change: "status"` with the full `changedFields`.

**React** when: you're the owner; a comment holds a question or request for you; you're the creator and the task moved to `REVIEW` (do the review); the event needs your next step.

**Ignore** it when the event isn't about your task or your area, or when no action is needed — that's a normal outcome, not every trigger deserves a reaction.

**Reply strictly through board comments.** A run handling a board trigger has no channel to the user — its final text is delivered to no one. Everything meant to be read (a result, a question, a blocker, a review verdict) goes through `create_comment` on the relevant task; the final text is only a short internal summary of what you did.

## Important

- Don't create duplicates — check the board before `create_task`.
- Don't change the status of other agents' tasks without a clear reason (reviewing a task you created is a reason).
- Accompany every status change with a comment — it's the team's audit trail.
- A `description` with acceptance criteria means any agent can tell when the task is done.
