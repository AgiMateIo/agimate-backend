# Webchat-коннектор

Чат пользователя с агентом из собственного фронта (аналог Telegram-канала, но платформа — наш UI).
Internal-коннектор без тулов и джоб: входящие приходят REST'ом (`/manage/webchat/**`, см.
`docs/services/control-api-manage-webchat.md`), исходящие доставляются на фронт через Centrifugo.

## Модель

- **Одна connection на пользователя** — контекстный коннектор со scope `USER`
  (`scope_id = userId`). Материализуется `ConnectionBindingService` find-or-create при первом чате
  с любым агентом; агенты подключаются к ней binding'ами `agent_connections`. Отвязка последнего
  агента сворачивает connection.
- **Канал per-agent** — `(agentId, "webchat", connectionId)`, handler `webchat`, создаётся лениво
  при первой сессии с агентом (`ChannelService.create` — с валидацией и binding'ом источника).
- **Сессии = `channel_sessions`** (тот же single-writer/steering/history-контур, что и у Telegram),
  но выбираются **явно**: фронт создаёт новую сессию в любой момент (`createNew`, минуя
  12h-TTL-эвристику), а `sessionId` едет в триггере declared prompt-`ChannelInfo` —
  `ChannelRouteResolver` использует объявленную открытую сессию вместо `findOrCreateActive`.
- **Audience обязателен**: connection общая, поэтому входящий триггер несёт
  `TriggerAudience.targetAgentIds = [агент канала]` — иначе fanout на всех привязанных агентов.

## Доставка вывода агента

`WebchatChannelHandler` — единственный handler с `deliverProgress = true`: роутер заполняет
progress-роль `Channels` тем же каналом, и worker шлёт и промежуточные строки, и финальный ответ
(`SendChannelMessage.stream = progress|answer|error`; поле добавлено в proto, пустое = answer).

`handleOutput` не вызывает тулов: `WebchatMessagePublisher` пишет строку в `webchat_messages`
(идемпотентно по `(session_id, message_id)` — worker шлёт детерминированные id, DBOS-replay не
дублирует) и публикует событие в Centrifugo-канал `webchat:{sessionId}`:

```json
{ "type": "webchat_message",
  "payload": { "sessionId": "...", "channelId": "...", "agentId": "...", "messageId": "...",
               "direction": "AGENT", "stream": "answer", "text": "...", "createdAt": "..." } }
```

События — at-least-once (replay переиздаёт), фронт дедуплицирует по `messageId`. Echo сообщений
пользователя публикуется тем же типом с `direction=USER` (синхронизация вкладок). Namespace
`webchat` в Centrifugo включает history+recovery — реконнект добирает пропущенное.

## Две истории

| Таблица | Что хранит | Кто пишет |
|---|---|---|
| `channel_session_messages` | LLM-история сессии (turn'ы для восстановления контекста воркером) | worker (gRPC Append) |
| `webchat_messages` | UI-лог: что реально показано пользователю (USER + AGENT answer/progress) | control-api на границе доставки |

## Файлы (следующая фаза)

Контракт заложен: `Part(type, storageRef, mime, size, meta)` в `InboundMessage`/`OutboundMessage`/proto,
колонка `parts JSONB` в `webchat_messages`, поле `parts` в send-запросе (пока обязано быть пустым).
Понадобится storage-сервис (S3/minio) + upload-эндпойнт + прокачка parts в worker/LLM.
