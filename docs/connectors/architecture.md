# Архитектура коннекторов (control-api)

Единый SPI для internal- и integration-коннекторов. Пакет: `ru.agimate.controlapi.connectors`.

## SPI (`connectors/core`)

```
ConnectorHandler                — connectorCode/Name, getTriggers, getTools, getTasks, executeTool, executeTask
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
  пессимистичные дефолты). Методы с `@Task` — фоновые таски: аннотация несёт расписание по умолчанию
  (`type`, `intervalSeconds`/`cron`/`zone`, `timeoutSeconds`). По умолчанию `isTaskOnly = true` — метод не
  попадает в `getTools()`/LLM-спеки и недоступен через `executeTool`; при `isTaskOnly = false` метод
  доступен и как тула, и как таска.

`BaseConnectorHandler` — единственный reflection-диспатчер: маппит `Map<String,Object> args` на параметры
метода по именам, привязывает `ConnectorContext` через ThreadLocal (`ConnectorContextHolder`, set/clear
только в базовом классе). `executeTask` диспатчит в **любой** `@Tool`-метод — таска может быть «вызовом
тулы по расписанию» (строка `connector_tasks` c `task_name` = имя тулы и `task_args` = её аргументы).

`ConnectorContext`: `identity` (для integration — id из `integration_credentials` строкой; internal — `null`),
`userId`, `agentId`, расшифрованные `credentials`, `webhookSecret`. Собирается только в `ConnectorContextFactory`.

Исключения: внутри коннекторного слоя — только `ConnectorException` (его сообщение безопасно отдаётся
агенту в error tool-result). `*StatusException` — строго на HTTP-границе
(`ConnectorRegistry.findHandler(...).orElseThrow(...)`).

Существующие коннекторы: `integrations/telegram/` (TelegramConnectorService + TelegramToolService,
декларативная таска `telegram.long_poll` в polling-режиме), `internal/board/` (BoardConnectorService +
BoardToolService), `internal/time/` (TimeConnectorService + TimeToolService — текущее время +
планирование отложенных задач агента, см. [«Планирование задач агентом»](#планирование-задач-агентом-time)).

## Выполнение

- **Тулы**: `AgentToolUseService` → ABAC → `ToolUseLog` → `ConnectorService.pushToConnector` →
  `execution/ToolExecutionService` (`@Async`): по типу хендлера собирает Context (integration —
  свежие credentials по `log.identity`), вызывает `executeTool`, пишет результат в лог и доставляет агенту.
- **Таски**: `tasks/ConnectorTaskScheduler` (`@Scheduled` 1s) атомарно claim'ит готовые строки
  `connector_tasks` (`FOR UPDATE SKIP LOCKED`, lease = `now + timeout_seconds`), исполняет в virtual
  threads через `tasks/TaskExecutionService` вне транзакции. `TaskExecutionService` реконструирует
  полный `ConnectorContext` из строки (`identity` + `user_id` + `agent_id`), поэтому таска исполняется
  с контекстом инициатора — так же, как если бы агент вызвал тулу сам.

## connector_tasks

`task_type`: `ONETIME` (успех → `COMPLETED`), `PERIODIC` (`task_config.intervalSeconds`; 0 = немедленный
повтор, long-poll), `CRON` (`task_config.cron`/`zone`). Ошибка любой таски → retry через 60s в `last_error`.
Crash recovery — по истечении `lease_until` строку подхватывает любая нода. `user_id` (NOT NULL) — владелец;
`agent_id` (nullable) — инициатор динамической задачи. `task_args` — аргументы метода; контекст инициатора
не в `task_args`, а в колонках (`identity`/`user_id`/`agent_id`).

Категории строк различает явный дискриминатор `kind`:

| `kind` | `identity` | `agent_id` | уникальность | пишется | живёт |
|---|---|---|---|---|---|
| **SYSTEM** — декларативная (интеграция) | id credentials | `null` | бизнес-ключ `(connector_code, identity, task_name)`, partial unique `WHERE kind = 'SYSTEM'` | listener upsert/sync из `getTasks()` | до удаления интеграции |
| **AGENT** — динамическая | identity tool-вызова (может быть `null`) | id агента-инициатора | нет — идентифицируется `id`, дубли легитимны | тула коннектора (напр. `time.schedule`) → `ConnectorTaskService.schedule(...)` | до срабатывания (`ONETIME`→`COMPLETED`) / отмены |
| **USER** — пользовательская | — | целевой агент (если адресная) | нет | manage-API (зарезервировано, ещё не реализовано) | — |

Уникальность бизнес-ключа — инвариант reconcile-синка SYSTEM-строк (`findByBusinessKey` →
`Optional`); пересинк деклараций (`syncIdentity`/`deleteStale`) не трогает чужие `kind`. Удаление
интеграции (`deleteByIdentity`) сносит все строки identity, включая динамические — без credentials
они неисполнимы.

`paused_at` — пользовательская пауза: пока поле не `NULL`, scheduler строку не подхватывает
(`claimReady` фильтрует). Отдельное поле, а не значение в `status`: переходами
`PENDING`/`RUNNING`/`COMPLETED` владеет scheduler, и pause внутри `status` гонялся бы с ними;
пересинк деклараций паузу тоже не сбрасывает.

Пользовательское управление — `/manage/connector-tasks/**` (list, pause/resume, delete для
USER/AGENT; см. `docs/services/control-api-manage-connector-tasks.md`). Lifecycle-чистки: удаление
агента сносит все его задачи (`deleteByAgentId`); тула `time.cancel_scheduled` удаляет только
`kind = AGENT` — задачу, созданную пользователем для агента, тулой отменить нельзя.

`ConnectorTaskService.schedule(...)` вставляет строку с будущим `next_run_at` (первое срабатывание),
`findActiveByAgent`/`cancel` — list/отмена по `(connector_code, user_id, agent_id)` с проверкой владельца.

### Планирование задач агентом (time)

`internal/time` даёт агенту тулы поверх этого механизма:

- `time.schedule(prompt, delaySeconds|intervalSeconds|cron[,zone])` — вставляет динамическую строку
  (`ONETIME`/`PERIODIC`/`CRON`), `task_name = time.fire`, `task_args = {prompt}`. Возвращает `id`.
- `time.scheduled_tasks` / `time.cancel_scheduled(id)` — список/отмена своих задач.
- `time.fire` — скрытая (`@Task isTaskOnly`) таска-диспетчер: на срок порождает триггер
  `trigger.time.due` (data `{prompt}`), адресованный агенту-инициатору через `TriggerAudience`.

Доставка: `TriggerRouterService.routeToAgent(userId, trigger)` — user-scoped (без привязки к команде,
в отличие от `routeInternalTrigger`), сужает кандидатов до audience. Агент получит напоминание, только
если у него есть осознанная ALLOW-политика на `time`/`trigger.time.due` — дефолтных политик не заводим.

## Lifecycle

- События `ConnectorCreatedEvent/ConnectorModifiedEvent (connectorCode, identity, userId)` и
  `ConnectorDeletedEvent (connectorCode, identity)` публикует `IntegrationService`
  (create/enable, updateCredentials, delete/disable). `userId` → `connector_tasks.user_id`.
- `ConnectorIdentityListener` (AFTER_COMMIT) превращает их в **декларативные** строки `connector_tasks`
  из `handler.getTasks()`: created → upsert, modified → sync (upsert + удаление stale), deleted →
  delete by identity. Касается только интеграций; динамические задачи агента сюда не попадают.
- `ConnectorBootstrap` (ApplicationReadyEvent) — upsert каталога `connectors` из registry
  (код — источник истины для name/type/credential_fields). Задачи на старте не регистрируются:
  декларативные заводятся по `ConnectorCreatedEvent`, динамические — агентом через тулы.
