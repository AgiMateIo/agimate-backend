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
| `save_memory_note(text)` | добавить заметку в hot (append); сессия берётся из вызова |
| `update_memory(text, version?, consolidationId?)` | CAS-запись cold; при `consolidationId` в той же транзакции удаляет заметки партии. Конфликт версии → ошибка «re-read and retry» |

`version` обязателен, когда cold уже существует (опускается только для самой первой записи).

## Триггеры (адресуются обратно агенту, audience, без канала)

- `persist-memory.consolidate` — `{consolidationId, notes[]}`: свернуть накопленные заметки в
  cold (агент зовёт `get_memory` → `update_memory(text, version, consolidationId)`).

## Фоновые задачи (на подключение, `@Job`, скрыты от LLM)

Заметки пишет только сам агент по ходу диалога. Ночной `daily`, который перечитывал сессии за сутки и
просил агента выписать из них факты, удалён 2026-09-16: он запускал модель на каждую сессию каждые
сутки ради подстраховки того, что навык и так требует делать в разговоре. Его строки `connector_jobs`
снимает ресинк при старте.

- `consolidation` (PERIODIC, 3600 с) — single-flight: если консолидация уже идёт
  (живой клейм), пропуск; иначе клеймит накопленные заметки и издаёт `consolidate`. Лиз клейма 30 мин
  (crash-recovery: брошенная консолидация реклеймится следующим запуском). cold защищён ещё и CAS.

  Раз в час, потому что несвёрнутые заметки уходят в контекст агента на каждом ходу отдельным блоком
  без ограничения по размеру: консолидация держит этот блок коротким.

  **По темпу, а не по часам**: cron привязал бы строки всех подключений установки к одной
  секунде (пик в `:00` на всей инсталляции), а периодическая строка считает следующий запуск от
  собственного завершения и потому остаётся там, где её застало предыдущее срабатывание. Разовое
  разведение уже существующих строк — `updates/2026/09/09-01-consolidation-job-spread.xml`.

## Доставка памяти воркеру (gRPC AgentContext)

Память подмешивается в системный промпт / запрос пользователя на стороне воркера — **без** вызова
тулов `get_*`. Для этого воркер-протокол (`agent_context.proto`, сервис `AgentContext`) отдаёт:

- `GetMemory(workflow_id, agent_id) → AgentMemory{content, version}` — свёрнутая cold-память;
- `GetMemoryNotes(workflow_id, agent_id) → {notes:[{id, content, session_id}]}` — несконсолидированные заметки.

Реализация — `AgentContextGrpcService` (читает через `PersistentMemoryService`). Тулы `get_memory`/
`get_memory_notes` при этом остаются — они нужны агенту при обработке триггера `consolidate`.

## Джобы консолидации

Декларативный `@Job` `consolidation` заводится не на агента, а на **подключение**:
`ConnectorIdentityListener` слушает `ConnectorCreatedEvent`/`ConnectorModifiedEvent`/
`ConnectorDeletedEvent` и через `ConnectorJobService` пишет, синхронизирует и удаляет строки
`connector_jobs` по ключу `(connectorCode, connectionId, userId)`. Все три пути идемпотентны.

Доступ агента к тулам памяти решает ABAC — `AgentConnectionPolicy` с `kind = TOOL` на binding'е
агента с коннектором `persist-memory`, дефолт-allow с уточняющими DENY.
