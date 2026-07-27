# AgiMate Backend

Backend services for [AgiMate](https://agimate.io) — a platform where specialized AI agents work
together, on your own server and your own keys.

## Modules

Everything lives under [`services/`](services/) as a single Gradle build.

| Module | What it does |
| --- | --- |
| `user-api` | Accounts, OAuth2 sign-in, JWT issuing and refresh |
| `control-api` | Agents, skills, connectors, channels and triggers, boards, LLM providers. Also serves the agent-worker gRPC protocol on `:9091` and an ACP WebSocket endpoint for IDE clients |
| `agent-worker` | Headless agent loop — consumes runs from a DBOS queue and drives the conversation with the model |
| `libs/common` | Shared security, JWT and utility code |
| `libs/agentworker-proto` | Protobuf/gRPC stubs shared by `control-api` and `agent-worker` |

## Stack

Java 21 (virtual threads) · Spring Boot 4 · PostgreSQL 18 · Liquibase · gRPC · Centrifugo

## Quick start

```bash
cd services
cp .env.example .env    # every variable is documented in the file

# generate the ES256 key pair and put the two values
# into JWT_PRIVATEKEY / JWT_PUBLICKEY in .env
../ops/generate-jwt-keys.sh

# PostgreSQL + Centrifugo
docker compose -f docker/docker-compose.yml up -d postgres centrifugo

./gradlew build
./gradlew :user-api:bootRun
./gradlew :control-api:bootRun
```

Liquibase applies the schema on first start.

`agent-worker` additionally needs a DBOS system database, which the compose file above does not
create yet — see [`services/agent-worker/.env.example`](services/agent-worker/.env.example) for its
configuration.

## Documentation

[`docs/`](docs/) — start with [architecture](docs/architecture.md) and
[deployment](docs/deploy.md). Also: [connectors](docs/connectors/architecture.md),
[worker protocol](docs/agimate-worker-protocol-spec.md), and per-service API reference under
[`docs/services/`](docs/services/). Parts of the documentation are written in Russian.

## License

[Apache-2.0](LICENSE)
