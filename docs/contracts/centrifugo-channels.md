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
