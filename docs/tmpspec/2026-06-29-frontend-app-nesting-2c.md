# Frontend: app tools/triggers вложены под apps (cleanup 2c)

> Часть наведения порядка в эндпойнтах (Шаг 2c). **Breaking**, мелкое: read-only списки тулов/триггеров
> устройства переехали из плоских путей под родителя `apps`. Старые пути удалены (404).

## Карта путей

| Было (404) | Стало |
|---|---|
| `GET /manage/app-tools/{appId}` | `GET /manage/apps/{appId}/tools/` |
| `GET /manage/app-triggers/{appId}` | `GET /manage/apps/{appId}/triggers/` |

- Ответы не изменились: `List<AppTool>` и `List<AppTrigger>` соответственно.
- Семантика та же (тулы/триггеры конкретного app по его `appId`); просто корректная REST-вложенность под `apps` + хвостовой `/` (списки).

## Чек-лист фронта

- [ ] `GET /manage/app-tools/{id}` → `GET /manage/apps/{id}/tools/`
- [ ] `GET /manage/app-triggers/{id}` → `GET /manage/apps/{id}/triggers/`
