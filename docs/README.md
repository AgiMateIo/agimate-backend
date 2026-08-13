# AgiMate — документация

Платформа, где специализированные ИИ-агенты работают вместе: скиллы описывают, что агент умеет,
коннекторы дают ему тулы, триггеры и фоновые джобы, приложения выносят его за пределы браузера.

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
| [mcp-server.md](contracts/mcp-server.md) | MCP-сервер: тулы агента наружу, ревизия 2026-07-28 |
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

В шапке каждого документа — `status` (`implemented`, `partial`, `deferred`, `analysis`) и даты
`created`/`implemented`. Даты взяты из git: первый коммит с документом и коммит, которым решение
доехало до кода.

| | |
|---|---|
| [uuid-primary-keys.md](decisions/uuid-primary-keys.md) | Переход на UUIDv7 в первичных ключах |
| [schema-conventions.md](decisions/schema-conventions.md) | Имена индексов и ограничений, комментарии в changelog'ах, сворачивание `updates/` в `initial/` |
| [media-transport.md](decisions/media-transport.md) | Как выбирается диалект провайдера при генерации изображений |
| [acp-comparison.md](decisions/acp-comparison.md) | Agent Communication Protocol против нашей архитектуры |
| [mcp-oauth.md](decisions/mcp-oauth.md) | OAuth в MCP-коннекторе: discovery по 401, CIMD вместо DCR, состояние на коннекции |
| [reasoning-content.md](decisions/reasoning-content.md) | Где живёт рассуждение модели: `LlmMeta` и `thinking_text`, а не текст сообщения |
| [run-cancellation.md](decisions/run-cancellation.md) | Остановка рана: кооперативная отмена на шве, drain тул-хода, точка невозврата по `openWorldHint` |
| [history-from-turn-ledger.md](decisions/history-from-turn-ledger.md) | История сессии из журнала ходов `agent_run_turns`, а не из проекции канала |
| [steering.md](decisions/steering.md) | Сообщение в занятую сессию подхватывает бегущий ран, а не следующий из очереди |
| [detached-tools.md](decisions/detached-tools.md) | Результат долгого тул-вызова приходит триггером, а не держит ход модели |
| [mcp-tasks.md](decisions/mcp-tasks.md) | Долгий вызов по MCP отвечает хэндлом таска; таск — строка `tool_call_logs` |
| [agent-sessions.md](decisions/agent-sessions.md) | Сессия есть у каждого рана и служит ключом упорядочивания, а не артефактом канала |
| [referrals.md](decisions/referrals.md) | Кто кого привёл: `?ref=` через cookie, атрибуция только при создании аккаунта, ссылка доступа не даёт |
| [deferred/](decisions/deferred/) | Разобрано, но не сделано: [mail](decisions/deferred/mail.md), [terminal](decisions/deferred/terminal.md), [a2a-external-agents](decisions/deferred/a2a-external-agents.md), [agent-to-agent-internal](decisions/deferred/agent-to-agent-internal.md), [pluggable-connectors](decisions/deferred/pluggable-connectors.md), [llm-inference-proxy](decisions/deferred/llm-inference-proxy.md), [matrix-connector](decisions/deferred/matrix-connector.md) |

---

Большая часть документации написана по-русски.
