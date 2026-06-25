# MCP-коннектор — спека для фронта

Управление подключениями к удалённым **MCP-серверам** через существующий Manage API control-api.
MCP — обычная integration-интеграция (`connectorCode = "mcp"`) с одной особенностью: **тулы
динамические** — у каждого сервера свой набор, который подгружается с сервера и кэшится на бэке.

Все эндпоинты — под `/manage/**`, авторизация — **пользовательский JWT** (`Authorization: Bearer <jwt>`,
как у остального дашборда).

## Конверты ответа

Успех:
```json
{ "response": <payload> }
```
Ошибка (HTTP 4xx/5xx):
```json
{ "error": { "message": "..." } }
```
Коды: `400` (валидация), `401` (нет/протух JWT), `403` (чужой ресурс), `404` (не найдено), `409` (дубль).

---

## Рекомендуемый флоу UI

```
[1] Получить поля credentials  →  [2] Создать интеграцию  →  [3] Test (валидация + загрузка тулов)
                                                                      ↓
                          [4] Показать список тулов  →  [5] Выдать тулы агенту (политики)
```

---

## [1] Поля credentials MCP-коннектора

`GET /manage/connectors/mcp`

```json
{ "response": {
  "code": "mcp",
  "type": "INTEGRATION",
  "name": "MCP Server",
  "description": null,
  "integrationMeta": {
    "credentialFields": {
      "url": "Server URL (Streamable HTTP)",
      "auth_token": "Bearer token (optional)",
      "headers": "Extra headers as JSON (optional)"
    },
    "supportsWebhooks": false
  }
}}
```

Форму credentials рисуем по `integrationMeta.credentialFields` (код поля → подпись). Для MCP:
- `url` — **обязателен**, endpoint Streamable HTTP (напр. `https://mcp.context7.com/mcp`);
- `auth_token` — опц., уходит как `Authorization: Bearer <auth_token>`;
- `headers` — опц., **строка с JSON-объектом** доп. заголовков, напр. `{"X-Api-Key":"..."}`.

> Список всех integration-коннекторов (чтобы показать «MCP» в каталоге) — `GET /manage/connectors/?type=INTEGRATION` (пагинация `page`/`size`).

---

## [2] Создать MCP-интеграцию

`POST /manage/integrations/credentials/`

```json
{
  "connectorCode": "mcp",
  "credentials": {
    "url": "https://mcp.context7.com/mcp",
    "auth_token": "ctx7sk-xxxxxxxx",
    "headers": "{\"X-Custom\":\"value\"}"
  },
  "name": "Context7"
}
```

На создании бэк делает хендшейк `initialize` (проверка доступности/auth) и сразу пытается подгрузить
тулы. Ответ:
```json
{ "response": {
  "id": "0193f0c2-...-uuid",
  "connectorCode": "mcp",
  "platformIdentifier": "https://mcp.context7.com/mcp",
  "name": "Context7",
  "enabled": true,
  "lastUsedAt": null,
  "createdAt": "2026-06-24T18:40:00"
}}
```
- `id` — это **`credentialId`** (identity экземпляра), используется во всех последующих вызовах.
- Ошибки: `400` если `url` пуст / сервер недоступен / auth не прошёл (текст в `error.message`);
  `409` если интеграция с тем же `url` у пользователя уже есть.

> `credentials` никогда не возвращаются обратно (хранятся зашифрованными).

---

## [3] Test интеграции (главный экшен) ⭐

`POST /manage/integrations/credentials/{credentialId}/test`

Валидирует credentials (для любого типа интеграции) и **для MCP синхронно перезагружает кэш тулов**.
Тело не нужно.

Успешный MCP:
```json
{ "response": {
  "valid": true,
  "identifier": "https://mcp.context7.com/mcp",
  "displayName": "MCP: Context7",
  "toolsDiscovered": 2,
  "toolsError": null
}}
```

Поля:
| поле | смысл |
|---|---|
| `valid` | credentials валидны / сервер доступен |
| `identifier` / `displayName` | как сервер представился (`serverInfo.name`) |
| `toolsDiscovered` | сколько тулов загружено в кэш (**только MCP**; `null` для прочих коннекторов) |
| `toolsError` | `valid:true`, но `tools/list` упал — текст ошибки; иначе `null` |

UX-трактовка:
- `valid:true, toolsDiscovered:N` → зелёный статус «Подключено, N тулов»; обновить список тулов из [4].
- `valid:true, toolsError:"..."` → жёлтый: «Подключение ок, но не удалось получить тулы: …».
- `valid:false` → красный: показать `errorMessage` (и подсветить поле `errorField`, если есть).

Кнопку «Проверить/Обновить тулы» в карточке интеграции вешаем на этот эндпоинт.

---

## [4] Список тулов экземпляра

`GET /manage/integrations/credentials/{credentialId}/tools/`

Отдаёт тулы конкретного экземпляра интеграции через SPI: для **MCP** — закэшированные (после
`test` / создания) тулы сервера, для **статических** коннекторов (Telegram и т.п.) — их штатный
набор тулов. Используется для отрисовки и для выбора при выдаче политик.

```json
{ "response": [
  {
    "name": "resolve-library-id",
    "title": "Resolve Context7 Library ID",
    "description": "Resolves a package/product name to a Context7-compatible library ID ...",
    "inputSchema": {
      "type": "object",
      "properties": {
        "query": { "type": "string", "description": "..." },
        "libraryName": { "type": "string", "description": "..." }
      },
      "required": ["query", "libraryName"]
    },
    "annotations": {
      "readOnlyHint": true, "destructiveHint": false,
      "idempotentHint": true, "openWorldHint": true
    }
  },
  { "name": "query-docs", "title": "Query Documentation", "...": "..." }
]}
```

Форма тула (MCP-совместимая, поля с `null` опускаются):
| поле | тип | примечание |
|---|---|---|
| `name` | string | имя тула (ключ для политик) |
| `title` | string? | человекочитаемое имя |
| `description` | string? | описание |
| `inputSchema` | object? | JSON Schema аргументов (произвольная, рисуем как есть) |
| `outputSchema` | object? | JSON Schema результата |
| `annotations` | object? | поведенческие хинты `readOnlyHint`/`destructiveHint`/`idempotentHint`/`openWorldHint` |
| `_meta` | object? | произвольные метаданные сервера |

---

## [5] Выдать тулы агенту (binding + политики)

> **Изменилось.** Эндпоинты `/manage/agent-tool-policies/*` и `/manage/agent-trigger-policies/*`
> **удалены**. Модель теперь — «коннектор доступен агенту = binding», с дефолт-allow и
> уточняющими политиками. Полный контракт — `docs/tmpspec/2026-06-26-frontend-agent-bindings-and-policies.md`.

Чтобы выдать MCP-сервер агенту — **привязать** его экземпляр (INSTANCE-коннектор → нужен `connectionId`):

`POST /manage/agents/{agentId}/connections/`
```json
{ "connectorCode": "mcp", "connectionId": "0193f0c2-...-connectionId" }
```
После binding все тулы сервера доступны по умолчанию (default-allow). Чтобы ограничить — правила на
binding (`AgentConnection.id` из ответа выше):

`POST /manage/agent-connections/{agentConnectionId}/policies/`
```json
{ "kind": "TOOL", "name": "query-docs", "effect": "DENY" }
```
- `name: null` = правило на весь коннектор (binding-wide); «только N тулов» = binding-wide `DENY` + точечные `ALLOW`.
- `paramsFilter` (опц.) ограничивает аргументы вызова.

Список привязок — `GET /manage/agents/{agentId}/connections/`; правила —
`GET|POST|PATCH|DELETE /manage/agent-connections/{agentConnectionId}/policies/...`.

---

## Управление существующей интеграцией

| Действие | Метод | Путь |
|---|---|---|
| Список интеграций | `GET` | `/manage/integrations/credentials/?connectorCode=mcp` |
| Карточка | `GET` | `/manage/integrations/credentials/{credentialId}` |
| Переименовать / вкл-выкл | `PATCH` | `/manage/integrations/credentials/{credentialId}/` — body `{ "name"?, "enabled"? }` |
| Обновить credentials | `PUT` | `/manage/integrations/credentials/{credentialId}/secret` — body `{ "credentials": {...} }` (тот же `url`/сервер) |
| Удалить | `DELETE` | `/manage/integrations/credentials/{credentialId}` |

- `enabled:false` — отключает интеграцию (тулы перестают отдаваться агентам).
- Смена `url` через `PUT /secret` запрещена (это другой сервер) → `400`; для другого сервера создаём новую интеграцию.
- Удаление интеграции каскадно чистит её кэш тулов.

---

## Замечания

- `platformIdentifier` у MCP = URL сервера; уникальность интеграции — по `(пользователь, url)`.
- `inputSchema` может содержать любые ключевые слова JSON Schema (`anyOf`, `format`, `$ref`, …) — рендерим
  обобщённо, не закладываемся на фиксированный набор.
- Кэш тулов обновляется автоматически при создании/смене credentials и вручную через [3] `test`.
