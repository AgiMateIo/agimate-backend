# Спецификация для фронтенда: connections (sub_code/full_code) + capabilities

**Дата:** 2026-06-25
**Сервис:** control-api (HTTP manage-API)
**Контекст:** переход коннекторов на единый реестр экземпляров `connections` (см.
`docs/connectors/architecture.md`).

## TL;DR

1. **`IntegrationResponse` сменил форму**: `platformIdentifier` → **`subCode`** + новое поле
   **`fullCode`** (стабильный handle экземпляра, `mcp_context7`). **Breaking.**
2. **`GET /manage/connectors/`** на каждом коннекторе отдаёт объект **`capabilities`** (4 оси);
   поле **`type` УДАЛЕНО** из `ConnectorResponse`, а **фильтр `?type=`** убран из листинга. **Breaking.**
3. Имена тулов/триггеров и их эндпоинты по форме **не менялись**.

> «Тип коннектора» больше не отдельное поле — выводится из `capabilities` (+ наличие
> `integrationMeta.credentialFields` = это интеграция, которую можно подключить с кредами).

---

## 1. `IntegrationResponse` — `platformIdentifier` → `subCode` + `fullCode`

```jsonc
// Было
{ "id": "…", "connectorCode": "mcp", "platformIdentifier": "https://mcp.context7.com/mcp",
  "name": "Context7", "enabled": true, "lastUsedAt": "…", "createdAt": "…" }

// Стало
{ "id": "…", "connectorCode": "mcp",
  "subCode": "https://mcp.context7.com/mcp",   // дискриминатор экземпляра (бывший platformIdentifier)
  "fullCode": "mcp_context7",                  // НОВОЕ: стабильный клиентский handle
  "name": "Context7", "enabled": true, "lastUsedAt": "…", "createdAt": "…" }
```

| Поле        | Замечание |
|-------------|-----------|
| `subCode`   | Канонический идентификатор экземпляра на платформе (telegram-username, MCP-URL). **Замена `platformIdentifier`.** |
| `fullCode`  | `connectorCode + "_" + slug(subCode)` — уникален в рамках пользователя; **использовать как заголовок/ярлык экземпляра в UI** (различает «context7» от другого MCP). |

Затронутые эндпоинты (форма элемента — `IntegrationResponse`; обёртки `SuccessResponse` без изменений):

| Метод и путь | Возвращает |
|---|---|
| `GET  /manage/integrations/credentials/` (опц. `?connectorCode=`) | `List<IntegrationResponse>` |
| `POST /manage/integrations/credentials/` | `IntegrationResponse` |
| `GET  /manage/integrations/credentials/{id}` | `IntegrationResponse` |
| `PATCH /manage/integrations/credentials/{id}/` (enable/disable, name) | `IntegrationResponse` |
| `PUT  /manage/integrations/credentials/{id}/secret` (обновить креды) | `IntegrationResponse` |

Создание/обновление credentials по телу — **без изменений** (тот же `connectorCode` + map credentials).

---

## 2. `GET /manage/connectors/` — `capabilities` вместо `type`

`type` удалён; вместо него `capabilities` (4 оси) рядом с `integrationMeta`. Фильтр `?type=` в
листинге убран — остаётся только `?search=`.

```jsonc
{
  "code": "mcp", "name": "MCP Server", "description": "…",   // поля "type" БОЛЬШЕ НЕТ
  "capabilities": {
    "transportDirection": "OUTBOUND",   // OUTBOUND | INBOUND — кто инициирует соединение
    "executionLocus": "BACKEND",        // BACKEND | EXTERNAL | AGENT — где исполняется тул
    "toolBinding": "DYNAMIC",           // STATIC | DYNAMIC — фиксированный набор тулов или per-instance
    "sharingScope": "PRIVATE"           // PRIVATE | TEAM_SHARED | GLOBAL
  },
  "integrationMeta": { "credentialFields": { … } }   // только для интеграций (есть credentialFields)
}
```

| Ось | Значения | Подсказка для UI |
|-----|----------|------------------|
| `transportDirection` | `OUTBOUND` / `INBOUND` | OUTBOUND — мы подключаемся к платформе (telegram/mcp); INBOUND — устройство к нам (app) |
| `executionLocus` | `BACKEND` / `EXTERNAL` / `AGENT` | где физически выполняется тул |
| `toolBinding` | `STATIC` / `DYNAMIC` | DYNAMIC → тулы per-instance, тянуть после подключения |
| `sharingScope` | `PRIVATE` / `TEAM_SHARED` / `GLOBAL` | приватный / общий для команды / глобальный |

`capabilities` опционально (`null` — если у коннектора не задан дескриптор). Старые поля (`code`,
`type`, `name`, `description`, `integrationMeta`) — без изменений.

---

## 3. Тулы экземпляра — форма без изменений

`GET /manage/integrations/credentials/{id}/tools/` по-прежнему отдаёт `List<ConnectorToolSpec>`
(`name`/`title`/`description`/`inputSchema`/`outputSchema`/`annotations`/`_meta`). Для динамических
коннекторов (MCP) — из дискаверенного кэша; для статических — их штатный набор. `POST .../{id}/test`
(валидация + пересборка кэша MCP) — без изменений.

> Для отображения «к какому экземпляру относится тул» используйте `fullCode` соответствующей
> интеграции из §1 — он же станет неймспейс-префиксом имени тула у агента.

---

## Чек-лист

- [ ] Читать `subCode` вместо `platformIdentifier`; показывать `fullCode` как ярлык экземпляра (§1).
- [ ] Отрисовать `capabilities` в каталоге коннекторов; по `toolBinding=DYNAMIC` подтягивать тулы
      экземпляра после подключения (§2).
- [ ] Проверить, что списки тулов/триггеров читаются как прежде (§3).

## Источники в коде

- `controller/manage/dto/IntegrationResponse.java`, `controller/manage/dto/ConnectorResponse.java`,
  `database/model/ConnectorCapabilities.java`
- `controller/manage/ManageIntegrationController.java`, `ManageConnectorController.java`
- `connectors/core/FullCodes.java`, `database/entities/Connection.java`
