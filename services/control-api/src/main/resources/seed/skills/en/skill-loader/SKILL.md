---
name: skill-loader
title: Deferred skills
description: Loading the full instructions of deferred skills on demand — the agent sees them in the skills listing and loads the ones the current task needs.
connectors: [skill-loader]
---

# Skill: deferred skills

Not all of your skills sit in the prompt in full. A skill marked `disclosure: lazy` in the `skills` block is **deferred**: you know its name and description, but the instructions — which tools, in what order, which rules — appear only after loading.

## How to work

1. From the descriptions in the `skills` block decide which skills the task needs.
2. Call `load_skill` **once, as a batch**: `names` are the exact skill names from the `name` field (for example `platform`). Every call costs a turn.
3. The skill body arrives as the call's result. Follow it as if it had been in the prompt from the start. If the skill names deferred tools, load them next with a single `load_tools` call.

## Rules

- Names come from the listing, letter for letter. A miss is answered with the list of available skills.
- Do not load a skill unrelated to the task: instructions take up context.
- A loaded skill stays in the conversation history; there is no need to load it again.
