# control-api — Webchat Endpoints

API specification for `/manage/webchat/**` — чат пользователя с агентом из фронта. Авторизация —
user JWT (audience `manage`). Архитектура коннектора: `docs/connectors/webchat.md`.

> Все пути ниже — относительно context path `/control`.

## Поток фронта

1. `POST /manage/webchat/sessions` `{agentId}` — новая сессия (binding/канал материализуются лениво).
2. `POST /manage/webchat/sessions/{id}/token` — Centrifugo-токены; подписка на `webchat:{sessionId}`.
3. `POST /manage/webchat/sessions/{id}/messages` `{text}` — отправка; в канал прилетают события
   `webchat_message`: echo (`direction=USER`), прогресс (`direction=AGENT, stream=progress`) и ответ
   (`stream=answer`). Дедупликация — по `messageId` (события at-least-once).
4. При открытии существующей сессии история — `GET /manage/webchat/sessions/{id}/messages/`.

Сообщение в сессию с активным run'ом обрабатывается штатной steering-политикой воркера
(`queue`/`steer`/`interrupt`).

## Endpoints

| Метод | Путь | Описание |
|---|---|---|
| POST | `/manage/webchat/sessions` | Новая сессия: `{agentId}` → `WebchatSessionResponse` |
| GET | `/manage/webchat/sessions/` | Список сессий (`?agentId=` опционально), свежие сверху |
| POST | `/manage/webchat/sessions/{id}/messages` | Отправить сообщение: `{text, parts?}` → `{sessionId, messageId}`; закрытая сессия/непустой `parts` → 400 |
| GET | `/manage/webchat/sessions/{id}/messages/` | История UI-лога (`page`, `size`; новые сначала) |
| DELETE | `/manage/webchat/sessions/{id}` | Закрыть сессию (`closedAt`; история остаётся читаемой) |
| POST | `/manage/webchat/sessions/{id}/token` | Centrifugo connection+subscription токены на `webchat:{sessionId}` |

### `WebchatSessionResponse`

```json
{ "sessionId": "...", "channelId": "...", "agentId": "...", "title": "первое сообщение…",
  "lastMessageAt": "...", "closedAt": null, "createdAt": "..." }
```

### `WebchatMessageResponse`

```json
{ "id": "...", "messageId": "...", "direction": "USER|AGENT",
  "stream": "answer|progress|error|null", "text": "...",
  "parts": [ { "type": "image", "fileId": "agf_…", "mime": "image/png", "size": 384211,
               "url": "/files/agf_…?exp=…&sig=…" } ],
  "createdAt": "..." }
```

`parts` в запросе зарезервирован под вложения от пользователя — сейчас должен быть пуст.

### Вложения ответа агента (`parts`)

Ответ агента может нести файлы (attach-конвенция, `docs/connectors/files.md`); они приходят и в
событии `webchat_message`, и в истории — полем `parts` (`null` — сообщение без вложений).

- `url` — подписанный URL содержимого (`GET /files/{fileId}?exp&sig`, без Authorization-заголовка —
  работает в `<img src>`), относительный к context path: фронт префиксует `{origin}/control`.
- Ссылка живёт `app.files.url-ttl` (дефолт 15 мин). Протухла (403) — перечитать историю: каждая
  выдача подписывает ссылки заново. Файл с истёкшим TTL хранилища отдаёт 404 — показывать заглушку.
- Изображения отдаются `Content-Disposition: inline` (рендер в браузере), остальные типы —
  `attachment`; активный контент (SVG/HTML и т.п.) деградирует до `octet-stream`.
- `parts` несёт только `stream=answer` — в `progress` вложения не приходят.
