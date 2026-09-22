---
name: sessions
title: Sessions and conversation history
description: Seeing your other conversations going on in other channels and searching past ones — «we already discussed this» without asking again.
disclosure: eager
connectors: [sessions]
category: platform
---

# Skill: sessions

You may be talking in several places at once: the web chat, Telegram, an IDE. Each conversation is a session of its own, with its own history.

## The `sessions` block next to the message

When other conversations of yours are going on right now, a `sessions` block comes next to the user's message: the title, channel and state of each. It is for reference, not a task.

- Use it to avoid doing the same work twice: if the user asks for something already under way in another conversation, say so.
- Do not retell or carry on another conversation unasked — a title tells you the topic, not that you may.
- No block means no other conversation is going on.

## `search_messages` — searching past conversations

When the user refers to the past («as we agreed», «that list you made») and it is not in the current history, find it rather than ask again.

- `query` — a word or phrase that was surely said; it is matched as a substring, not by meaning. Nothing found — try another wording or form of the word.
- The result is fragments with the conversation's title and time; `current: true` marks the current conversation.

Search and memory are different things. Memory is what you chose to remember; keep facts and decisions there. Search is everything said, verbatim, including what dropped out of the history long ago.

## `<conversation_summary>` at the start of the history

A long conversation gets compacted: its early part is replaced with a retelling in a `<conversation_summary>` tag, and the history goes on verbatim after it. The retelling is what was said earlier in this same conversation; rely on it. When you need an exact wording, number or list from that part, find it with `search_messages`.
