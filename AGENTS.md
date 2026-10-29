# AGENTS.md

Guidance for AI coding agents working in this repository. Humans start with [README.md](README.md)
and [CONTRIBUTING.md](CONTRIBUTING.md); this file is the agent-facing entry point.

The code, comments and commit messages are in English. `docs/` is in Russian — link to it, do not
translate it. Test `@DisplayName`s are in Russian too (see [Build and test](#build-and-test) and
[services/AGENTS.md](services/AGENTS.md)).

## What this is

AgiMate backend: a self-hosted platform where specialised AI agents work together. One Gradle build
under `services/` with three Spring Boot services and two shared libraries.

| Module | Responsibility |
| --- | --- |
| `services/user-api` | Accounts, OAuth2 sign-in, JWT issuing and refresh, sessions, push devices |
| `services/control-api` | The domain: agents, skills, connectors, channels, triggers, boards, LLM providers. Also the worker gRPC protocol (`:9091`) and the ACP WebSocket endpoint for IDE clients |
| `services/agent-worker` | Headless agent loop: consumes runs from the DBOS queue and drives the conversation with the model |
| `services/libs/common` | Shared security, JWT, response wrappers and utilities |
| `services/libs/agentworker-proto` | Protobuf/gRPC stubs shared by `control-api` and `agent-worker` |
| `clients/acp-bridge` | Node bridge for ACP clients; not part of the Gradle build |

Stack: Java 25 · Spring Boot 4 · PostgreSQL 18 · Liquibase · gRPC · Centrifugo.

## Read this first

1. [docs/README.md](docs/README.md) — the documentation map, organised by intent.
2. [docs/architecture/overview.md](docs/architecture/overview.md) — services, data ownership, auth, ports.
3. [services/AGENTS.md](services/AGENTS.md) — build/test commands and module-level layout.
4. [CONTRIBUTING.md](CONTRIBUTING.md) — setup, commits, PRs.

Task → where to start:

| Task | Read |
| --- | --- |
| Add/change a REST endpoint | `services/AGENTS.md` § HTTP, then the neighbouring controller and its tests |
| Add a connector, tool, trigger or job | [docs/architecture/connectors.md](docs/architecture/connectors.md) |
| Add a skill, preset, seed text or translation | [docs/decisions/content-taxonomy.md](docs/decisions/content-taxonomy.md), [docs/architecture/content-language.md](docs/architecture/content-language.md), [docs/decisions/language-catalogs.md](docs/decisions/language-catalogs.md) |
| Change ABAC, channels or trigger routing | [docs/architecture/channels-and-triggers.md](docs/architecture/channels-and-triggers.md) |
| Touch the run lifecycle or the worker protocol | [docs/architecture/agents-and-runs.md](docs/architecture/agents-and-runs.md), [docs/contracts/worker-protocol.md](docs/contracts/worker-protocol.md), [docs/services/agent-worker.md](docs/services/agent-worker.md) |
| Change the schema | [docs/decisions/schema-conventions.md](docs/decisions/schema-conventions.md), [services/AGENTS.md](services/AGENTS.md) § Migrations |
| Run the stack locally | [docs/operations/local-stack.md](docs/operations/local-stack.md) |
| Make an architectural decision | [docs/README.md](docs/README.md) § «Как ведётся документация», then a new file in `docs/decisions/` |

## Build and test

JDK **25** is required: every module sets a Java 25 toolchain. Gradle can find it in the usual
locations, but set `JAVA_HOME` explicitly if the shell default is older:

```bash
cd services

./gradlew build                              # compile + all tests + bootJars (~30-40 s warm)
./gradlew test                               # tests only
./gradlew :libs:common:test                  # cheap loop (~5-8 s)
./gradlew :control-api:test --tests "ru.agimate.controlapi.service.AgentSkillServiceTest"
./gradlew :control-api:compileJava           # fastest syntax check
```

**Build and tests need no Docker, no database and no `services/.env`.** Tests are plain JUnit 5 +
Mockito unit tests — there is no Spring context, no Testcontainers and no H2 usage anywhere. The two
skipped tests are manual smoke tests behind `@EnabledIfSystemProperty`.

Running the services is different: `bootRun` needs the local stack.

```bash
cd ops && ./dev-init.sh                              # keys, services/.env, rendered local configs
cd ops && docker compose --profile infra up -d       # PostgreSQL, Centrifugo, mailpit
cd services && ./gradlew :control-api:bootRun
```

Profiles: `bootRun` defaults to `local`. To open OpenAPI, add the `develop` profile on top:
`SPRING_PROFILES_ACTIVE=local,develop ./gradlew :control-api:bootRun` → `/control/docs/ui`
(`:8180` locally); `user-api` serves `/user/docs/ui`. Without `local` the service refuses to start
because the secrets guard rejects an empty key set.

Known friction: there is **no** formatter, linter, checkstyle, spotless or pre-commit hook. Match the
neighbouring file's style; the only automated gate is compile + tests.

## Ground rules

- **English in code, comments and commits; Russian in `docs/` and in test `@DisplayName`s.** A
  comment restates *why*, not *what*; never add javadoc for completeness. Refer to classes and
  methods with `{@link}`, not plain text.
- **Request/response schemas are generated from the code** and are never duplicated in `docs/` or
  `AGENTS.md`. Point at Swagger instead.
- **Never log or commit secrets or user content.** Keep local keys in `services/.env` and
  `application-local.yaml` (both gitignored); log ids, not credentials.
- **Do not edit generated or ignored trees**: `services/**/build/`, generated protobuf stubs,
  `.idea/`, `.claude/`, `docs/tmpspec/`.
- **No new global tools without a decision.** Do not add a formatter, a test framework or a new
  HTTP client just for one change; follow what the module already uses.

## Rules that bite

These are the conventions an agent most often gets wrong. The full, linked version is in
[docs/development/code-conventions.md](docs/development/code-conventions.md).

1. **A new `/manage/**` controller must be added to `SecurityConfig.JWT_CHAIN_PATHS`.** The JWT
   chain matches an explicit list, not `/manage/**`. A forgotten entry answers 403 to the rightful
   owner. `ManagePathsSecurityTest` fails the build if you forget.
2. **DI:** constructor injection via `@RequiredArgsConstructor`. `@Autowired` is never used on
   fields or setters; on a constructor only when a second, test-only constructor exists (see
   `ConnectorRegistry`).
3. **HTTP:** controllers expose `public static final String PATH`; request/response DTOs are records
   (`*Request`/`*Response`, per audience area in `control-api`); responses are wrapped in
   `SuccessResponse.ok/empty`; errors are thrown as `*StatusException` and rendered by
   `BaseErrorHandlerControllerAdvice` — do not return `ResponseEntity` for errors yourself. Paged
   listings use `PageResponse<T>` with `page`/`size`; a collection path ends with `/`.
4. **IDs:** the database generates them — `@Generated(event = INSERT) @ColumnDefault("uuidv7()")`.
   `UUIDUtils.generateUUIDv8()` only when the id is needed *before* insert because something else
   already references it (a connection key, an `agf_` file reference, a trace id).
5. **Entities are flat:** a FK is a plain `UUID` column, not an association. There are only six JPA
   associations in the codebase, all `LAZY`. Use `@Enumerated(EnumType.STRING)`, `TEXT` (not
   `VARCHAR(n)`), `columnDefinition = "TEXT"`, and duplicate unique constraints in
   `@Table(uniqueConstraints = …)` with the same name as the changelog.
6. **Migrations:** `src/main/resources/db/changelog/updates/YYYY/MM/DD-NN-name.xml`; the master
   changelog includes them recursively, so a new file needs no registration. Write comments as XML
   `<!-- -->`, never `<comment>`; `updated_at` is `NOT NULL DEFAULT CURRENT_TIMESTAMP`; index and
   constraint names follow [schema-conventions.md](docs/decisions/schema-conventions.md).
7. **Tests are unit tests.** `@ExtendWith(MockitoExtension.class)`, `@Nested`, package-private test
   classes, Russian `@DisplayName`. Do not introduce `@SpringBootTest`/`@DataJpaTest`/Testcontainers:
   if a test needs a Spring context, the code is not separated enough.
8. **Connectors:** the SPI is composition — `ConnectorHandler` plus the capability interfaces
   (`ToolProvider`, `TriggerProvider`, `JobProvider`, `PromptBlockProvider`, …), resolved through
   `ConnectorRegistry`. A connector is a facade bean plus a `<Name>ToolService` with `@Tool`/`@ToolParam`
   methods; schemas are built by reflection. Inside the connector layer throw only
   `ConnectorException`, and never depend on `controller/**`. Everything that reaches the agent from a
   `@Tool` declaration is English; catalogue texts go through `seed/texts/<lang>/` and the
   `ConnectorTextsTest`/`PromptTextsTest` guards.
9. **Seed content is seed-only-if-missing.** Editing `seed/skills/**/SKILL.md` changes nothing on an
   environment that has already been seeded; a language catalogue is a separate record, not a
   translation.
10. **DBOS durable-step results must be plain serializable types** — never protobuf messages and
    never secrets.
11. **Two Jackson generations coexist in `control-api`:** Spring Boot 4 injects Jackson 3
    (`tools.jackson`); `libs/common`'s `JsonUtils` is statically Jackson 2 (`com.fasterxml`).
    Injecting a `com.fasterxml` `ObjectMapper` breaks the context at startup.
12. **gRPC is not always on:** the server is enabled by the `local` profile, TLS is a deployment
    option. `AgentRunWorkflow` is an interface; the implementation is `AgentRunner`. Queue
    `agent_exec`, workflow `run_agent`, partition key is the session id.

## Documentation workflow

Long work starts with a document in `docs/decisions/`, not with code. The header carries a status
(`analysis | accepted | partial | implemented | deferred`), and a `## План` section of checkboxes is
the task list. Once it lands, the status becomes `partial`/`implemented` with the date. What comes
next and in what order lives in [docs/roadmap.md](docs/roadmap.md).

When behaviour, an endpoint, an environment variable or the architecture changes, update the matching
`docs/` page and add the new decision to the table in [docs/README.md](docs/README.md). `docs/tmpspec/`
holds throwaway deltas for neighbouring teams and is gitignored — do not treat it as documentation
(links to it may be dead in a clean clone).

## Commits and pull requests

```
<type>: <object> — <delta>
```

`<type>` is one of `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `chore`; the subject is English,
at most 72 characters, lower case after the colon, no trailing period, no scope in parentheses.
`<object>` is a noun first — the entity, connector, endpoint or table someone will grep for; `<delta>`
is the result, not the action ("soft delete for agents instead of hard-delete"). A "+" in the subject
means the commit should be split. No `Co-Authored-By` trailer. Body (optional, 2–4 bullets) explains
*why*, not which files changed. Run `./gradlew build` before opening a PR.

## Where to look next

- [docs/README.md](docs/README.md) — every doc, by intent
- [docs/architecture/overview.md](docs/architecture/overview.md) — the system in one read
- [docs/architecture/connectors.md](docs/architecture/connectors.md) — the connector SPI
- [docs/architecture/channels-and-triggers.md](docs/architecture/channels-and-triggers.md) — routing and channels
- [docs/contracts/worker-protocol.md](docs/contracts/worker-protocol.md) — control-api ↔ agent-worker
- [docs/development/code-conventions.md](docs/development/code-conventions.md) — the full convention list
- [docs/decisions/schema-conventions.md](docs/decisions/schema-conventions.md) — migrations and names
- [docs/operations/local-stack.md](docs/operations/local-stack.md) — the local stack
