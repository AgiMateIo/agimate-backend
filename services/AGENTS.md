# services/AGENTS.md

Scope: everything under `services/` (the Gradle build). Read [../AGENTS.md](../AGENTS.md) first — it
covers the repository-wide rules. This file adds the build, module layout and the conventions that
only matter when you change Java code.

## Build and test

Run every Gradle command from `services/`.

- **Toolchain:** Java **25** (every module sets `languageVersion = 25`). Gradle 9.8 is the wrapper;
  Gradle detects JDK 25 in the usual install locations, but exporting `JAVA_HOME` removes ambiguity
  when the shell default is 21.
- **No infrastructure needed:** `build` and `test` never touch PostgreSQL, Centrifugo or
  `services/.env`; tests are plain JUnit 5 + Mockito. The first build downloads Gradle, Maven Central
  and JitPack (`control-api` pulls `io.github.cosinekitty:astronomy` only from JitPack).
- **No formatters/linters:** no spotless, checkstyle, editorconfig or pre-commit hook. Match the
  neighbouring file and keep imports in the file's existing style.

```bash
cd services

./gradlew build                                  # compile + all tests + bootJars
./gradlew test                                   # all tests (~20-35 s)
./gradlew :control-api:test --tests "ru.agimate.controlapi.config.ManagePathsSecurityTest"
./gradlew :libs:common:test                      # cheap loop
./gradlew :control-api:compileJava               # fastest check
./gradlew :control-api:bootJar                   # deployment jar
```

`build` re-runs the service tests on every invocation because `bootBuildInfo` regenerates a timestamp
file — that is expected, not a hang. `MailSmokeTest` and `WorkerAuthkeyGeneratorTest` are skipped by
default: they are manual, enabled by system properties.

## Run locally

```bash
cd ops && ./dev-init.sh
cd ops && docker compose --profile infra up -d      # PostgreSQL, Centrifugo, mailpit
cd services && ./gradlew :user-api:bootRun
cd services && ./gradlew :control-api:bootRun
cd services && ./gradlew :agent-worker:bootRun       # needs the shared DBOS database
```

`bootRun` activates the `local` profile (keys and local URLs come from `application-local.yaml`,
rendered by `dev-init.sh` and gitignored). OpenAPI is off in `local`; add `develop` on top —
`SPRING_PROFILES_ACTIVE=local,develop` — and open `/control/docs/ui` (`:8180`) or `/user/docs/ui`
(`:8080`). `control-api` and `user-api` both own `:8080` in deployment, but locally `control-api`
moves to `:8180` (management `:8188`); `agent-worker` serves only `/actuator/health` on `:8089`.
The gRPC server (`:9091`) is enabled by the `local` profile only; TLS is a deployment option, not a
local requirement.

## Module layout

```
services/
├── user-api/                 accounts, OAuth2, JWT, sessions, push
├── control-api/              the domain; controllers split by audience
├── agent-worker/             DBOS consumer + Spring AI agent loop
└── libs/
    ├── common/               security, JWT, response wrappers, exceptions, utils
    └── agentworker-proto/    .proto sources; stubs generated at build time
```

Shared package layout: `controller` → `service` → `database/{repositories,entities}`, plus `config`,
`security`, `util`.

- **user-api:** DTOs in `controller/dto/{request,response}`, entity↔DTO mapping in `mappers/`.
- **control-api:** controllers are grouped by audience — `controller/agent/`, `app/`, `manage/`,
  `webhook/` — and DTOs live per area (`controller/<area>/dto/…`); there is no central `mappers`
  package. Extra packages: `abac`, `acp`, `connectors`, `grpc`, `realtime`, `storage`, `util`.
- **agent-worker:** pure logic in `agent/`, `dto/`, `llm/`; the DBOS surface in `workers/` and
  `config/DbosRuntime`; the gRPC client to `control-api` in `grpc/`.
- **libs/agentworker-proto:** edit the `.proto` files, never the generated classes. Both services
  depend on the module, so the protocol cannot drift.

## HTTP layer

Follow the shape of `control-api/src/main/java/ru/agimate/controlapi/controller/manage/ManageAgentStarterController.java`:

- `@RestController` + `@RequestMapping(XController.PATH)` where `PATH` is a `public static final
  String` referenced by the security config and tests.
- Class-level `@Tag(name = …, description = …)`, method-level `@Operation(summary = …)`.
- DTOs are records: request records carry validation annotations, response records carry `@Schema` on
  the components.
- Return `SuccessResponse.ok(value)` / `SuccessResponse.empty()`; never build `ResponseEntity` for a
  normal path. Errors are thrown as `*StatusException` from `libs/common` and rendered by
  `BaseErrorHandlerControllerAdvice`; the body is `{"error":{"message":…,"details":{…}}}`.
- Identity: `@AuthenticationPrincipal AgimateUserPrincipal principal`, then
  `UUID.fromString(principal.id())`.
- Collections live at a path ending in `/`; paged listings return `SuccessResponse<PageResponse<T>>`
  with `page` (default 0) and `size` (default 20).

**Security chains.** Requests are matched by four ordered chains in `control-api`'s
`SecurityConfig`: `@Order(1)` JWT for `/manage/**` (+ `/admin/**`), `@Order(2)` app key, `@Order(3)`
agent key for `/agent/**`, `/acp`, `/mcp`, `@Order(4)` public (webhooks, `/oauth/**`,
`/.well-known/**`, `/files/**` by HMAC, `/docs/**`). A new `/manage/**` controller must be added to
`SecurityConfig.JWT_CHAIN_PATHS`, otherwise it falls through to a later chain and answers 403 to its
rightful owner. `ManagePathsSecurityTest` enforces that list.

## Persistence

- Entities extend `BaseEntity`; IDs are database-generated:
  `@Generated(event = INSERT) @ColumnDefault("uuidv7()")` on `UUID id`.
  `UUIDUtils.generateUUIDv8()` only where the id must exist before insert because it already
  addresses something else (a connection key, an `agf_` file reference, a blob key, a trace id).
- Entities are flat: foreign keys are plain `UUID` columns, associations are rare and always
  `FetchType.LAZY`. Use `@Enumerated(EnumType.STRING)`; `TEXT` instead of `VARCHAR(n)`; duplicate
  unique constraints in `@Table(uniqueConstraints = …)` with the same names as the changelog.
- **Migrations:** `src/main/resources/db/changelog/updates/YYYY/MM/DD-NN-name.xml` (the number is the
  sequence within a date). `db.changelog-master.yaml` uses `includeAll … recursive: true`, so a new
  file needs no registration. `user-api` keeps an empty `updates/placeholder.xml` — do not delete it,
  it keeps the directory in git.
- `updated_at` is `NOT NULL DEFAULT CURRENT_TIMESTAMP`; comments are XML `<!-- -->`, not `<comment>`,
  and are required where the DDL cannot explain itself (a partial unique index, `COALESCE` in a key,
  denormalisation). Index/constraint naming: [../docs/decisions/schema-conventions.md](../docs/decisions/schema-conventions.md).
- Transactions: `@Transactional(readOnly = true)` on the class, `@Transactional` on mutating methods.
- JSONB columns use `@JdbcTypeCode(SqlTypes.JSON)`; on PostgreSQL 18, `TEXT` columns are the norm.

## Connectors, skills and routing

The long version is [../docs/architecture/connectors.md](../docs/architecture/connectors.md) and
[../docs/architecture/channels-and-triggers.md](../docs/architecture/channels-and-triggers.md).
The short version:

- The SPI is composition: `ConnectorHandler` (identity and capabilities) plus capability interfaces
  implemented à la carte — `ToolProvider`, `TriggerProvider`, `JobProvider`, `PromptBlockProvider`,
  `ViewProvider`. Consumers resolve them via `ConnectorRegistry.findCapability(code, X.class)` or the
  static `ConnectorRegistry.capability(handler, X.class)`.
- A connector is `<Name>ConnectorService` (facade, usually `@Component`) plus `<Name>ToolService` with
  `@Tool`/`@Job` methods and `@ToolParam` parameters. Schemas are built by reflection; do not
  hand-write JSON schemas. Registration is automatic (Spring beans; `ConnectorBootstrap` upserts the
  catalogue row) — no hand-maintained registry.
- Inside the connector layer throw only `ConnectorException` (its text is shown to the agent) and
  never import `controller/**`. `*StatusException` belongs to the HTTP boundary.
- `@Service` is for transactional domain services only; SPI facades, tool services and infrastructure
  executors are `@Component`.
- Skills are catalogued and bound to agents (`Skill` → `AgentSkill` → `AgentSkillConnection`); the
  run delivers a skill only when its declared requirements resolve to satisfied connection instances.
  Seed content is seed-only-if-missing, and a language catalogue is a separate record, not a
  translation.
- ABAC is one table, `AgentConnectionPolicy`, with `PolicyKind.TOOL|TRIGGER`; inside an active
  binding the model is default-allow, exact tool name > binding-wide rule. A channel is a separate
  layer from a policy: ABAC decides *who*, the channel decides *how*.
- All content that reaches the agent from a `@Tool`/`@ToolParam`/`TriggerSpec.description`
  declaration is English and has no translation bundle. `connectorName`/`connectorDescription` and
  `ContextDirectives.guidance` are translated through `seed/texts/<lang>/`; the code value is the
  source of truth and the fallback. `ConnectorTextsTest`/`PromptTextsTest` fail when a new code has
  no translation.

## Worker and protocol

- `agent-worker` consumes a DBOS run enqueued by `control-api`. Contract: class `AgentRunWorkflow`
  (an **interface**, implemented by `AgentRunner`), workflow `run_agent`, partition `agent_exec`,
  partition key = `sessionId`. Protos live in `libs/agentworker-proto`.
- DBOS durable-step results must be plain serializable types (records/primitives): no protobuf
  messages, no secrets. Long tool calls detach and report back as a trigger.
- The worker owns no database of the control plane; it fetches the run context in one
  `GetRunContext` RPC and re-reads content by id on replay.
- Two Jackson generations coexist in `control-api`: Spring Boot 4 injects Jackson 3 (`tools.jackson`),
  while `libs/common`'s `JsonUtils` is statically Jackson 2 (`com.fasterxml`). Injecting
  `com.fasterxml.jackson.databind.ObjectMapper` breaks startup.

## Tests

- JUnit 5 + Mockito only. No `@SpringBootTest`, `@DataJpaTest`, `@WebMvcTest` or Testcontainers —
  a test that needs a Spring context is a sign the code is not separated enough.
- `@ExtendWith(MockitoExtension.class)`, `@Mock` repositories, `@InjectMocks` for the class under
  test; package-private test classes; `@Nested` groups; Russian `@DisplayName`.
- Static imports from `Assertions` and `Mockito`; `@BeforeEach` for setup.
- Tests sit next to the feature in the same module; tests for a feature ship in the same `feat`
  commit.

## Time, logging, comments

- Time: `LocalDateTime` in the database is UTC; every service pins the JVM to UTC in `main()`, and
  the root Gradle build sets `user.timezone=UTC` for tests. A user-facing zone (cron `zone`, the
  agent's timezone) is converted explicitly; timestamps handed to an agent carry the zone.
- Logging: Lombok `@Slf4j`. Log ids (`agentId`, `connectionId`, `runId`), never credentials, tokens
  or user content.
- Comments are English and explain *why*. One line of prose is the default; a comment restating the
  next line is worse than none. `@param` on record components and where a constraint is invisible in
  the type; `@return` only when the signature does not say it. No javadoc task or doclint in the build.

## Do not

- Do not add Spring-context tests, H2 wiring or Testcontainers.
- Do not hand-write tool JSON schemas or hand-register connectors.
- Do not add a formatter, a new HTTP client, a mapping library or a new test framework for one change.
- Do not edit generated protobuf classes or anything under `build/`.
- Do not commit `services/.env`, `application-local.yaml` or rendered `ops/centrifugo/config.yaml`.
