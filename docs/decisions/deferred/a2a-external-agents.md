---
status: deferred
created: 2026-08-02
---

# A2A и внешние агенты (отложено)

Результат исследования (июль 2026): что такое A2A v1.0, три формы его интеграции в платформу и
отдельный трек «Claude Code / Cursor как мозг агента». Задача отложена — документ фиксирует факты,
дизайн-варианты и открытые вопросы, чтобы вернуться без повторного исследования.

## Контекст: два разных «ACP» — не путать

| | Agent **Communication** Protocol | Agent **Client** Protocol |
|---|---|---|
| Автор | IBM / BeeAI | Zed |
| Слой | агент ↔ агент (S2S) | IDE/редактор ↔ агент |
| Судьба | **влился в A2A** (LF AI & Data, 2025-08-29), разработка свёрнута | **жив и растёт**: Zed 1.0 (апрель 2026), JetBrains — вся линейка, ACP Registry, 20+ агентов |

У нас реализован **второй** (`/acp`, см. `docs/contracts/acp.md`). Новости «ACP влился в A2A»
нашей реализации не касаются. Итоговый стек: **MCP** = агент↔тулы, **ACP (Zed)** = IDE↔агент,
**A2A** = агент↔агент. См. также `docs/decisions/acp-comparison.md`.

Общение **наших** агентов между собой — отдельная задача без сетевого протокола, см.
[agent-to-agent-internal.md](../agent-to-agent-internal.md).

## A2A v1.0 — выжимка фактов (проверено по спеке, июль 2026)

Спека: <https://a2a-protocol.org/latest/specification/>, канонический протобаф —
`a2aproject/A2A/specification/a2a.proto`.

- **Версия**: v1.0.1 (v1.0.0 — 2026-03-12, первый stable). **Ломающий rewrite против 0.3.x**:
  другие имена методов (`SendMessage` vs `message/send`), Part без `kind`-дискриминатора,
  `tenant`-роутинг, JWS-подписи AgentCard, `ListTasks`. Почти все туториалы и **Spring
  AI-интеграция (`spring-ai-a2a-server-autoconfigure`, янв 2026) говорят на мёртвом 0.3 — не брать**.
- **Транспорты** («protocol bindings»): JSON-RPC 2.0/HTTP, gRPC, REST (HTTP+JSON) — равноправны,
  ни один не обязателен. Сервер объявляет свои в AgentCard (`supportedInterfaces`, первый = предпочтительный),
  клиент берёт первый поддерживаемый. Клиент обязан слать заголовок `A2A-Version` (пустой ⇒ трактуется как 0.3).
- **AgentCard**: `name`, `description`, `supportedInterfaces[{url, protocolBinding, tenant?, protocolVersion}]`,
  `capabilities{streaming?, pushNotifications?, extensions[]}`, `securitySchemes` (OpenAPI-style: APIKey/HTTP/
  OAuth2/OIDC/mTLS), `skills[{id, name, description, tags}]` — **skills без inputSchema, это описания, не тулы**;
  `signatures` (JWS, канонизация RFC 8785 JCS).
- **Well-known `/.well-known/agent-card.json` — только один из трёх механизмов discovery** (плюс реестры и
  direct configuration); по RFC 8615 живёт в корне origin. Карта может лежать по любому URL. Мультиагентность:
  карты по путям с agentId + direct config, поддомены, или один эндпоинт + `tenant` (добавлен в 1.0 ровно
  под мультитенантные платформы).
- **Task lifecycle**: `SUBMITTED → WORKING → COMPLETED | FAILED | CANCELED | REJECTED` (терминальные),
  прерванные `INPUT_REQUIRED` / `AUTH_REQUIRED` (агент спрашивает посреди работы, клиент отвечает в тот же
  `taskId`). `contextId` = разговор (серия задач), `taskId` = задача; оба генерирует сервер.
- **Операции** (11): `SendMessage` (блокирующий по умолчанию; `returnImmediately` — сразу),
  `SendStreamingMessage`/`SubscribeToTask` (SSE), `GetTask`, `ListTasks`, `CancelTask`,
  CRUD push-конфигов (вебхук: POST StreamResponse на URL клиента, at-least-once), `GetExtendedAgentCard`.
  Поллинг `GetTask` легален всегда; стриминг и пуши опциональны (`capabilities`).
- **Результаты** оформляются как `Artifact` (parts: text/raw/url/data), отдельно от переписки.
- **Java SDK**: официальный `a2a-java` 1.1.0 (spec 1.0, groupId `org.a2aproject.sdk`), но reference-серверы —
  Quarkus, first-party Spring-модуля нет. Для клиента реалистично писать руками по образцу `McpClient`
  (4–5 HTTP-операций).
- **Адопция**: 150+ организаций; продакшн-серверы — AWS Bedrock AgentCore, Azure AI Foundry / Copilot Studio,
  Google Vertex/ADK, BeeAI. **Ни один крупный coding-агент (Claude Code, Cursor, Gemini CLI, Codex…) нативного
  A2A не имеет — все они стандартизировались на ACP (Zed)**; для Claude Code есть только community-обёртки
  (`claude-a2a`, `a2claude`).

## Три формы интеграции A2A (ортогональны, можно в любом порядке)

### A. Outbound-коннектор: наш агент делегирует внешнему (роль клиента)

Самая дешёвая форма, идеально ложится в connectors SPI по образцу MCP-коннектора:

- `connectors/integrations/a2a/A2aConnectorService` — `IntegrationConnectorHandler` + `ToolProvider`,
  `ExecutionLocus.BACKEND`. Connection = один удалённый агент: URL AgentCard + креды → `secrets`.
- `validateCredentials` = фетч + валидация AgentCard; skills → описания в тулах/промпт-блоке.
- Вызов = блокирующий `SendMessage` внутри poll-бюджета (`agent.tool.poll-timeout`, 60s); не успел —
  возвращаем `taskId` + статус, агент доберёт `get_task`. `INPUT_REQUIRED` — просто tool-result со
  статусом, наш агент отвечает `send_message(taskId, …)` — ложится на LLM-цикл без новой механики.
- **Нерешённая развилка — модель тулов**: (1) три фиксированных тула `send_message(message, taskId?, skillId?)` /
  `get_task(taskId)` / `cancel_task(taskId)` — проще, skills всё равно без схем; (2) тул на каждый skill
  (строки в `connection_tools`, как MCP) — LLM видит навыки раздельно, но все схемы вырождаются в
  `(message, taskId?)`. Склонялись к (1).
- Фаза 2: push-вебхуки для долгих задач — наша `/webhook/{connectionId}`-инфраструктура подходит почти без
  изменений (`IntegrationConnectorHandler.normalizeInbound` → триггер о завершении задачи).

### B. `AgentType.A2A`: внешний облачный агент как мозг нашего агента

Идея Евгения: A2A как четвёртый тип агента рядом с GENERIC/WEBHOOK/CENTRIFUGO. Чужой мозг получает наше
«тело»: каналы (Telegram/webchat), триггеры, историю, trigger-политики.

- Схема: `AgentType.A2A`; у агента — URL AgentCard + `secret_id` (исходящие креды).
- `service/delivery/A2aTransport`: триггер → `SendMessage`; маппинги `agent_sessions.id ↔ contextId`,
  `run ↔ taskId` (маленькая таблица или колонки).
- Проекция ответа в штатный поток message-log: WORKING → PROGRESS, artifacts/ответ → ANSWER,
  FAILED → ERROR → существующий `ChannelHandler.handleOutput`. Канальный слой без изменений.
- `INPUT_REQUIRED` ложится идеально: вопрос агента → ANSWER в канал → следующее сообщение пользователя
  уходит с тем же `taskId`. У A2A есть `CancelTask` — отмена у таких агентов появится раньше, чем у GENERIC.
- Не работает по определению (мозг — чёрный ящик): наши тулы, скиллы, промпт-блоки, tool-ABAC, GetRunContext.
- Открытый вопрос: сериализация per-session (single-writer) — DBOS-очередь с partition key либо
  per-session lock в control-api; не решали.

### C. Inbound-сервер: наши агенты доступны извне по A2A

Стратегически интересно (вызов из чужих оркестраторов, канал дистрибуции), но дорого и преждевременно:

- Маппинг напрашивается: `Task` ↔ `TriggerLogAgent` (run), `contextId` ↔ `agent_sessions.id`,
  состояния ↔ проекция SaveMessage (INBOUND→WORKING, ANSWER→COMPLETED, ERROR→FAILED).
  ChannelHandler `a2a-server` + блокирующий `SendMessage` через pending-future (паттерн `AcpSessionRegistry`).
- Мультиагентность решается штатно: карта/эндпоинт по пути с agentId или `tenant`-роутинг;
  auth — `APIKeySecurityScheme{header, X-Api-Key}` = наш существующий агентский контур
  (`apiKeySecurityFilterChain`). Well-known в корне не нужен: наши агенты приватные, discovery = direct config.
- **Дыры, из-за которых отложено**: нет отмены рана (`CancelTask` → `TaskNotCancelableError` — легально,
  но убого); нет публичного base-URL-проперти (есть только `app.integration.webhook-base-url`);
  SSE-стриминг упрётся в одну реплику (как ACP-registry); нужен полноценный task-store поверх ранов;
  новый публичный security-контур. И главное — нет потребителя.

## Трек «Claude Code / Cursor как мозг» — это не A2A, это ACP-клиент

Заголовочный кейс «подключить Claude Code» формой B не решается: coding-агенты говорят на ACP (Zed),
они — локальные сабпроцессы, их кто-то должен запустить и вести (`session/new` / `session/prompt`).
Мы становимся **ACP-клиентом** (зеркало нашей текущей agent-side реализации) через посредника:

```
control-api ──Centrifugo (паттерн apps/devices, EXTERNAL)──► host-app на машине пользователя
            ──stdio/ACP──► claude-code-acp | cursor-agent | gemini-cli | … (весь ACP Registry)
```

- Host-app — зеркальный `clients/acp-bridge`: демон, подключается к нам наружу, спаунит агента из
  реестра, гоняет ACP по stdio. Один host-app покрывает весь реестр (20+ агентов), не по интеграции на вендора.
- `session/update` → наши PROGRESS/ANSWER → каналы; `session/request_permission` можно роутить пользователю
  в Telegram («агент просит запись файла — разрешить?») — прямое развитие дифференциатора apps/devices.
- Прямое развитие нашей ACP-экспертизы: протокол уже умеем, меняется только роль.

## Риски (для любой формы)

1. **Стоимость и петли**: вызов удалённого агента = чужой LLM-цикл непредсказуемой длины; агент-вызывает-агента
   может породить пинг-понг. Нужны бюджеты/лимиты на A2A-вызовы; платформенные LLM-квоты чужой расход не видят.
2. **Prompt injection**: ответ удалённого агента и описания skills из AgentCard — недоверенный LLM-текст.
   Оборачивать untrusted-маркером (механика openWorld-тулов уже есть).
3. **Утечка данных**: делегирование = отправка содержимого задачи наружу. Только явное подключение
   (connection создаёт пользователь) + ABAC.
4. **Долгоживущее состояние**: taskId/contextId живут на чужой стороне; наша сторона должна помнить «хвосты»
   незавершённых задач.
5. **Зрелость**: v1.0 — март 2026, один ломающий rewrite уже случился; Java-экосистема Quarkus-центрична.

## Рекомендованная фазировка (на момент анализа)

1. **Если драйвер — «Claude Code в Telegram»**: трек ACP-клиент/host-app (ярче продуктово, строится на
   готовых apps/devices + ACP-экспертизе).
2. **Если драйвер — делегирование облачным агентам**: форма A (outbound-коннектор), самая дешёвая.
3. Форма B (`AgentType.A2A`) — когда появится конкретный облачный агент-мозг.
4. Форма C (inbound-сервер) — последней, когда появится реальный внешний потребитель наших агентов.
5. «Не делать ничего» тоже легально: цена ожидания низкая, зрелость экосистемы растёт.

## Открытые вопросы

1. Модель тулов формы A: фиксированные send/get/cancel vs тул-на-skill (склонялись к фиксированным).
2. Сериализация per-session для `A2aTransport` (форма B): DBOS-очередь vs lock в control-api.
3. Host-app: отдельный бинарь или расширение `clients/acp-bridge`? Дистрибуция (brew/npm/бинарь)?
4. Лимиты на A2A-вызовы: где считать (connection? агент? пользователь?) и чем ограничивать.
5. Форма C: делать ли task-store поверх ранов или ждать нативной отмены рана в worker-протоколе.

## Ключевые файлы-образцы (проверены 2026-07-16, до реорганизации docs)

- `connectors/integrations/mcp/` (в т.ч. `McpClient`) — образец интеграции с динамическими тулами и
  ручным HTTP-клиентом (для формы A).
- `connectors/internal/acp/AcpConnectorService.java`, `service/acp/{AcpService, AcpSessionRegistry}`,
  `acp/AcpWebSocketHandler.java` — agent-side ACP, зеркалится для роли клиента; `AcpSessionRegistry.request`
  (pending-future) — паттерн для блокирующего SendMessage формы C.
- `service/delivery/DbosTransport.java`, `service/AgentDeliveryService.java` — роутинг доставки по
  `AgentType`, куда встаёт `A2aTransport` (форма B).
- `service/channel/MessageLogService.java` — проекция SaveMessage → каналы (куда вливается ответ
  внешнего агента).
- `controller/webhook/ConnectionWebhookController.java` — шаблон публичного inbound-эндпоинта
  (пуш-вебхуки формы A, каркас формы C).
- `database/enums/ExecutionKind.java` (`APP` → Centrifugo push в приложение) — транспортная канва host-app.
- Полная карта точек расширения и брифинг по спеке A2A — в истории исследования; ключевые выводы
  перенесены сюда.
