# Каналы Centrifugo

Real-time delivery to connected apps and agents. Channels are namespaced (`app`, `agent`, `user`,
`webchat` — see `ops/centrifugo/config.yaml`); all of them are server-side only, clients may
neither subscribe nor publish on their own.

Client connection and subscription tokens are ES256 JWTs signed by control-api with
`CENTRIFUGO_PRIVATEKEY`; Centrifugo verifies them with the matching public key in
`client.token.ecdsa_public_key`. This pair is independent of the user-JWT one.


## Неймспейсы

Конфигурация — шаблон `ops/templates/centrifugo.config.yaml`, из него
`ops/dev-init.sh` рендерит `ops/centrifugo/config.yaml`.

| Неймспейс | Назначение | История |
|---|---|---|
| `app` | Push тул-вызовов в подключённые приложения | 100 публикаций, 24 ч |
| `agent` | Доставка результатов и событий агентам | 100 публикаций, 10 мин |
| `user` | Все уведомления приложений, сообщения веб-чата тоже (`allow_tags_filter`, восстановление при реконнекте) | 1000 публикаций, 6 ч |
| `webchat` **(устарел)** | Сообщения одной переписки — до перехода клиентов на `user` | 100 публикаций, 24 ч |

Во всех неймспейсах `allow_subscribe_for_client` и `allow_publish_for_client` — `false`:
подписки и публикации только серверные, клиент сам ни на что подписаться не может.

## Как публикуется

Всё, что уходит в Centrifugo, идёт через пакет `realtime` control-api
([decisions/realtime-notifications.md](../decisions/realtime-notifications.md)): домен сообщает факт
(`RealtimeEvent`), `RealtimeMessages` решает, в какой канал, с каким типом, тегами и нагрузкой он
уходит, `RealtimePublisher` отправляет. Правила одни для всех каналов:

- **только после коммита** транзакции, в которой случилось изменение; откаченное не уходит никогда;
- **сбой публикации ни на что не влияет** — он в логе, и только; потерянное событие чинит следующее
  чтение клиента, потерянную команду `agent:`/`app:` — таймаут вызова или рана;
- **одинаковые события одной транзакции** уходят один раз;
- **имя канала** собирает только `RealtimeChannels` — и для публикации, и для токена подписки.

## Что приходит в `user:{userId}`

Личный канал пользователя: события, которые надо получить, не находясь ни в одной конкретной
сущности. Токены — `POST /manage/centrifugo/token`, подписка одна на всё приложение. У неймспейса
включён `allow_tags_filter`, и каждое событие несёт теги — клиент может отфильтровать доставку на
стороне Centrifugo, а не разбирать всё подряд.

| Тип | Когда | Теги | Полезная нагрузка |
|---|---|---|---|
| `board.task.*` | изменения задач доски | `entity=board.task`, `boardId` | задача |
| `agent.request.*` | поручения между агентами команды: `started`, `appended`, `reported`, `cancelled` | `entity=agent.request`, `teamId` | строка поручения, как в `GET /manage/agentic-teams/{teamId}/requests/` |
| `session.created` | появилась сессия: новый веб-чат, первое событие коннекции, субагент | `entity=session`, `agentId` | строка, как в `GET /manage/sessions/` |
| `session.updated` | изменилась строка сессии: заголовок, закрытие, прочтение, сообщение веб-чата, начало и конец рана | `entity=session`, `agentId` | строка, как в `GET /manage/sessions/` |
| `webchat.agent.updated` | то же, если сессия веб-чата | `entity=webchat.agent`, `agentId` | строка, как в `GET /manage/webchat/contacts/` |
| `webchat.message` | сообщение переписки веб-чата: ответ агента, `progress`, эхо своего | `entity=webchat.message`, `agentId`, `sessionId` | сообщение, как ниже в `webchat:{sessionId}` |
| `webchat_activity` **(устарело)** | агент доставил сообщение в веб-чат (`answer`/`error`, но не `progress`) | `entity=webchat.message`, `agentId` | `agentId`, `sessionId`, `messageId`, `stream`, `preview`, `createdAt` |

События сессий несут строку целиком, собранную после коммита: клиент заменяет строку по `id`
(контакт — по `agentId`), что бы ни поменялось, а повтор доставки ничего не портит. Когда какое из
них публикуется — [decisions/session-events.md](../decisions/session-events.md).

`webchat_activity` — тонкое прибавочное событие для бейджа: «пришло ещё одно, вот превью». Его
заменяют `session.updated` и `webchat.agent.updated`; публикуется параллельно, пока веб-фронт и
Android не перейдут, потом удаляется.

`webchat.message` заменяет канал переписки `webchat:{sessionId}`: одна подписка на всё приложение
вместо переподписки при каждом открытии чата. Клиент раскладывает события по тегу `sessionId` у себя
и дедуплицирует по `messageId`. До перехода клиентов то же сообщение параллельно уходит и в
`webchat:{sessionId}` типом `webchat_message`; потом канал, его неймспейс и эндпойнт токена
`/manage/webchat/sessions/{id}/token` удаляются.
