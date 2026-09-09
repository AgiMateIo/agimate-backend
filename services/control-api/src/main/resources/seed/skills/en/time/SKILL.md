---
name: time
title: Time and scheduler
description: Current time in UTC and scheduling deferred tasks for yourself — one-off reminders, periodic runs and cron schedules.
connectors: [time]
---

# Skill: AgiMate Time

The time connector is the current time plus deferred tasks **for yourself**: at the appointed moment you "wake up" as a separate run with the prompt you gave. That's how reminders, monitoring and scheduled work are done — without holding context. All time is **UTC**.

## Tools: what to call when

- `current_datetime` — "what time is it" (UTC, ISO-8601). Call it before computing delays and before scheduling.
- `schedule(prompt, …)` — schedule a task. **Exactly one mode** per call:
  - `delaySeconds` — once, in N seconds (`ONETIME`);
  - `intervalSeconds` — every N seconds (`PERIODIC`);
  - `cron` — a 6-field Spring cron with seconds (`0 0 9 * * *` — daily at 09:00), UTC by default; for local time set `zone` (IANA, e.g. `Europe/Berlin`).
- `scheduled_tasks` — the list of your active tasks. Check it before scheduling so you don't breed duplicates.
- `cancel_scheduled(id)` — cancel a task. `PERIODIC` and `CRON` live forever until you cancel them — clean up the ones you no longer need.

## A task prompt is self-contained

When it fires, the future "you" will have none of the current context — only the prompt text. Write it so it's clear what to do and where to send the result: not "remind about the call" but "remind the user in chat: call with the supplier at 15:00 CET".

## The time.due trigger

When a task comes due you wake up as a separate run: **the text of your own prompt** arrives as a trusted instruction (not a JSON event), marked as your own deferred task. Carry it out. If the task is `PERIODIC`/`CRON` and no longer needed, cancel it via `cancel_scheduled`.

**Reply delivery:** if the task was scheduled from a chat with the user, the run's final answer goes back to that chat. If there was no chat, the final text is delivered to no one — pass results through tools instead (a board comment, sending to a channel, and so on).

## Patterns

- One-off reminder: `schedule(prompt: "Remind the user in chat: check the supplier's reply", delaySeconds: 3600)`.
- Monitoring: `schedule(prompt: "Check for new tasks in BACKLOG and pick up a suitable one", intervalSeconds: 1800)`; when it's no longer needed — `scheduled_tasks` → `cancel_scheduled(id)`.
- On a schedule: `schedule(prompt: "Put together the daily board report", cron: "0 0 18 * * MON-FRI", zone: "Europe/Berlin")`.
