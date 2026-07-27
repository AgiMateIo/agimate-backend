# Контракт control-api ↔ agent-worker

Протокол между бэкендом (control-api) и пулом воркеров, исполняющих агентов внутри DBOS-workflow.
Две части: **DBOS** доставляет раны на воркеры, **gRPC** обслуживает обратные вызовы воркера в бэкенд.

Proto-файлы: `services/libs/agentworker-proto/src/main/proto/agentworker/`.

## Разделение ответственности

Бэкенд собирает контекст и владеет политикой, воркер рендерит и крутит цикл. Из этого следует всё
остальное:

- Воркер **не имеет доступа к БД бэкенда** и знает про ран только `{agent_id, run_id}`.
- Все side-effects (вызовы тулов, доступ к файлам) идут через бэкенд. Прямая интеграция у воркера
  одна — с LLM-провайдером.
- Воркер — **единственный писатель истории**; доставка сообщения в канал есть проекция этой записи,
  а не отдельное действие.
- Бэкенд **не доверяет воркеру в части прав**: ABAC перепроверяется на `ExecuteTool`, независимо от
  того, какие тулы воркер показал модели.

Бэкенд при этом не пушит ничего в воркер по gRPC (это работа DBOS) и не хранит ключи воркеров в БД.

## Транспорт

| | |
|---|---|
| gRPC | поверх TLS (HTTP/2), порт `9091` (`grpc.server.port`) |
| Инициатор | только воркер; server-push и bidi-стримы не используются — их роль выполняет DBOS |
| Plaintext | допустим лишь локально (`grpc.server.security.enabled=false`) |
| Доставка ранов | DBOS: durable enqueue в очередь `agent_exec`, партиционированную по `session_id` |

Партиционирование очереди — **контрактное требование**, а не деталь: оно даёт
single-writer-per-session, поэтому отдельного регистрационного хэндшейка у ранов нет.

### Сервисы

| Сервис | RPC |
|---|---|
| `WorkerControl` | `HealthCheck` |
| `AgentContext` | `GetRunContext`, `GetLlmCredentials`, `GetFile`, `ReportLlmUsage` |
| `MessageLog` | `SaveMessage`, `SaveTurn`, `SavePrompt` |
| `ToolGateway` | `ExecuteToolAsync`, `GetToolResult` |

## Аутентификация

Каждый RPC несёт:

- `authorization: Bearer <wrkp…>` — полный ключ пула (64 символа, префикс `wrkp`,
  формат — [api-keys.md](api-keys.md));
- `x-worker-instance: <uuid>` — генерируется раз на процесс, для аудита и трейсинга.

`WorkerPoolAuthInterceptor` разбирает ключ (`AppKeyUtils.parse`), проверяет CRC32, находит пул по
`keyId` в `WorkerPoolRegistry` и сверяет SHA-256 секрета с сохранённым хэшем. Неудача →
`UNAUTHENTICATED`; успех → `WorkerPoolContext(poolId, workerInstanceId)` доступен через
`WorkerPoolContextHolder.current()`.

**Источник истины — конфиг, а не БД.** Это осознанно: identity воркера не должна зависеть от
доступности базы. Реестр читается из `worker-pools.authkeys` на старте и в БД не ходит.

### Ключи пулов

В конфиге лежит **authkey** (80 символов) — не тот ключ, что у воркера:

```
authkey = prefix(4) + keyId(12) + sha256Hex(64)     ← бэкенд
fullKey = prefix(4) + keyId(12) + payload(48)       ← воркер, содержит секрет
```

Поиска по `keyId` и сверки `sha256(secret)` достаточно, чтобы аутентифицировать, никогда не храня
секрет. Bcrypt не нужен: у ключа минимум 32 случайных байта, перебор нерелевантен.

```yaml
worker-pools:
  authkeys:
    - wrkpaf4bvIRmNRDt4172e9b5bf81ca8d7f510bfe8ff7e33f13bd57d57e8b7ce6c0b02510aaeba59d
```

В проде — через `WORKER_POOLS_AUTHKEYS_0`, `_1`, … Генерация — gated JUnit-тестом, чтобы не держать
такую магию в проде:

```bash
cd services
./gradlew :control-api:test --tests "*WorkerAuthkeyGeneratorTest" -Dgenerate.worker.authkey=true --rerun-tasks
```

Ротация аддитивна: добавить новый authkey → передеплоить бэкенд → передеплоить воркеры с новым
full key → убрать старый authkey.

## Модель согласованности

Весь контекст рана фиксируется **одним durable-шагом** (`GetRunContext` → чекпоинт
`prepare_context`), поэтому versioned reads не нужны: replay берёт чекпоинт, конфигурация не может
поменяться под ногами по ходу рана.

Отсюда два жёстких правила:

- **Смена формы чекпоинта** (`PreparedContext`, DBOS-payload, сигнатуры дочерних workflow)
  несовместима с in-flight ранами → деплой только после **drain**: остановить триггеры, дождаться
  пустых очередей DBOS.
- **Результат durable-шага — только plain-сериализуемые типы.** Не protobuf (Jackson не переваривает
  рекурсивные дескрипторы — оборачивать, как `SlotClaim`) и не секреты: `api_key` запрашивается
  inline, вне шагов.

`SaveTurn` и `SavePrompt` чекпоинтов не добавляют — они идемпотентные проекции уже durable данных,
поэтому drain перед их изменением не нужен.

## `GetRunContext`

`GetRunContext(agent_id, run_id)` → `RunContext`, весь контекст рана одним вызовом
(`run_id` = `agent_runs.id` = DBOS workflow id). Сборка — `RunContextService`; политика
(`ContextSpec`: `DIALOGUE` при prompt-канале в снапшоте `agent_runs.channels`, иначе
`SYSTEM_TRIGGER`) целиком на бэке, воркер рендерит блоки в присланном порядке.

| Поле | Содержимое |
|---|---|
| `system_blocks` | Упорядоченные `PromptBlock`, стабильные первыми — ради prompt-cache: agent → инструкции → блоки `PromptBlockProvider`-коннекторов → team → листинг скиллов → тела скиллов → trigger guidance |
| `user_blocks` | User-ход: блоки коннекторов (`ephemeral=true` — в историю не попадают) + основной промпт последним. Диалоговый текст `trusted`, событие триггера `trusted=false` — воркер оборачивает как недоверенные данные |
| `tools` | `ConnectorToolSpec`, уже отскоупленные binding-гейтом и скиллами |
| `history` | Сессионная история «как видел пользователь»: только завершённые раны (`completed=true`), окно 50, фильтр `historyDetail` из пресета |
| `inbound_parts` | Ссылки на вложения текущего рана (`agf_`), без байтов — безопасно для чекпоинта |

`PromptBlock{name, source, content, attrs, trusted, ephemeral}`: `name`/`attrs` становятся
XML-тегом у рендерера, пустой `name` — сырой текст. LLM-креды в `RunContext` **не входят** —
`GetLlmCredentials` вызывается inline на каждый `llm_call`.

### Тул-ходы в истории

Текстовая история учила модель **имитировать вызов тула текстом**: строка вида «🔧 name» выдавалась
за финальный ответ, и тул не исполнялся (слабые модели делают это регулярно). Поэтому тул-ход живёт
в истории структурно, и дробится на две записи:

- `PROGRESS/TOOL_CALL` с `tool_turn{text, calls[]}` — **до** исполнения, `text` идёт в канал сразу;
- `PROGRESS/TOOL_RESULT` с `tool_turn{results[]}` — **после**, `text` пуст, в канал не доставляется.

`GetRunContext.history` отдаёт их соседними записями, воркер сшивает в нативную пару
`tool_use`/`tool_result` look-ahead'ом. Осиротевшую results-запись (calls-половину срезало окном)
воркер отбрасывает — `tool_result` без `tool_use` провайдеры отклоняют. Легаси-строки `🔧 name`
санитизируются в `[вызван инструмент name]`. Дополнительно в цикле воркера стоит guard: «финал» с
паттерном имитации не принимается, вместо него идёт корректирующий user-ход (до 2 раз за ран).

## `GetFile`

`GetFile(file_id, agent_id)` → `stream FileChunk{data, mime, total_size}` — содержимое вложения
чанками ~128 КБ (первый несёт `mime` и `total_size`). Гейт владения: `file.user_id == agent.user_id`,
иначе `NOT_FOUND` — существование чужих файлов не раскрывается.

Как и `GetLlmCredentials`, вызывается inline при `llm_call` и **не оборачивается в durable-шаг**,
чтобы байты не попали в чекпоинт. Недоступный файл воркер пропускает: текст сообщения уже содержит
стаб, «зрение» деградирует, ран не падает. Потолок сборки — 32 МБ.

## Журнал: `SaveMessage`, `SaveTurn`, `SavePrompt`

Три записи с разным назначением — их легко перепутать:

| RPC | Таблица | Назначение | Капы |
|---|---|---|---|
| `SaveMessage` | `channel_session_messages` | Канальная проекция «как видел пользователь» | JSON-поля до 32 КБ |
| `SaveTurn` | `agent_run_turns` | Канонический транскрипт ходов | без капов |
| `SavePrompt` | `agent_runs.prompt` | Снимок промпта на старте рана | — |

### `SaveMessage`

`SaveMessage(agent_id, run_id, seq, kind, progress_type, text)`. Идемпотентность — UNIQUE
`(run_id, seq)` с `ON CONFLICT DO NOTHING`; доставка дедуплицируется детерминированным `message_id`
от `(run_id, seq)`.

- `INBOUND` (seq=0) — ack «агент получил»; текст пуст, канонику бэк берёт сам.
- `PROGRESS` (+ `progress_type`) → progress-канал, если он есть.
- `ANSWER` → answer-канал; в той же транзакции все сообщения рана помечаются `completed=true` и ран
  становится виден истории. Текст пишется в `agent_runs.result` для **любого** рана.
- `ERROR` → progress/answer/prompt-фолбэк, текст в `agent_runs.error`. **ERROR не завершает ран** —
  его сообщения в историю не попадут.

### `SaveTurn`

По строке на ход: `ASSISTANT` несёт `text`/`thinking`/`tool_calls`, `TOOL` — только `tool_results`.
Пишется для всех ранов, включая direct. Идемпотентность — UNIQUE `(run_id, turn_index)`.

`finish_reason`/`model`/`call_id` — provenance LLM-хода; `call_id` = id дочернего `llm_call` =
join-ключ к `llm_usage_log.call_id`.

**Асимметрия транскрипта и учёта.** Турн пишется только на спроецированные ходы, а usage
(`ReportLlmUsage`) считается на **каждый** дошедший до модели вызов — включая имитацию вызова
текстом и truncation-обрыв, которые турна не дают. Поэтому join `llm_usage_log → agent_run_turns`
может дать usage-строки без парного турна. Это ожидаемо: учёт покрывает весь расход, журнал —
только транскрипт. `agent_run_turns` не является полным перечнем LLM-вызовов.

### `SavePrompt`

Снимок промпта ровно как он ушёл в первый LLM-вызов: `system + history + триггер`, сериализованный
воркером в JSON. **First-write-wins** — пишется только если `prompt IS NULL`: replay переотправит
снимок, но окно истории могло сдвинуться, и нужен именно стартовый. Вместе `prompt` = вход рана,
`agent_run_turns` = его выход.

Точность content-exact, но не wire-exact: обёртки Spring AI и провайдерский JSON не отражаются.
Вложения — ссылками, не байтами. Хранится opaque-деревом, бэк его не интерпретирует.

## Исполнение тулов

`ToolGateway` оборачивает `AgentToolCallService`: идемпотентность по `tool_call_id`, ABAC, аудит в
`tool_call_logs`, доставка через `ConnectorService`.

BACKEND-locus тулы исполняются асинхронно на bounded-пуле `toolExecutor` (8..32 потока, очередь 200,
CallerRuns при переполнении): ack `ExecuteToolAsync` возвращается сразу, долгий вызов не держит
gRPC-тред, результат воркер забирает поллингом `GetToolResult` (PENDING → SUCCESS/ERROR).

| Код | Причина |
|---|---|
| `PERMISSION_DENIED` | ABAC запретил. Это **валидный результат тула**, а не сетевая ошибка — воркер отдаёт его модели как tool response |
| `ABORTED` | Тот же `tool_call_id` переиспользован с другим входом |
| `INVALID_ARGUMENT` | Нет `tool_call_id`/`connector_code`/`tool_name` либо не парсится UUID |

### Откуда берутся тулы

Отдельного discovery-RPC нет — тулы приходят в `RunContext.tools`, ключ `connections.id`. Активные
binding'и фильтруются скоупом скиллов, затем по `definition_binding`: **STATIC**-коннекторы отдают
тулы рефлексией `@Tool`-методов, **DYNAMIC** (`mcp`, device `app`) — набор из `connection_tools`.

**Именование.** В хранилище имена — голые локальные идентификаторы (`schedule`, `get_tasks`), без
префикса коннектора. Глобальную уникальность для модели даёт `namespace`, который бэк выводит на
экземпляр: `connector_code` для контекстных синглтонов (time, board, persist-memory) и `full_code`
для мульти-инстансных (`mcp_context7`, `telegram_<bot>`). Агент видит `time.schedule`,
`mcp_context7.resolve-library-id`. На `ExecuteTool` воркер шлёт **голое** имя — namespace
презентационный, роутинг не меняет.

Листинг **не применяет** per-tool политики: запрет срабатывает на исполнении
(`PERMISSION_DENIED`). Воркер может показать модели тул, вызов которого будет отклонён.

## Жизненный цикл рана

Строка `agent_runs`, её id — канонический `run_id` == DBOS `workflow_id`.

`status` — проекция потока `SaveMessage`, нужна только для наблюдаемости и ортогональна
`result`/`error`:

```
ENQUEUED ──первый SaveMessage──► RUNNING ──ANSWER──► DONE
                                    └─────ERROR / stale──► FAILED
```

Терминальные статусы липкие — переигранный INBOUND не воскрешает завершённый ран.

**Liveness.** Каждый RPC рана продлевает `last_activity_at` (`RunActivityService.touch`,
best-effort). Самый длинный легальный тихий участок — один LLM-вызов с ретраями; ран, молчащий
дольше 15 минут, добирает `@Scheduled`-сборщик: `RUNNING` → `FAILED` с маркером. Это никого не
блокирует — следующий ран сессии стартует независимо от статуса предыдущего.

## Границы контракта

Сознательно не входит: mTLS и per-workflow JWT (расширение будет аддитивным — добавится
`x-workflow-token`, существующие RPC не сломаются); LLM Gateway — креды отдаются воркеру напрямую
через `GetLlmCredentials`; стриминговое и батчевое исполнение тулов; Knowledge Base.

## Локальный запуск

gRPC-сервер по умолчанию выключен:

```bash
GRPC_SERVER_ENABLED=true \
GRPC_SERVER_SECURITY_ENABLED=false \
WORKER_POOLS_AUTHKEYS_0=<authkey> \
./gradlew :control-api:bootRun
```

Проверка (`brew install grpcurl`):

```bash
grpcurl -plaintext \
  -H "authorization: Bearer <full key>" \
  -H "x-worker-instance: $(uuidgen)" \
  -d '{}' localhost:9091 ru.agimate.agentworker.WorkerControl/HealthCheck
```
