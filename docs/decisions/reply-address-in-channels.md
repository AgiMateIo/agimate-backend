---
status: partial
created: 2026-09-15
updated: 2026-09-15
---

# Адрес ответа в снимке каналов рана

Ран отвечает туда, что записано в его `agent_runs.channels`. Сегодня для Telegram и Generic этого
мало: снимок знает канал и сессию, а чат (или другие поля адреса) достаётся при отправке из журнала
сессии. Решение переносит адрес в снимок.

## Что было до

- `ChannelInfo` — `{channelId, sessionId, messageId}`; `messageId` не заполняется. У Telegram канал —
  это бот на `(agent, telegram, connection_id)`, чатов у него сколько угодно, и все они живут в одной
  активной сессии канала.
- Чат есть только во входе сообщения (`trigger_logs.input.chatId`). `MessageLogPersistence` копирует
  вход рана в `channel_session_messages.trigger_input` у каждой строки `INBOUND` — у рана события
  тоже.
- `ChannelMessageOutboundService.send` получает канал и сессию, а `replyContext` для обработчика берёт
  из `lookupLastInboundTrigger`: `trigger_input` самой свежей строки сессии. Telegram читает из него
  `chatId`, иначе `config.defaultChatId`, иначе бросает; Generic подставляет `{trigger.*}`.

Ран события — `report_received` субагента, `tool_completed`, `time.due` — перед ответом пишет свою
строку `INBOUND` с данными события без `chatId`, потом ищет адрес и находит её. Ответ не доставляется,
ошибку глотает доставка. Субагенты сделали это ежедневным: в Telegram не доходит ни один отчёт, если
не задан `defaultChatId`. Та же механика отправит ответ не в тот чат, если бот говорит с несколькими
чатами и последним написал другой.

## Решение

**Адрес — часть `ChannelInfo`.** Новое поле `address` (`Map`, пустое не хранится). Кладёт его роутер
при маршрутизации сообщения; что считать адресом, решает обработчик канала:

- `ChannelHandler.replyAddress(config, trigger)`, по умолчанию пусто;
- Telegram — `{chatId}`;
- Generic — только те поля `trigger.data`, на которые ссылается `replyToolParams` через
  `{trigger.<path>}`, в той же вложенности: весь payload копировался бы в снимок каждого рана события;
- webchat, ACP, субагенты — пусто: они адресуют сессией.

**Копии снимка переносят адрес сами.** `DetachedToolResultDelivery` и `SubagentReportDelivery` копируют
`ChannelInfo` целиком; `ChannelRouteResolver.resolveProactiveChannels` переносит его рядом с
`messageId`. Ответ «нечего останавливать» на `/stop` получает адрес чата, приславшего команду.

**Доставка читает адрес из цели.** `send` принимает целевой `ChannelInfo`; `OutboundDispatch.replyContext`
переименован в `address`.

**Напоминания.** `time.schedule` сохраняет в строку джобы канал и сессию, а `fire` собирает
`ChannelInfo` заново — адрес при этом терялся бы. `schedule` берёт адрес из снимка вызвавшего рана
(слот `prompt` или `answer` с сессией вызова) и кладёт в аргументы джобы `replyAddress`; `fire`
получает его параметром и возвращает в `ChannelInfo`.

Колонка `connector_jobs.reply_address` рядом с `channel_id`/`session_id` была бы симметричнее, но
потянула бы миграцию, поле в `ConnectorEnv` и чтение снимка рана в `ToolExecutionService` для каждого
вызова тула. Колонки канала и сессии нужны платформе — по ним строится партиция и история; адрес
никто, кроме `fire`, не читает, поэтому он живёт в аргументах, как и `prompt`.

## Переход

У снимков, созданных до выката, адреса нет: раны в очереди, отчёт и `tool_completed`, чей исходный
ран старше выката, напоминания, запланированные раньше. Для них `send` на один выпуск оставляет
старый поиск по журналу. Чтобы этот запасной путь был верным, `trigger_input` пишется только у ранов
с prompt-каналом: событие больше не становится «последним входом».

## Что не решает

- Стиринг между чатами: ран из чата 1, поглотивший сообщение из чата 2, ответит только в чат 1.
  Причина — одна сессия на бота, а не на чат; см. [agent-participants.md](agent-participants.md).
- `messageId` (ответ на конкретное сообщение, треды) по-прежнему не заполняется.

## Отвергнуто

- **Только не писать `trigger_input` у событий.** Убирает симптом, но адрес остаётся общим значением
  сессии «кто писал последним»: `tool_completed` уйдёт в чат последнего написавшего, а не вызвавшего.
- **Весь `trigger.data` как адрес Generic.** Размер снимка рана растёт на payload события в каждой
  копии.
- **`chatId` в данных событий.** Каждый источник событий начал бы знать адреса каналов.

## План

- [x] `ChannelInfo.address`, `ChannelHandler.replyAddress`: Telegram, Generic.
- [x] `ChannelRouteResolver`: адрес у обычного маршрута, у `/stop` и у проактивного.
- [x] `ChannelMessageOutboundService.send` по целевому `ChannelInfo`; запасной поиск по журналу.
- [x] `MessageLogPersistence`: `trigger_input` только у ранов с prompt-каналом.
- [x] Напоминания: адрес в аргументах джобы `time.fire`.
- [x] `architecture/channels-and-triggers.md`.
- [ ] Живой прогон: отчёт субагента и `tool_completed` в Telegram без `defaultChatId`.
- [ ] После одного выпуска: удалить запасной поиск по журналу и колонку `trigger_input`.
