# Contributing to AgiMate Backend

Thanks for taking the time. This page covers what you need before opening a pull request.

## Contributor License Agreement

Every contributor signs the [CLA](CLA.md) once, on their first pull request. A bot comments
with instructions; you reply in the pull request and it records the signature. Nothing to
print, sign or email.

The CLA lets the project be distributed under terms other than Apache-2.0 in the future
(a commercial licence alongside the open one). Without it that door closes permanently the
moment the first outside change lands, because reopening it would need every contributor's
consent. You keep full ownership of your contribution either way.

## Reporting a security issue

Do not open an issue. See the
[security policy](https://github.com/AgiMateIo/.github/blob/main/SECURITY.md).

## Getting the project running

The [README](README.md) has the quick start: Docker Compose for PostgreSQL and Centrifugo,
then Gradle. Everything below assumes you are in `services/`.

```bash
./gradlew build                       # compile and test everything
./gradlew :control-api:test           # one module
./gradlew :libs:common:test --tests "ru.agimate.common.util.UUIDUtilsTest"
```

[`docs/`](docs/) is organised by intent — start with
[architecture/overview.md](docs/architecture/overview.md). Note that most of the
documentation is written in Russian; the code and its comments are in English.

## Conventions worth knowing before you write code

These are the ones a newcomer trips over. The rest you can read off the surrounding code.

- **Comments are in English**, and they explain *why*, not *what*. A comment restating the
  next line is worse than no comment. Russian appears in code only as data — a quoted
  literal, or a non-ASCII example where the example is the point.
- **Layout**: `controller` → `service` → `database/{repositories,entities}`, plus `config`,
  `security`, `util`. In `control-api` controllers are split by audience
  (`agent/`, `app/`, `manage/`, `webhook/`) and DTOs live per area.
- **Dependency injection** through `@RequiredArgsConstructor`, never `@Autowired`.
- **DTOs are records**: requests carry validation annotations, responses carry `@Schema`.
- **Responses are wrapped**: `SuccessResponse<T>` → `{ "response": <T> }`, errors →
  `ErrorResponse`. Throw `NotFoundStatusException`, `BadRequestStatusException` and friends
  at the HTTP boundary; inside the connector layer throw `ConnectorException` instead.
- **Entities**: `@Enumerated(EnumType.STRING)`, `FetchType.LAZY`, `TEXT` over `VARCHAR(n)`,
  and `UUIDUtils.generateUUIDv8()` for public identifiers.
- **Migrations** go to `updates/YYYY/MM/DD-NN-name.xml`. `updated_at` is always
  `NOT NULL DEFAULT CURRENT_TIMESTAMP`. Add a `<comment>` wherever the DDL does not explain
  itself — a partial unique index, a `COALESCE` in a key, a denormalisation. The schema shows
  what is true now and `git blame` shows when it changed, but neither records why.
- **Tests** are JUnit 5, grouped with `@Nested` and named with `@DisplayName`.

## Commits

```
<type>: <object> — <delta>
```

`<type>` is one of `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `chore`. Whether a
change is a feature or a refactor is decided by whether it is visible from outside — to the
API, the agent or the user — not by how much code moved.

`<object>` is a noun and comes first: the entity, connector, endpoint or table someone will
later grep the history for. `<delta>` is the result, not the action — "soft delete for agents
instead of hard delete", not "fixed deletion".

Keep the subject at 72 characters or less, lower case after the colon, no trailing period,
no scope in parentheses. If you need an "and" in the object, the commit wants splitting. An
optional body of two to four bullets explains why it was done this way and what follows from
it — not which files changed.

## Pull requests

Small and focused beats large and complete. Make sure `./gradlew build` passes, say what
you changed and why in the description, and update `docs/` if you touched endpoints,
environment variables or architecture — request and response schemas are generated from the
code and are never duplicated there.
