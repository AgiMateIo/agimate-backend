# Persistent memory (internal connector)

Код: `persist-memory`. Пакет: `controlapi.connectors.internal.persistentmemory`.

Долговременная память агента из двух слоёв, ключ — `agent_id`:

- **cold** (`persistent_memory_cold`) — свёрнутый MD-файл, один ряд на агента. Пишется только
  консолидацией; конкурентные записи отсекаются CAS по `version`.
- **hot** (`persistent_memory_hot`) — журнал заметок. Добавление = INSERT (append-only), поэтому
  конкурентные записи не конфликтуют. Консолидация клеймит партию (`consolidation_id` + `claimed_at`
  как лиз), сворачивает в cold и удаляет заметки.

## Тулы (видны LLM)

| Тула | Назначение |
|------|------------|
| `get_memory()` → `{content, version}` | прочитать cold (version нужен для `update_memory`) |
| `get_memory_notes()` → `{notes:[...]}` | прочитать ещё не сконсолидированные заметки |
| `save_memory_note(text, sessionId?)` | добавить заметку в hot (append) |
| `update_memory(text, version?, consolidationId?)` | CAS-запись cold; при `consolidationId` в той же транзакции удаляет заметки партии. Конфликт версии → ошибка «re-read and retry» |

`version` обязателен, когда cold уже существует (опускается только для самой первой записи).

## Триггеры (адресуются обратно агенту, audience, без канала)

- `persist-memory.notes_by_session` — `{sessionId, messages[]}`: собрать заметки по сессии
  (агент читает сообщения и зовёт `save_memory_note`).
- `persist-memory.consolidate` — `{consolidationId, notes[]}`: свернуть накопленные заметки в
  cold (агент зовёт `get_memory` → `update_memory(text, version, consolidationId)`).

## Фоновые задачи (per-agent, `@Job`, скрыты от LLM)

- `daily` (CRON `0 0 3 * * *`) — сессии агента с сообщениями за 24ч → по каждой издаёт
  `persist-memory.notes_by_session`.
- `consolidation` (CRON `0 0 * * * *`) — single-flight: если консолидация уже идёт
  (живой клейм), пропуск; иначе клеймит накопленные заметки и издаёт `consolidate`. Лиз клейма 30 мин
  (crash-recovery: брошенная консолидация реклеймится следующим часом). cold защищён ещё и CAS.

## Доставка памяти воркеру (gRPC AgentContext)

Память подмешивается в системный промпт / запрос пользователя на стороне воркера — **без** вызова
тулов `get_*`. Для этого воркер-протокол (`agent_context.proto`, сервис `AgentContext`) отдаёт:

- `GetMemory(workflow_id, agent_id) → AgentMemory{content, version}` — свёрнутая cold-память;
- `GetMemoryNotes(workflow_id, agent_id) → {notes:[{id, content, session_id}]}` — несконсолидированные заметки.

Реализация — `AgentContextGrpcService` (читает через `PersistentMemoryService`). Тулы `get_memory`/
`get_memory_notes` при этом остаются — они нужны агенту при обработке триггера `consolidate`.

## Джобы консолидации

Декларативные `@Job` (daily + consolidation) заводятся не на агента, а на **подключение**:
`ConnectorIdentityListener` слушает `ConnectorCreatedEvent`/`ConnectorModifiedEvent`/
`ConnectorDeletedEvent` и через `ConnectorJobService` пишет, синхронизирует и удаляет строки
`connector_jobs` по ключу `(connectorCode, connectionId, userId)`. Все три пути идемпотентны.

Доступ агента к тулам памяти решает ABAC — `AgentConnectionPolicy` с `kind = TOOL` на binding'е
агента с коннектором `persist-memory`, дефолт-allow с уточняющими DENY.
