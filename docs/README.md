# Agimate Documentation

Agimate is a platform where specialized AI agents work together: skills describe what an agent
can do, connectors give it tools, triggers and background jobs, and devices extend it beyond the
browser.

## Tech Stack

- **Java 21** with virtual threads
- **Spring Boot 4.0**
- **PostgreSQL 18** with Liquibase migrations
- **Centrifugo** for real-time messaging
- **gRPC** for the agent-worker protocol

## Quick Start

```bash
cd ops && docker compose --profile infra up -d    # PostgreSQL + Centrifugo

cd ../services
./gradlew build
./gradlew test
./gradlew :user-api:bootRun
./gradlew :control-api:bootRun --args='--server.port=8180'
```

See [`ops/README.md`](../ops/README.md) for the other stack profiles.

## Services and ports

| Service      | HTTP port | Context path | Notes                                        |
|--------------|-----------|--------------|----------------------------------------------|
| user-api     | 8080      | `/user/`     | Accounts, OAuth2, JWT                        |
| control-api  | 8080      | `/control`   | Agents, connectors, channels; gRPC on `:9091` |
| agent-worker | —         | —            | Headless DBOS queue consumer                 |

All HTTP services expose management endpoints on port **8088**.

## General

| Document | Description |
|---|---|
| [architecture.md](architecture.md) | Services, databases, authentication flows |
| [deploy.md](deploy.md) | Environment variables, key generation, configuration |
| [ci-overview.md](ci-overview.md) | Build and deploy pipeline |
| [api-key-format.md](api-key-format.md) | Positional format of the keys the platform issues |
| [agent-context-design.md](agent-context-design.md) | How an agent's context is assembled |
| [agimate-worker-protocol-spec.md](agimate-worker-protocol-spec.md) | control-api ↔ agent-worker gRPC contract |
| [acp-review-backend.md](acp-review-backend.md) | Agent Communication Protocol vs this architecture |
| [refactoring-uuid-pk.md](refactoring-uuid-pk.md) | Historical RFC: UUIDv7 primary keys |

## Services

| Document | Description |
|---|---|
| [user-api.md](services/user-api.md) | Endpoints and configuration |
| [control-api.md](services/control-api.md) | Endpoints and configuration |
| [agent-worker.md](services/agent-worker.md) | Agent loop, DBOS queue, deployment |
| [control-api-grpc-worker.md](services/control-api-grpc-worker.md) | gRPC server for the worker protocol |
| [control-api-acp.md](services/control-api-acp.md) | ACP WebSocket endpoint for IDE clients |
| [agent-channels-integration.md](services/agent-channels-integration.md) | Channels and trigger routing |
| [control-api-trigger-log-probe.md](services/control-api-trigger-log-probe.md) | Trigger matching probe |

## API reference

Request and response schemas are not duplicated here — they come from the code. Run either
service under the `develop` profile and open the OpenAPI UI:

```bash
./gradlew :control-api:bootRun --args='--spring.profiles.active=develop --server.port=8180'
# → http://localhost:8180/control/docs/ui
```

The `local` profile (the default) keeps `springdoc.swagger-ui.enabled` and
`springdoc.api-docs.enabled` off, and `application-prod.yaml` disables them explicitly.

## Connectors

| Document | Description |
|---|---|
| [architecture.md](connectors/architecture.md) | Connector SPI, capabilities, registry |
| [files.md](connectors/files.md) | File layer, `agf_` references |
| [platform.md](connectors/platform.md) | Meta-agent managing the platform |
| [persistent-memory.md](connectors/persistent-memory.md) | Agent long-term memory |
| [sheets.md](connectors/sheets.md) | Agent-owned tables |
| [webchat.md](connectors/webchat.md) · [media.md](connectors/media.md) · [astro-divination.md](connectors/astro-divination.md) | Individual connectors |
| [mail.md](connectors/mail.md) · [terminal.md](connectors/terminal.md) | Design notes for connectors that are **not implemented** |

Parts of the documentation are written in Russian.
