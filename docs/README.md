# AgiMate — документация

Платформа, где специализированные ИИ-агенты работают вместе: скиллы описывают, что агент умеет,
коннекторы дают ему тулы, триггеры и фоновые джобы, устройства выносят его за пределы браузера.

Разделы устроены по тому, зачем вы сюда пришли.

## Начать отсюда

| | |
|---|---|
| **[architecture/overview.md](architecture/overview.md)** | Из чего состоит система и почему именно так — читается один раз, целиком |
| **[operations/local-stack.md](operations/local-stack.md)** | Поднять локально за одну команду |

Схемы запросов и ответов здесь не дублируются — они генерируются из кода. Запустите сервис под
профилем `develop` и откройте OpenAPI:

```bash
./gradlew :control-api:bootRun --args='--spring.profiles.active=develop --server.port=8180'
# → http://localhost:8180/control/docs/ui
```

## Архитектура

Как устроено и почему. Читается для понимания системы, а не для решения конкретной задачи.

| | |
|---|---|
| [overview.md](architecture/overview.md) | Сервисы, владение данными, аутентификация, порты |
| [agents-and-runs.md](architecture/agents-and-runs.md) | Жизненный цикл рана, сборка контекста агента |
| [connectors.md](architecture/connectors.md) | SPI коннекторов: капабилити, реестр, джобы |
| [channels-and-triggers.md](architecture/channels-and-triggers.md) | Маршрутизация триггеров и каналы: политика решает «кому», канал — «как» |
| [content-language.md](architecture/content-language.md) | Язык системного контента установки |

## Контракты

Интерфейсы, которых нет в OpenAPI: другой транспорт или другая сторона.

| | |
|---|---|
| [worker-protocol.md](contracts/worker-protocol.md) | control-api ↔ agent-worker: DBOS + gRPC |
| [acp.md](contracts/acp.md) | Agent Client Protocol поверх WebSocket, для IDE |
| [api-keys.md](contracts/api-keys.md) | Позиционный формат ключей платформы |
| [centrifugo-channels.md](contracts/centrifugo-channels.md) | Неймспейсы реального времени и выпуск токенов |
| [trigger-log-probe.md](contracts/trigger-log-probe.md) | Probe-код обнаружения каналов: бэкенд, фронт и текст в мессенджере |

## Эксплуатация

| | |
|---|---|
| [local-stack.md](operations/local-stack.md) | Локальный стенд: профили `infra`, `edge`, `full` |
| [deploy.md](operations/deploy.md) | Переменные окружения, генерация ключей, порты |
| [ci.md](operations/ci.md) | Сборка и деплой |

## Сервисы

| | |
|---|---|
| [control-api.md](services/control-api.md) · [user-api.md](services/user-api.md) · [agent-worker.md](services/agent-worker.md) | Настройки, переменные окружения, устройство |

## Коннекторы

| | |
|---|---|
| [files.md](connectors/files.md) | Файловый слой, ссылки `agf_` |
| [platform.md](connectors/platform.md) | Мета-агент, управляющий платформой |
| [persistent-memory.md](connectors/persistent-memory.md) · [sheets.md](connectors/sheets.md) | Долгая память и таблицы агента |
| [webchat.md](connectors/webchat.md) · [media.md](connectors/media.md) · [astro-divination.md](connectors/astro-divination.md) | Остальные коннекторы |

## Решения

Что и почему было решено. Прошлое не протухает — в отличие от планов.

| | |
|---|---|
| [uuid-primary-keys.md](decisions/uuid-primary-keys.md) | Переход на UUIDv7 в первичных ключах |
| [media-transport.md](decisions/media-transport.md) | Как выбирается диалект провайдера при генерации изображений |
| [acp-comparison.md](decisions/acp-comparison.md) | Agent Communication Protocol против нашей архитектуры |
| [deferred/](decisions/deferred/) | Разобрано, но не сделано: [mail](decisions/deferred/mail.md), [terminal](decisions/deferred/terminal.md) |

---

Большая часть документации написана по-русски.
