# Язык системного контента

`APP_CONTENT_LANGUAGE` (property `app.content.language`, enum `ContentLanguage`: `ru` | `en`) is the
installation's **default** content language: the language of a call whose client names none (and of
everything outside an HTTP request), of the stored connector rows and of the trusted instruction
blocks the platform injects into agent prompts. It is **not** the language agents
reply in — that follows the user and is stated in the instructions themselves. A typo in the value
fails startup (the property binds to an enum).

Presets and skills are not one set in one language: every language has a catalogue of its own, and
the sets may differ — see [decisions/language-catalogs.md](../decisions/language-catalogs.md).

Content lives in the classpath by kind first and language second, and `SeedContentLocator` is the
only place that knows the layout:

```
resources/seed/presets/<lang>/<code>/PRESET.md          # seeded by SystemPresetBootstrap
resources/seed/skills/<lang>/<code>/SKILL.md            # seeded by SystemSkillBootstrap
resources/seed/texts/<lang>/connectors.properties       # connector catalog name/description
resources/seed/texts/<lang>/prompt.properties           # trusted prompt blocks (behaviour, not captions)
resources/seed/texts/<lang>/llm-providers.properties    # LLM provider catalog descriptions
resources/seed/llm-providers.yaml                       # the provider catalog itself — no language axis
resources/seed/llm-models.yaml                          # model capability snapshot — no language axis
```

Kind first, so the root of `seed/` reads as the list of things the platform seeds and content with no
language dimension has a place in it: `llm-providers.yaml` carries URLs, provider types and model ids,
and only its descriptions are translated.

Adding a language = a new `ContentLanguage` constant plus a directory under each kind. A preset or a
skill in one language's folder is a record of its own, not a translation of the other language's:
a code may exist in one language only, and nothing falls back to another language. What does hold
is the reference: a preset's `skills` resolve among the skills of its own language (or language-less
ones), which `SystemPresetBootstrapTest` checks for the seeds.

**Two different lifecycles:**

- **Presets and skills — every language is seeded.** Both bootstraps walk every `seed/<kind>/<lang>/`
  folder, seed-only-if-missing keyed by `(name, language)`; a record carries its `language` (a
  preset always, a skill may be `NULL` — «every catalogue»: user skills and system ones not written
  for an audience). A skill name is resolved in a language — that language's version, else the
  language-less one (`SkillRepository.findByNamesInLanguage`). Which catalogue a listing shows is the
  request's language. An agent carries its own `language` — the catalogue it was created from, the
  preset's being looked up there — and never follows a switch: preset `instructions` are copied into
  it, skills are bound by ID.
- **The request's language** is `Accept-Language`, read by `ContentLanguageResolver` (the best
  supported primary subtag by weight; none, or outside an HTTP request — the default). The header is
  read directly rather than through Spring's `LocaleResolver`, whose locale also drives validation
  messages. It decides the gallery (`/manage/agent-presets/`, `/all/`), the public skill listing,
  the preset an agent is created from, the catalogue an admin creates a preset or a system skill in,
  and the connector and taxonomy captions. Reading it marks the response `Vary: Accept-Language`.
  The platform connector's `list_presets`/`list_skills`/`create_agent` have no header and use the
  calling agent's language instead.
- **Connector catalog — stored in the property's language, answered in the request's.**
  `ConnectorBootstrap` upserts `connectors` rows on every start in `APP_CONTENT_LANGUAGE`; `?search=`,
  the listing's order and the platform connector read them. `/manage/connectors/` answers in the
  request's language through `ConnectorLabels`, which falls back to the English in the code — the
  handler's, or `StaticConnector` for `app`/`claude-code` — never to the row. English stays in the code (`connectorName()`/`connectorDescription()`) as the last-resort fallback,
  which is why there is deliberately no `seed/texts/en/connectors.properties`; `ConnectorTextsTest`
  enforces that every registered code has a translation in every other language. Taxonomy labels
  (`TaxonomyTexts`) are not stored at all and follow the request the same way.
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

