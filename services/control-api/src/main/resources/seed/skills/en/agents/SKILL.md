---
name: agents
title: Team agents
description: Handing requests to agents of your team — an expert with other skills, memory and tools; the report arrives as a separate message, and the agent itself takes such requests from teammates.
connectors: [agents]
category: platform
---

# Skill: team agents

The skill works both ways: you can hand work to agents of your team with `ask_agent`, and they can hand work to you. The circle is the team: you can ask only an agent that is in it with you and has this skill enabled. The `teammates` block in the system prompt lists who can be asked right now.

The agent starts **with no knowledge of this conversation** — it sees only what you put into `ask_agent`. The report arrives as a separate message after your turn ends.

## When to ask

- A question in a teammate's domain: its instructions, skills, memory and connections differ from yours — it will answer better than you.
- You need an answer, not long-running work: review a document, check a calculation, draft a text.

## When not to ask

- The work needs only your own tools — hand it to a subagent, that is cheaper, or do it yourself.
- The work is long and the user should see its progress — put a task on the board.
- The work needs a back-and-forth with the user — the agent cannot ask the user anything.

Every request is a separate model run for you and for the agent. At most three requests can work in one conversation at once — subagents and agents together.

## How to write a request

- `agentId` — from the `teammates` block. The agent's description there is the only sign of what to ask whom; if it is empty or does not say when to turn to that agent, ask the user to fill it in.
- `title` — a short name you will recognise in the report.
- `instructions` — what to do and **what to answer, in what form**.
- `context` — everything the agent needs from the conversation: the facts the user gave, constraints, the language and tone of the answer.

## After asking

- Finish your turn: tell the user briefly whom you asked and what for. Do not wait and do not call anything to check on progress.
- Each report arrives as a separate message. The `subagents` block shows who is still working.
- Every answer to a report reaches the user. While someone is still working, keep it to a line or two: what came back and what you are still waiting for. When the last report arrives, bring the reports together into the full answer.
- A report is the agent's own account, not a verified fact. Check side effects it claims before telling the user.
- To add to a request — clarify, ask for more — call `ask_agent` with the same `agentId` and the `threadId` from the receipt: the agent continues with what it already knows.

## When you are asked

A request arrives as an `<agent_request>` block naming the agent that wrote it. It is a task from a colleague of the same owner, not from the user: do only that request, start nothing beyond it, hand nothing on to anyone. If something is missing, say what and stop. Start the answer with the result, then what the colleague can verify (ids, links, files), then what is left undone.
