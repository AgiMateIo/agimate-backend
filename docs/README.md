# AgiMate — документация

Платформа, где специализированные ИИ-агенты работают вместе: скиллы описывают, что агент умеет,
коннекторы дают ему тулы, триггеры и фоновые джобы, приложения выносят его за пределы браузера.

Разделы устроены по тому, зачем вы сюда пришли.

## Начать отсюда

| | |
|---|---|
| **[architecture/overview.md](architecture/overview.md)** | Из чего состоит система и почему именно так — читается один раз, целиком |
| **[operations/local-stack.md](operations/local-stack.md)** | Поднять локально за одну команду |
| **[roadmap.md](roadmap.md)** | Что впереди и в каком порядке |

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
| [outbound-http.md](architecture/outbound-http.md) | Исходящие вызовы по пользовательскому адресу: где они, и чем закрыты |

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
| [context-loaders.md](connectors/context-loaders.md) | `skill-loader` и `tool-loader`: раскрытие навыков и схем тулов по требованию |
| [subagents.md](connectors/subagents.md) | Субагенты: поручения своим копиям в сессиях канала, отчёт в разговор |
| [agents.md](connectors/agents.md) | Агенты команды: поручения другому агенту по схеме субагентов, круг — команда |
| [webchat.md](connectors/webchat.md) · [media.md](connectors/media.md) · [astro-divination.md](connectors/astro-divination.md) | Остальные коннекторы |

## Решения

Что и почему было решено. Прошлое не протухает — в отличие от планов.

Что означают статусы в шапке и когда такой документ заводится —
[«Как ведётся документация»](#как-ведётся-документация).

| | |
|---|---|
| [uuid-primary-keys.md](decisions/uuid-primary-keys.md) | Переход на UUIDv7 в первичных ключах |
| [schema-conventions.md](decisions/schema-conventions.md) | Имена индексов и ограничений, комментарии в changelog'ах, сворачивание `updates/` в `initial/` |
| [media-transport.md](decisions/media-transport.md) | Как выбирается диалект провайдера при генерации изображений |
| [acp-comparison.md](decisions/acp-comparison.md) | Agent Communication Protocol против нашей архитектуры |
| [mcp-oauth.md](decisions/mcp-oauth.md) | OAuth в MCP-коннекторе: discovery по 401, CIMD вместо DCR, состояние на коннекции |
| [reasoning-content.md](decisions/reasoning-content.md) | Где живёт рассуждение модели: поле сообщения и `thinking_text`, потому что провайдер требует его назад |
| [run-cancellation.md](decisions/run-cancellation.md) | Остановка рана: кооперативная отмена на шве, drain тул-хода, точка невозврата по `openWorldHint` |
| [history-from-turn-ledger.md](decisions/history-from-turn-ledger.md) | История сессии из журнала ходов `agent_run_turns`, а не из проекции канала |
| [steering.md](decisions/steering.md) | Сообщение в занятую сессию подхватывает бегущий ран, а не следующий из очереди |
| [detached-tools.md](decisions/detached-tools.md) | Результат долгого тул-вызова приходит триггером, а не держит ход модели |
| [mcp-tasks.md](decisions/mcp-tasks.md) | Долгий вызов по MCP отвечает хэндлом таска; таск — строка `tool_call_logs` |
| [agent-sessions.md](decisions/agent-sessions.md) | Сессия есть у каждого рана и служит ключом упорядочивания, а не артефактом канала |
| [referrals.md](decisions/referrals.md) | Кто кого привёл: `?ref=` через cookie, атрибуция только при создании аккаунта, ссылка доступа не даёт |
| [agent-participants.md](decisions/agent-participants.md) | Разные люди в одном канале: `agent_participants` обязателен у канального рана; личность — тип и ключ, полномочия — сравнение аккаунтов |
| [native-auth.md](decisions/native-auth.md) | Вход нативного клиента: одноразовый код с PKCE вместо cookie, реестр сессий устройств |
| [push-notifications.md](decisions/push-notifications.md) | Пуш-уведомления: устройства и транспорт у user-api, содержание у control-api, между ними реле |
| [push-second-channel.md](decisions/push-second-channel.md) | Второй канал уведомлений: FCM тем же универсальным API, параллельно RuStore, а не вместо |
| [agent-to-agent-internal.md](decisions/agent-to-agent-internal.md) | Агенты одного пользователя поручают друг другу работу: коннектор `agents` по схеме субагентов, круг — команда, как у доски; привязка — участие, ABAC — исключения |
| [agent-rooms.md](decisions/agent-rooms.md) | Рой агентов в общем чате: лента комнаты — надсистема, сессия участника — его подробности; будит только адрес, «увидеть» отделено от «проснуться» |
| [subagents.md](decisions/subagents.md) | Агент поручает подзадачу своей копии: ребёнок — сессия канала `subagents`, квитанция сразу, отчёт триггером в разговор, цепочка ранов в `origin_run_id` |
| [email-password-auth.md](decisions/email-password-auth.md) | Пароль рядом с четырьмя OAuth-провайдерами: чужая установка не должна зависеть от регистрации приложения в Google |
| [platform-admin-mcp.md](decisions/platform-admin-mcp.md) | Мета-агент и MCP-клиент умеют всё, что веб-панель: коннектор `platform` покрывает `/manage/**` целиком |
| [dbos-ids-only.md](decisions/dbos-ids-only.md) | Чекпоинт DBOS хранит ссылку, переписка — у control-api: вызов модели и тулы становятся шагами рана, реплей перечитывает по id |
| [llm-streaming.md](decisions/llm-streaming.md) | Ответ модели читается по мере генерации: пауза между чанками отличает «завис» от «думает», ретраи — в один слой |
| [provider-turn-envelope.md](decisions/provider-turn-envelope.md) | Что уезжает обратно провайдеру в assistant-ходе: происхождение решает содержимое, провайдер — чего стоит отсутствие |
| [connector-job-concurrency.md](decisions/connector-job-concurrency.md) | Сколько джоб узел берёт разом: потолок на узле, а не на установке, и почему непрерывный опрос его не касается |
| [run-fairness.md](decisions/run-fairness.md) | Шумный пользователь занимает слоты модели: квота — бюджет, лимитер — поток, а доли в моменте не считает никто |
| [skill-connector-requirements.md](decisions/skill-connector-requirements.md) | Навык объявляет экземпляр, а не код: ключ требования, параметры MCP и правила доступа; мастер подключения получает план, а не догадки |
| [content-taxonomy.md](decisions/content-taxonomy.md) | Категории и теги каталога: одна ось «о чём это» на навыки и пресеты, закрытый словарь тегов на остальное, дозаполнение засеянных установок |
| [outbound-tls-trust.md](decisions/outbound-tls-trust.md) | Корень, которого нет в `cacerts`: штатный набор якорей плюс наш, по умолчанию выключенный, и почему доверие живёт у установки, а не у подключения |
| [reply-address-in-channels.md](decisions/reply-address-in-channels.md) | Куда уходит ответ: чат — часть снимка каналов рана, а не последний вход сессии, поэтому событие в разговоре не сбивает адрес |
| [egress-proxy.md](decisions/egress-proxy.md) | Заблокированные из России LLM, MCP и Telegram: системный HTTP-прокси только для хостов из списка оператора, и почему гард адреса для них проверяет прокси, а не цель |
| [files-and-pages.md](decisions/files-and-pages.md) | `agf_` — документ, а не снимок: версии файла, закрепление отправленного и коннектор `files` |
| [connector-views.md](decisions/connector-views.md) | Интерактивная панель вместо текста: формат вью из MCP Apps, место лаунчера диктует ось владельца данных, а тулы панель зовёт от имени агента — второй поверхности исполнения не заводим |
| [deferred/](decisions/deferred/) | Разобрано, но не сделано: [mail](decisions/deferred/mail.md), [terminal](decisions/deferred/terminal.md), [terminal-app](decisions/deferred/terminal-app.md), [a2a-external-agents](decisions/deferred/a2a-external-agents.md), [pluggable-connectors](decisions/deferred/pluggable-connectors.md), [llm-inference-proxy](decisions/deferred/llm-inference-proxy.md), [matrix-connector](decisions/deferred/matrix-connector.md), [page-publishing](decisions/deferred/page-publishing.md) |

## Как ведётся документация

Разделы отличаются не темой, а сроком жизни — от этого зависит, что и где заводить.

| Куда | Что там лежит | Когда заводится | Когда исчезает |
|---|---|---|---|
| `architecture/`, `contracts/`, `services/`, `connectors/`, `operations/` | как система устроена **сейчас** | вместе с подсистемой | не исчезает, правится вместе с кодом |
| `decisions/` | что и почему решено, включая отвергнутые варианты | **до** кода, как только работа длиннее одного присеста | не исчезает, меняет статус |
| `tmpspec/` | дельта этой работы для соседней команды (фронт, Android, devops) | когда работа задевает клиента | удаляется, когда клиент выкатил |
| [roadmap.md](roadmap.md) | что впереди и в каком порядке | — | строка уходит, когда работа сделана |

Шапка с датами есть только у `decisions/`: там она означает состояние решения. У остальных
разделов её нет намеренно — «когда правили» знает git, а проставленное руками `updated:` устаревает
на второй неделе.

### Жизненный цикл решения

Долгая работа начинается с документа в `decisions/`, а не с кода. Даты в шапке берутся из git:
`created` — первый коммит с документом, `implemented` — коммит, которым решение доехало до кода.

| status | Значит |
|---|---|
| `analysis` | разобрано, решения ещё нет |
| `accepted` | решено, к коду не приступали |
| `partial` | часть доехала; в тексте сказано, какая именно и где проходит граница |
| `implemented` | доехало целиком |
| `deferred` | не делаем сейчас; документ переезжает в `decisions/deferred/`, пункт в roadmap остаётся |

У `partial` и `implemented` дата `implemented` обязательна — это дата первой посадки. У `deferred`
при пересмотре ставится `updated`.

Секция `## План` с чекбоксами внутри документа — рабочий список задач. Он переживает и перерыв в
работе, и смену исполнителя, чего не умеет ни один чат.

Новый документ в `decisions/` добавляется строкой в таблицу выше — с подводкой своими словами, а не
пересказом заголовка. Пункт roadmap документа не требует: если требовать, список перестанут
пополнять.


---

Большая часть документации написана по-русски.
