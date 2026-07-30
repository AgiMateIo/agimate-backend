# control-api

Control API for connector registration, tool delivery, trigger submission, and AI agent integration.

## Configuration

| Setting         | Value        |
|-----------------|--------------|
| Port            | 8080         |
| Context Path    | `/control`    |
| Management Port | 8088         |
| Database        | am_control_db |

## Authentication

| Mechanism | Header | Scope |
|-----------|--------|-------|
| **Connector Auth** | `X-App-Auth-Key: <key>` | `/app/**` — device/connector endpoints |
| **API Key** | `X-Api-Key: <key>` | `/agent/**` — agent API endpoints |
| **JWT** | `Authorization: Bearer <jwt>` | `/manage/**` — management endpoints (`/manage/admin/**` — ADMIN only, see [Админский раздел](#админский-раздел-manageadmin)) |
| **Public** | — | `/`, `/webhook/**`, `/actuator/health` |

## Environment Variables

| Variable                | Description                       |
|-------------------------|-----------------------------------|
| `JWT_PUBLICKEY`         | ECDSA public key (Base64, X.509)  |
| `CENTRIFUGO_APIKEY`     | Centrifugo HTTP API key           |
| `CENTRIFUGO_PRIVATEKEY` | Centrifugo JWT private key        |
| `CENTRIFUGO_PUBLICKEY`  | Centrifugo JWT public key         |
| `APP_CONTENT_LANGUAGE`  | System content language of the installation: `ru` (default) or `en`. See [architecture/content-language.md](../architecture/content-language.md) |
| `APP_SECRETS_ENCRYPTION_KEY` | KEK for the envelope-encrypted `secrets` store (AES-256, Base64, 32 bytes). Required outside `local`/`test` profiles — startup fails without it |
| `APP_INTEGRATION_WEBHOOK_BASE_URL` | Public URL for webhook callbacks |
| `INBOUND_RATE_LIMIT_ENABLED` | Inbound rate limiting for device/webhook traffic (default `true`) |
| `INBOUND_RATE_LIMIT_TRIGGERS_PER_MINUTE` | Trigger events per minute per connection — `/app/trigger/new` + `/webhook/*` (default `120`, `<=0` disables) |
| `INBOUND_RATE_LIMIT_TOOL_RESULTS_PER_MINUTE` | Tool results per minute per connection — `/app/tools/result` (default `120`, `<=0` disables) |
| `INBOUND_RATE_LIMIT_FILE_UPLOADS_PER_MINUTE` | File uploads per minute per connection — `/app/files` (default `30`, `<=0` disables) |
| `APP_FILES_BACKEND` | Connector file layer blob store: `local` (disk, default; root — `APP_FILES_LOCAL_DIR`, empty = `~/.agimate/files`) or `s3` (`docs/connectors/files.md`) |
| `APP_FILES_BUCKET` / `APP_FILES_ENDPOINT` / `APP_FILES_REGION` / `APP_FILES_ACCESS_KEY` / `APP_FILES_SECRET_KEY` | s3 backend only; empty endpoint = AWS, empty keys = AWS credentials chain |

## Inbound Rate Limiting

Trigger and tool-result ingestion from external sources is rate-limited per connection (token bucket, in-memory, burst = the per-minute limit). The key is `connectionId` — for device apps `app.id == connection.id`, for webhooks it is the path parameter, so all inbound surfaces share one mechanism:

- `/app/trigger/new`, `/app/tools/result` — over-limit requests get **429** `{ "error": { "message": "..." } }`; the device should back off.
- `/webhook/{connectionId}` — over-limit requests are dropped silently with **200** `ok` (the source is unauthenticated, and webhook platforms endlessly retry non-2xx responses). The drop is logged.

## API reference

Paths, request and response schemas are generated from the code. See Swagger at
**`/control/docs/ui`** (`develop` profile) — the auth contour for each prefix is in the
[Authentication](#authentication) table above.

Common error envelope for every group:

| Status | Meaning |
|--------|---------|
| 401 | Missing or invalid credential |
| 403 | Authenticated but not authorized |
| 429 | Inbound rate limit exceeded (see above) |

## Каталог LLM-провайдеров

`GET /manage/llm-providers/catalog/` отдаёт известные шлюзы, которыми фронт предзаполняет форму
добавления провайдера: `base_url`, диалект генерации картинок (`media_transport`), модели на старт
и ссылку, где взять ключ. Пользователь дописывает только имя и API-ключ.

Каталог существует ради двух полей, которых пользователь знать не может: точной формы `base_url`
(`https://openrouter.ai/api/v1` — с `/v1`, без хвостового слэша) и транспорта медиа, который у
двух `OPENAI_COMPATIBLE`-шлюзов разный. **В резолве он не участвует никогда** — заполняет форму и
на этом заканчивается, источник истины для работающего провайдера остаётся строкой `llm_providers`.
Этим он отличается от таблицы префиксов `base_url`, отложенной в
[decisions/media-transport.md](../decisions/media-transport.md): без сопоставления по URL нет ни
нормализации, ни молчаливых промахов, а ошибка ловится, пока форма ещё открыта.

Записи сидятся из `resources/seed/llm-providers.yaml` при каждом старте, upsert по `code`.
Владение разделено:

| Что | Кто владеет | Следствие |
|---|---|---|
| Содержимое (`base_url`, модели, транспорт, тексты) | сид | протухший id модели чинится деплоем, а не миграцией данных |
| `enabled` | инсталляция | отключённая рекомендация остаётся отключённой после апгрейда |
| Строка с кодом вне сида | инсталляция | обход идёт по файлу, а не по таблице — корпоративный шлюз не трогают |

Правка остальных полей руками в БД будет затёрта следующим стартом: таблица здесь переключатель,
а не редактор.

В каталоге только то, что работает целиком: агентский цикл говорит на OpenAI-диалекте
(`ModelFactory` в agent-worker), поэтому `ANTHROPIC` и `GEMINI` — которые дискавери поддерживает —
обещали бы агента, который не запустится. Проверяется тестом `LlmCatalogSeedTest`.

Описания переводятся через `seed/texts/<lang>/llm-providers.properties` (ключ `<code>.description`);
названия — бренды и не переводятся. Английский текст в YAML — первоисточник и фолбэк, поэтому
файла для `en` нет, как и у коннекторов.

### Снапшот возможностей моделей

`llm_model_defaults` — курируемый фолбэк для провайдеров, чей `/models` отдаёт голые id без
метаданных (OpenAI, Anthropic). Накладывается по полям в `refresh-models`, значение из дискавери
всегда бьёт снапшот, отсутствие строки означает «неизвестно» — ровно как до появления таблицы.

Снапшот живёт в `resources/seed/llm-models.yaml` и сидится тем же способом, что и каталог:
`LlmModelDefaultsBootstrap` делает upsert по `model` на каждом старте, записи вне файла не трогает,
а совпавшие не переписывает — иначе несколько сотен UPDATE'ов на каждый старт обнулили бы смысл
`updated_at`. Раньше это был INSERT-блок в `initial-28` с `ON CONFLICT DO NOTHING`: снапшот
застывал на том, чем инсталляцию засеяли впервые, и сдвинуть его могла только миграция данных.
Перезапись безопасна — в таблицу никто не пишет в рантайме, а фолбэк применяется по полям.

## Админский раздел `/manage/admin`

Всё под этим префиксом требует роль `ADMIN`; префикс — единственный гейт. Он же целиком (а не
поимённо, как остальные manage-контроллеры) добавлен в `securityMatcher` JWT-цепочки, поэтому новый
админский эндпойнт не требует правки `SecurityConfig` — и не может уехать в прод без роли.
Обратная сторона: контроллер, повешенный на путь вне префикса, теряет гейт молча, поэтому
принадлежность пакета и пути сверяет `ManageAdminSectionTest`. `@PreAuthorize` в контроллерах
раздела намеренно нет — иначе гейт был бы в двух местах, и не было бы видно, какое из них главное.

Пока в разделе один эндпойнт — расход токенов произвольного пользователя
(`GET /manage/admin/llm-usage/{userId}/`). Форма ответа та же, что у пользовательского
`/manage/llm-usage/`: по провайдеру на строку, текущие окна DAY/MONTH, использованные токены,
запросы и остаток квоты. Неизвестный `userId` — не ошибка: справочником пользователей владеет
user-api, а control-api видит только `user_id` в своих счётчиках, поэтому ответ на такой запрос —
платформенный провайдер с нулями.

Две вещи, которых здесь нет намеренно:

- **Денег нет, есть токены.** Цена вызова в `llm_usage_log` не фиксируется, а считать её задним
  числом по текущему прайсу — значит переписывать историю при каждом изменении цен у шлюза.
- **Истории нет, есть текущие окна.** Произвольный период считается по `llm_usage_log`
  (`group by`), счётчики дают только календарные DAY/MONTH.

Смена роли пользователя живёт в user-api ([user-api.md](user-api.md)) — там же и оговорка про
задержку: control-api берёт роль из claim'а access-токена, поэтому понижение доходит до него
только со следующим refresh.

## Tool invocation

A tool call never executes inside the request that asked for it — the caller gets an id and the
result arrives asynchronously. This is what lets a tool run on a device the platform does not
control, and it is why every tool call has a log row:

1. The agent asks control-api to invoke a tool on a connection.
2. control-api authorizes it against ABAC (`ConnectionAccessEvaluator`, `PolicyKind.TOOL`),
   writes a `tool_call_logs` row and returns its id.
3. Execution is dispatched by connector kind — internal connectors run in-process, integrations
   call the platform API, device connectors get a Centrifugo push.
4. The executor reports the result back; the log row is completed and the result is delivered to
   the agent over its channel.

Backend-side jobs follow the same shape through `connector_jobs`, claimed with
`FOR UPDATE SKIP LOCKED` (`docs/architecture/connectors.md`).

## Inbound triggers

Inbound events — a device trigger, a platform webhook, a message in a channel — converge on one
path: `TriggerRouterService` decides **which** agents get the event (bindings plus ABAC with
`PolicyKind.TRIGGER` and an optional `params_filter`), and the channel decides **how** the
conversation is conducted (message extraction, chat filtering, reply). Policy and channel are
deliberately separate layers; see
[agent-channels-integration.md](../architecture/channels-and-triggers.md).

## Database

Migrations: `services/control-api/src/main/resources/db/changelog/`.

| Area | Tables |
|---|---|
| Agents | `agents`, `agent_presets`, `agent_skills`, `agent_llms`, `agentic_teams`, `skills` |
| Runs | `agent_runs`, `agent_run_turns`, `trigger_logs`, `tool_call_logs`, `webhook_delivery_logs` |
| Connections | `connectors`, `connections`, `connection_tools`, `connection_triggers`, `agent_connections`, `agent_connection_policies`, `connector_jobs` |
| Channels | `channels`, `channel_sessions`, `channel_session_messages`, `webchat_messages` |
| Apps and files | `apps`, `files`, `secrets` |
| LLM | `llm_providers`, `llm_provider_models`, `llm_model_defaults`, `llm_provider_catalog`, `llm_quotas`, `llm_usage_counters`, `llm_usage_log` |
| Connector data | `persistent_memory_hot`, `persistent_memory_cold`, `sheets`, `sheet_rows`, `boards`, `board_tasks`, `board_task_comments` |
