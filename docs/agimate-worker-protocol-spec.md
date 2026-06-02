# AgiMate — Спецификация взаимодействия Backend ↔ Generic Worker

> **Документ описывает протокол взаимодействия между бэкендом AgiMate (Spring Boot) и пулом Generic-воркеров, исполняющих агентов внутри DBOS-workflow.**
> Версия: PoC. Спецификация описывает только протокол и принципы; решения по конкретной реализации (proto-файлы, схемы БД, классы interceptor'ов) принимаются в отраслевых чатах.

---

## 1. Общие принципы

### 1.1 Архитектурные допущения

- Generic-воркер — пул процессов, каждый исполняет несколько DBOS-workflow одновременно. Воркер не имеет доступа к БД бэкенда.
- Workflow — единица исполнения агентского сценария: триггер → инициализация контекста → ReAct-loop → результат.
- Доставка триггеров и сигналов на воркеры идёт **только через DBOS** (durable enqueue / signals). gRPC используется исключительно для client-initiated вызовов от воркера к бэкенду.
- Все side-effects (вызовы tools, обращения к LLM в будущем, доступ к Knowledge Base) проходят через бэкенд. Воркер не имеет прямых интеграций с внешними сервисами кроме LLM (на этапе PoC).

### 1.2 Транспорт

- Протокол: **gRPC поверх TLS** (HTTP/2). TLS обязателен.
- mTLS — отложен до момента появления on-prem воркеров или внешних партнёров.
- Единственный инициатор вызовов — воркер. Бэкенд не делает push-RPC.
- Server-side bidi streams для команд/триггеров **не используются**: их роль выполняет DBOS.

### 1.3 Authentication / Authorization

- На этапе PoC — **single-level identity**: воркер аутентифицируется по `worker_pool_key`.
- Передача ключа: gRPC metadata `authorization: Bearer <worker_pool_key>` на каждом RPC. Установка прозрачна для бизнес-кода — выполняется через client-side interceptor, навешиваемый на канал.
- Дополнительная metadata: `x-worker-instance: <uuid>` (для аудита и трейсинга).
- Авторизация на уровне workflow (per-workflow JWT, RBAC по конкретному агенту/тенанту) — закладывается в дизайн, но **не реализуется в PoC**. Расширение протокола будет аддитивным: добавляется `x-workflow-token` в metadata, существующие RPC не ломаются.

### 1.4 Управление ключами

- Множество пулов поддерживается из коробки. Каждый пул — отдельный `worker_pool_key`.
- Источник истины — **конфиг бэкенда**, не БД. Это сознательное решение: identity воркера не должна зависеть от доступности БД.
- В конфиге хранится не сам ключ, а его **SHA-256 хэш**. Bcrypt/argon2 не требуется — ключи имеют достаточную энтропию (минимум 32 случайных байта), brute-force нерелевантен.
- Ротация: новый ключ добавляется в конфиг параллельно со старым; после redeploy воркеров старый удаляется.

### 1.5 Versioning и совместимость

- Все объекты, описывающие исполнение (агент, скил, конфигурация команды), запрашиваются воркером **с указанием версии**, зафиксированной на старте workflow. Это защищает long-running workflows от изменений конфигурации в процессе исполнения.
- Версия фиксируется бэкендом в DBOS-payload при старте workflow и используется воркером во всех последующих запросах.

---

## 2. Спецификация для Backend

### 2.1 Зона ответственности

Бэкенд предоставляет gRPC-сервисы для воркеров и обеспечивает:
- аутентификацию воркеров и tagging всех операций пулом-источником;
- доступ к сущностям предметной области (агенты, скилы, команды, KB);
- выполнение tool-вызовов через Tool Gateway с RBAC и аудитом;
- приём телеметрии и результатов от воркеров;
- доставку результатов конечным пользователям (real-time / webhook) — после `SubmitResult`.

### 2.2 gRPC-сервисы (логический контракт)

| Сервис | Назначение |
|---|---|
| `WorkerControl` | Health-check / heartbeat. На PoC — минимальный, без registration flow |
| `AgentContext` | Read-only доступ к агент-спекам, скилам, контексту команды, секциям Knowledge Base. Все запросы — с версиями |
| `ToolGateway` | Единственная точка вызова tools. Sync, streaming, batch, async паттерны (см. 2.4) |
| `WorkflowReporting` | Логи, статусы, телеметрия, финальный результат workflow |

### 2.3 Authorization Interceptor

- Серверный interceptor извлекает `authorization: Bearer ...` из metadata на каждом RPC.
- Вычисляет SHA-256 от token, ищет в in-memory карте пулов (загружается при старте из конфига).
- При успехе — кладёт `PoolContext (pool_id, pool_name)` в gRPC Context. Downstream-сервисы читают из Context'а; в бизнес-коде не должно быть дублирования auth-логики.
- При неудаче — `UNAUTHENTICATED`.
- Проверка — без обращения к БД и без кэшей. Рассчитывается на каждом RPC.

### 2.4 Паттерны вызова tools

Бэкенд должен поддерживать четыре режима исполнения tools:

| Режим | Назначение | Транспорт |
|---|---|---|
| **Sync** | Быстрые tools (< 5 сек), результат сразу | gRPC unary |
| **Streaming** | Tools со стримингом промежуточных событий (bash, поиск) | gRPC server-streaming |
| **Batch** | Параллельный fan-out нескольких tools для оптимизации latency | gRPC unary с массивом запросов |
| **Async** | Long-running tools (внешние устройства, human-in-the-loop). Ответ возвращается через DBOS signal | gRPC unary с возвратом `tool_use_id`; результат — `DBOS.send(workflow_id, tool_use_id, result)` |

Polling-RPC за результатом async-tool **не предусмотрен** — это anti-pattern на DBOS.

### 2.5 Tool Gateway: проверка прав

- Проверка права на вызов tool — implicit invariant внутри `ExecuteTool*`. Отдельного `CheckPermission` RPC нет.
- Проверка опирается на: (a) принадлежность tool скилу агента, (b) RBAC-политики тенанта, (c) ограничения скила (rate limit, allowed args).
- Все вызовы (успешные и отклонённые) логируются в audit log с привязкой `pool_id`, `workflow_id`, `agent_id`, `tool_name`.

### 2.6 Knowledge Base

- Доступ к секциям KB — через `AgentContext`, c проверкой прав агента на секцию по RBAC.
- Воркер не имеет общего доступа к KB; запрашивает только ту секцию, на которую у агента есть право чтения.

### 2.7 LLM-credentials (этап PoC)

- На этапе PoC принят **вариант A**: воркер получает API-ключ LLM-провайдера от бэкенда и вызывает LLM напрямую.
- Endpoint выдачи ключа — часть `AgentContext` (например, в составе `AgentSpec` или отдельным RPC).
- Эволюция к **варианту B** (LLM-Gateway, бэкенд как proxy для LLM-вызовов) запланирована и будет аддитивной — добавляется новый сервис `LlmGateway`, при наличии — воркер использует его, иначе fallback к прямому вызову.

### 2.8 Telemetry & Audit

- Воркер шлёт логи, статусы и трейсы через `WorkflowReporting`. Бэкенд сохраняет их с тэгами `pool_id`, `workflow_id`, `agent_id`, `tenant_id`.
- Audit-trail tool-вызовов формируется на стороне Tool Gateway — независимо от reporting'а от воркера. Это гарантирует, что компрометированный или некорректный воркер не сможет скрыть факт вызова tool.

### 2.9 Что бэкенд НЕ делает

- Не пушит триггеры в воркер по gRPC (это делает DBOS).
- Не хранит worker_pool_key в БД.
- Не доверяет воркеру в части RBAC-проверок (всё перепроверяется на стороне Tool Gateway).
- Не отдаёт LLM-ключи в открытом виде в логи / телеметрию.

---

## 3. Спецификация для Generic Worker

### 3.1 Зона ответственности

Воркер — durable executor агентских workflow:
- запускает и координирует ReAct-loop агента;
- получает все необходимые конфигурации с бэкенда через gRPC;
- вызывает tools исключительно через `ToolGateway`;
- отчитывается о ходе исполнения и финальном результате через `WorkflowReporting`;
- не реализует бизнес-логики, специфичной для конкретного агента или скила — она приходит из конфигурации.

### 3.2 Bootstrap

При старте процесса воркер должен:
- получить из окружения `WORKER_POOL_KEY` (mounted secret или env-переменная);
- получить адрес бэкенда (`BACKEND_GRPC_ADDR`);
- сгенерировать `WORKER_INSTANCE_ID` (UUID, живёт до конца процесса);
- установить TLS gRPC-канал с бэкендом, навесить client interceptor для metadata-авторизации;
- зарегистрироваться в DBOS как обработчик нужных workflow-типов.

### 3.3 gRPC client

- Один долгоживущий канал на воркер-процесс. Все стабы создаются на этом канале.
- Client interceptor автоматически добавляет в metadata каждого RPC:
  - `authorization: Bearer <WORKER_POOL_KEY>`
  - `x-worker-instance: <WORKER_INSTANCE_ID>`
  - `x-trace-id: <uuid>` — генерируется на каждом workflow-step или прокидывается из workflow context (для distributed tracing)
- Бизнес-код не работает с metadata напрямую.

### 3.4 Жизненный цикл workflow

При получении DBOS-workflow воркер:

1. Извлекает из payload: `workflow_id`, `agent_id`, `agent_version`, `team_id`, `trigger_data`, версии связанных сущностей.
2. Запрашивает у `AgentContext` агент-спеку, скилы, контекст команды, нужные секции KB — все с зафиксированными версиями.
3. Получает LLM-credentials (PoC) для нужного провайдера.
4. Инициализирует ReAct-loop: системный промпт из агента, пользовательский input из `trigger_data`, набор tools из скилов.
5. На каждой итерации: либо завершает loop, либо вызывает tool через `ToolGateway` (см. 3.6).
6. По завершении вызывает `SubmitResult`. Доставку результата пользователю (real-time, webhook) **выполняет бэкенд**, не воркер.

### 3.5 Версионирование

- Воркер всегда использует версии сущностей, зафиксированные в payload workflow на старте.
- Воркер **не** запрашивает "последнюю версию" по ходу исполнения — это ломает воспроизводимость.
- Если в процессе обновился скил — это применится к новым workflow, не к текущему.

### 3.6 Вызовы tools

- Любое внешнее действие — через `ToolGateway`. Воркер не делает прямых HTTP/SDK-вызовов в сторону внешних систем.
- Выбор паттерна (sync / stream / batch / async) определяется на стороне воркера на основании метаданных tool, полученных в `SkillSpec`.
- При получении `PERMISSION_DENIED` от Tool Gateway — это валидный ответ, не сетевая ошибка. Воркер передаёт его в LLM как tool result, чтобы агент мог скорректировать поведение.
- Long-running tools: воркер вызывает `ExecuteToolAsync`, получает `tool_use_id`, далее уходит в `DBOS.recv(tool_use_id)`. Workflow засыпает, не жжёт CPU.

### 3.7 Telemetry

- Воркер шлёт через `WorkflowReporting`:
  - structured logs (`AppendLog`) — каждый шаг ReAct-loop, входы/выходы LLM (без секретов), решения о tool-calls;
  - статусы (`ReportStatus`) — `started`, `step_completed`, `waiting_signal`, `completed`, `failed`;
  - трейсы (`EmitTrace`) — для distributed tracing.
- Не дублирует audit, который ведёт Tool Gateway. Reporting — про ход исполнения; audit — про факты side-effects.

### 3.8 Error handling

- Транзиентные ошибки gRPC (`UNAVAILABLE`, `DEADLINE_EXCEEDED`) — retry с экспоненциальным backoff, idempotency-key для не-idempotent операций.
- `UNAUTHENTICATED` — fatal на уровне воркера: либо ключ не валиден, либо отозван. Worker должен прекратить попытки и рапортовать в health-метрики.
- `PERMISSION_DENIED` от ToolGateway — **не** ошибка воркера, это бизнес-результат tool-вызова.
- Падения воркера в середине workflow обрабатываются DBOS — workflow подхватит другой воркер с последнего checkpoint'а.

### 3.9 Чего воркер НЕ делает

- Не имеет доступа к БД бэкенда.
- Не хранит долгосрочного состояния между workflow (всё durable-state — в DBOS).
- Не делает прямых вызовов внешних сервисов кроме LLM (PoC) и DBOS.
- Не доставляет результаты пользователю — только submit на бэкенд.
- Не принимает решения по RBAC — это responsibility Tool Gateway.
- Не работает с разными версиями сущностей в рамках одного workflow.

---

## 4. Roadmap расширения протокола (post-PoC)

| Этап | Что добавляется | Совместимость |
|---|---|---|
| **PoC** | gRPC + TLS, pool-level Bearer, прямой LLM-доступ | — |
| **Phase 1** | Per-workflow JWT в дополнительной metadata (`x-workflow-token`), per-agent RBAC scope | Аддитивно. Старые воркеры работают |
| **Phase 2** | LLM Gateway (вариант B), централизованный учёт токенов, revenue-share для провайдеров | Аддитивно. Воркер выбирает proxy если доступен |
| **Phase 3** | mTLS для on-prem / партнёрских воркеров, Worker Registration с capability negotiation | Аддитивно через альтернативные ChannelCredentials |

Ни одно расширение не ломает базового PoC-контракта: те же сервисы, та же transport-схема, добавляются metadata-поля и новые сервисы.

---

## 5. Текущая реализация Backend (PoC)

Раздел отражает состояние кода в `services/device-api` после PoC-итерации. Подробный how-to: [`docs/services/device-api-grpc-worker.md`](services/device-api-grpc-worker.md).

### 5.1 Транспорт и порт

- gRPC-сервер поднимается внутри `device-api` (Spring Boot 4) на отдельном порту `9091` (HTTP/2). Управляется флагом `grpc.server.enabled`.
- TLS включается через `grpc.server.security.enabled` + `certificate-chain` / `private-key` (PEM). В local dev допустим plaintext.
- Реализация: прямые `io.grpc:grpc-netty-shaded` + `com.google.protobuf` 3.25.5 (без Spring-стартеров — для совместимости с SB 4.0). Жизненным циклом сервера управляет `GrpcServerLifecycle` (`@PostConstruct` start, `@PreDestroy` graceful shutdown).
- Все Spring-бины `BindableService` автоматически биндятся; все `ServerInterceptor` бины — навешиваются как глобальные.

### 5.2 Proto-контракты

Proto-файлы лежат в `services/device-api/src/main/proto/agentworker/`, package `ru.agimate.agentworker`:

| Файл | Сервис | Реализованные RPC |
|---|---|---|
| `worker_control.proto` | `WorkerControl` | `HealthCheck` |
| `agent_context.proto` | `AgentContext` | `GetAgentSpec`, `GetSkill`, `GetTeamContext`, `GetLlmCredentials` |
| `tool_gateway.proto` | `ToolGateway` | `ExecuteTool` (sync); `ExecuteToolStream`/`Batch`/`Async` → `UNIMPLEMENTED` |

`workflow_reporting.proto` намеренно **не создан** — `WorkflowReporting` сервис в PoC отсутствует.

### 5.3 Авторизация: `worker_pool_key`

- **Префикс** ключа: `wrkp` (4 lowercase). Формат полного ключа — стандартный `AppKeyUtils`: `prefix(4) + keyId(12) + payload(48) = 64 char`. Внутри payload — 32-байтный секрет + CRC32. Воркер хранит и предъявляет именно эту строку.
- **Хранение в конфиге** — одна строка `authkey` (80 символов) на пул:

  ```
  authkey = prefix(4) + keyId(12) + sha256Hex(secret, 64)
  ```

  Это **не полный ключ** — секрета в конфиге нет, только его SHA-256. Поиск пула — по `keyId`, верификация — `sha256(secret) == keyHash`.
- **Конфиг**: `worker-pools.authkeys: List<String>` (`WorkerPoolProperties`). В env — `WORKER_POOLS_AUTHKEYS_0`, `..._1` и т.д.
- **Загрузка**: `WorkerPoolRegistry.@PostConstruct` парсит все authkey, строит `Map<keyId, ParsedWorkerAuthkey>`. Битая строка / неверный prefix / дублирующий `keyId` → fail-fast при старте. Ни одного обращения к БД.
- **Интерсептор**: `WorkerPoolAuthInterceptor` извлекает `authorization: Bearer <token>` и `x-worker-instance` из metadata, валидирует через `WorkerPoolKeyAuthService` (parse → check prefix → CRC32 → registry lookup → SHA-256). При успехе кладёт `WorkerPoolContext(poolId, workerInstanceId)` в gRPC `Context`; downstream-код читает через `WorkerPoolContextHolder.current()`. На PoC `poolId == keyId` (отдельного `pool_name` пока нет).
- **Генерация ключей**: gated JUnit-тест `WorkerAuthkeyGeneratorTest`, запуск:
  ```
  ./gradlew :device-api:test --tests "*WorkerAuthkeyGeneratorTest" -Dgenerate.worker.authkey=true --rerun-tasks
  ```
  Печатает `fullKey` (отдать воркеру) и `authkey` (положить в `WORKER_POOLS_AUTHKEYS_*`). Никаких CLI runner'ов в production-коде.
- `x-trace-id` пока не обрабатывается серверной стороной (заложено как расширение).

### 5.4 AgentContext — переиспользование существующих сервисов

Реализация только читающая, обёртка над текущими сервисами `device-api`:

- `GetAgentSpec` → `AgentRepository` + `AgentSkillRepository` + `AgenticTeamRepository` + `AgentLlmRepository`. Возвращает `AgentSpec` со скилами в формате `AgentSkillRef(skill_id, version)` (версия из `AgentSkill.installedSkillVersion`).
- `GetSkill` → `SkillRepository.findByPubIdNotDeleted`. Если запрошенная `version` не равна текущей — `FAILED_PRECONDITION` (защита воркфлоу от смены конфигурации в процессе исполнения, см. §1.5).
- `GetTeamContext` → `AgenticTeamRepository` + `AgentRepository.findByUserPubIdAndAgenticTeamId`.
- `GetLlmCredentials` → `AgentLlmRepository` + `LlmProviderRepository` + `IntegrationEncryptionService.decryptCredentials` (**вариант A** из §2.7). Возвращает `provider_type / base_url / api_key / model`. Логируется только факт выдачи (`pool`, `agent`, `providerType`) — ключ в логи не пишется.

Проверка принадлежности агента/скила пулу — **не реализована** в PoC: на текущем этапе любой валидный пул видит любого агента. Закладка под Phase 1 (per-agent RBAC scope) аддитивная — добавится фильтрация по `WorkerPoolContextHolder.current().poolId()` в каждом RPC.

### 5.5 ToolGateway

- `ExecuteTool` оборачивает существующий `AgentToolUseService.processToolUse(agentPubId, ToolUseRequest)`:
  - идемпотентность через `tool_use_id` → `ToolUseRequest.id` (БД-уникальность по `(agent_pub_id, tool_use_id)`),
  - ABAC через `ToolPolicyDbEvaluatorService` (внутри `processToolUse`),
  - audit через `ToolUseLogService` — все вызовы пишутся в `tool_use_log` независимо от reporting'а воркера (см. §2.8),
  - доставка через `ConnectorService.pushToConnector`.
- В proto `ExecuteToolRequest` добавлены поля `connector_code`, `identity`, `agent_session_id` — нужны для прямой стыковки с текущей моделью `ToolUseRequest`. Ожидается, что воркер выводит их из `SkillSpec` / workflow payload.
- Маппинг ошибок:
  - `ForbiddenStatusException` (ABAC отказал) → **`PERMISSION_DENIED`** — для воркера это валидный tool-результат (см. §3.6), не сетевая ошибка;
  - `ConflictStatusException` (тот же `tool_use_id` с другим input) → **`ABORTED`**;
  - `NotFoundStatusException` → **`NOT_FOUND`**;
  - отсутствие/невалидность UUID, `tool_use_id`, `connector_code`, `tool_name` → **`INVALID_ARGUMENT`**.
- `ExecuteToolStream` / `ExecuteToolBatch` / `ExecuteToolAsync` → `UNIMPLEMENTED` (заложены в proto, ждут реализации; async-результат пойдёт через `DBOS.send(workflow_id, tool_use_id, result)` без polling-RPC, см. §2.4).

### 5.6 Структура кода

```
services/device-api/src/main/java/ru/agimate/deviceapi/
├── config/
│   ├── GrpcServerProperties.java         // grpc.server.* (port, security)
│   └── WorkerPoolProperties.java         // worker-pools.authkeys: List<String>
└── grpc/
    ├── GrpcServerLifecycle.java          // Netty server, TLS, lifecycle
    ├── auth/
    │   ├── ParsedWorkerAuthkey.java      // 80-char authkey parser/builder
    │   ├── WorkerPoolRegistry.java       // in-memory map keyed by keyId
    │   ├── WorkerPoolKeyAuthService.java // validate full key from worker
    │   ├── WorkerPoolAuthInterceptor.java
    │   ├── WorkerPoolContext.java
    │   └── WorkerPoolContextHolder.java
    └── service/
        ├── WorkerControlGrpcService.java
        ├── AgentContextGrpcService.java
        └── ToolGatewayGrpcService.java
services/device-api/src/main/proto/agentworker/
    ├── worker_control.proto
    ├── agent_context.proto
    └── tool_gateway.proto
```

Тесты (`src/test/.../grpc/auth/`): `ParsedWorkerAuthkeyTest`, `WorkerPoolKeyAuthServiceTest`, `WorkerPoolAuthInterceptorTest` (in-process gRPC), `WorkerAuthkeyGeneratorTest` (gated).

### 5.7 Что в PoC намеренно отложено

- **`WorkflowReporting`** целиком (`AppendLog`, `ReportStatus`, `EmitTrace`, `SubmitResult`) — proto, сервисы, БД-таблицы для логов/статусов/результатов. Доставка финального результата пользователю (Centrifugo / webhook через `AgentDeliveryService`) подключится после.
- **mTLS**, per-workflow JWT (`x-workflow-token`), Worker Registration с capability negotiation — Phase 1–3.
- **LLM Gateway** (вариант B §2.7) — пока вариант A (выдача API-ключа воркеру).
- **Knowledge Base RPC** — заглушка (`UNIMPLEMENTED`); сущности KB в БД ещё нет.
- **Streaming/Batch/Async tools** — заглушки `UNIMPLEMENTED`.
- **Per-pool / per-agent RBAC** в `AgentContext` — на PoC любой валидный пул видит любого агента.
- **`x-trace-id`** обработка на сервере (distributed tracing backbone — открытый вопрос §6).

### 5.8 Соответствие спецификации

| Пункт спеки | Статус |
|---|---|
| §1.2 gRPC + TLS, единственный инициатор — воркер | ✅ |
| §1.3 Bearer `worker_pool_key`, `x-worker-instance` | ✅ |
| §1.4 хэши в конфиге, не в БД, ротация через redeploy | ✅ |
| §1.5 versioned reads | ✅ для `GetSkill` (по `installedSkillVersion`); `agent_version` в `AgentSpec` принимается, но строгая фиксация — закладка |
| §2.3 Authorization Interceptor без БД/кэшей | ✅ (валидация на каждый RPC) |
| §2.4 Sync tool-режим | ✅; Streaming/Batch/Async — `UNIMPLEMENTED` |
| §2.5 RBAC внутри `ExecuteTool*`, без отдельного `CheckPermission` | ✅ (через `AgentToolUseService` + ABAC) |
| §2.6 Knowledge Base | ⏳ stub |
| §2.7 LLM-credentials вариант A | ✅ (`GetLlmCredentials`) |
| §2.8 Audit на стороне Tool Gateway независимо от воркера | ✅ (`ToolUseLogService` пишет всегда) |
| §2.9 Не хранить ключи в БД, не светить LLM-ключи в логах | ✅ |

---

## 6. Открытые вопросы

Решаются в отраслевых чатах при обсуждении реализации:

- **Conversation state** — где живёт история сообщений ReAct-loop: внутри DBOS workflow state или в Redis на бэкенде? От ответа зависит, нужны ли `Save/LoadConversation` в `WorkflowReporting`.
- **Format AgentSpec** — единый proto-message со всем (включая системный промпт, скилы, KB-pointers) или композиция из нескольких RPC?
- **Streaming-tools контракт** — фиксированная схема событий для `ExecuteToolStream` или extensible через `Any` / JSON?
- **Distributed tracing backbone** — OpenTelemetry-совместимый или собственный формат?
- **Cancellation semantics** — отмена workflow в DBOS должна ли прерывать in-flight tool-вызов на бэкенде, или ждать его завершения?
