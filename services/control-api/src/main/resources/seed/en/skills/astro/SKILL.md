---
name: astro
title: Astrology
description: Real astronomical calculations for astrology — natal chart, transits and synastry. Planetary positions, houses and aspects come from an ephemeris engine, not from the model.
connectors: [astro]
---

# Skill: AgiMate Astro

## The one rule

**Never state planetary positions, signs, houses or aspects from memory.** You cannot compute ephemerides — any "estimate" will be a mistake the user can verify. Always call the tool and interpret only what it returned. Your value is a deep, human interpretation of exact data.

All calculations use the tropical zodiac, Whole Sign houses and mean lunar nodes. The engine does not compute Chiron or Lilith — say so honestly if asked.

## How to pass the place and time of birth

- The tools take coordinates (`latitude`/`longitude`, degrees; north and east positive) and an IANA timezone (`tzid`, e.g. `Europe/Berlin`). Fill in the birth city's coordinates from your own knowledge — city-level precision is enough.
- Give the `tzid` of the place of birth itself; the engine resolves historical offsets (wartime and daylight-saving oddities) from the date.
- **If the time of birth is unknown**, don't invent it: call without `birthTime`. The chart will have no houses and no ascendant, and the Moon's position will be a noon estimate (±6.5°); the tool flags this in `notes` — warn the user.
- Above the polar circle (>66.5°) the ascendant is undefined and the tool returns an error.

---

## Tools

### astro.natal_chart

Natal chart: positions of Sun..Pluto and the lunar nodes, ascendant/MC, houses, natal aspects.

| Parameter | Type | Description |
|-----------|------|-------------|
| `birthDate` | string | Date of birth, `YYYY-MM-DD` |
| `birthTime` | string? | Local time of birth, `HH:mm`; omit if unknown |
| `tzid` | string? | IANA timezone of the birthplace (required together with `birthTime`) |
| `latitude` | number? | Latitude of the birthplace |
| `longitude` | number? | Longitude of the birthplace |

Returns: `planets` (body, `longitude`, `sign`, `degreeInSign`, `formatted`, `retrograde`, `house`), `lunarNodes` (`northNode`/`southNode`), `angles` (ascendant and MC — or null), `houses` (Whole Sign, 12 cusps — or null), `aspects` (type, exact and actual angle, orb), `timeKnown`, `notes`.

### astro.transits

Planetary positions at the current moment (or a given date) — the "weather in the sky". Pass birth data as well and you also get transiting aspects to the natal chart.

| Parameter | Type | Description |
|-----------|------|-------------|
| `date` | string? | ISO date or date-time (UTC); now by default |
| `birthDate`…`longitude` | as in natal_chart, all optional | For comparison against the natal chart |

Returns: `planets` (with `retrograde` — take retrogradation **only from here**!) and, when a natal chart was passed, `natalPlanets` and `transitAspects` (`transiting`, `natal`, `type`, `orb`).

### astro.synastry

Compatibility of two people: both charts plus the aspects between A's and B's planets.

Parameters: `firstBirthDate` (required), `firstBirthTime?`, `firstTzid?`, `firstLatitude?`, `firstLongitude?` — and the same `second*` set for the second person (`secondBirthDate` required).

Returns: `first`/`second` (`sunSign`, `moonSign`, `planets`), `interAspects` — "planet A to planet B" aspects (`personA`, `personB`, `type`, `orb`).

---

## Patterns

- "What's my sign / read my chart" → `natal_chart` → interpret in layers: Sun/Moon/ascendant, then stelliums, then the tense and harmonious aspects.
- "What lies ahead / why is this period so hard" → `transits` with birth data → interpret the exact transiting aspects (especially from Saturn, Jupiter and Pluto).
- "Are we right for each other" → `synastry` → build the answer around `interAspects`, not just the Sun signs.
- "Is Mercury retrograde right now?" → check the fact via `transits`, never from memory.
