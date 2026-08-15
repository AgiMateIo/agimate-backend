# Webchat-коннектор

Чат пользователя с агентом из собственного фронта (аналог Telegram-канала, но платформа — наш UI).
Internal-коннектор без тулов и джоб: входящие приходят REST'ом (`/manage/webchat/**`, см.
OpenAPI, `/control/manage/webchat`), исходящие доставляются на фронт через Centrifugo.

## Модель

- **Одна connection на пользователя** — контекстный коннектор со scope `USER`
  (`scope_id = userId`). Материализуется `ConnectionBindingService` find-or-create при первом чате
  с любым агентом; агенты подключаются к ней binding'ами `agent_connections`. Отвязка последнего
  агента сворачивает connection.
- **Канал per-agent** — `(agentId, "webchat", connectionId)`, handler `webchat`, создаётся лениво
  при первой сессии с агентом (`ChannelService.create` — с валидацией и binding'ом источника).
- **Сессии = `agent_sessions`** (тот же single-writer/steering/history-контур, что и у Telegram),
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
               "direction": "AGENT", "stream": "answer", "text": "...",
               "parts": [ { "type": "image", "fileId": "agf_…", "mime": "image/png",
                            "size": 384211, "url": "/files/agf_…?exp=…&sig=…" } ],
               "createdAt": "..." } }
```

События — at-least-once (replay переиздаёт), фронт дедуплицирует по `messageId`. Echo сообщений
пользователя публикуется тем же типом с `direction=USER` (синхронизация вкладок). Namespace
`webchat` в Centrifugo включает history+recovery — реконнект добирает пропущенное.

## Непрочитанное и бейджи

Указатель прочтения — `agent_sessions.last_read_message_id`: строка `webchat_messages`, до которой
пользователь дочитал. Именно `id` строки, а не `message_id` доставки: `id` — uuidv7, и его порядок в
PostgreSQL совпадает с временным, поэтому сравнение точное и без ничьих внутри миллисекунды.

Непрочитанным считается сообщение с `direction = AGENT` и `stream ≠ progress` — то есть `answer` и
`error`. Промежуточные строки в счётчик не идут: один ответ проходит через десяток `progress`, и
бейдж сказал бы «12» про одну реплику. `error` идёт: провалившийся запуск по расписанию иначе
останется незамеченным.

Указатель двигают три события, и всегда только вперёд (`advanceReadPointer`) — иначе второе
устройство, открывшее старый вид переписки, отменило бы прочтение первого:

- явная отметка `POST /manage/webchat/sessions/{id}/read` (тело `{lastReadMessageId}`; без тела —
  до конца сессии);
- отправка своего сообщения — написал, значит прочитал;
- закрытие сессии: закрытая переписка хранит историю, но больше не просит внимания в списках.

**Бейдж, когда открыт список, а не чат.** События `webchat_message` живут в канале
`webchat:{sessionId}`, на который клиент в списке контактов не подписан. Поэтому доставленное
сообщение агента дублируется тонким событием в собственный канал пользователя `user:{userId}`
(`docs/contracts/centrifugo-channels.md`). Публикация бейджа обёрнута в try/catch: потерянный бейдж
чинится следующим листингом, а исключение уронило бы уже записанное и опубликованное сообщение.

## Списки: что несёт строка

`GET /manage/webchat/sessions/` и `GET /manage/webchat/contacts/` отдают, кроме самой строки, три
вычисляемых поля: `unreadCount`, `lastMessage` (превью, обрезанное до 160 символов; `progress` не
превью) и `isRunning`.

`isRunning` — есть ли у сессии живой ран: `RUNNING`, либо `ENQUEUED` не старше порога сметателя
(15 минут). Порог обязателен: `ENQUEUED` не подметается ничем, и ран, который воркер так и не взял,
иначе оставил бы «печатает…» гореть вечно.

Контакты — отдельный эндпойнт, а не поля в `GET /manage/agents/`: список отсортирован по свежести
переписки, ключ сортировки — агрегат по `agent_sessions`, и склеить такой порядок на клиенте из двух
листингов между страницами нельзя. Каждое из трёх полей — один батч-запрос на страницу, а не запрос
на строку.

## Две истории

| Таблица | Что хранит | Кто пишет |
|---|---|---|
| `channel_session_messages` | LLM-история сессии (turn'ы для восстановления контекста воркером) | worker (gRPC Append) |
| `webchat_messages` | UI-лог: что реально показано пользователю (USER + AGENT answer/progress) | control-api на границе доставки |

## Файлы

**Вложения ответа агента работают** (attach-конвенция, `docs/connectors/files.md`):
`supportsOutboundAttachments = true`, `OutboundMessage.parts` пишутся в `webchat_messages.parts`
(без URL — только `type/fileId/mime/size` плюс `name`, если у файла оно есть) и уходят фронту в событии и истории со свежим
подписанным URL содержимого (`GET /files/{fileId}?exp&sig`, TTL `app.files.url-ttl`). Parts несёт
только answer-стрим. Изображения фронт рендерит `<img src>` прямо по ссылке.

**Входящие файлы от пользователя работают** (docs/connectors/files.md, раздел «Входящие вложения»):
`POST /manage/webchat/files` (multipart) → `fileId`, затем `parts: [{fileId}]` в send-запросе.
`WebchatService` валидирует владение/READY/TTL, кладёт `parts` в data триггера; `handleInput` мапит
их в `InboundMessage.parts` + текст-стаб. Воркер тянет байты изображения `GetFile`'ом при LLM-вызове
и подаёт модели как `Media` («зрение»). Эхо USER-сообщения несёт свои `parts` (подписанный URL).
