# control-api: ACP-эндпоинт (`/acp`) — диалог с агентом из IDE

Реализация agent-side [Agent Client Protocol](https://agentclientprotocol.com) (ACP от Zed —
JSON-RPC 2.0; не путать с Agent Communication Protocol из
[acp-review-backend.md](../decisions/acp-comparison.md)). Позволяет разговаривать с агентом из Zed и
других ACP-клиентов.

## Транспорт и подключение

ACP-клиенты запускают агента как локальный сабпроцесс (stdio), а наши агенты живут на сервере,
поэтому подключение двухзвенное:

```
IDE (Zed) ──stdio NDJSON──► clients/acp-bridge ──wss + X-Api-Key──► control-api /acp
```

- Эндпоинт: WebSocket `/acp` на основном HTTP-порту control-api; с учётом context-path
  (`/control`) внешний путь — **`/control/acp`** (ingress должен пропускать WebSocket-upgrade).
  Один JSON-RPC фрейм = один текстовый фрейм WS.
- Мост: [`clients/acp-bridge`](../../clients/acp-bridge/README.md) — транспорт без логики протокола.

## Аутентификация

Агентский `X-Api-Key` на handshake (тот же контур, что `/agent/**`: `AgentAuthFilter`,
`apiKeySecurityFilterChain`). Ключ привязан к паре агент+владелец: **один ключ = один агент** =
одна запись `agent_servers` в IDE. ACP-метод `authenticate` не используется (`authMethods: []`),
`AcpHandshakeInterceptor` переносит `AgentPrincipal` в атрибуты WS-сессии.

## Устройство (зеркало webchat)

| Слой | Класс |
|---|---|
| Коннектор (internal, USER-scope connection) | `connectors/internal/acp/AcpConnectorService` |
| Канал (handler `acp`, `deliverProgress=true`) | `service/channel/handler/AcpChannelHandler` |
| Оркестрация сессий/prompt | `service/acp/AcpService` |
| Живые соединения + pending prompt | `service/acp/AcpSessionRegistry` |
| JSON-RPC поверх WS | `acp/AcpWebSocketHandler`, `config/AcpWebSocketConfig` |

ACP-сессия ↔ `channel_sessions.id` (single-writer-per-session очереди `agent_exec` совпадает с
ACP-требованием «один активный prompt на сессию»). Вход — штатный триггер-пайплайн
(`message_received` коннектора `acp` → `TriggerRouterService`), выход — проекция SaveMessage:
`MessageLogService.deliver` → `AcpChannelHandler.handleOutput` → JSON-RPC фрейм в живое соединение.

## Методы

| Метод | Поведение |
|---|---|
| `initialize` | `{protocolVersion: 1, agentCapabilities: {loadSession: true}, authMethods: []}` |
| `session/new` | binding+канал find-or-create, новая `channel_sessions`-строка → `{sessionId}`; `cwd` кладётся в registry как корень сессии, `mcpServers` мост забирает себе (наверх идёт `_agimateMcp`) |
| `session/load` | реплей истории из `channel_session_messages` нотификациями `session/update` (INBOUND → `user_message_chunk`, ANSWER → `agent_message_chunk`), PROGRESS не реплеится; `cwd` — как в `session/new` |
| `session/prompt` | text-блоки склеиваются → триггер-пайплайн; ответ асинхронный: rpc-id висит в registry до ANSWER (`{stopReason: "end_turn"}`) или ERROR (JSON-RPC error `-32000`) |
| `session/cancel` | мягкий: отпускает клиента (`stopReason: "cancelled"`), ран доработает, ответ останется в истории |

Маппинг progress-событий агента на `session/update`: THINKING → `agent_thought_chunk`,
TOOL_CALL → `tool_call` (сразу `completed` — пер-тул статусов у бэка нет), TEXT и финальный
answer → `agent_message_chunk`.

Коды ошибок: `-32602` bad request / не-текстовый контент, `-32001` not found, `-32003` forbidden,
`-32002` prompt уже в полёте, `-32000` ошибка рана агента, `-32603` внутренняя.

## IDE-тулы (fs/terminal) — обратный вызов в живое соединение

Агент получает тулы, исполняемые **на машине пользователя** обратным JSON-RPC-вызовом в живой
WebSocket ACP-сессии (клиент — Zed). Это связывает два существующих паттерна: DELEGATED-подобную
доставку вызова наружу (control-api диспатчит, но сам не исполняет) и `session/request_permission`
(диалог подтверждения рисует IDE).

**Тулы** (`connectors/internal/acp/AcpToolService`, `DefinitionBinding.STATIC`):

| Тул | ACP-вызовы | Подтверждение | Хинты |
|---|---|---|---|
| `read_file(path, line?, limit?)` | `fs/read_text_file` | нет | readOnly, openWorld |
| `write_file(path, content)` | `session/request_permission` → `fs/write_text_file` | да | destructive, openWorld |
| `run_command(command, args?, cwd?)` | permission → `terminal/create` → `wait_for_exit` → `output` → `release` | да | destructive, openWorld |

### Корень проекта (`cwd`)

`cwd` из `session/new`/`session/load` живёт в `AcpSessionRegistry` рядом с capabilities: он приходит
при каждой привязке сессии, а тулы работают только при живом соединении — персистить нечего.
Относительный или пустой `cwd` трактуется как «не прислали».

Он нужен в двух местах:

- `run_command` без явного `cwd` подставляет корень сессии. Без подстановки `terminal/create` уходит
  без `cwd`, спека дефолт не определяет, и клиент выбирает свой — у Zed это домашняя директория,
  а не проект пользователя.
- `AcpConnectorService` (`PromptBlockProvider`) отдаёт SYSTEM-блок `ide_session` с путём проекта:
  `read_file`/`write_file` принимают только абсолютные пути, и взять их агенту иначе неоткуда.
  Блок появляется только когда prompt-сессия рана есть в registry, то есть разговор идёт из IDE.

После реконнекта моста `cwd` возвращается вместе с `mcpTools` в `_agimate/restore` — мост помнит
состояние каждой сессии независимо от того, поднимал ли он для неё MCP-серверы.

**Транспорт**: `AcpSessionRegistry.request(sessionId, method, params)` шлёт server→client запрос с
id `srv-N` и возвращает `CompletableFuture`; ответ клиента (фрейм без `method`, с `id`) роутится в
`handleResponse`. Тул-метод крутится в пуле `toolExecutor` и блокирующе ждёт future. Сессия
адресуется полем `ConnectorEnv.sessionId` (протянуто из `tool_call_logs.agent_session_id`).

**Доступность**: тулы попадают в контекст DIALOGUE-рана, чей prompt-канал — ACP
(`ChannelHandler.contributesPromptTools() = true` подмешивает коннектор канала в `RunContextService`
мимо скилл-гейта). Требуют клиентских capabilities из `initialize`
(`fs.readTextFile`/`fs.writeTextFile`/`terminal`).

**Сид-контент**: системный скилл `acp` (инструкция «как работать из IDE») и пресет `coder`
(«Программист», `skills: [acp, persist-memory]`) — `resources/seed/<lang>/`. Скилл объявляет
`connectors: []` **намеренно**: тулы приносит prompt-канал, а `connectors: [acp]` привязало бы
acp-connection к агенту и выдало бы `read_file`/`write_file`/`run_command` во всех каналах, где они
гарантированно падают (`SystemSkillBootstrapTest.CONNECTORLESS_SKILLS`). Состав тулов до подключения
IDE неизвестен (session-scoped MCP), поэтому скилл учит смотреть список тулов рана, а не перечисляет
их как данность.

**Обрыв IDE = валидный error tool-result** (не зависание): нет живого соединения / нет capability /
таймаут / отказ пользователя → `ConnectorException` → запись `error` в `tool_call_logs` → воркер
отдаёт модели `isError`-результат, ран продолжается без IDE. «Соединение оборвано» и «capability
выключена» — разные ошибки: первая транзиентная («IDE not connected»), вторая — отказ клиента.

**Размер фрейма**: лимит входящего/исходящего WebSocket-сообщения поднят до
`AcpWebSocketConfig.MAX_MESSAGE_BYTES` (8 МБ). Дефолт контейнера — 8 КБ, чего не хватает: ответы IDE
на `terminal/output`/`fs/read_text_file` больше → контейнер рвал бы соединение с close 1009.

## MCP-тулы IDE (session-scoped, проброс из Zed)

MCP-серверы, подключённые пользователем в IDE (Zed), становятся тулами агента, **пока идёт разговор
из IDE**. Агент у нас удалённый и до локальных (stdio) MCP-серверов сам не дотянется, поэтому вызовы
проксируются через живое ACP-соединение — по спеке ACP это client-side проксирование не описано, это
наше расширение поверх собственного моста.

**Поток:**
1. Zed передаёт `mcpServers` в `session/new`. **Мост** (`clients/acp-bridge`, работает MCP-хостом на
   базе `@modelcontextprotocol/sdk`) поднимает эти серверы локально, делает `tools/list` и инжектит
   агрегированный список в `session/new` полем `_agimateMcp` (`[{server, tool}]`).
2. `AcpWebSocketHandler` кладёт тулы в `AcpSessionRegistry.mcpTools[sessionId]` (in-memory,
   неймспейс-имя `<server>__<tool>` → спек + ссылка), чистится на disconnect.
3. `RunContextService` листит тулы prompt-канала session-aware (env с sessionId) →
   `AcpConnectorService.getTools(env)` мёржит фиксированные IDE-тулы + session MCP-тулы. LLM-имя —
   `acp.<server>__<tool>`.
4. Вызов → `AcpConnectorService.executeTool` → `AcpToolService.callMcpTool` → обратный `mcp/call_tool`
   в мост → локальный MCP-сервер. Мутирующие тулы (MCP-аннотация `readOnly=false`) сначала спрашивают
   `session/request_permission`.

**Доступ/ABAC**: MCP-тулы — обычные тулы ACP-коннектора, evaluate по имени
(`ConnectionAccessEvaluator`, `PolicyKind.TOOL`). Default-allow, DENY-политикой можно закрыть
конкретный тул/сервер. Вывод — openWorld → воркер оборачивает untrusted.

**Ограничения (сверх общих ACP-MVP):** только в IDE-канале и только пока чат открыт (session-scoped);
одна реплика control-api; список берётся на `session/new` (`tools/list_changed` в пределах сессии не
отслеживается — свежий список подхватит следующая сессия); на реконнекте (`session/load`) MCP-тулы
есть, только если клиент повторно прислал `mcpServers`.

## Реконнект: рестарт control-api не роняет IDE-чат

Разрыв WebSocket (деплой/рестарт control-api) мост переживает сам: реконнект с backoff
(до ~3 минут), IDE ничего не замечает. После реконнекта мост восстанавливает in-memory
состояние сервера:

1. реплеит `initialize` со спец-id (`bridge-init-N`), ответ глотает — IDE его не ждала;
2. шлёт нотификацию **`_agimate/restore`** `{sessions: [{sessionId, mcpTools, cwd}]}` — сервер для
   каждой сессии проверяет владение (`AcpService.assertOwned`), заново привязывает её к
   соединению, возвращает корень проекта и кладёт MCP-тулы. Чужие/несуществующие сессии молча
   скипаются (лог-warn).

Локальные MCP-серверы при этом живут в мосте непрерывно — не перезапускаются. `401/403` на
handshake — фатально сразу (ключ не станет валидным от ретраев), мост выходит с кодом 2.

Известный остаток: prompt, висевший в момент рестарта, не восстанавливается (pending rpc-id был
in-memory) — ран доработает, его ответ придёт как `session/update` и ляжет в историю, но
`{stopReason}` для того prompt-запроса Zed не получит; лечится cancel/новым сообщением.

Roadmap-развитие (durable-доступ к MCP во всех каналах, эффекты политик `ASK`/`LLM_DECISION`,
`channelOnly`-коннекторы) — см. `docs/connectors/architecture.md`.

**Бюджеты** (под worker poll-timeout `agent.tool.poll-timeout`, дефолт 60s): fs — 25s, подтверждение —
30s, `wait_for_exit` — 45s (по таймауту — `kill` + `release` + частичный вывод с `timedOut: true`).
Долгие команды упрутся в poll-timeout — операторам IDE-нагруженных агентов поднимать бюджет.

**Trust boundary**: `read_file`/`run_command` — `openWorld`, их вывод воркер оборачивает
untrusted-маркером. Каждый `write_file`/`run_command` спрашивает подтверждение (allow_once/reject_once,
без «always» в MVP). Серверный ABAC работает штатно: DENY-правило `AgentConnectionPolicy` на
`run_command` для агента режет тул до исполнения.

## Ограничения MVP (осознанные)

- **Одна реплика control-api**: `AcpSessionRegistry` in-memory, проекция SaveMessage происходит
  на инстансе, принявшем gRPC воркера. Масштабирование → внутренний pub/sub (Centrifugo).
- **Turn-level стриминг**: чанки по завершённым ходам модели (как webchat), не token-level.
- `session/cancel` не останавливает ран на сервере (механизма отмены рана нет).
- Только текстовый контент prompt'а.
- Разрыв WS теряет pending prompt на клиенте; история не теряется — `session/load` покажет ответ.
- IDE-тулы: `run_command` ограничен бюджетом ~45s (не для долгих процессов); IDE-тулы блокируют
  поток пула `toolExecutor` на время вызова; разрешения без «always allow».
