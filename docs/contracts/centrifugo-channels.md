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
| `user` | Пользовательские уведомления (`allow_tags_filter`) | 100 публикаций, 10 мин |
| `webchat` | Веб-чат, с восстановлением при реконнекте | 100 публикаций, 24 ч |

Во всех неймспейсах `allow_subscribe_for_client` и `allow_publish_for_client` — `false`:
подписки и публикации только серверные, клиент сам ни на что подписаться не может.

## Что приходит в `user:{userId}`

Личный канал пользователя: события, которые надо получить, не находясь ни в одной конкретной
сущности. Токены — `POST /manage/centrifugo/token`, подписка одна на всё приложение. У неймспейса
включён `allow_tags_filter`, и каждое событие несёт теги — клиент может отфильтровать доставку на
стороне Centrifugo, а не разбирать всё подряд.

| Тип | Когда | Теги | Полезная нагрузка |
|---|---|---|---|
| `board.task.*` | изменения задач доски | `entity=board.task`, `boardId` | задача |
| `webchat_activity` | агент доставил сообщение в веб-чат (`answer`/`error`, но не `progress`) | `entity=webchat.message`, `agentId` | `agentId`, `sessionId`, `messageId`, `stream`, `preview`, `createdAt` |

`webchat_activity` намеренно тонкое: оно поднимает бейдж в списке контактов, пока клиент не открыл
ни одной переписки. Само сообщение едет своим каналом `webchat:{sessionId}` — клиент с открытым
чатом рисует его оттуда и дедуплицирует по `messageId`.
