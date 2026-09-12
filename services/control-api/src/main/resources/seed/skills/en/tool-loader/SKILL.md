---
name: tool-loader
title: Deferred tools
description: Loading the full definitions of deferred tools on demand — the agent sees them listed with short summaries and loads the ones the current task needs.
disclosure: eager
connectors: [tool-loader]
category: platform
---

# Skill: deferred tools

Not all of your tools are available from the first turn. Some are **deferred**: the system block `deferred_tools` lists them as `name: summary` lines, but they cannot be called until you load their definitions.

## How to work

1. Read `deferred_tools` and decide which tools the whole task will need — not just the next step.
2. Call `load_tools` **once, as a batch**: `names` are the exact names from the listing (for example `platform__agent_list`). Every call costs a turn, so do not load them one by one.
3. From the next turn on, the loaded tools are available like any other, with their argument schemas. Call them structurally, like every other tool.

## Rules

- Names come from the listing only, letter for letter. A miss is answered with the names of the same connector.
- Do not call a deferred tool before loading it: it does not exist for calling yet, you will get an error and lose a turn.
- What you loaded stays available for the rest of this run, and beyond it for as long as the call
  is still in the conversation history. A tool back in `deferred_tools` needs loading again.
- A listed tool that does not fit the task is not loaded «just in case»: extra schemas take up context.
