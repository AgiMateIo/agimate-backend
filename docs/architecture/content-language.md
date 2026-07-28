# Язык системного контента

`APP_CONTENT_LANGUAGE` (property `app.content.language`, enum `ContentLanguage`: `ru` | `en`) selects
the language of the content the platform ships: agent presets, system skills, the connector catalog
and the trusted instruction blocks the platform injects into agent prompts. It is **not** the language
agents reply in — that follows the user and is stated in the instructions themselves. A typo in the
value fails startup (the property binds to an enum).

Content lives per language in the classpath, and `SeedContentLocator` is the only place that knows
the layout:

```
resources/seed/<lang>/presets/<code>/PRESET.md      # seeded by SystemPresetBootstrap
resources/seed/<lang>/skills/<code>/SKILL.md        # seeded by SystemSkillBootstrap
resources/seed/<lang>/connectors.properties         # connector catalog name/description
resources/seed/<lang>/prompt.properties             # trusted prompt blocks (behaviour, not captions)
```

Adding a language = a new `ContentLanguage` constant plus a copy of the directory. Only `title`,
`description` and the body are translated: `name`, `skills`, `connectors` and `sortOrder` are machine
keys and must be byte-identical across languages — a translated slug silently breaks the
preset→skill and skill→connector links, which is what `SeedContentParityTest` guards. A file missing
for the selected language falls back to `en` with a warning rather than failing the seed.

**Two different lifecycles:**

- **Presets and skills — the language is fixed by the first seeding.** Both bootstraps are
  seed-only-if-missing, keyed by the language-independent `name`, and the database holds one language
  at a time. Changing `APP_CONTENT_LANGUAGE` on an already-seeded environment therefore translates
  nothing: it is a choice for a fresh installation. To switch in development, delete the system rows
  (`skills` where `user_id = '00000000-0000-0000-0000-000000000000'`, and `agent_presets`) and
  restart. Existing agents never follow a switch in any case — preset `instructions` are copied into
  the agent at creation, and skills are bound by ID.
- **Connector catalog — follows the property.** `ConnectorBootstrap` upserts `connectors` rows on
  every start, so `name`/`description` move to the new language after a restart, with no migration.
  English stays in the code (`connectorName()`/`connectorDescription()`) as the last-resort fallback,
  which is why there is deliberately no `seed/en/connectors.properties`; `ConnectorTextsTest`
  enforces that every registered code has a translation in every other language.
- **Prompt blocks — follow the property.** Resolved per run in `RunContextService`, so a restart is
  enough. Keys live in `PromptTexts`: `run.trigger.guidance` (autonomous event handling),
  `run.tool-call.guidance` (never imitate a tool call in text), `run.attachment.guidance`
  (the `[[attach:]]` convention), and `connector.<code>.<trigger>.guidance` with a fallback to
  `connector.<code>.guidance` for `ContextDirectives.guidance`. These are **behaviour, not captions** —
  a bad translation changes what agents do — which is why they sit in a bundle separate from the
  connector catalog: different reader, different cost of error. `PromptTextsTest` enforces
  completeness; the English source stays in `RunContextService`/`ContextDirectives` as the fallback.

**The tool layer needs no bundle by convention** — tool descriptions (`@Tool`), parameter descriptions
(`@ToolParam`, including example values inside them) and trigger descriptions
(`TriggerSpec.description`) are written in English — the same source language as the rest of the
code — and no translation bundle covers them: unlike prompt blocks, a tool schema is read by the model
in the MCP convention, where English is the norm. `@Tool(title)` is not used at all: display titles for tools are an open UI
question, and having them on one connector only made listings uneven. See
`docs/architecture/connectors.md`.

The one path by which an English tool text could reach a Russian-speaking user is the platform
connector relaying `get_connector` output; the `platform` skill instructs the agent to retell rather
than quote.

