# Local stack

One `compose.yaml`, three profiles. All of them share the same credentials and ports, and those
match the defaults in each service's `application.yaml` — so the everyday loop needs no `.env`.

| Profile | Contains | Use it for |
| --- | --- | --- |
| `infra` | PostgreSQL, Centrifugo | The daily loop: services run from the IDE or `gradle bootRun` |
| `edge` | Caddy | Adds the `*.agimate.lc` hostnames on `:8000` in front of the host services |
| `full` | infra + user-api, control-api, agent-worker | Smoke test that the images build and the stack starts |

```bash
cd ops

docker compose --profile infra up -d                  # postgres + centrifugo
docker compose --profile infra --profile edge up -d   # + caddy
docker compose --profile full up -d                   # everything in containers
```

## Everyday loop

```bash
cd ops && docker compose --profile infra up -d
cd ../services
./gradlew :user-api:bootRun
./gradlew :control-api:bootRun --args='--server.port=8180'
```

Both services default to `8080`, so control-api is moved to `8180` when they run side by side —
that is also the port the Caddyfile proxies `/control/*` to.

## Databases

`ops/postgres/init-databases.sh` creates all three on the first start of an empty volume:

| Database | Owner |
| --- | --- |
| `am_user_db` | user-api |
| `am_control_db` | control-api |
| `dbos` | DBOS system database — **shared**: control-api produces `run_agent`, agent-worker consumes it. Both must point here, or the queue silently has no consumer |

User `agimate`, password `agimate_dev_password`. To recreate from scratch:
`docker compose --profile infra down -v`.

## Keys

The stack needs **two independent** ES256 pairs, so run the generator twice:

```bash
./generate-jwt-keys.sh   # → JWT_PRIVATEKEY / JWT_PUBLICKEY          (user auth)
./generate-jwt-keys.sh   # → CENTRIFUGO_PRIVATEKEY / CENTRIFUGO_PUBLICKEY
```

Both pairs go into `services/.env`. For the second one the script also prints the public key in
PEM form: it goes into `client.token.ecdsa_public_key` in `ops/centrifugo/config.json`, because
control-api signs Centrifugo client tokens with `CENTRIFUGO_PRIVATEKEY` and Centrifugo verifies
them with that public half. The config ships with a `REPLACE_WITH_CENTRIFUGO_PUBLIC_KEY`
placeholder.

Every other value in `ops/centrifugo/config.json` is a local-development placeholder
(`dev_api_key`, `dev_admin_password`). Do not reuse them anywhere reachable.

## The `.lc` hostnames

The `edge` profile expects these in `/etc/hosts`:

```
127.0.0.1 www.agimate.lc api.agimate.lc centrifugo.agimate.lc
```

Caddy runs in a container while the services it fronts run on the host, so the Caddyfile targets
`host.docker.internal`. On Linux that name comes from the `host-gateway` mapping declared in
`compose.yaml`; on Docker Desktop it is built in.

## Ports

| | Host |
| --- | --- |
| PostgreSQL | 5432 |
| Centrifugo | 9000 → 8000 |
| Caddy | 8000 |
| user-api | 8080 |
| control-api | 8180 |
| agent-worker | — (headless) |

Under `full`, agent-worker shares control-api's network namespace: it refuses plaintext gRPC to
anything but loopback, and this keeps `localhost:9091` pointing at control-api without weakening
the check.
