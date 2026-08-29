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
cd ops
./dev-init.sh                    # generates the keys and the local configuration
docker compose --profile infra up -d   # PostgreSQL + Centrifugo

cd ../services
./gradlew build
./gradlew :user-api:bootRun
./gradlew :control-api:bootRun
```

`dev-init.sh` generates every key the stack needs — user JWT, Centrifugo, worker pool, encryption
keys — writes them to `services/.env` and renders the configs that read from it. Re-running it
fills in what is missing without rotating what is already there. The only thing it cannot invent
is OAuth2 credentials: without them the services still start, only the sign-in does not work.
Liquibase applies the schema on first start.

Details and the compose profiles: [docs/operations/local-stack.md](docs/operations/local-stack.md).

To run everything in containers instead, including `agent-worker`:
`cd ops && docker compose --profile full up -d`.

## Documentation

[`docs/`](docs/) is organised by intent: `architecture/` (how it is put together and why),
`contracts/` (interfaces outside OpenAPI — the worker protocol, ACP, key formats),
`operations/` (run and deploy it), `connectors/`, and `decisions/`.

Start with [architecture/overview.md](docs/architecture/overview.md). Request and response schemas
are not in the docs — they are generated from the code, see
[the OpenAPI section](docs/README.md#начать-отсюда). Most of the documentation is in Russian.

## Related repositories

- [agimate-frontend](https://github.com/AgiMateIo/agimate-frontend) — the web dashboard for these services
- [agimate-chat-android](https://github.com/AgiMateIo/agimate-chat-android) — Android chat client for your agents
- [connector-desktop](https://github.com/AgiMateIo/connector-desktop) — cross-platform system tray agent
- [connector-android](https://github.com/AgiMateIo/connector-android) — Android companion agent
- [n8n-nodes-agimate](https://github.com/AgiMateIo/n8n-nodes-agimate) — n8n community nodes

## Contributing

Pull requests are welcome — [CONTRIBUTING.md](CONTRIBUTING.md) covers the setup, the
conventions and the commit format. Contributors sign the [CLA](CLA.md) once, on their first
pull request, by replying to a bot comment.

Found a security problem? Do not open an issue — follow the
[security policy](https://github.com/AgiMateIo/.github/blob/main/SECURITY.md).

## License

[Apache-2.0](LICENSE)
