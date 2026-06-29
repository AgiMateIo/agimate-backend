# Frontend: `/manage/integrations` → `/manage/connections` (cleanup 2a)

> Часть наведения порядка в эндпойнтах (Шаг 2a). **Breaking** для фронта: группа управления
> экземплярами коннекторов переименована под сущность `Connection`. Старые пути удалены (404).
> Полные схемы — `docs/services/control-api-manage-connections.md`.

---

## 0. TL;DR

«Integration credentials» — это были обычные `Connection` (экземпляры коннекторов). Группа
переименована, DTO выровнены под `Connection`, листинг фильтруется по реальным полям. «Integration»
осталось только как **тип коннектора** (есть `credentialFields`) — create/secret/test валидны лишь для таких.

---

## 1. Карта путей (старое → новое)

| Было (404) | Стало |
|---|---|
| `GET /manage/integrations/credentials/?connectorCode=` | `GET /manage/connections/?connectorCode=&scope=&enabled=` |
| `POST /manage/integrations/credentials/` | `POST /manage/connections/` |
| `GET /manage/integrations/credentials/{credentialId}` | `GET /manage/connections/{connectionId}` |
| `PATCH /manage/integrations/credentials/{credentialId}/` | `PATCH /manage/connections/{connectionId}` (без хвостового `/`) |
| `PUT /manage/integrations/credentials/{credentialId}/secret` | `PUT /manage/connections/{connectionId}/secret` |
| `DELETE /manage/integrations/credentials/{credentialId}` | `DELETE /manage/connections/{connectionId}` |
| `GET /manage/integrations/credentials/{credentialId}/tools/` | `GET /manage/connections/{connectionId}/tools/` |
| `POST /manage/integrations/credentials/{credentialId}/test` | `POST /manage/connections/{connectionId}/test` |
| `GET /manage/integrations/tools/?connectorCode=` | `GET /manage/connectors/{code}/tools/` (каталог типа) |
| `GET /manage/integrations/triggers/?connectorCode=` | `GET /manage/connectors/{code}/triggers/` (каталог типа) |

Каталожные tools/triggers переехали под **каталог коннекторов** (`/manage/connectors/{code}/...`),
т.к. они про тип, а не про экземпляр.

## 2. Фильтры листинга (по реальным полям)

`GET /manage/connections/` — все коннекшены пользователя, опциональные query-фильтры:
- `connectorCode` — код коннектора (`telegram`, `mcp`, …);
- `scope` — `IdentityScope`: `INSTANCE` (созданные пользователем telegram/mcp/app) / `AGENT` / `TEAM` / `USER` / `GLOBAL`;
- `enabled` — `true`/`false`.

Для вкладки «мои интеграции» используйте `?scope=INSTANCE` (то, что юзер реально создаёт).

## 3. DTO `ConnectionResponse` (было `IntegrationResponse`)

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
Изменения полей: `platformIdentifier` → **`subCode`**; добавлено **`scope`**. (`fullCode` уже был.)

Запросы:
- `POST /manage/connections/` — `CreateConnectionRequest { connectorCode, credentials: {…}, name? }` (без изменений по структуре, переименован класс).
- `PATCH /manage/connections/{id}` — `UpdateConnectionRequest { enabled?, name? }`.
- `PUT /manage/connections/{id}/secret` — `UpdateConnectionSecretRequest { credentials: {…} }`.
- `POST /manage/connections/{id}/test` → `ConnectionTestResponse { valid, identifier, displayName, errorField?, errorMessage?, toolsDiscovered?, toolsError? }`.

## 4. Ошибки

`404 "Connection not found"`, `409 "Connection already exists for <code>: <identifier>"`,
`400 "Integration connector not found: <code>"` (create по не-integration коннектору).

## 5. Чек-лист фронта

- [ ] Перебить все вызовы `/manage/integrations/credentials/*` на `/manage/connections/*` (см. карту §1).
- [ ] `platformIdentifier` → `subCode`; показать `scope` где нужно.
- [ ] Вкладка экземпляров — `GET /manage/connections/?scope=INSTANCE` (+ при необходимости `connectorCode`/`enabled`).
- [ ] Каталожные tools/triggers — на `/manage/connectors/{code}/tools/` и `/triggers/`.
- [ ] PATCH-настройки — без хвостового `/`.

> Не затронуто в 2a (придёт отдельно): webhook `/webhook/integration/{id}` и `ToolCallRequest.identity` (Шаг 2b).
