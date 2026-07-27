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
# PostgreSQL + Centrifugo; see docs/operations/local-stack.md for the other profiles
cd ops && docker compose --profile infra up -d

cd ../services
./gradlew build
./gradlew :user-api:bootRun
./gradlew :control-api:bootRun --args='--server.port=8180'
```

Liquibase applies the schema on first start, and the stack defaults line up with the ones baked
into each `application.yaml` — so nothing else is needed to get the two APIs talking to the
database. For OAuth2 sign-in, JWT keys and connector credentials, copy
[`services/.env.example`](services/.env.example) to `services/.env`; every variable there is
documented in place.

To run everything in containers instead, including `agent-worker`:
`cd ops && docker compose --profile full up -d`.

## Documentation

[`docs/`](docs/) is organised by intent: `architecture/` (how it is put together and why),
`contracts/` (interfaces outside OpenAPI — the worker protocol, ACP, key formats),
`operations/` (run and deploy it), `connectors/`, and `decisions/`.

Start with [architecture/overview.md](docs/architecture/overview.md). Request and response schemas
are not in the docs — they are generated from the code, see
[the OpenAPI section](docs/README.md#начать-отсюда). Most of the documentation is in Russian.

## License

[Apache-2.0](LICENSE)
