# Спецификация для фронтенда: binding'и агент↔коннектор + политики доступа

**Дата:** 2026-06-26
**Сервис:** control-api (HTTP manage-API)
**Контекст:** переход на модель «коннектор доступен агенту = binding» (`agent_connections`) +
единые правила уточнения (`agent_connection_policies`). Заменяет `agent_tool_policies` /
`agent_trigger_policies`.

## TL;DR

1. **Удалены** эндпоинты `/manage/agent-tool-policies/*` и `/manage/agent-trigger-policies/*`. **Breaking.**
2. **Новое:** `/manage/agents/{agentId}/connections` — привязать/отвязать коннектор к агенту (гейт
   доступности). Без binding коннектор агенту недоступен.
3. **Новое:** `/manage/agent-connections/{agentConnectionId}/policies` — уточнение доступа поверх
   binding. Модель **дефолт-allow**: при наличии binding тул/триггер разрешён, политики лишь
   ограничивают (DENY) или фильтруют (`params_filter`), либо делают allow-list.
4. У коннектора в каталоге теперь `capabilities.supportedScopes`/`defaultScope` вместо `sharingScope`
   (см. соседнюю спеку про capabilities).

---

## 1. Модель

- **binding** (`agent_connections`) — факт «этот экземпляр коннектора доступен агенту». Нет активного
  binding → агент не видит и не вызывает коннектор. Один экземпляр может быть привязан к нескольким
  агентам (общий `scope_id` → командная память/board).
- **policy** (`agent_connection_policies`) — необязательное уточнение поверх binding. Дефолт — всё
  разрешено. Прецеденс при разрешении `(kind, name)`: **точное имя > binding-wide (`name=null`) >
  дефолт-allow**.
  - deny-list: точечные `DENY` на конкретные тулы.
  - allow-list: binding-wide `DENY` (`name=null`) + точечные `ALLOW`.
  - `params_filter`: для `TOOL` ограничивает аргументы вызова, для `TRIGGER` — параметры события.

Привязка контекстных коннекторов (память/board/time) **материализует** экземпляр под выбранный scope
(AGENT/TEAM/USER). INSTANCE-коннекторы (telegram/mcp/app) привязываются по конкретному `connectionId`.

---

## 2. Binding'и: `/manage/agents/{agentId}/connections`

| Метод и путь | Тело | Возвращает |
|---|---|---|
| `GET  /manage/agents/{agentId}/connections/` | — | `List<AgentConnection>` |
| `POST /manage/agents/{agentId}/connections/` | `BindConnectionRequest` | `AgentConnection` |
| `DELETE /manage/agents/{agentId}/connections/{connectionId}` | — | `204/empty` |

**`BindConnectionRequest`:**
```jsonc
{
  "connectorCode": "persist-memory",   // обязательно
  "scope": "TEAM",                      // опц.; ∈ capabilities.supportedScopes; null → defaultScope.
                                        //   Игнорируется для INSTANCE.
  "connectionId": "…uuid…"              // ОБЯЗАТЕЛЬНО для INSTANCE-коннекторов (какой экземпляр);
                                        //   опустить для контекстных (AGENT/TEAM/USER).
}
```

**`AgentConnection` (ответ):**
```jsonc
{
  "id": "…",                    // ⬅ id binding'а — им управляются политики (см. §3)
  "connectionId": "…",          // id экземпляра коннектора
  "connectorCode": "persist-memory",
  "fullCode": "persist-memory_…",
  "name": "Memory",
  "identityScope": "TEAM",      // INSTANCE | AGENT | TEAM | USER | GLOBAL
  "scopeId": "…teamId…",        // носитель scope; null для INSTANCE/GLOBAL
  "enabled": true,
  "createdAt": "…"
}
```

> UI: при привязке коннектора, если `capabilities.supportedScopes.length > 1` — показать выбор scope
> (например, у памяти AGENT vs TEAM: «личная» vs «общая для команды»). Один scope — выбор не нужен.
> Для INSTANCE-коннекторов сперва выбрать экземпляр (`connectionId`) из списка интеграций.

---

## 3. Политики: `/manage/agent-connections/{agentConnectionId}/policies`

`{agentConnectionId}` = `AgentConnection.id` из §2.

| Метод и путь | Тело | Возвращает |
|---|---|---|
| `GET   …/policies/` | — | `List<AgentConnectionPolicy>` |
| `POST  …/policies/` | `CreatePolicyRequest` | `AgentConnectionPolicy` |
| `PATCH …/policies/{policyId}` | `UpdatePolicyRequest` | `AgentConnectionPolicy` |
| `DELETE …/policies/{policyId}` | — | `204/empty` |

**`CreatePolicyRequest`:**
```jsonc
{
  "kind": "TOOL",               // TOOL | TRIGGER — обязательно
  "name": "send_message",       // имя тула/триггера; null = правило на весь коннектор (binding-wide)
  "effect": "DENY",             // ALLOW | DENY — обязательно
  "paramsFilter": { "chatId": "123" }, // опц.; TOOL — аргументы, TRIGGER — параметры события
  "description": "…"            // опц.
}
```

**`UpdatePolicyRequest`** (PATCH; `effect`/`description` — частично, `paramsFilter` заменяется целиком,
`null` очищает):
```jsonc
{ "effect": "ALLOW", "paramsFilter": null, "description": "…" }
```

**`AgentConnectionPolicy` (ответ):**
```jsonc
{
  "id": "…", "agentConnectionId": "…",
  "kind": "TOOL", "name": "send_message",
  "effect": "DENY", "paramsFilter": { "chatId": "123" },
  "description": "…", "source": null, "createdAt": "…"
}
```

> На каждый `(binding, kind, name)` — не более одного активного правила (повтор → `409`).
> Чтобы «запретить всё, кроме N тулов»: одно binding-wide `DENY` (`name=null`) + по `ALLOW` на нужные.

---

## Чек-лист

- [ ] Убрать вызовы `/manage/agent-tool-policies` и `/manage/agent-trigger-policies` (удалены).
- [ ] Экран агента: список привязанных коннекторов (`GET …/connections/`), привязка с выбором scope,
      отвязка.
- [ ] Управление доступом: по `AgentConnection.id` — список/создание/удаление правил
      (`…/agent-connections/{id}/policies/`).
- [ ] Каталог коннекторов: читать `capabilities.supportedScopes`/`defaultScope` вместо `sharingScope`.

## Источники в коде

- `controller/manage/ManageAgentConnectionController.java`,
  `controller/manage/ManageAgentConnectionPolicyController.java`
- DTO: `AgentConnectionResponse`, `BindConnectionRequest`, `AgentConnectionPolicyResponse`,
  `CreateAgentConnectionPolicyRequest`, `UpdateAgentConnectionPolicyRequest`
- `service/connection/ConnectionBindingService.java`, `abac/AgentConnectionPolicyService.java`
