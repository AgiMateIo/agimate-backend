# ACP vs AgiMate backend — что взять, что у нас иначе, что у нас лучше

Разбор [Agent Communication Protocol](https://agentcommunicationprotocol.dev/) применительно к нашей
архитектуре (worker-протокол + connectors SPI + channels/triggers). Источник по нашей стороне:
[`agimate-worker-protocol-spec.md`](agimate-worker-protocol-spec.md),
[`connectors/architecture.md`](connectors/architecture.md).

## Рамка: это разные классы систем

- **ACP** — открытый протокол **интероперабельности** между агентами из разных фреймворков
  (BeeAI/LangChain/CrewAI) и от разных вендоров. Отсюда его ДНК: REST без SDK (curl/Postman),
  агенты адресуются по имени, manifest для discovery, offline-discovery через OCI-образы, MIME-типы
  «любой формат без смены протокола».
- **У нас** — вертикально интегрированный control plane одной платформы: gRPC worker-плоскость +
  DBOS-durability + connector SPI + ABAC. Агенты — строки в БД, а не сетевые сервисы для discovery.

Вывод: половина примитивов ACP решает проблему, которой у нас нет (открытый мультивендорный
discovery). Брать стоит не «протокол», а отдельные **модельные/lifecycle-идеи**, не завязанные на
открытую экосистему.

## 1. Что стоит взять

- **`await` / `resume` — first-class «агент ждёт ввода» на том же run'е.** У нас Async-tool с
  возвратом через DBOS-signal — но это уровень *тулы*. ACP-шный await — на уровне *агента/run'а*:
  ReAct-loop спрашивает уточнение и продолжает **с тем же in-memory состоянием**, run = `awaiting`.
  У нас сейчас любой `агент→пользователь` через канал **завершает** workflow, ответ юзера прилетает
  новым триггером и сшивается по `sessionId` — то есть мы рвём и пересобираем ReAct-состояние на
  каждый ход. Для уточняющего вопроса посреди задачи дешевле `DBOS.recv` на том же workflow.
  → Прямой вход в открытый вопрос §6 про **conversation state**.
- **Словарь run-статусов, особенно `awaiting` и `cancelling`.** ACP:
  `created → in-progress → awaiting → (cancelling) → completed/failed`. Наш `ReportStatus`
  (`started/step_completed/waiting_signal/completed/failed`) близок, но `WorkflowReporting` **не
  реализован** (§5.7). `cancelling` — готовый ответ на открытый вопрос §6 про **cancellation**
  (отмена = состояние дренажа in-flight tool-вызова, а не мгновенная смерть).
- **MessagePart с `content_type` (MIME) как форма для мультимодальности.** ACP Message = упорядоченный
  список частей, каждая со своим MIME. У нас `ChannelHandler.handleInput` вытаскивает текст (generic —
  JSON от `trigger.data`). Пока агенты текстовые — YAGNI. **Когда** прилетит картинка/файл/аудио —
  не плодить ad-hoc поля в `InboundMessage`, а взять форму «ordered parts + MIME».

## 2. Что решено альтернативным (нормальным) путём

| ACP | У нас | Комментарий |
|---|---|---|
| Sync/Async/Streaming как REST-паттерны на `/runs` (poll или SSE) | DBOS durable workflow (async = дефолт) + 4 паттерна на уровне *тулы* (sync/stream/batch/async) поверх gRPC | ACP-async = poll/SSE; наш = DBOS-signal, **polling сознательно запрещён**. Наш путь durable, переживает краш. |
| Sessions: история на сервере по `session_id` поверх stateless REST | `TriggerLogAgent.sessionId` (single-writer-per-session из prompt-канала) | Ключ у нас чище — не клиентский, а enforced single-writer. Где живёт сама история — открытый §6; ACP отвечает «server-side store». |
| Discovery: адресация по имени, manifest-роутинг, offline-discovery через OCI | Воркер получает `agent_id` в DBOS-payload и тянет `AgentSpec` по id (versioned) | Платформа и так знает всех агентов. OCI/offline — нерелевантно, мы не пакуем агентов в артефакты. |
| Transport: REST/HTTP, no-SDK, curl-friendly | gRPC/TLS/HTTP2 на worker-плоскости | Разные приоритеты. При этом user-facing surface у нас **тоже REST** (user-api/control-api app) — просто разнесли плоскости. |

## 3. Где мы сильнее

1. **Durable execution / crash recovery (DBOS).** ACP не обязывает durable exactly-once: упавший
   ACP-сервер теряет in-flight runs, если имплементатор сам не добавит durability. У нас воркер умер →
   другой подхватил с checkpoint'а; async-результаты через durable signal.
2. **Versioned/reproducible execution.** Пиннуем версии сущностей на старте workflow; long-running не
   ломается от смены конфига (`FAILED_PRECONDITION`). У ACP поведение агента может «уплыть».
3. **Модель доверия к воркеру.** Воркер без доступа к БД и прямых внешних интеграций (всё через
   ToolGateway), RBAC перепроверяется на гейте, независимый audit (скомпрометированный воркер не
   спрячет tool-вызов), LLM-ключи не в логах, pool-key как SHA-256 в конфиге. ACP security отдаёт
   имплементатору.
4. **Push-free / single-initiator.** Бэкенд не пушит в воркер — всё через DBOS (durable enqueue/signal).
   ACP await/SSE требуют живого HTTP-соединения сервер→клиент, хрупко на дисконнектах.
5. **Слои `what`/`how`/`execution`.** ABAC (что разрешено) / канал (как строится взаимодействие) /
   DBOS-worker (исполнение) + trigger-routing (`planRoutes → dispatch`, audience + ABAC,
   single-writer sessionId). ACP схлопывает endpoint агента с его I/O.

**Не берём осознанно:** offline/OCI-discovery, name-based сетевой discovery, и особенно **polling за
async-результатом** (ACP допускает — мы правильно отвергли, регрессировать нельзя).

## Практический выхлоп (в открытые вопросы §6 worker-спеки)

- **Cancellation** → ввести статус `cancelling` как явное состояние дренажа in-flight tool-вызова.
- **Conversation state** → рассмотреть `await/resume` через `DBOS.recv` на том же workflow для
  уточняющих вопросов (сохраняет ReAct-состояние; межтриггерный кейс — отдельно).
- **WorkflowReporting** (когда дойдёт) → принять ACP-словарь run-статусов как референс, добавив
  `awaiting`.

## Источники

- [ACP — Architecture](https://agentcommunicationprotocol.dev/core-concepts/architecture)
- [ACP — Agent Manifest](https://agentcommunicationprotocol.dev/core-concepts/agent-manifest)
- [What is ACP? — IBM](https://www.ibm.com/think/topics/agent-communication-protocol)
- [Survey of Agent Interoperability Protocols (MCP/ACP/A2A/ANP) — arXiv](https://arxiv.org/html/2505.02279v1)
