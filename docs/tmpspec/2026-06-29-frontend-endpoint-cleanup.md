# Frontend: control-api endpoint cleanup — что изменилось (сводка)

> Один документ на весь заход наведения порядка в эндпойнтах control-api (A + 2a–2d + 3 + 4).
> Pre-prod, БД ресетится — **старые пути удалены (404), двойной поддержки нет**. Детали по каждому
> шагу — в отдельных `2026-06-29-frontend-*.md`. Скиллы — отдельно в `2026-06-26-frontend-skills.md`.

Коммиты: `3a8bcea` (A), `584a2a4` (2a), `37f33dc` (2b), `e565ab7` (2c), `b65b770` (2d),
`2559020` (3), `82d74a4` (4).

---

## 0. TL;DR для фронта

1. **`/manage/integrations/credentials/*` → `/manage/connections/*`** — самое крупное. Это были обычные
   экземпляры коннекторов (`Connection`). DTO выровнены, листинг фильтруется по реальным полям.
2. **Каталог тулов/триггеров типа** → `/manage/connectors/{code}/tools|triggers/`; **тулы экземпляра** →
   `/manage/connections/{id}/tools/`. Группа `/manage/tools/**` удалена.
3. **App tools/triggers** вложены под `/manage/apps/{appId}/...`.
4. **Задачи досок** вложены под `/manage/boards/{boardId}/tasks/{taskId}/...`.
5. **Webhook URL** теперь `/webhook/{connectionId}` (без `/integration`). Берётся из ответа коннекшена,
   руками не собирать.
6. Мелочи: `GET /agent/llm/` (слеш), PATCH без хвостового `/`.
7. **Ужесточение доступа (A)** — не ломает контракты, но кросс-владельческие запросы теперь честно `404`.

---

## 1. Полная карта путей (старое → новое)

### 1.1. Connections (бывшие integration credentials) — 2a

| Было (404) | Стало |
|---|---|
| `GET /manage/integrations/credentials/?connectorCode=` | `GET /manage/connections/?connectorCode=&scope=&enabled=` |
| `POST /manage/integrations/credentials/` | `POST /manage/connections/` |
| `GET /manage/integrations/credentials/{credentialId}` | `GET /manage/connections/{connectionId}` |
| `PATCH /manage/integrations/credentials/{credentialId}/` | `PATCH /manage/connections/{connectionId}` (без хвостового `/`) |
| `PUT /manage/integrations/credentials/{credentialId}/secret` | `PUT /manage/connections/{connectionId}/secret` |
| `DELETE /manage/integrations/credentials/{credentialId}` | `DELETE /manage/connections/{connectionId}` |
| `POST /manage/integrations/credentials/{credentialId}/test` | `POST /manage/connections/{connectionId}/test` |

### 1.2. Листинг тулов/триггеров — 2a + 3

| Было (404) | Стало | Смысл |
|---|---|---|
| `GET /manage/integrations/tools/?connectorCode=` | `GET /manage/connectors/{code}/tools/` | каталог **типа** |
| `GET /manage/integrations/triggers/?connectorCode=` | `GET /manage/connectors/{code}/triggers/` | каталог **типа** |
| `GET /manage/tools/{code}/?identity=` (без id) | `GET /manage/connectors/{code}/tools/` | каталог **типа** |
| `GET /manage/tools/{code}/{toolName}` | `GET /manage/connectors/{code}/tools/{toolName}` | схема тула типа |
| `GET /manage/tools/{code}/?identity={connId}` | `GET /manage/connections/{connId}/tools/` | тулы **экземпляра** |
| `GET /manage/integrations/credentials/{credentialId}/tools/` | `GET /manage/connections/{connectionId}/tools/` | тулы **экземпляра** |

Модель: **каталог типа** (предопределённые тулы) под `/manage/connectors/{code}`; **тулы конкретного
экземпляра** под `/manage/connections/{id}`. Для **DYNAMIC** (mcp) каталог типа пустой — тулы только по
экземпляру (без `connectionId` → `400`). Для **STATIC** (time/board/persist-memory/telegram) каталог
типа отдаёт тулы.

### 1.3. App tools/triggers — 2c

| Было (404) | Стало |
|---|---|
| `GET /manage/app-tools/{appId}` | `GET /manage/apps/{appId}/tools/` |
| `GET /manage/app-triggers/{appId}` | `GET /manage/apps/{appId}/triggers/` |

Ответы те же (`List<AppTool>` / `List<AppTrigger>`).

### 1.4. Доски — 2d

| Было (404) | Стало |
|---|---|
| `PATCH /manage/boards/tasks/{taskId}/status` | `PATCH /manage/boards/{boardId}/tasks/{taskId}/status` |
| `GET /manage/boards/tasks/{taskId}/comments/` | `GET /manage/boards/{boardId}/tasks/{taskId}/comments/` |
| `POST /manage/boards/tasks/{taskId}/comments/` | `POST /manage/boards/{boardId}/tasks/{taskId}/comments/` |
| `GET /manage/boards/{id}` | `GET /manage/boards/{boardId}` (то же значение, переименована переменная) |

Бэкенд проверяет, что задача принадлежит доске из пути (иначе `404`).

### 1.5. Webhook — 2b

| Было | Стало |
|---|---|
| `POST /webhook/integration/{connectionId}` | `POST /webhook/{connectionId}` |

Это URL для **внешних платформ** (telegram и т.п.). Бэкенд кладёт готовый URL в ответ коннекшена —
фронт его не конструирует, но при показе/копировании URL для ре-сетапа вебхука учесть новый путь.

### 1.6. Agent API — 2d

| Было | Стало |
|---|---|
| `GET /agent/llm` | `GET /agent/llm/` |

(`GET /agent/llm/{name}` без изменений.)

---

## 2. DTO `ConnectionResponse` (было `IntegrationResponse`)

```jsonc
{
  "id": "uuid",
  "connectorCode": "telegram",
  "subCode": "my_bot",            // БЫЛО platformIdentifier
  "fullCode": "telegram_my_bot",
  "scope": "INSTANCE",            // НОВОЕ (IdentityScope)
  "name": "My Telegram Bot",
  "enabled": true,
  "lastUsedAt": "2026-06-29T12:00:00",
  "createdAt": "2026-06-29T12:00:00"
}
```

- `platformIdentifier` → **`subCode`**; добавлено **`scope`** (`fullCode` уже был).
- Запросы (структура та же, переименованы классы):
  - `POST /manage/connections/` → `CreateConnectionRequest { connectorCode, credentials:{…}, name? }`
  - `PATCH /manage/connections/{id}` → `UpdateConnectionRequest { enabled?, name? }`
  - `PUT /manage/connections/{id}/secret` → `UpdateConnectionSecretRequest { credentials:{…} }`
  - `POST /manage/connections/{id}/test` → `ConnectionTestResponse { valid, identifier, displayName, errorField?, errorMessage?, toolsDiscovered?, toolsError? }`

### Фильтры листинга `GET /manage/connections/`
Все коннекшены пользователя; опциональные query:
- `connectorCode` — `telegram` / `mcp` / …
- `scope` — `INSTANCE` (то, что юзер создаёт сам) / `AGENT` / `TEAM` / `USER` / `GLOBAL`
- `enabled` — `true` / `false`

Вкладка «мои интеграции» → `?scope=INSTANCE` (+ при необходимости `connectorCode`/`enabled`).

«Integration» больше не сущность, а **тип коннектора** (имеет `credentialFields`): create/secret/test
валидны только для таких коннекторов.

---

## 3. Ужесточение доступа (A) — поведенческое, без смены контрактов

Раньше часть запросов не проверяла владельца. Теперь:
- `GET /manage/agents/{id}` — чужой агент → `404` (было: мог отдать чужого).
- отвязка коннекшена от агента — требует владения агентом → чужой → `404`.
- webhook delivery logs — фильтруются по владельцу.
- `POST /manage/apps` — `connectorCode` валидируется (несуществующий/не-INSTANCE → `400`).

Контракты тел/ответов не менялись. Если фронт где-то полагался на доступ к чужим сущностям — это была
дыра, теперь `404`/`400`.

---

## 4. Чек-лист фронта

- [ ] `/manage/integrations/credentials/*` → `/manage/connections/*` (см. §1.1); PATCH без хвостового `/`.
- [ ] `platformIdentifier` → `subCode`; показать `scope`; вкладка экземпляров `?scope=INSTANCE`.
- [ ] Каталог тулов/триггеров типа → `/manage/connectors/{code}/tools|triggers/` (+ `/{toolName}`).
- [ ] Тулы экземпляра → `/manage/connections/{id}/tools/`; для mcp каталог типа пустой — брать с экземпляра.
- [ ] Убрать все вызовы `/manage/tools/**` и `/manage/integrations/*`.
- [ ] App: `/manage/app-tools|app-triggers/{id}` → `/manage/apps/{id}/tools|triggers/`.
- [ ] Доски: добавить `{boardId}` в статус/комментарии задач.
- [ ] `GET /agent/llm` → `GET /agent/llm/`.
- [ ] Webhook URL брать из ответа коннекшена (новый путь `/webhook/{id}`), не собирать руками.

---

## 5. Сознательно НЕ меняли (чтобы не искали)

- `ToolCallRequest.identity` **не** переименован в `connectionId` — `identity` это сквозной доменный
  термин (proto/контекст/каналы/триггеры). Уточнили только `@Schema`.
- `getBoards`/`getComments` `List`→`Page` — досок единицы, пагинацию не вводили (форма ответа — массив).
- DELETE остаётся `200` + конверт `SuccessResponse`, не `204`.
- Полные схемы DTO/ошибок — `docs/services/control-api-manage-connections.md` и соседние.
</content>
</invoke>
