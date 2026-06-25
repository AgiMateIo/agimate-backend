# Архитектура коннекторов (control-api)

Единый SPI для internal- и integration-коннекторов. Пакет: `ru.agimate.controlapi.connectors`.

## Реестр экземпляров (`connections`)

Подключённый экземпляр любого коннектора — строка `connections` (`id` = `identity` во всём
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

**Capabilities** — type-level дескриптор на `connectors`, **разложен по 4 колонкам** (рантайм ветвится
на них напрямую, без отдельного `ConnectorType` — он удалён): `transport_direction` (OUTBOUND/INBOUND),
`execution_locus` (BACKEND/EXTERNAL/AGENT — `ConnectorService.pushToConnector` роутит исполнение),
`tool_binding` (STATIC рефлексией / DYNAMIC из `connection_tools` — единое место листинга
`ToolDefinitionService` + gRPC), `sharing_scope`. Источник истины — SPI
`ConnectorHandler.capabilities()` (агрегат `ConnectorCapabilities`), заполняется бутстрапом
(`Connector.applyCapabilities`). «Интеграция» (подключаемый юзером коннектор с кредами) =
`credentialFields != null` (`Connector.isIntegration()`).

**Динамические тулы/триггеры** экземпляра (MCP-серверы, device-apps) — `connection_tools` /
`connection_triggers` (обобщают прежний `mcp_tool` + `apps.tools/triggers` JSONB; схемы сырым
JSON-текстом для фиделити). Статические коннекторы тулы отдают рефлексией, в этих таблицах не
материализуются.

## SPI (`connectors/core`)

```
ConnectorHandler                — connectorCode/Name, getTriggers, getTools, getJobs, executeTool, executeJob
├── IntegrationConnectorHandler — + getCredentialFields, validateCredentials, webhooks (setup/remove/normalizeInbound/validate)
└── InternalConnectorHandler    — маркер (без credentials)
```

Коннектор состоит из двух классов:

- **`<Name>ConnectorService`** — фасад: implements `IntegrationConnectorHandler`/`InternalConnectorHandler`,
  extends `BaseConnectorHandler`. Содержит метаданные, credentials/webhook-логику.
- **`<Name>ToolService`** — методы с собственной MCP-совместимой `@Tool` (`name`/`title`/`description`/
  `annotations`/`_meta`); параметры описываются `@ToolParam`. `getTools()` отдаёт `ConnectorToolSpec`
  (MCP): `inputSchema`/`outputSchema` строятся рефлексией (`ToolSchemaReflector`, без сторонних библиотек),
  `annotations` — поведенческие хинты (`readOnlyHint`/`destructiveHint`/`idempotentHint`/`openWorldHint`,
  пессимистичные дефолты). Методы с `@Job` — фоновые задачи: аннотация несёт расписание по умолчанию
  (`type`, `intervalSeconds`/`cron`/`zone`, `timeoutSeconds`). По умолчанию `isJobOnly = true` — метод не
  попадает в `getTools()`/LLM-спеки и недоступен через `executeTool`; при `isJobOnly = false` метод
  доступен и как тула, и как задача.

Тулы коннектора статичны и привязаны к `connectorCode` (строятся рефлексией один раз). Исключение —
**динамические коннекторы** (MCP, см. ниже): набор тулов per-instance и открывается в рантайме. Для них
SPI даёт context-aware перегрузку `getTools(ConnectorContext)` (дефолт — те же статические `getTools()`);
gRPC-листинг (`GetConnectorTools`) единообразно зовёт её с контекстом по `identity` — без спец-кейсов.

`BaseConnectorHandler` — единственный reflection-диспатчер: маппит `Map<String,Object> args` на параметры
метода по именам, привязывает `ConnectorContext` через ThreadLocal (`ConnectorContextHolder`, set/clear
только в базовом классе). `executeJob` диспатчит в **любой** `@Tool`-метод — задача может быть «вызовом
тулы по расписанию» (строка `connector_jobs` c `name` = имя тулы и `args` = её аргументы).

`ConnectorContext`: `identity` (= `connections.id` строкой; internal — `null`), `userId`, `agentId`,
расшифрованные `credentials` (из `secrets` по `secret_id`), `webhookSecret`. Собирается только в
`ConnectorContextFactory`.

Исключения: внутри коннекторного слоя — только `ConnectorException` (его сообщение безопасно отдаётся
агенту в error tool-result). `*StatusException` — строго на HTTP-границе
(`ConnectorRegistry.findHandler(...).orElseThrow(...)`).

Существующие коннекторы: `integrations/telegram/` (TelegramConnectorService + TelegramToolService,
декларативная таска `telegram.long_poll` в polling-режиме), `internal/board/` (BoardConnectorService +
BoardToolService), `internal/time/` (TimeConnectorService + TimeToolService — текущее время +
планирование отложенных задач агента, см. [«Планирование задач агентом»](#планирование-задач-агентом-time)),
`integrations/mcp/` (MCP-коннектор к удалённым серверам, см. [«MCP-коннектор»](#mcp-коннектор)).

### MCP-коннектор

`integrations/mcp/` — универсальный коннектор к удалённому MCP-серверу (транспорт **Streamable HTTP**,
auth — статический Bearer-токен/произвольные заголовки). Особенность: тулы **динамические и per-instance** —
каждый экземпляр (строка `connections` = `url` + auth в `secrets`) отдаёт свой набор через `tools/list`.
Поэтому `McpConnectorService implements IntegrationConnectorHandler` напрямую (без `BaseConnectorHandler` и
`@Tool`-методов):

- `getTools()` пуст (статических тулов нет); `getTools(ctx)` отдаёт список из `connection_tools` по `ctx.identity()`.
- `validateCredentials` = хендшейк `initialize` (доступность + auth); `identifier` = URL сервера (канонический
  ключ экземпляра, идёт в `sub_code`).
- `executeTool` проксирует в `tools/call`; путь исполнения (`ToolExecutionService`, свежие credentials по
  `identity`) — общий, без изменений.

**Кэш `connection_tools`** (per-connection, сырые JSON-схемы текстом для фиделити произвольной JSON Schema —
`JsonSchema` сохраняет нестандартные ключевые слова через `@JsonAnySetter`). Синк — `McpToolDiscoveryListener`
(AFTER_COMMIT, аналог `ConnectorIdentityListener` для тасок): на create/modify — ре-дискавери `tools/list` →
upsert + удаление пропавших; на delete — чистка по identity. Сетевой `tools/list` (`discover`) отделён от
записи в БД (`reconcile`), чтобы не держать транзакцию на время сетевого вызова.

Manage-API: `POST /manage/integrations/credentials/{id}/test` — единый «тест интеграции»: валидация
credentials (для всех типов — доступность/auth платформы) + для MCP синхронная пересборка кэша тулов
(возвращает `toolsDiscovered`/`toolsError`, не роняя сам тест). Тулы экземпляра (для UI политик) —
`GET /manage/integrations/credentials/{id}/tools/`: отдаёт через SPI `getTools(ctx)` (MCP — из кэша,
статические коннекторы — их штатный набор), без спец-кейсов.

ABAC: `AgentToolPolicy.connectorIdentity` скоупит политику на конкретный MCP-сервер; имена тулов для политик
берутся из кэша. Периодический refresh по расписанию и MCP `resources`/`prompts` — вне scope (YAGNI).

## Выполнение

- **Тулы**: `AgentToolCallService` → ABAC → `ToolCallLog` → `ConnectorService.pushToConnector` →
  `execution/ToolExecutionService` (`@Async`): по типу хендлера собирает Context (integration —
  свежие credentials по `log.identity`), вызывает `executeTool`, пишет результат в лог и доставляет агенту.
- **Задачи**: `jobs/ConnectorJobScheduler` (`@Scheduled` 1s) атомарно claim'ит готовые строки
  `connector_jobs` (`FOR UPDATE SKIP LOCKED`, lease = `now + timeout_seconds`), исполняет в virtual
  threads через `jobs/JobExecutionService` вне транзакции. `JobExecutionService` реконструирует
  полный `ConnectorContext` из строки (`identity` + `user_id` + `agent_id`), поэтому задача исполняется
  с контекстом инициатора — так же, как если бы агент вызвал тулу сам.

## connector_jobs

`type`: `ONETIME` (успех → `COMPLETED`), `PERIODIC` (`config.intervalSeconds`; 0 = немедленный
повтор, long-poll), `CRON` (`config.cron`/`zone`). Ошибка любой задачи → retry через 60s в `last_error`.
Crash recovery — по истечении `lease_until` строку подхватывает любая нода. `user_id` (NOT NULL) — владелец;
`agent_id` (nullable) — инициатор динамической задачи. `args` — аргументы метода; контекст инициатора
не в `args`, а в колонках (`identity`/`user_id`/`agent_id`).

Категории строк различает явный дискриминатор `kind`:

| `kind` | `identity` | `agent_id` | уникальность | пишется | живёт |
|---|---|---|---|---|---|
| **SYSTEM** — декларативная (интеграция) | id credentials | `null` | бизнес-ключ `(connector_code, identity, name)`, partial unique `WHERE kind = 'SYSTEM'` | listener upsert/sync из `getJobs()` | до удаления интеграции |
| **AGENT** — динамическая | identity tool-вызова (может быть `null`) | id агента-инициатора | нет — идентифицируется `id`, дубли легитимны | тула коннектора (напр. `time.schedule`) → `ConnectorJobService.schedule(...)` | до срабатывания (`ONETIME`→`COMPLETED`) / отмены |
| **USER** — пользовательская | — | целевой агент (если адресная) | нет | manage-API (зарезервировано, ещё не реализовано) | — |

Уникальность бизнес-ключа — инвариант reconcile-синка SYSTEM-строк (`findByBusinessKey` →
`Optional`); пересинк деклараций (`syncIdentity`/`deleteStale`) не трогает чужие `kind`. Удаление
интеграции (`deleteByIdentity`) сносит все строки identity, включая динамические — без credentials
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
  (`ONETIME`/`PERIODIC`/`CRON`), `name = time.fire`, `args = {prompt}`. Возвращает `id`.
- `time.scheduled_tasks` / `time.cancel_scheduled(id)` — список/отмена своих задач.
- `time.fire` — скрытая (`@Job isJobOnly`) задача-диспетчер: на срок порождает триггер
  `trigger.time.due` (data `{prompt}`), адресованный агенту-инициатору через `TriggerAudience`.

Доставка: `TriggerRouterService.routeToAgent(userId, trigger)` — user-scoped (без привязки к команде,
в отличие от `routeInternalTrigger`), сужает кандидатов до audience. Агент получит напоминание, только
если у него есть осознанная ALLOW-политика на `time`/`trigger.time.due` — дефолтных политик не заводим.

## Lifecycle

- События `ConnectorCreatedEvent/ConnectorModifiedEvent (connectorCode, identity, userId)` и
  `ConnectorDeletedEvent (connectorCode, identity)` публикует `IntegrationService`
  (create/enable, updateCredentials, delete/disable). `userId` → `connector_jobs.user_id`.
- `ConnectorIdentityListener` (AFTER_COMMIT) превращает их в **декларативные** строки `connector_jobs`
  из `handler.getJobs()`: created → upsert, modified → sync (upsert + удаление stale), deleted →
  delete by identity. Касается только интеграций; динамические задачи агента сюда не попадают.
- `ConnectorBootstrap` (ApplicationReadyEvent) — upsert каталога `connectors` из registry
  (код — источник истины для name/type/credential_fields). Задачи на старте не регистрируются:
  декларативные заводятся по `ConnectorCreatedEvent`, динамические — агентом через тулы.
