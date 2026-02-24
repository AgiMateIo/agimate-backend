# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Build Commands

Working directory - services

```bash
cd services

# Build entire project
./gradlew build

# Run tests
./gradlew test

# Run a specific module's tests
./gradlew :user-api:test
./gradlew :device-api:test
./gradlew :libs:common:test

# Run a single test class
./gradlew :libs:common:test --tests "ru.agimate.common.util.UUIDUtilsTest"

# Run services locally
./gradlew :user-api:bootRun
./gradlew :device-api:bootRun

# Create deployment JARs
./gradlew :user-api:bootJar
./gradlew :device-api:bootJar
```

## Documentation

**Important**: After modifying endpoints, environment variables, or architecture:
- Update relevant files in `/docs/`
- Keep documentation in sync with code

See `/docs/` for:
- [Architecture](docs/architecture.md) — Services, databases, authentication flows
- [Deployment](docs/deploy.md) — Environment variables, key generation, ports
- [Services](../docs/services/) — API endpoints, configuration per service

## Key Patterns

- **Exception Handling**: Extend `BaseErrorHandlerControllerAdvice`. Use `NotFoundStatusException`, `BadRequestStatusException` for HTTP errors.
- **Response Wrappers**: Wrap responses with `SuccessResponse<T>` → `{ "response": <T> }`, errors with `ErrorResponse` → `{ "error": { "message": "..." } }`
- **Entity IDs**: Use `UUIDUtils.generateUuidV8()` for public-facing identifiers
- **Endpoint Paths**: If endpoint returns a list, path should end with `/`
- **Migrations**: `updated_at` always NOT NULL + DEFAULT CURRENT_TIMESTAMP (matches BaseEntity)
- **Migrations**: Don't create explicit indexes on columns with UNIQUE constraint (PostgreSQL creates one automatically)
- **Migrations**: Composite business keys should be UNIQUE constraints, not just indexes
- **Migrations format**: `updates/YYYY/MM/DD-00-name.xml` (00 — порядковый номер на дату, например `updates/2026/02/19-01-add-users.xml`)
- **JPA Entities**: Duplicate unique constraints in `@Table(uniqueConstraints=...)` on the entity
