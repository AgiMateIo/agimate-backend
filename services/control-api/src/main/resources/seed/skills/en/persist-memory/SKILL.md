---
name: persist-memory
title: Long-term memory
description: The agent's long-term memory — saving facts as notes during a conversation and consolidating them into folded memory on triggers.
connectors: [persist-memory]
category: platform
---

# Skill: AgiMate Memory

Memory has two layers:

- **cold** — folded memory. It is **already in your context** (the memory block) — don't call tools to "recall" something.
- **hot** — notes: raw facts you add as the conversation goes (`save_memory_note`). They are in your context too until folded into cold; folding is initiated once a day by a trigger — never start consolidation by hand.

Your main duty during a conversation is to **save notes at the right moment**: there is no other way into memory, nobody rereads the dialogues later.

## When to save a note

Call `save_memory_note` as soon as something appears that will be useful in future conversations:

- the user asks you to remember something, or states a durable fact about themselves: name, role, timezone, projects, preferences, constraints;
- a decision or agreement has been made that you'll come back to;
- **negative signals are the most valuable — don't let them slip**: you were corrected or your work was redone (save what was wrong and what right looks like), the user expressed displeasure (save what triggered it), they set a hard prohibition ("never …"), you made a mistake and it was pointed out (save the anti-pattern).

Rules:

- **When in doubt, save.** Notes are cheap and append-only; duplicates and contradictions are resolved by consolidation — don't re-read memory to check.
- **One note, one self-contained fact**: the future "you" will read it without the conversation around it. Bad: "he agreed". Good: "The user approved the billing release for 2026-07-01".
- **Don't save noise**: throwaway remarks and momentary task details.

## The structure of cold memory

cold enters the context on every turn — keep it compact. When consolidating, lay facts out under these sections in this order (omit empty ones):

```markdown
# Memory

## Profile              ← durable facts about the user (name, role, timezone, projects)
## Preferences          ← how to talk and work: tone, format, style, degree of autonomy
## What to avoid        ← irritants, prohibitions, edits that recurred — the most valuable part
## Techniques and tools ← what works, pitfalls, "if X then do Y" rules
## Working context      ← active goals/tasks/agreements; clear out what's finished
## Glossary             ← terms and entities in the user's vocabulary (optional)
```

One bullet, one line, one atomic fact. Compactness beats completeness: merge related items, resolve contradictions in favour of the more recent, and don't duplicate what's already in the system prompt or in skills.

## Triggers

### consolidate — "fold notes into cold"

Arrives once a day if notes have piled up: `data` holds `consolidationId` and `notes`. Proceed like this:

1. `get_memory()` → the current contents of cold and its `version`.
2. Merge `data.notes` into cold using the structure above: sort into sections, drop duplicates and stale items, clear finished work out of "Working context".
3. `update_memory(text, version, consolidationId: data.consolidationId)` — atomically writes cold and deletes the folded notes.
4. On a "memory changed" error (version conflict), re-read `get_memory` and repeat step 3: the notes were not lost.
