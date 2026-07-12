# Frontend: капабилити коннекшена (tools / triggers / jobs) единообразно по connectionId

> **Не breaking.** Только добавления + починка двух ошибочных `400`. Существующие пути работают
> как раньше. Суть: капабилити коннекшена (тулы, триггеры, джобы) теперь адресуются **единообразно
> по `connectionId`**, а листинги больше не падают на «канальных» коннекторах без тулов/триггеров.

## 0. Модель (важно понять)

`connector` — это лишь **декларация** обработки подключения. Он может не знать, какие у него тулы /
триггеры / джобы. Конкретный набор появляется, когда создан **`connection`**. Более того — **разные
connection одного и того же connector могут давать разные наборы** (MCP: свои тулы per-instance;
device-app: свои триггеры).

Отсюда правило для фронта:

- **Каталог типа** (`/manage/connectors/{code}/...`) — для экрана «до создания коннекшена» (что тип
  в принципе умеет). Для DYNAMIC-типов он может быть пустым — это нормально.
- **Экземпляр** (`/manage/connections/{connectionId}/...`) — **источник правды** для конкретного
  коннекшена. На карточке коннекшена всё берём отсюда.

## 1. Что добавилось

| Метод | Назначение | Возвращает |
|---|---|---|
| `GET /control/manage/connections/{connectionId}/triggers/` | **NEW** триггеры экземпляра | `TriggerSpecificationResponse[]` |
| `GET /control/manage/connections/{connectionId}/jobs/` | **NEW** фоновые джобы экземпляра | `ConnectorJobResponse[]` |

Уже существовал и не менялся по контракту:

| Метод | Назначение |
|---|---|
| `GET /control/manage/connections/{connectionId}/tools/` | тулы экземпляра |

Итог: на карточке коннекшена три вкладки — **Tools / Triggers / Jobs** — все по одному `connectionId`.

## 2. Починка ошибочных `400` (баги)

Раньше эти листинги падали `400` на легальных коннекторах, у которых просто нет соответствующей
капабилити. Теперь возвращают **пустой список** (или полный набор):

- `GET /manage/connectors/{code}/triggers/` для внутренних коннекторов (`persist-memory`, `webchat`,
  `acp`, `time`) больше **не** отдаёт `400 "Connector is not an integration"` — отдаёт их триггеры.
- `GET /manage/connections/{id}/tools/` для канальных коннекторов (`webchat`, `acp`) больше **не**
  отдаёт `400 "Unsupported connector"` — отдаёт `[]`.

Правило для фронта: **отсутствие капабилити = пустой список, не ошибка.** Пустой массив рисуем как
«нет тулов / триггеров / джоб», без баннера ошибки.

## 3. Семантика: specs vs instances

Тонкий, но важный момент — форма ответа разная, потому что сущности разные:

- **Tools / Triggers = specs (декларации).** «Что коннекшен умеет». Плоские спеки для рендера
  списков/пикеров.
  - Триггеры экземпляра = объединение type-declared (спеки типа) ∪ динамических per-connection
    (device-app). Динамический триггер с тем же `name` перекрывает одноимённый type-declared.
- **Jobs = instances (runtime-строки).** «Что реально запланировано/крутится для коннекшена»: со
  `status`, `nextRunAt`, `pausedAt`, `lastError`. Это не пикер — это монитор + управление.

## 4. Формы ответов

### TriggerSpecificationResponse (tools/triggers — specs)
```json
{ "name": "message_received",
  "description": "Message from the user typed in the web chat",
  "params": ["sessionId", "messageId", "text"] }
```
- `params` — имена параметров в `trigger.data`. Для динамических триггеров извлекаются best-effort из
  их JSON Schema; может быть `[]`.

### ConnectorJobResponse (jobs — instances)
```json
{ "id": "0190aa...", "kind": "SYSTEM", "connectorCode": "persist-memory",
  "connectionId": "038b756a-...", "agentId": null, "name": "consolidate",
  "type": "CRON", "config": { "cron": "0 3 * * *", "zone": "UTC" }, "args": {},
  "status": "PENDING", "nextRunAt": "2026-07-13T03:00:00",
  "pausedAt": null, "lastError": null, "createdAt": "2026-07-12T20:00:00" }
```
- `kind`: `SYSTEM` (декларирована коннектором — удалить нельзя, только пауза), `USER`, `AGENT`.
- `pausedAt != null` → scheduler пропускает задачу до resume.

## 5. Управление джобами (lifecycle)

Листинг — по коннекшену; **действия — по `id` джобы** на прежнем контроллере:

| Действие | Метод |
|---|---|
| Пауза | `POST /control/manage/connector-jobs/{id}/pause` |
| Возобновить | `POST /control/manage/connector-jobs/{id}/resume` |
| Запустить сейчас | `POST /control/manage/connector-jobs/{id}/run-now` |
| Удалить (USER/AGENT) | `DELETE /control/manage/connector-jobs/{id}` |

`run-now` работает только из `PENDING` и не на паузе. Удаление `SYSTEM`-джобы → `400` (её пересоздаст
reconcile) — для `SYSTEM` показываем только паузу.

Глобальный список джоб (все коннекшены, с фильтрами/пагинацией) остаётся:
`GET /control/manage/connector-jobs/?connectorCode=&kind=&page=&size=`.

## 6. Ошибки

| Статус | Когда |
|---|---|
| `404` | Коннекшен не найден или чужой (для всех трёх `/connections/{id}/{tools\|triggers\|jobs}/`) |
| `200 []` | Капабилити нет — это норма, не ошибка |

## 7. Рекомендация по экрану коннекшена

1. Загрузить коннекшен: `GET /manage/connections/{id}`.
2. Три вкладки параллельно: `.../tools/`, `.../triggers/`, `.../jobs/`.
3. Пустой массив → «нет …», без ошибки.
4. В Jobs — экшены по `id` через `/manage/connector-jobs/{id}/...`; для `SYSTEM` прятать «Удалить».
