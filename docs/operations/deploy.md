# Deployment

## Environment Variables

### JWT Keys (All Services)

| Variable          | Description                                     | Required      |
|-------------------|-------------------------------------------------|---------------|
| `JWT_PRIVATEKEY`  | Base64-encoded ECDSA P-256 private key (PKCS#8) | user-api only |
| `JWT_PUBLICKEY`   | Base64-encoded ECDSA P-256 public key (X.509)   | All services  |

### OAuth2 (user-api)

| Variable                          | Description                                          |
|-----------------------------------|------------------------------------------------------|
| `GOOGLE_CLIENT_ID`                | Google OAuth2 client ID                              |
| `GOOGLE_CLIENT_SECRET`            | Google OAuth2 client secret                          |
| `YANDEX_CLIENT_ID`                | Yandex OAuth2 client ID                              |
| `YANDEX_CLIENT_SECRET`            | Yandex OAuth2 client secret                          |
| `APP_OAUTH_COOKIE_ENCRYPTION_KEY` | AES-256 key for OAuth2 cookies (Base64, 32 bytes)    |
| `APP_OAUTH_COOKIE_DOMAIN`         | Default cookie domain for refresh tokens             |
| `APP_OAUTH_COOKIE_SECURE`         | `true` for production (HTTPS)                        |
| `APP_OAUTH_FRONTEND_REDIRECT_URL` | Default frontend redirect URL after OAuth2 login     |
| `APP_OAUTH_ALLOWED_REDIRECT_URLS` | Comma-separated whitelist for multi-domain redirects |

### Secrets store (control-api)

| Variable                     | Description                                                                                    |
|------------------------------|------------------------------------------------------------------------------------------------|
| `APP_SECRETS_ENCRYPTION_KEY` | KEK for the envelope-encrypted `secrets` store (AES-256, Base64, 32 bytes). Required outside `local`/`test` profiles — startup fails without it |

### File storage (control-api)

Blob store for the connector file layer (`docs/connectors/files.md`).

| Variable               | Description                                                          |
|------------------------|----------------------------------------------------------------------|
| `APP_FILES_BACKEND`    | `local` (disk, default — dev/single-node) or `s3`                    |
| `APP_FILES_LOCAL_DIR`  | Root dir for the local backend; empty = `~/.agimate/files`           |
| `APP_FILES_BUCKET`     | s3 only: bucket name (default `agimate-files`)                       |
| `APP_FILES_ENDPOINT`   | s3 only: S3-compatible endpoint (MinIO etc.); empty = AWS            |
| `APP_FILES_REGION`     | s3 only: region (default `us-east-1`)                                |
| `APP_FILES_ACCESS_KEY` | s3 only: access key; empty (with empty secret) = AWS credentials chain |
| `APP_FILES_SECRET_KEY` | s3 only: secret key                                                  |
| `APP_FILES_URL_SECRET` | HMAC secret for signed file links (`GET /files/…?exp&sig`, webchat attachments). Required outside `local`/`test` profiles — startup fails without it; dev fallback is a random per-boot key |
| `APP_FILES_URL_TTL`    | Signed link lifetime (default `15m`)                                 |

### Centrifugo (control-api)

| Variable                | Description                            |
|-------------------------|----------------------------------------|
| `CENTRIFUGO_APIKEY`     | Centrifugo HTTP API key                |
| `CENTRIFUGO_PRIVATEKEY` | Centrifugo JWT signing private key     |
| `CENTRIFUGO_PUBLICKEY`  | Centrifugo JWT verification public key |

### Generic Worker gRPC (control-api)

| Variable                                | Description                                                       |
|-----------------------------------------|-------------------------------------------------------------------|
| `GRPC_SERVER_ENABLED`                   | Enable gRPC server (`true`/`false`)                               |
| `GRPC_SERVER_PORT`                      | gRPC server port (default `9091`)                                 |
| `GRPC_SERVER_SECURITY_ENABLED`          | Enable TLS (must be `true` in production)                         |
| `GRPC_SERVER_SECURITY_CERTIFICATECHAIN` | Path to PEM certificate chain                                     |
| `GRPC_SERVER_SECURITY_PRIVATEKEY`       | Path to PEM private key                                           |
| `WORKER_POOLS_AUTHKEYS_0`, ..._N        | Worker pool authkeys, one per pool. See `docs/contracts/worker-protocol.md`. |

### DBOS (control-api)

| Variable                        | Description                                                    |
|---------------------------------|----------------------------------------------------------------|
| `DBOS_ENABLED`                  | Enable DBOS delivery of agent runs (`true`/`false`)            |
| `DBOS_SYSTEM_DATABASE_URL`      | JDBC URL of the DBOS system Postgres (shared with agent-worker) |
| `DBOS_SYSTEM_DATABASE_USERNAME` | DBOS Postgres user                                             |
| `DBOS_SYSTEM_DATABASE_PASSWORD` | DBOS Postgres password                                         |
| `DBOS_SYSTEM_DATABASE_SCHEMA`   | DBOS schema (default `dbos`)                                   |

**Startup order on a `dev.dbos:transact` upgrade: agent-worker first, then control-api.** Only the
worker's DBOS runtime migrates the shared system schema at launch; control-api's `DBOSClient` never
migrates and fails enqueues until the schema is current.

### agent-worker

Full list with defaults: `services/agent-worker/.env.example`.

| Variable                          | Description                                                        |
|-----------------------------------|--------------------------------------------------------------------|
| `AGENT_GRPC_TARGET`               | control-api worker gRPC endpoint (default `localhost:9091`)        |
| `AGENT_GRPC_USE_TLS`              | Enable TLS to control-api (`true` in production)                   |
| `AGENT_GRPC_CA_CERT`              | PEM CA cert path for TLS verification (optional)                   |
| `AGENT_GRPC_AUTH_TOKEN`           | Worker-pool authkey (Bearer), must match a control-api pool key    |
| `AGENT_AGENT_ID`                  | Worker/agent deployment id                                         |
| `AGENT_AGENT_WORKFLOW_ID`         | Workflow id sent on AgentContext RPCs                              |
| `AGENT_CONCURRENCY_AGENT_RUNS`    | Per-worker concurrency of the run-router queue (default 3)         |
| `AGENT_CONCURRENCY_LLM`           | Concurrent model requests per worker (default 3)                   |
| `AGENT_CONCURRENCY_TOOL`          | Concurrent backend tool calls per worker (default 8)               |
| `AGENT_SESSION_ON_ACTIVE_MESSAGE` | Policy on a message into an active session: `queue`/`steer`/`interrupt` |
| `AGENT_DBOS_DATABASE_URL`         | JDBC URL of the DBOS system Postgres (same as control-api's)       |
| `AGENT_DBOS_USERNAME`             | DBOS Postgres user                                                 |
| `AGENT_DBOS_PASSWORD`             | DBOS Postgres password                                             |
| `AGENT_DBOS_SCHEMA`               | DBOS schema (default `dbos`)                                       |

## Key Generation

### JWT Keys (ES256)

```bash
openssl ecparam -name prime256v1 -genkey -noout -out ec-key.pem
openssl pkcs8 -topk8 -nocrypt -in ec-key.pem -out ec-private.pem
openssl ec -in ec-key.pem -pubout -out ec-public.pem
```

`JWT_PRIVATEKEY` / `JWT_PUBLICKEY` take the base64 body without the PEM headers, in one line.
For a local stack the same keys are generated by `ops/dev-init.sh` — see
[local-stack.md](local-stack.md).

### AES-256 Keys (Encryption)

```bash
# Generate random 32-byte key, Base64 encoded
openssl rand -base64 32
```

Use for:
- `APP_OAUTH_COOKIE_ENCRYPTION_KEY`
- `APP_SECRETS_ENCRYPTION_KEY`

### Centrifugo Keys

Centrifugo uses the same ES256 key format as JWT. Generate using the JWT key generation steps above.

## Ports

| Port | Service              | Purpose                                       |
|------|----------------------|-----------------------------------------------|
| 8080 | All                  | HTTP API                                      |
| 8088 | Web services         | Management (health)                           |
| 8089 | agent-worker         | Management (health) — see below               |
| 9090 | user-api             | gRPC server for internal s2s interactions     |
| 9091 | control-api           | gRPC server for Generic Worker protocol (TLS) |

agent-worker publishes no ports: it consumes DBOS queues from Postgres and dials out to control-api :9091. Its embedded server carries `/actuator/health` and nothing else, so the container health check matches the other two services; the port differs from 8088 because in compose the worker shares control-api's network namespace.

control-api also serves the ACP WebSocket endpoint `/acp` on the main HTTP port (8080) — the ingress/reverse proxy in front of control-api must allow WebSocket upgrade on this path.

## Spring Profiles

| Profile   | Description                                     |
|-----------|-------------------------------------------------|
| `local`   | Development with debug logging, Swagger enabled |
| `develop` | Development environment                         |
| `test`    | Test configuration                              |
| `prod`    | Production: Swagger off, JSON logs              |

Swagger UI available at `/{context-path}/docs/ui` when enabled (local profile).

## Логи

Логи идут только в stdout — файловых аппендеров нет ни у одного сервиса, за сбор и ротацию
отвечает рантайм (в проде — инфраструктурный репозиторий, не этот). Общий `logback-spring.xml`
всех трёх сервисов сводится к `include` одного файла в `libs/common`; профиль `local` печатает
всё от `debug`, остальные — то, что разрешают уровни.

### Формат

Текст или JSON решает профиль стенда, разновидность JSON — переменная окружения (см. ниже):

| Профиль                        | Формат                                           |
|--------------------------------|--------------------------------------------------|
| `local`                        | Цветной паттерн с `logger:line`                   |
| `!local & !prod` (`develop`, `test`, любой неназванный) | Цветной паттерн без `logger:line` |
| `prod`                         | **JSON, одна строка = один объект**               |

Средняя ветка записана отрицанием намеренно: три ветки обязаны покрывать все профили, иначе
профиль, не попавший ни в одну, останется вообще без аппендера — а сервис, который молчит,
это единственный отказ здесь, который выглядит как успех.

Даёт JSON штатный `StructuredLogEncoder` Spring Boot — отдельной зависимости вроде
`logstash-logback-encoder` не требуется. Формат выбирается пропертёй
`logging.structured.format.console` (в k8s — `LOGGING_STRUCTURED_FORMAT_CONSOLE`), пересборка для
смены не нужна:

| Значение             | Что получится                                                                                  |
|----------------------|------------------------------------------------------------------------------------------------|
| не задано → **`ecs`** | `@timestamp`, `log.level`, `log.logger`, `message`, `service.name`, `error.stack_trace`         |
| `logstash`           | `@timestamp`, `@version`, `level`, `level_value`, `logger_name`, `message`                       |
| `gelf`               | GELF 1.1: `short_message`, `host`, числовой `level`, остальное префиксом `_`                     |

**Дефолт задан через `<springProperty defaultValue="ecs">`, а не через `${CONSOLE_LOG_STRUCTURED_FORMAT}`
из `defaults.xml` Spring Boot — и это не стилистика.** Boot определяет свою переменную как пустую
строку, когда проперть не задана, а `StructuredLogEncoder.start()` проверяет только `!= null`:
пустая строка доходит до фабрики форматтеров, инициализация Logback падает целиком, и сервис
**не стартует** («Could not initialize Logback logging from classpath:logback-spring.xml»). То есть
прямая подстановка сделала бы проперть обязательной для запуска.

Отдельного профиля логирования (`prod,log-logstash`) намеренно нет: профиль — глобальный
строковый переключатель, под который заводится `application-log-logstash.yaml` и `@Profile(...)`
на бинах, а нужна здесь одна строка. Проперть выше уже даёт ту же ручку, валидируется Spring Boot
и не создаёт второго механизма, который может разойтись с первым. Профили остаются про стенд:
`local`, `develop`, `prod`.

Два следствия, ради которых это и сделано: **стектрейс едет одним событием**, а не N строками,
которые сборщик склеивает эвристикой; и **MDC становится полями верхнего уровня** —
`x-request-id` (см. `RequestIdFilter`), `run` (прогон агента в agent-worker), `jobKey`
(джоба коннектора) можно фильтровать, а не грепать.

Уровни задаются **только** через `logging.level.*` в yaml'ах: `root: warn` глушит Spring,
Hibernate и Tomcat, `ru.agimate: info` (в agent-worker — `ru.agimate.agentworker: info`) оставляет
события приложения: старт прогона агента, вызов тула, генерацию медиа со стоимостью, смену роли
пользователя.

До 2026-07-30 у аппендера для не-`local` профилей стоял `ThresholdFilter` на `WARN`, который
перекрывал эти уровни: `info` в конфиге разрешал событие, а единственный приёмник его выбрасывал,
и в проде не было видно ни одной INFO-строки. Фильтр снят — если в проде вдруг снова пропадут
INFO-логи, смотреть надо сюда, а не в уровни.

## Database Configuration

Each service connects to its own PostgreSQL database:

| Service        | Database         | Default URL                                         |
|----------------|------------------|-----------------------------------------------------|
| user-api       | am_user_db       | `jdbc:postgresql://localhost:5432/am_user_db`       |
| control-api     | am_control_db     | `jdbc:postgresql://localhost:5432/am_control_db`     |

Configure via standard Spring datasource properties or environment variables:
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

## Building for Production

```bash
# Create deployment JARs
./gradlew :user-api:bootJar
./gradlew :control-api:bootJar
./gradlew :agent-worker:bootJar
```

JARs are created in `services/{service}/build/libs/`.

## Running Centrifugo

Centrifugo is used for real-time messaging to devices.

### Local Development

Centrifugo is part of the local stack — see [`local-stack.md`](local-stack.md):

```bash
cd ops
docker compose --profile infra up -d
```

Configuration file: `ops/centrifugo/config.yaml`, rendered by `ops/dev-init.sh` from
`ops/templates/centrifugo.config.yaml`. control-api signs Centrifugo client tokens with
`CENTRIFUGO_PRIVATEKEY` (ES256), so `client.token.ecdsa_public_key` must hold the matching
`CENTRIFUGO_PUBLICKEY` in PEM form. This is a pair of its own, independent of the user-JWT one.

### Ports

| Port | Purpose                |
|------|------------------------|
| 9000 | WebSocket/HTTP API     |

Admin UI available at `http://localhost:9000` (credentials in `config.yaml`).

### Configuration

Key configuration options in `config.yaml`:

| Field                             | Description                                 |
|-----------------------------------|---------------------------------------------|
| `client.token.ecdsa_public_key`   | Public key for verifying client JWT tokens  |
| `http_api.key`                    | API key for server-to-server communication  |
| `admin.password`                  | Admin UI password                           |
| `channel.namespaces`              | Channel namespace configuration             |

### Channel Namespaces

| Namespace | Pattern                                    | Description                        |
|-----------|--------------------------------------------|------------------------------------|
| `app`     | `app:{appId}`                              | Tool-call push to device apps      |
| `agent`   | `agent:{apiKeyPubId}`                      | Agent events (tool results, triggers) |
| `user`    | `user:{userId}`                            | User events for the management UI  |
| `webchat` | `webchat:{sessionId}`                      | Webchat session messages (history+recovery enabled) |

- Publish/Subscribe restricted to server-side only
- History: 100 messages, 24h TTL

### Production Deployment

For production, ensure:
1. Generate new keys (do not use development keys)
2. Set `CENTRIFUGO_APIKEY` in control-api to match `http_api.key`
3. Set `CENTRIFUGO_PUBLICKEY` in control-api to match `client.token.ecdsa_public_key`
4. Configure `allowed_origins` appropriately
5. Disable admin UI or use strong credentials
