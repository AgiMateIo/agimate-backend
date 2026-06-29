# Frontend: консистентность REST (cleanup 2d)

> Часть наведения порядка в эндпойнтах (Шаг 2d). **Breaking** по путям. Старые пути удалены (404).

## Доски: задачи вложены в `{boardId}`

| Было (404) | Стало |
|---|---|
| `PATCH /manage/boards/tasks/{taskId}/status` | `PATCH /manage/boards/{boardId}/tasks/{taskId}/status` |
| `GET /manage/boards/tasks/{taskId}/comments/` | `GET /manage/boards/{boardId}/tasks/{taskId}/comments/` |
| `POST /manage/boards/tasks/{taskId}/comments/` | `POST /manage/boards/{boardId}/tasks/{taskId}/comments/` |

- Тело/ответ не изменились. Бэкенд проверяет, что задача принадлежит доске из пути (иначе `404`).
- Путь-переменная доски выровнена: `GET /manage/boards/{boardId}` (было `{id}`) — значение URL то же.

## Agent API: trailing slash на списке

| Было | Стало |
|---|---|
| `GET /agent/llm` | `GET /agent/llm/` |

(`GET /agent/llm/{name}` без изменений.)

## Чек-лист фронта

- [ ] Все вызовы статуса/комментариев задач — добавить `{boardId}` в путь.
- [ ] `GET /agent/llm` → `GET /agent/llm/`.

> Сознательно **не делали** (YAGNI): `getBoards`/`getComments` `List`→`Page` — досок у юзера единицы,
> пагинация избыточна; форму ответа (массив) не меняли. Если для комментариев реально нужна пагинация —
> заведём отдельно под конкретный кейс.
