# Архитектура коннекторов (control-api)

Единый SPI для internal- и integration-коннекторов. Пакет: `ru.agimate.controlapi.connectors`.

## Реестр экземпляров (`connections`)

Подключённый экземпляр любого коннектора — строка `connections` (`id` = `connection_id` во всём
downstream: channels, ABAC-политики, trigger/tool-логи, `connector_jobs`). Сворачивает прежний
`integration_credentials`; на `apps` ссылается через `app_id` (device-auth/linking не дублируются).

- `connector_code` (тип, FK→`connectors`) + `sub_code` (дискриминатор экземпляра: telegram-username,
  MCP-host, app-имя) → `full_code = connector_code + "_" + sub_code` (`mcp_context7`) — стабильный
  клиентский handle и префикс неймспейса тулов. Сборка — `connectors/core/FullCodes`.
- Уникальность среди активных строк: `(connector_code, user_id, sub_code)` и `(full_code, user_id)`
  (partial unique `WHERE deleted_at IS NULL`).
- `id` назначается явно при создании (`UUIDUtils.generateUUIDv8()` для интеграций, `app.id` для APP,
  старый id при бэкфилле) — поэтому не генерится БД.

**Секреты** (`secrets`) — envelope-шифрование (`connectors/core/secret`). На каждый секрет случайный
DEK шифрует данные (AES-256-GCM); DEK шифруется KEK (один источник, `app.secrets.encryption-key`) с
AAD = `entity + owner_id` (нельзя расшифровать, перенеся строку на другого владельца). Outbound-креды
коннектора лежат в `secrets`, адресуются `connections.secret_id`; inbound-verifier устройства
(`apps.key_*`) — невозвратный, в `secrets` не кладётся.

**Traits** — type-level дескриптор на `connectors`: **только функциональные оси** — те, на которых
ветвится механика; у каждой ровно один механизм-читатель. Источник истины — SPI
`ConnectorHandler.traits()` (агрегат `ConnectorTraits`), заполняется бутстрапом
(`Connector.applyTraits`):

- `execution_kind` (BACKEND — in-proc `@Tool`-метод хендлера, включая коннекторы с внешними
  HTTP-вызовами внутри тул-сервиса: telegram/mcp/media; DEVICE — push в канал устройства;
  LOOPBACK — исполняет вызывающий агент, цикл `/tool/check` + `/tool/result`). Читатель —
  `ConnectorService.pushToConnector`. Бывшая пара `execution_locus × transport_direction`
  кодировала эти же три случая; различие «наша инфра vs внешняя платформа» — информационное,
  живёт здесь, а не в данных.
- `definition_binding` (STATIC рефлексией/SPI / DYNAMIC из `connection_tools`/`connection_triggers`).
  Читатель — листинг (`ToolDefinitionService` + gRPC).

**Выводимые оси не декларируются.** Экземплярность — «пользователь приносит идентичность
экземпляра» — выводится в одной точке, `Connector.isInstanceBearing()` =
`credentialFields != null || execution_kind = DEVICE`. В коде ось первично зафиксирована
дизъюнктными типами хендлеров (`InternalConnectorHandler` / `IntegrationConnectorHandler`);
согласованность двух фиксаций гарантирует fail-fast инвариант в `ConnectorBootstrap` (integration-
хендлер без credential-полей роняет старт — это ошибка моделирования, а не конфигурации).
Граница применимости деривации: появись backend-исполняемый коннектор с пользовательскими
экземплярами — экземплярность становится явной осью traits (миграция локальна). LOOPBACK считается
неэкземплярным по договорённости.

**Две роли connection** (по оси экземплярности):

- **Строка-режим** (внутренние: board/persist-memory/time/media/webchat/acp, гипотетически wikipedia) —
  **одна на пользователя** (`ConnectionBindingService.ensureModeConnection`, find-or-create по
  `(connector_code, user_id)`, `full_code = code_userId`, `sub_code = null`, без секретов).
  «Внешний по сети» ≠ «внешний по модели»: media/wikipedia ходят во внешние API, но экземпляра,
  который приносит пользователь, у них нет.
- **Экземпляр** (telegram/mcp/app) — конкретный внешний объект: `sub_code` = его идентичность
  (username бота, URL сервера), секреты обязательны, строк сколько экземпляров.

**Владелец данных (identity scope)** — ось-знание, в данных не хранится (колонок
`identity_scope`/`scope_id` нет): правило воплощено в коде каждого коннектора и задокументировано
в его class-javadoc. Формулировка: *правило вывода владельца ресурса из личности вызывающего,
применяемое когда вызов не несёт явного адреса*. AGENT — вывести из агента (память: пространство =
`env.agentId`; time: задачи фильтруются по `env.agentId`), TEAM — из команды агента (board:
`env.agentId → team → board`), «правила нет» — ресурс либо явно адресован в вызове (сессия у
webchat/acp, экземпляр у telegram/mcp), либо отсутствует (media).

**Чек-лист нового коннектора** — два вопроса вместо заполнения полей:

1. *Оставь в env только userId и явные адреса вызова — что сломается?* Сломается → есть правило
   вывода владельца (AGENT/TEAM), зафиксируй его в коде и class-javadoc. Не сломается → правила нет.
2. *Пользователь приносит идентичность экземпляра (креды/устройство)?* Да → integration/device:
   экземпляры, явный bind. Нет → internal: строка-режим, доступ выдают скиллы.

| | владелец: агент | владелец: команда | правила нет |
|---|---|---|---|
| **Режим** (1/пользователя, скиллы) | persist-memory, time | board | media, webchat, acp |
| **Экземпляры** (N, явный bind) | — | — | telegram, mcp, app |

Чувствительность к агенту бывает трёх видов, ось — только первый: вывод владельца из caller'а
(память); явный адрес в вызове — это канальный слой (webchat/acp: session → channel → agent);
использование `env.agentId` per-call (логи, снапшот инициатора) — есть у всех и осью не является.

**Динамические тулы/триггеры** экземпляра (MCP-серверы, device-apps) — `connection_tools` /
`connection_triggers` (обобщают прежний `mcp_tool` + `apps.tools/triggers` JSONB; схемы сырым
JSON-текстом для фиделити). Статические коннекторы тулы отдают рефлексией, в этих таблицах не
материализуются.

## Доступ агента (binding + политики)

Два уровня (заменяют прежние `agent_tool_policies` / `agent_trigger_policies`):

- **Binding** (`agent_connections`, M:N agent↔connection) — гейт доступности. Нет активной строки →
  коннектор агенту недоступен (даже если `connections`-запись есть). Жизненный цикл по типу коннектора:
  внутренние — **скиллы источник истины** (`AgentSkillPolicyService.applyDiff` — полная реконсиляция:
  добавляет недостающие привязки, снимает не требуемые ни одним скиллом; привязки, удерживаемые
  активным каналом — webchat/acp, — не трогает; ручной bind/unbind внутренних через manage-API
  запрещён), внешние — явный bind на существующий экземпляр (`ensureBindingToExisting`). Строка-режим
  внутреннего коннектора материализуется при первом использовании (`ConnectorCreatedEvent` для
  регистрации джоб) и живёт дальше независимо от привязок — collapse нет.
- **Политики** (`agent_connection_policies`) — уточнение поверх binding. Модель **дефолт-allow**: при
  наличии binding тул/триггер разрешён, если нет правила. Прецеденс при разрешении `(kind, name)`:
  точное имя > binding-wide (`name IS NULL`) > дефолт-allow (числового priority нет — одно активное
  правило на `(binding, kind, name)`). `params_filter` победившего правила применяется на месте вызова:
  `TOOL` — аргументы (`AgentToolCallService`), `TRIGGER` — параметры события (`TriggerRouterService`).
  Покрывает deny-list (точечные DENY) и allow-list (binding-wide DENY + точечные ALLOW).

`ConnectionAccessEvaluator` — единая точка: `evaluate(agentId, connectionId, kind, name)` → гейт по
binding + прецеденс. `connection_id` тула/триггера = `connections.id`. Листинг доступного агенту
(`AgentService`) — объединение тулов/триггеров привязанных экземпляров за вычетом DENY.

**Каналы** — слой «как» (`channels.connection_id` → экземпляр). Создание канала гарантирует binding на
источник и reply-экземпляры; chat-filtering хранится в `channels.input_filter` и применяется в
`ChannelRouteResolver` (не в ABAC). Удаление канала binding не трогает.

`ChannelHandler.contributesPromptTools()` — канал приносит тулы своего коннектора в контекст
DIALOGUE-рана мимо скилл-гейта (`requiredConnectors` в `RunContextService`): семантика «канал
приносит тулы», пока разговор идёт из него. Используется ACP-каналом для IDE-тулов (fs/terminal) и
session-scoped MCP-тулов (проброшенных из IDE), чтобы не требовать ручного скилла на каждого агента.

**Roadmap — `channelOnly` коннекторы/коннекшены (не реализовано).** Сейчас тулы ACP-коннектора
(fs/terminal/session-MCP) технически видны в контексте любого DIALOGUE-рана этого агента, а не только
IDE-канала, — ограничение держится на том, что они существуют лишь пока жив ACP-сессия
(session-scoped, in-memory). Идея — явный флаг на коннекторе/коннекшене «тулы доступны только из
своего канала» (channelOnly), чтобы разграничение было декларативным, а не следствием эфемерности.
Полезно и для других канал-специфичных тулов (напр. reply-тулы). Продумать: где хранится флаг
(capabilities коннектора vs поле connection), как его учитывает `RunContextService.collectTools`, и
не дублирует ли он `contributesPromptTools`.

## SPI (`connectors/core`)

SPI — композиция: identity-ядро + capability-интерфейсы, которые фасад реализует à la carte.

```
ConnectorHandler                — identity: connectorCode/Name, capabilities
├── IntegrationConnectorHandler — + getCredentialFields, validateCredentials, webhooks (setup/remove/normalizeInbound/validate)
└── InternalConnectorHandler    — маркер (без credentials)

Capability-интерфейсы (реализуются по необходимости):
ToolProvider     — getTools, getTools(ctx), executeTool
TriggerProvider  — getTriggers
JobProvider      — getJobs, executeJob
PromptBlockProvider  — promptBlocks(ctx) → List<PromptBlock>
```

Потребители достают capability через `findCapability(code, X.class)` (листинги, `Optional`) или —
на execution-путях, где handler уже получен из registry — статическим
`ConnectorRegistry.capability(handler, X.class)` (бросает `ConnectorException`).
Коннектор без capability — валидное состояние: нет тулов/тасок/триггеров — интерфейс просто
не реализуется (webchat реализует только `TriggerProvider`).

**`PromptBlockProvider`** — блоки контекста для LLM-промпта агента: собираются при подготовке
контекста рана по каждой активной привязанной connection. `PromptBlock`:
`{name, placement: SYSTEM|USER, content, attrs, stable}` — `name`/`attrs` становятся XML-тегом у
рендерера на воркере, `stable` — подсказка порядка (стабильные раньше, дружелюбно к prompt-кэшу).
Инвариант: блок O(1) от объёма данных коннектора; растущие листинги — через тулы, не блоки.
Пример — persist-memory: cold-память → SYSTEM-блок `memory` (attr `version` для CAS в
`update_memory`), hot-заметки → USER-блок `memory_notes`.

**Директивы контекста триггера (`ContextDirectives` в `TriggerSpec`).** Триггер статически
декларирует, какой контекст нужен его рану, — overlay поверх route-пресета `ContextSpec`
(DIALOGUE/SYSTEM_TRIGGER выбирает маршрут, коннектор его не знает; `null`-поле = «как в базе»),
накладывается один раз в `EffectiveContext.of` при сборке (`RunContextService`). Поля двух классов
риска:

- **trust** — `presentation=PROMPT` + `promptParam` (событие рендерится trusted-текстом из
  `data[promptParam]` вместо untrusted-JSON; легитимно только когда payload собирает наш код —
  `time.due`) и `guidance` (trusted user-блок перед блоком события; статическая константа кода,
  без интерполяции данных). PROMPT разрешён только internal-коннекторам — fail-fast guard в
  `ConnectorBootstrap`.
- **scope** — `skillTools` (собирать ли тулы скиллов), `ownConnectionTools` (тулы connection
  события — именно этой connection, не всех экземпляров кода), `historyLimit` (окно истории,
  `0` — без неё). Меняют объём контекста, не доверие.

Источник директив — **только код** (`TriggerProvider.getTriggers()` через registry): динамические
декларации (`connection_triggers` устройств/MCP) и payload события в резолве не участвуют —
незнакомый триггер получает базовый пресет (default-safe). Потребители: `time.due`
(PROMPT+guidance+ownConnectionTools), memory-триггеры (`skillTools=false`, `ownConnectionTools=true`,
`historyLimit=0` — материал уже в `data`, тела подошедших скиллов остаются: memory-скилл и есть
инструкция обработки).

Коннектор состоит из двух классов:

- **`<Name>ConnectorService`** — фасад: implements `IntegrationConnectorHandler`/`InternalConnectorHandler`
  + нужные capability-интерфейсы, extends `BaseConnectorHandler` (даёт `ToolProvider` + `JobProvider`
  рефлексией). Содержит метаданные, credentials/webhook-логику.
- **`<Name>ToolService`** — методы с собственной MCP-совместимой `@Tool` (`name`/`title`/`description`/
  `annotations`/`_meta`); параметры описываются `@ToolParam`. `getTools()` отдаёт `ConnectorToolSpec`
  (MCP): `inputSchema`/`outputSchema` строятся рефлексией (`ToolSchemaReflector`, без сторонних библиотек),
  `annotations` — поведенческие хинты (`readOnlyHint`/`destructiveHint`/`idempotentHint`/`openWorldHint`,
  пессимистичные дефолты). Долгие тулы декларируют `@Tool(timeoutSeconds=…)` — бюджет ожидания
  результата воркером (кламп 30 мин; `0`/не задан → дефолт воркера `agent.tool.poll-timeout`, 60s);
  бюджет ограничивает только ожидание, выполнение на бэке не отменяется. Методы с `@Job` — **декларативные** фоновые задачи: аннотация несёт расписание
  (`type`, `intervalSeconds`/`cron`/`zone`, `timeoutSeconds`), на материализации экземпляра reconcile-синк
  заводит на каждую строку `connector_jobs` (`kind=SYSTEM`, по одной на connection_id). `@Job` всегда скрыт от
  LLM (нет в `getTools()`, недоступен через `executeTool`) — это фоновый процесс, а не тула.
  Скрытую **цель динамического диспатча** (строки `kind=AGENT`, напр. `time.fire`) `@Job` помечать нельзя
  (reconcile завёл бы её как SYSTEM без агента-инициатора) — для этого обычный `@Tool(internal = true)`:
  скрыт от LLM, но остаётся целью `executeJob`.

Тулы коннектора статичны и привязаны к `connectorCode` (строятся рефлексией один раз). Исключение —
**динамические коннекторы** (MCP, см. ниже): набор тулов per-instance и открывается в рантайме. Для них
`ToolProvider` даёт context-aware перегрузку `getTools(ConnectorEnv)` (дефолт — те же статические `getTools()`);
gRPC-листинг (`GetConnectionTools(connection_id)`) единообразно зовёт её с контекстом по `connection_id` — без
спец-кейсов. Воркер сперва получает доступные агенту экземпляры через `GetConnections(agent_id)`
(привязки `agent_connections` → `connections`), затем по каждому зовёт `GetConnectionTools`.

**Именование тулов и триггеров (единая форма).** Хранимое имя (`@Tool(name=…)`, ключи `getTriggers()`,
`@Job`) — **голый локальный идентификатор** в `snake_case` без префиксов: `schedule`, `get_tasks`,
`message_received`, `consolidate`, `daily`. Глобальную уникальность для LLM даёт `namespace`, который бэк
выводит на экземпляр: `connector_code` для контекстных синглтонов (time/board/persist-memory — у агента ровно
один) и `full_code` для multi-instance (`mcp_context7`, `telegram_<bot>`). Воркер строит agent-facing имя как
`{namespace}.{name}` (`time.schedule`, `persist-memory.save_memory_note`, `mcp_context7.resolve-library-id`),
а на `executeTool`/маршрутизации возвращает голое `name` — namespace только для показа. Тулы и триггеры
именуются **одинаково**; различаются `PolicyKind` (TOOL/TRIGGER), поэтому префикс `trigger.` не нужен.

`BaseConnectorHandler` — единственный reflection-диспатчер: маппит `Map<String,Object> args` на параметры
метода по именам, привязывает `ConnectorEnv` через ThreadLocal (`ConnectorEnvHolder`, set/clear
только в базовом классе). `executeJob` диспатчит в **любой** `@Tool`-метод — задача может быть «вызовом
тулы по расписанию» (строка `connector_jobs` c `name` = имя тулы и `args` = её аргументы).

`ConnectorEnv`: `connection_id` (= `connections.id` строкой; internal — `null`), `userId`, `agentId`,
`runId` (ран-инициатор `agent_runs.id`; `null` вне tool-use потока рана — джобы/webhooks/listing;
приходит из `tool_call_logs.run_id`, нужен учёту media-usage для привязки к рану), `channelId`,
`sessionId`, расшифрованные `credentials` (из `secrets` по `secret_id`), `webhookSecret`. Собирается
только в `ConnectorEnvFactory`.

Исключения: внутри коннекторного слоя — только `ConnectorException` (его сообщение безопасно отдаётся
агенту в error tool-result). `*StatusException` — строго на HTTP-границе
(`ConnectorRegistry.findHandler(...).orElseThrow(...)`).

Существующие коннекторы: `integrations/telegram/` (TelegramConnectorService + TelegramToolService,
декларативная таска `telegram.long_poll` в polling-режиме), `internal/board/` (BoardConnectorService +
BoardToolService), `internal/time/` (TimeConnectorService + TimeToolService — текущее время +
планирование отложенных задач агента, см. [«Планирование задач агентом»](#планирование-задач-агентом-time)),
`integrations/mcp/` (MCP-коннектор к удалённым серверам, см. [«MCP-коннектор»](#mcp-коннектор)),
`internal/webchat/` (чат с агентом из фронта; scope `USER` — одна connection на пользователя, без
тулов/джоб, см. `docs/connectors/webchat.md`), `internal/astro/` + `internal/divination/`
(астрология по эфемеридам и Матрица/нумерология/Таро для пресета «Астролог», см.
`docs/connectors/astro-divination.md`), `internal/media/` (MediaConnectorService + MediaToolService —
«модель как инструмент»: `gen_image`/`edit_image`/`read_image` чужой моделью, выбор модели и ключи —
в `service/llm/MediaInferenceService`, см. `docs/connectors/media.md`).

### MCP-коннектор

`integrations/mcp/` — универсальный коннектор к удалённому MCP-серверу (транспорт **Streamable HTTP**,
auth — статический Bearer-токен/произвольные заголовки). Особенность: тулы **динамические и per-instance** —
каждый экземпляр (строка `connections` = `url` + auth в `secrets`) отдаёт свой набор через `tools/list`.
Поэтому `McpConnectorService implements IntegrationConnectorHandler, ToolProvider` напрямую
(без `BaseConnectorHandler` и `@Tool`-методов; `JobProvider` не реализует — фоновых тасок нет):

- `getTools()` пуст (статических тулов нет); `getTools(ctx)` отдаёт список из `connection_tools` по `ctx.connectionId()`.
- `validateCredentials` = хендшейк `initialize` (доступность + auth); `identifier` = URL сервера (канонический
  ключ экземпляра, идёт в `sub_code`).
- `executeTool` проксирует в `tools/call`; путь исполнения (`ToolExecutionService`, свежие credentials по
  `connection_id`) — общий, без изменений.

**SSRF-guard.** URL задаёт пользователь, а запрос делает бэкенд — перед каждым обращением (`probe`/
`tools/list`/`tools/call`, единый чокпоинт `McpClient.openSession`) проверяем цель: схема только
`http(s)`, хост резолвится, и все его адреса должны быть публичными — loopback / link-local (вкл.
`169.254.169.254`) / site-local / any-local / multicast / IPv6 unique-local блокируются. Резолв на
каждом вызове сужает (но не закрывает полностью) окно DNS-rebinding. Флаг
`app.connectors.mcp.allow-private-targets` (default `false`) снимает проверку для локальной разработки.

**Кэш `connection_tools`** (per-connection, сырые JSON-схемы текстом для фиделити произвольной JSON Schema —
`JsonSchema` сохраняет нестандартные ключевые слова через `@JsonAnySetter`). Синк — `McpToolDiscoveryListener`
(AFTER_COMMIT, аналог `ConnectorIdentityListener` для тасок): на create/modify — ре-дискавери `tools/list` →
upsert + удаление пропавших; на delete — чистка по connection_id. Сетевой `tools/list` (`discover`) отделён от
записи в БД (`reconcile`), чтобы не держать транзакцию на время сетевого вызова.

Manage-API: `POST /manage/integrations/credentials/{id}/test` — единый «тест интеграции»: валидация
credentials (для всех типов — доступность/auth платформы) + для MCP синхронная пересборка кэша тулов
(возвращает `toolsDiscovered`/`toolsError`, не роняя сам тест). Тулы экземпляра (для UI политик) —
`GET /manage/integrations/credentials/{id}/tools/`: отдаёт через SPI `getTools(ctx)` (MCP — из кэша,
статические коннекторы — их штатный набор), без спец-кейсов.

ABAC: доступ к MCP-серверу — binding агента на его connection; правила `agent_connection_policies`
скоупятся по `(binding, kind, name)`, имена тулов берутся из `connection_tools`. Периодический refresh по
расписанию и MCP `resources`/`prompts` — вне scope (YAGNI).

## Выполнение

- **Тулы**: `AgentToolCallService` → ABAC → `ToolCallLog` → `ConnectorService.pushToConnector` →
  `execution/ToolExecutionService` (`@Async`): по типу хендлера собирает Context (integration —
  свежие credentials по `log.connectionId`), вызывает `executeTool`, пишет результат в лог и доставляет агенту.
- **Задачи**: `jobs/ConnectorJobScheduler` (`@Scheduled` 1s) атомарно claim'ит готовые строки
  `connector_jobs` (`FOR UPDATE SKIP LOCKED`, lease = `now + timeout_seconds`), исполняет в virtual
  threads через `jobs/JobExecutionService` вне транзакции. `JobExecutionService` реконструирует
  полный `ConnectorEnv` из строки (`connection_id` + `user_id` + `agent_id`), поэтому задача исполняется
  с контекстом инициатора — так же, как если бы агент вызвал тулу сам.

## connector_jobs

`type`: `ONETIME` (успех → `COMPLETED`), `PERIODIC` (`config.intervalSeconds`; 0 = немедленный
повтор, long-poll), `CRON` (`config.cron`/`zone`). Ошибка любой задачи → retry через 60s в `last_error`.
Crash recovery — по истечении `lease_until` строку подхватывает любая нода. `user_id` (NOT NULL) — владелец;
`agent_id` (nullable) — инициатор динамической задачи. `args` — аргументы метода; контекст инициатора
не в `args`, а в колонках (`connection_id`/`user_id`/`agent_id`).

Категории строк различает явный дискриминатор `kind`:

| `kind` | `connection_id` | `agent_id` | уникальность | пишется | живёт |
|---|---|---|---|---|---|
| **SYSTEM** — декларативная (интеграция) | id credentials | `null` | бизнес-ключ `(connector_code, connection_id, name)`, partial unique `WHERE kind = 'SYSTEM'` | listener upsert/sync из `getJobs()` | до удаления интеграции |
| **AGENT** — динамическая | connection_id tool-вызова (может быть `null`) | id агента-инициатора | нет — идентифицируется `id`, дубли легитимны | тула коннектора (напр. `time.schedule`) → `ConnectorJobService.schedule(...)` | до срабатывания (`ONETIME`→`COMPLETED`) / отмены |
| **USER** — пользовательская | — | целевой агент (если адресная) | нет | manage-API (зарезервировано, ещё не реализовано) | — |

Уникальность бизнес-ключа — инвариант reconcile-синка SYSTEM-строк (`findByBusinessKey` →
`Optional`); пересинк деклараций (`syncIdentity`/`deleteStale`) не трогает чужие `kind`. Удаление
интеграции (`deleteByConnectionId`) сносит все строки connection_id, включая динамические — без credentials
они неисполнимы.

`paused_at` — пользовательская пауза: пока поле не `NULL`, scheduler строку не подхватывает
(`claimReady` фильтрует). Отдельное поле, а не значение в `status`: переходами
`PENDING`/`RUNNING`/`COMPLETED` владеет scheduler, и pause внутри `status` гонялся бы с ними;
пересинк деклараций паузу тоже не сбрасывает.

Пользовательское управление — `/manage/connector-jobs/**` (list, pause/resume, delete для
USER/AGENT; см. `docs/services/control-api-manage-connector-jobs.md`). Lifecycle-чистки: удаление
агента сносит все его задачи (`deleteByAgentId`); тула `time.cancel_scheduled` удаляет только
`kind = AGENT` — задачу, созданную пользователем для агента, тулой отменить нельзя.

`ConnectorJobService.schedule(...)` вставляет строку с будущим `next_run_at` (первое срабатывание),
`findActiveByAgent`/`cancel` — list/отмена по `(connector_code, user_id, agent_id)` с проверкой владельца.

### Планирование задач агентом (time)

`internal/time` даёт агенту тулы поверх этого механизма:

- `time.schedule(prompt, delaySeconds|intervalSeconds|cron[,zone])` — вставляет динамическую строку
  (`ONETIME`/`PERIODIC`/`CRON`), `name = time.fire`, `args = {prompt}`; в строку снимаются `channel_id`
  и `session_id` prompt-канала вызова. Возвращает `id`.
- `time.scheduled_tasks` / `time.cancel_scheduled(id)` — список/отмена своих задач.
- `time.fire` — скрытая (`@Tool(internal = true)`) цель диспатча: на срок порождает триггер
  `due` (agent-facing `time.due`, data `{prompt}`), адресованный агенту-инициатору через `TriggerAudience`;
  снимки канала/сессии уезжают проактивными `progress`/`answer`-ссылками. Сессию перерезолвливает
  `ChannelRouteResolver`: снапшот, пока открыт, иначе активная сессия канала (симметрично фолбэку
  outbound-доставки) — ран напоминания получает историю, партицию и персист этой сессии.
  По `ContextDirectives` триггера промпт рендерится **trusted**-блоком `trigger_prompt` (авторство —
  сам агент) с guidance-преамбулой, а тулы time-connection доступны без скилла (`ownConnectionTools`).

Доставка: `TriggerRouterService.routeTrigger(userId, trigger)` (единая точка входа; `routeWhTrigger`/
`routeAppTrigger` — тонкие обёртки) сужает кандидатов до audience (агент-инициатор), затем применяет
ABAC. Модель — **дефолт-allow при binding**: напоминание доставляется, потому что у агента есть активный
binding на time-коннектор (его заводит сам time-скилл) — отдельная ALLOW-политика не нужна и не
создаётся, явное правило требуется лишь для **DENY**. Нет binding'а (скилл не привязан) — доставки нет.

## Lifecycle

- События `ConnectorCreatedEvent/ConnectorModifiedEvent (connectorCode, connection_id, userId)` и
  `ConnectorDeletedEvent (connectorCode, connection_id)` публикует `IntegrationService`
  (create/enable, updateCredentials, delete/disable). `userId` → `connector_jobs.user_id`.
- `ConnectorIdentityListener` (AFTER_COMMIT) превращает их в **декларативные** строки `connector_jobs`
  из `JobProvider.getJobs()` (коннектор без `JobProvider` тасок не имеет): created → upsert, modified → sync (upsert + удаление stale), deleted →
  delete by connection_id. Касается только интеграций; динамические задачи агента сюда не попадают.
- `ConnectorBootstrap` (ApplicationReadyEvent) — upsert каталога `connectors` из registry
  (код — источник истины для name/type/credential_fields). Задачи на старте не регистрируются:
  декларативные заводятся по `ConnectorCreatedEvent`, динамические — агентом через тулы.
