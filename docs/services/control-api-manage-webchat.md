# control-api — Webchat Endpoints

API specification for `/manage/webchat/**` — чат пользователя с агентом из фронта. Авторизация —
user JWT (audience `manage`). Архитектура коннектора: `docs/connectors/webchat.md`.

> Все пути ниже — относительно context path `/control`.

## Поток фронта

1. `POST /manage/webchat/sessions` `{agentId}` — новая сессия (binding/канал материализуются лениво).
2. `POST /manage/webchat/sessions/{id}/token` — Centrifugo-токены; подписка на `webchat:{sessionId}`.
3. (опц.) `POST /manage/webchat/files` — загрузить вложения, получить `fileId` каждого.
4. `POST /manage/webchat/sessions/{id}/messages` `{text?, parts?}` — отправка (текст и/или
   `parts: [{fileId}]`, хотя бы одно непустое); в канал прилетают события `webchat_message`: echo
   (`direction=USER`, со своими `parts`), прогресс (`direction=AGENT, stream=progress`) и ответ
   (`stream=answer`). Дедупликация — по `messageId` (события at-least-once).
5. При открытии существующей сессии история — `GET /manage/webchat/sessions/{id}/messages/`.

Сообщение в сессию с активным run'ом обрабатывается штатной steering-политикой воркера
(`queue`/`steer`/`interrupt`).

## Endpoints

| Метод | Путь | Описание |
|---|---|---|
| POST | `/manage/webchat/sessions` | Новая сессия: `{agentId}` → `WebchatSessionResponse` |
| GET | `/manage/webchat/sessions/` | Список сессий (`?agentId=` опционально), свежие сверху |
| POST | `/manage/webchat/files` | Загрузить файл (multipart `file`) → `WebchatFileResponse`; rate-limit 429 |
| POST | `/manage/webchat/sessions/{id}/messages` | Отправить сообщение: `{text?, parts?}` → `{sessionId, messageId}`; закрытая сессия / пустое сообщение / невалидный `parts` → 400 |
| GET | `/manage/webchat/sessions/{id}/messages/` | История UI-лога (`page`, `size`; новые сначала) |
| DELETE | `/manage/webchat/sessions/{id}` | Закрыть сессию (`closedAt`; история остаётся читаемой) |
| POST | `/manage/webchat/sessions/{id}/token` | Centrifugo connection+subscription токены на `webchat:{sessionId}` |

### Загрузка файла (`WebchatFileResponse`)

`POST /manage/webchat/files` — multipart-часть `file`; лимит размера/суточная квота — общие для
файлового слоя (`app.files.*`), rate-limit — bucket `FILE_UPLOAD` (429 при превышении).

```json
{ "fileId": "agf_…", "mime": "image/png", "size": 384211, "expiresAt": "..." }
```

`fileId` кладётся во вложения сообщения: `parts: [{ "fileId": "agf_…" }]` при отправке.

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

В **запросе** отправки `parts` — вложения пользователя: `[{ "fileId": "agf_…" }]` (файлы из
`POST /manage/webchat/files`). В **ответе**/истории `parts` — полное описание (URL — подписанная
ссылка на содержимое, как у вложений агента). Текст опционален при непустых `parts`; пустое
сообщение (ни текста, ни вложений) → 400.

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
