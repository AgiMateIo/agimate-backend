# control-api: ACP-эндпоинт (`/acp`) — диалог с агентом из IDE

Реализация agent-side [Agent Client Protocol](https://agentclientprotocol.com) (ACP от Zed —
JSON-RPC 2.0; не путать с Agent Communication Protocol из
[acp-review-backen.md](../acp-review-backen.md)). Позволяет разговаривать с агентом из Zed и
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
| `session/new` | binding+канал find-or-create, новая `channel_sessions`-строка → `{sessionId}`; `cwd`/`mcpServers` игнорируются |
| `session/load` | реплей истории из `channel_session_messages` нотификациями `session/update` (INBOUND → `user_message_chunk`, ANSWER → `agent_message_chunk`), PROGRESS не реплеится |
| `session/prompt` | text-блоки склеиваются → триггер-пайплайн; ответ асинхронный: rpc-id висит в registry до ANSWER (`{stopReason: "end_turn"}`) или ERROR (JSON-RPC error `-32000`) |
| `session/cancel` | мягкий: отпускает клиента (`stopReason: "cancelled"`), ран доработает, ответ останется в истории |

Маппинг progress-событий агента на `session/update`: THINKING → `agent_thought_chunk`,
TOOL_CALL → `tool_call` (сразу `completed` — пер-тул статусов у бэка нет), TEXT и финальный
answer → `agent_message_chunk`.

Коды ошибок: `-32602` bad request / не-текстовый контент, `-32001` not found, `-32003` forbidden,
`-32002` prompt уже в полёте, `-32000` ошибка рана агента, `-32603` внутренняя.

## Ограничения MVP (осознанные)

- **Одна реплика control-api**: `AcpSessionRegistry` in-memory, проекция SaveMessage происходит
  на инстансе, принявшем gRPC воркера. Масштабирование → внутренний pub/sub (Centrifugo).
- **Turn-level стриминг**: чанки по завершённым ходам модели (как webchat), не token-level.
- `session/cancel` не останавливает ран на сервере (механизма отмены рана нет).
- Только текстовый контент prompt'а; клиентские capabilities (fs/terminal) не используются.
- Разрыв WS теряет pending prompt на клиенте; история не теряется — `session/load` покажет ответ.
