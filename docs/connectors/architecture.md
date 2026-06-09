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
- **`<Name>ToolService`** — `@Tool`-методы (langchain4j). Методы с `@TaskOnly` — фоновые таски:
  не попадают в LLM-спеки и недоступны через `executeTool`, аннотация несёт расписание по умолчанию
  (`type`, `intervalSeconds`/`cron`/`zone`, `timeoutSeconds`).

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
таска `telegram.long_poll` в polling-режиме), `internal/board/` (BoardConnectorService + BoardToolService).

## Выполнение

- **Тулы**: `AgentToolUseService` → ABAC → `ToolUseLog` → `ConnectorService.pushToConnector` →
  `execution/ToolExecutionService` (`@Async`): по типу хендлера собирает Context (integration —
  свежие credentials по `log.identity`), вызывает `executeTool`, пишет результат в лог и доставляет агенту.
- **Таски**: `tasks/ConnectorTaskScheduler` (`@Scheduled` 1s) атомарно claim'ит готовые строки
  `connector_tasks` (`FOR UPDATE SKIP LOCKED`, lease = `now + timeout_seconds`), исполняет в virtual
  threads через `tasks/TaskExecutionService` вне транзакции.

## connector_tasks

Бизнес-ключ `(connector_code, identity, task_name)` (partial unique, identity NULL = глобальная задача).
`task_type`: `ONETIME` (успех → `COMPLETED`), `PERIODIC` (`task_config.intervalSeconds`; 0 = немедленный
повтор, long-poll), `CRON` (`task_config.cron`/`zone`). Ошибка любой таски → retry через 60s в `last_error`.
Crash recovery — по истечении `lease_until` строку подхватывает любая нода.

## Lifecycle

- События `ConnectorCreatedEvent/ConnectorModifiedEvent/ConnectorDeletedEvent (connectorCode, identity)`
  публикует `IntegrationService` (create/enable, updateCredentials, delete/disable).
- `ConnectorIdentityListener` (AFTER_COMMIT) превращает их в строки `connector_tasks` из `handler.getTasks()`:
  created → upsert, modified → sync (upsert + удаление stale), deleted → delete by identity.
- `ConnectorBootstrap` (ApplicationReadyEvent) — upsert каталога `connectors` из registry
  (код — источник истины для name/type/credential_fields) + синк глобальных тасок internal-коннекторов.
