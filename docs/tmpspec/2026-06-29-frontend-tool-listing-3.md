# Frontend: дедуп листинга тулов (cleanup 3)

> Часть наведения порядка в эндпойнтах (Шаг 3 — D). **Breaking**: убрана дублирующая группа
> `/manage/tools/**`; листинг тулов теперь чётко разведён «каталог типа» vs «экземпляр».

## 0. Модель

- **Каталог типа коннектора** (предопределённые тулы/триггеры) → под `/manage/connectors/{code}`.
- **Тулы конкретного экземпляра** → под `/manage/connections/{connectionId}`.
- Единый бэкенд-источник — `ToolDefinitionService` (по `toolBinding`: STATIC — рефлексия, DYNAMIC — `connection_tools`).

## 1. Карта путей

| Было (404) | Стало |
|---|---|
| `GET /manage/tools/{code}/?identity=` (без id) | `GET /manage/connectors/{code}/tools/` (каталог типа) |
| `GET /manage/tools/{code}/?identity={connId}` | `GET /manage/connections/{connId}/tools/` (экземпляр) |
| `GET /manage/tools/{code}/{toolName}` | `GET /manage/connectors/{code}/tools/{toolName}` (схема тула) |

Каталожные триггеры — `GET /manage/connectors/{code}/triggers/` (как в 2a).

## 2. Поведение по типам

- **STATIC** (time/board/persist-memory/telegram): `/manage/connectors/{code}/tools/` отдаёт тулы типа.
- **DYNAMIC** (mcp): у типа тулов нет → `/manage/connectors/{code}/tools/` отдаёт **пустой список**; реальные
  тулы берём по экземпляру: `/manage/connections/{connectionId}/tools/` (для DYNAMIC `connectionId` обязателен,
  иначе `400`).

## 3. Не изменилось

- `/manage/connections/{connectionId}/tools/` — контракт тот же (внутри переключили на `ToolDefinitionService`).
- Agent API: `GET /agent/tools/{code}?identity=` — схемы тулов (для DYNAMIC `identity` обязателен). Это **не то же**,
  что `GET /agent/tool/` (список разрешённых агенту тулов) — разные назначения, оба остаются.

## 4. Чек-лист фронта

- [ ] Убрать вызовы `/manage/tools/**`.
- [ ] Каталог тулов типа → `/manage/connectors/{code}/tools/` (+ `/{toolName}` для схемы).
- [ ] Тулы экземпляра → `/manage/connections/{id}/tools/`.
- [ ] Для mcp каталог пустой — показывать тулы из экземпляра.
