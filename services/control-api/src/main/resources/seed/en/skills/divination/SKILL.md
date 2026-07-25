---
name: divination
title: Divination and numerology
description: Destiny Matrix, numerology and Tarot. Every number and drawn card comes from a deterministic engine — the model only interprets.
connectors: [divination]
---

# Skill: AgiMate Divination

## The one rule

**Never compute numbers or "draw" cards yourself.** Matrix arcana, life path numbers and drawn Tarot cards always come from the tools — the user can recompute and check. The interpretation is yours; the facts are the engine's.

---

## Tools

### divination.matrix_of_destiny

The Destiny Matrix from a birth date (arcana 1–22, sums above 22 are reduced).

| Parameter | Type | Description |
|-----------|------|-------------|
| `birthDate` | string | Date of birth, `YYYY-MM-DD` |

Returns: `points` — the energies of each point (`day` — character, `month` — talents, `year` — ancestral karma, `mission` — karmic task, `center` — comfort zone, `paternalLine`/`maternalLine` — family lines, `southEast`/`southWest`), `moneyLine` (`money`, `relationships`), `karmicTail` — the three arcana of the karmic tail. Interpret each number as the corresponding major arcana of the Tarot.

### divination.numerology

Numerology from a birth date: life path number (master numbers 11/22/33 are preserved), birthday number, personal year.

| Parameter | Type | Description |
|-----------|------|-------------|
| `birthDate` | string | Date of birth, `YYYY-MM-DD` |

Returns: `lifePath` (`value`, `isMaster`, `components` — day/month/year separately), `birthdayNumber`, `personalYear`. If `isMaster: true`, always unpack the special meaning of the master number.

### divination.tarot_card_of_day

The user's card of the day. **Deterministic**: one user gets the same card all day, however many times they ask. Don't draw the card of the day through a spread — only through this tool.

| Parameter | Type | Description |
|-----------|------|-------------|
| `date` | string? | Date `YYYY-MM-DD` (UTC); today by default |

Returns: `card` (`nameRu`/`nameEn`, `arcana`, `suit`, `number`, `reversed`, `keywords` — already matched to the actual orientation), `sameForWholeDay: true`.

### divination.tarot_draw_spread

A random spread from the full 78-card Rider–Waite deck, without repeats; any card may come up reversed.

| Parameter | Type | Description |
|-----------|------|-------------|
| `spread` | string | `THREE_CARD` (past/present/future), `CELTIC_CROSS` (10 positions), `YES_NO` (1 card) |
| `question` | string? | The user's question (for context) |

Returns: `cards` — the cards with their position in the spread (`position`), orientation (`reversed`) and keywords.

---

## Patterns

- Shape the question with the user before a spread, then make **one** `tarot_draw_spread` call. Don't redraw because the answer wasn't liked — that empties the reading of meaning; a second spread on the same question only on an explicit request.
- Interpret each card by its position in the spread and its orientation; the keywords from the tool are a foothold, the full meaning is your expertise.
- "Quick yes/no" → `YES_NO`; "make sense of a situation" → `THREE_CARD`; "a deep reading" → `CELTIC_CROSS`.
- Weave the Matrix and numerology into one personality reading — both are built from the same birth date, so show the themes that echo across them.
