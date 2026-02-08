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
./gradlew :connectors-api:test
./gradlew :libs:common:test

# Run a single test class
./gradlew :libs:common:test --tests "ru.agimate.common.util.UUIDUtilsTest"

# Run services locally
./gradlew :user-api:bootRun
./gradlew :device-api:bootRun
./gradlew :connectors-api:bootRun

# Create deployment JARs
./gradlew :user-api:bootJar
./gradlew :device-api:bootJar
./gradlew :connectors-api:bootJar
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
- **Response Wrappers**: Wrap responses with `SuccessResponse<T>`, errors with `ErrorResponse`
- **Entity IDs**: Use `UUIDUtils.generateUuidV8()` for public-facing identifiers
- **Endpoint Paths**: If endpoint returns a list, path should end with `/`
- **Migrations**: `updated_at` always NOT NULL + DEFAULT CURRENT_TIMESTAMP (matches BaseEntity)
- **Migrations**: Don't create explicit indexes on columns with UNIQUE constraint (PostgreSQL creates one automatically)
- **Migrations**: Composite business keys should be UNIQUE constraints, not just indexes
- **JPA Entities**: Duplicate unique constraints in `@Table(uniqueConstraints=...)` on the entity

## Adding New Connector

1. **Create DTOs** in `controller/dto/{connector}/`:
   - Request DTOs with `@Schema` and Jakarta validation
   - Response DTOs with `result` and `durationMs` fields

2. **Create Service** `{Connector}CallService.java`:
   - Manually create `ConnectorMethod` objects
   - Implement business logic using `ConnectorClient.execute()`

3. **Create Controller** `{Connector}CallController.java`:
   - Map endpoints under `/api/connectors/call/{connector}/`
   - Add OpenAPI annotations (`@Operation`, `@ApiResponses`, etc.)
   - Use `@Tag` for grouping in OpenAPI spec

4. **Create Client** implementing `ConnectorClient` interface

5. **Update ConnectorController** to add required credential fields to `CONNECTOR_REQUIRED_FIELDS` map

6. **Generate OpenAPI**: `./gradlew :connectors-api:generateOpenApi`

7. **Commit** the updated `openapi.json` to git

**Note**: The `openapi.json` must be committed — MethodController reads it at runtime for `/api/connectors/methods/{connectorCode}/`.

## OpenAPI Generation

```bash
# Generate OpenAPI specification
./gradlew :connectors-api:generateOpenApi
```

Output locations:
- `connectors-api/build/generated/openapi/openapi.json`
- `connectors-api/src/main/resources/static/openapi.json`
