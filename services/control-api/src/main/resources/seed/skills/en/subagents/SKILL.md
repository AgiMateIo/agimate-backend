---
name: subagents
title: Subagents
description: Handing self-contained requests to copies of yourself that work in parallel and report back — research across many sources, long reading and independent pieces stay out of the conversation.
connectors: [subagents]
category: platform
---

# Skill: subagents

A subagent is a copy of you with the same skills and tools, working in its own session. It starts **with no knowledge of this conversation** — it sees only what you put into `ask_subagent`. It reports back with a separate message after your turn ends.

## When to ask

- The work would flood your context: research across many pages, reading a long document, comparing many options.
- The work splits into independent pieces that can run at the same time — one subagent per piece.

## When not to ask

- A single lookup or a single tool call — do it yourself, it is faster and cheaper.
- The work needs a back-and-forth with the user — a subagent cannot ask the user anything.
- The pieces depend on each other's results — do them in order yourself.

Every subagent is a separate model run: ask only when the gain in context is worth it. At most three can work in one conversation at once.

## How to write a request

- `title` — a short name you will recognise in the report.
- `instructions` — what to do and **what to return, in what form**: a table, a list with links, a yes/no with the reason.
- `context` — everything the subagent needs from the conversation: the facts the user gave, constraints, the language and tone of the answer. Repeat it for every subagent; they do not share it.

## After asking

- Finish your turn: tell the user briefly what you started. Do not wait and do not call anything to check on progress.
- Each report arrives as a separate message. The `subagents` block shows who is still working.
- While someone is still working, your answer to a report stays in the history and does not reach the user — note what the final reply needs. When the last report arrives, your answer goes to the user: bring the reports together.
- A report is the subagent's own account, not a verified fact. Check side effects it claims (a message sent, a file written) before telling the user.
- To add to a subagent's request — clarify, ask for more — call `ask_subagent` with its `subagentId`: it continues with what it already knows.
