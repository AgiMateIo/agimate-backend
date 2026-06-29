# Control-API: наведение порядка в эндпойнтах (B/C/D/E + каскад)

> План-спека по итогам ревью эндпойнтов. Бакет **A** (баги доступа) уже исправлен (commit `3a8bcea`).
> Здесь — остальное: выравнивание имён под сущности (B), консистентность REST/путей (C),
> дедуп/мусор (D), мелочи (E) и каскад удаления App (correctness).
>
> Разбито на **шаги** — каждый самостоятельно выкатываемый. Порядок: сперва неломающее (Шаг 1),
> потом ломающие ренеймы пачкой (Шаг 2–3) с синхронным обновлением фронта/воркера/девайса и спек.
>
> Теги: **[B]** имена/сущности, **[C]** REST/пути, **[D]** дедуп/мусор, **[E]** мелочь, **[cascade]** correctness.
> 🔴 = breaking для внешнего потребителя, 🟢 = внутреннее/безопасное.

---

## Контекст: сущность ≠ имя

После SPI-рефактора единственная сущность экземпляра — **`Connection`** (свернула `integration_credentials`,
ссылается на `apps` через `app_id`). Слова «integration» и «credential» в путях/DTO/сервисах остались от
старой модели и теперь врут. **`Connector`** — это каталог-тип (`code`), не экземпляр. Цель шагов B —
чтобы путь/DTO/переменная пути называли ту сущность, с которой реально работают.

---

## Шаг 1 — Неломающее: каскад + мусор + безопасные правки 🟢

Можно выкатывать сразу, потребители не затрагиваются.

1. **[cascade] App.delete не каскадит на binding'и.** `AppService.deleteApp` (`AppService:118-128`) soft-удаляет
   `App` и его `Connection`, но `agent_connections` (+ их `agent_connection_policies`) на эту connection
   остаются «активными» → осиротевшие binding'и/политики.
   - Фикс: при удалении app отвязать все активные binding'и его connection. Добавить в
     `ConnectionBindingService` метод `detachConnection(UUID connectionId)` (soft-delete всех активных
     `agent_connections` по connectionId + их политик через `policyRepository.softDeleteByAgentConnectionId`
     + сброс кэша решений), вызвать из `deleteApp` после soft-delete connection.
   - Проверка: после удаления app у его агентов нет «активных» binding'ов (`findActiveByAgentId`).

2. **[D] Удалить мёртвый `AgentConnectorsController`** — регистрирует `/agent/connectors`, **0 эндпойнтов**
   (`controller/agent/AgentConnectorsController.java`). Удалить класс. (Проверить, что путь не упомянут в
   `SecurityConfig`.)

3. **[B] `identity` для DYNAMIC-коннекторов не валидируется.** `/agent/tools/{connectorCode}` и
   `/manage/tools/{connectorCode}/` принимают `?identity` как optional; для DYNAMIC (mcp/app) он обязателен,
   но при отсутствии возвращается пустой результат вместо `400`. Фикс: если `connector.toolBinding == DYNAMIC`
   и `identity == null` → `BadRequestStatusException`.

4. **[E] Неверные описания операций.** В log-контроллерах summary/description говорят «filtering by API key»,
   а фильтр по `agentId` (`ManageToolCallLogsController`, `ManageWebhookDeliveryLogsController`). Поправить текст.

5. **[E] Устаревшие комментарии/Schema.** `ChannelResponse`/доки про `inputFilter` «на AgentTriggerPolicy» —
   теперь поле на `Channel`. Привести в соответствие.

6. **[E/A-leftover] PATCH политики не сверяет путь.** `PATCH /manage/agent-connections/{agentConnectionId}/policies/{policyId}`
   — `{agentConnectionId}` не сверяется с binding'ом политики (утечки нет — lookup user-scoped, но путь
   вводит в заблуждение). Безопасный фикс: проверить `policy.agentConnectionId == agentConnectionId` → `409/404`.
   (Альтернатива — выкинуть сегмент из пути — ломающая, см. Шаг 2.)

**Доки после Шага 1:** обновить `docs/services/control-api-manage-*` где задеты тексты; спеки внешним не нужны.

---

## Шаг 2 — Имена под сущности + консистентность путей (B + C) 🔴

Ломающее. Выкатывать пачкой с синхронным обновлением фронта/воркера/девайса и спек. Не забыть
`SecurityConfig` filter-chain (CLAUDE.md checklist) и удаление старых путей (без двойной поддержки — pre-prod).

### B — переименования под `Connection`
1. 🔴 **`/manage/integrations/credentials/...` → `/manage/connections/...`** (`ManageIntegrationController`).
   - `{credentialId}` → `{connectionId}`; убрать сегмент `/credentials/` (connection ≠ credential).
   - DTO: `IntegrationResponse` → `ConnectionResponse`, `*IntegrationRequest` → `*ConnectionRequest`,
     `UpdateIntegrationCredentialsRequest` → `UpdateConnectionSecretRequest`, `IntegrationTestResponse` → `ConnectionTestResponse`.
   - Сервис `IntegrationService` → `ConnectionService` (или влить в существующий connection-слой).
   - Решение к согласованию: «integration» как **категория** connection (есть `credentialFields`) — оставить как
     фильтр (`GET /manage/connections/?kind=integration`) или не выделять. Рекомендую фильтр-параметр, без отдельного пути.
2. 🔴 **`/webhook/integration/{integrationId}` → `/webhook/{connectionId}`** (`IntegrationWebhookController`;
   `{integrationId}` фактически `connections.id`). Переименовать класс → `ConnectionWebhookController`.
3. 🔴 **`ToolCallRequest.identity` → `connectionId`** (это UUID connection, путается с `Connection.identityScope`).
   Затрагивает `/agent/tool/check`, `/agent/tool/call` и их сервис.
4. 🔴 **`/manage/app-tools/{appId}`, `/manage/app-triggers/{appId}` → `/manage/apps/{appId}/tools/`,
   `/manage/apps/{appId}/triggers/`** (вложенные ресурсы App). Удалить `ManageAppToolsController`/`ManageAppTriggersController`,
   перенести в под-ресурсы apps.

### C — консистентность путей
5. 🔴 **Trailing slash на списках** (правило проекта): `/agent/llm` → `/agent/llm/` (возвращает список);
   `/agent/tool/` (map) — привести к правилу. Свериться по всем GET-спискам.
6. 🔴 **Доски: вложить задачи в boardId.** `/manage/boards/tasks/{taskId}/status` и
   `/manage/boards/tasks/{taskId}/comments/` → `/manage/boards/{boardId}/tasks/{taskId}/...`.
7. 🔴 **Доски: `List` → `Page`** (`getBoards`, `getComments`) + `page`/`size` (меняет форму ответа).
8. 🟢→🔴 **Конвенция path-переменных.** Свести `{id}`/`{agentId}`/`{pubId}`/`{credentialId}` к одной схеме:
   родитель во вложенном пути — `{<entity>Id}` (`{agentId}`, `{boardId}`), сам ресурс — `{id}`. (Меняет только
   внутренние имена переменных, путь по значению тот же — безопасно, кроме случаев где переименовали сам сегмент.)

**Потребители Шага 2:** фронт (все manage-вызовы + формы), воркер (`/webhook/{connectionId}` если шлёт; tool DTO
`connectionId`), девайс (если зовёт переименованные). **Доки:** новый `docs/tmpspec/` для фронта (полный diff путей/DTO)
и для воркера (если задет `identity`→`connectionId` и webhook); обновить `docs/services/control-api-*`.

---

## Шаг 3 — Дедуп / консолидация эндпойнтов (D) 🔴 + verify-first

Сначала проверить живость, потом удалять.

1. **[D] Три листинга тулов коннектора → один.** Сейчас: `/manage/integrations/tools/` (каталог по `connectorCode`),
   `/manage/integrations/credentials/{id}/tools/` (инстанс), `/manage/tools/{connectorCode}/` (универсально по `?identity`).
   Свести к `/manage/tools/{connectorCode}/?identity=` (без identity → каталог-тулы типа, с identity → тулы инстанса).
   Удалить два дубля из (пере-named) connections-контроллера.
2. **[D] `/agent/tool/` vs `/agent/tools/{connectorCode}`.** Разные схемы (`List<ToolDefinition>` vs
   `Map<…,ConnectorToolSpec>`). **Verify:** реальный путь исполнения тулов — gRPC `ToolGateway`; REST-группа
   `/agent/tool/*` (`AgentToolCallController`: `call`/`check`/`result`) похожа на legacy device-маршрут.
   Сначала подтвердить, кто её зовёт (девайс? старый воркер?), затем: удалить если мёртвая, иначе оставить и
   развести назначение в доках. Не удалять вслепую.
3. **[D] probe-эндпойнты trigger-logs.** `match` отдаёт `404` как «ещё не найдено» (нестандартно для polling),
   `issueProbe` без user-контекста. Уточнить модель безопасности; либо документировать как есть, либо
   засекьюрить/убрать. (Тех-функция discovery — низкий приоритет, но решить.)

**Потребители Шага 3:** зависит от verify (девайс/воркер). **Доки:** отметить в соответствующих спеках, что удалено.

---

## Шаг 4 — Мелочи/стиль (E) 🟢, пачкой

Решения к согласованию, затем единый проход:
1. **DELETE → 204 No Content** вместо `SuccessResponse<Void>` (`{response:null}`). Решить: менять или оставить
   текущую обёртку ради единообразия конверта. (Затрагивает все DELETE — мелко-ломающее по статус-коду.)
2. **PATCH vs PUT.** Сейчас разнобой (channels/llm-providers = PATCH, agents/teams/skills = PUT). Договориться:
   PATCH — частичное, PUT — полная замена; привести к правилу.
3. **Centrifugo TTL** захардкожен 3600 (`*CentrifugoTokenController`) → вынести в конфиг (`CentrifugoProperties`),
   по memory — env через relaxed binding, в yaml дефолт.
4. **Прочие @Schema/описания** (например `AgentSkillWithConnectorsResponse.connectorCodes` — пример `["board","time"]`).

---

## Сводный порядок выкатки

| Шаг | Содержимое | Breaking | Нужны спеки потребителям |
|-----|-----------|----------|--------------------------|
| 1 | каскад, мусор, валидация identity, тексты | нет | нет |
| 2 | ренеймы под Connection + пути/слэши/доски | да | фронт + воркер |
| 3 | дедуп тул-листингов, legacy `/agent/tool/*`, probe | да (verify) | по итогам verify |
| 4 | DELETE 204, PATCH/PUT, Centrifugo TTL, schema | мелко | нет |

**Перед Шагом 2:** согласовать (а) выделять ли «integration» как категорию connection (рекомендация — фильтр-параметр,
не отдельный путь); (б) делаем ли двойную поддержку старых путей или режем сразу (pre-prod → режем). После согласования —
план изменений по схеме/именам, затем код.
