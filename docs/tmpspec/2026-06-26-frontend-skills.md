# Frontend: упрощение скиллов (одна таблица, без клонов и featured)

> Временная спека для фронтенда. Бэкенд свернул скиллы в **одну таблицу** `skills`. Это
> **breaking-изменение** API группы `/control/manage/skills/**`, `/control/manage/agents/{id}/skills/**`
> и `/control/agent/skills/**`. Полные схемы — в `docs/services/control-api-manage-skills.md`.

---

## 0. TL;DR что поменялось

| Было | Стало |
|---|---|
| `is_featured` / витрина `/featured/` | **удалено** (системные скиллы теперь в `/public/`) |
| Клонирование (`POST /{id}/clone`, `parentPubId`, `myCopyId`) | **удалено** — публичный/системный скилл привязывается к агенту **напрямую**, без копии |
| Отдельное управление коннекторами скилла | **удалено** — коннекторы объявляются в frontmatter `SKILL.md` |
| Файлы скилла (`/manage/skill-files/**`, `/agent/skills/{id}.zip`) | **удалено** — у скилла только `SKILL.md`, его тело лежит в поле |
| `SkillDetailResponse.skillMd` (с frontmatter) | `mdContent` — **тело без frontmatter** |
| — | у скилла появилось `connectorCodes: string[]` |
| — | в списке скиллов агента — статус подключения коннекторов (KV) |

---

## 1. Удалённые эндпоинты (вернут 404)

- `GET /control/manage/skills/featured/`
- `POST /control/manage/skills/{id}/clone`
- Вся группа `GET/POST/DELETE /control/manage/skill-files/**`
- `GET /control/agent/skills/{skillPubId}.zip`

Убрать соответствующие кнопки/вью: вкладку «Featured», кнопки «Clone»/«Open my copy», менеджер файлов скилла, скачивание ZIP.

---

## 2. Изменённые DTO

### `SkillResponse` (списки + create/update)
```jsonc
{
  "id": "uuid",
  "name": "string",
  "description": "string|null",
  "connectorCodes": ["board", "time"],   // НОВОЕ: коннекторы скилла
  "version": 3,
  "isPublic": true,
  "userId": "uuid",
  "createdAt": "2026-06-26T12:00:00",
  "updatedAt": "2026-06-26T12:00:00"
}
```
Удалены поля: `isFeatured`, `parentPubId`, `myCopyId`.

### `SkillDetailResponse` (`GET /manage/skills/{id}`)
То же, что `SkillResponse`, плюс:
```jsonc
{ "mdContent": "# Skill: ...\n\n## Обзор\n..." }   // тело SKILL.md БЕЗ frontmatter
```
Поле `skillMd` (раньше — полный файл с frontmatter) **переименовано** в `mdContent` и теперь содержит
**только тело**. Имя/описание/коннекторы бери из соответствующих полей, не парси frontmatter на фронте.

### `AgentSkillResponse` (`GET /manage/agents/{agentId}/skills/`)
```jsonc
{
  "id": "uuid",
  "agentId": "uuid",
  "skillId": "uuid",
  "skillName": "string|null",
  "connectors": [                          // НОВОЕ: статус коннекторов для этого агента
    { "connectorCode": "board", "connectionId": "uuid" },
    { "connectorCode": "mcp",   "connectionId": null }
  ],
  "needsReinstall": false,
  "createdAt": "...",
  "updatedAt": "..."
}
```
`connectionId === null` → у агента нет активного коннекшена этого типа: показать CTA «Подключить
коннектор `<code>`». Если для всех `connectors` есть `connectionId` — скилл полностью обеспечен.

### `AgentSkillWithConnectorsResponse` (`GET /agent/skills/`, agent API-key)
```jsonc
{ "skillId": "uuid", "skillName": "string|null", "description": "string|null", "connectorCodes": ["board"] }
```

---

## 3. Создание/редактирование скилла — коннекторы в frontmatter

Тело запроса `POST /manage/skills/` и `PUT /manage/skills/{id}` **не изменилось**: `{ skillMd, isPublic }`.
Но `connectors` теперь объявляются **внутри frontmatter** `SKILL.md`:

```markdown
---
name: AgiMate Kanban Board
description: Работа с доской команды агентов
connectors: [board]
---

# Skill: ...   ← это уходит в mdContent
```

Бэкенд парсит `name`/`description`/`connectors` из frontmatter, тело кладёт в `mdContent`. Отдельного
эндпоинта «добавить коннектор к скиллу» больше нет — редактирование коннекторов = редактирование
frontmatter в редакторе скилла.

---

## 4. Новый флоу привязки (без клонирования)

Раньше: чужой публичный скилл нужно было **клонировать**, потом привязывать свою копию.
Теперь: **привязываем напрямую** — `POST /control/manage/agents/{agentId}/skills/` с `{ "skillId": "<любой свой или публичный>" }`.

- Вкладка «Публичные» (`GET /manage/skills/public/`) теперь отдаёт **все** публичные скиллы (без
  `myCopyId`). Кнопка на карточке — сразу «Привязать к агенту», без шага клонирования.
- Системные скиллы (`AgiMate Time` / `Kanban Board` / `Memory`) — публичные, видны в `/public/`,
  привязываются напрямую.
- Отвязка (`DELETE /manage/agents/{agentId}/skills/{skillId}`) убирает связь, но **не отзывает**
  доступ к коннекторам (add-only) — это нормально, отдельная отвязка коннектора делается в другом месте.

> Общий мутабельный скилл: если владелец публичного скилла его обновит — у привязавших агентов
> поднимется `needsReinstall`; если удалит — скилл пропадёт из их списка. Закладывай это в UX
> (бейдж «обновился», «скилл недоступен»).

---

## 5. Чек-лист миграции фронта

- [ ] Убрать вкладку/раздел **Featured** и поле `isFeatured`.
- [ ] Убрать **клонирование**: кнопки Clone/Open-my-copy, обработку `parentPubId`/`myCopyId`/409-`existingSkillId`.
- [ ] Убрать **менеджер файлов** скилла и кнопку скачивания ZIP.
- [ ] Detail: читать `mdContent` (тело) вместо `skillMd`; не ждать frontmatter в значении.
- [ ] Показать `connectorCodes` на карточке/детали скилла.
- [ ] В списке скиллов агента — рендерить `connectors[]` со статусом подключения (`connectionId == null` → CTA «Подключить»).
- [ ] Публичный скилл привязывать напрямую (без clone); системные скиллы искать в `/public/`.
- [ ] Редактор коннекторов скилла = редактирование `connectors: [...]` во frontmatter.
