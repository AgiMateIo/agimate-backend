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
  "stream": "answer|progress|error|null", "text": "...", "createdAt": "..." }
```

`parts` в запросе зарезервирован под вложения (файлы/картинки) — сейчас должен быть пуст.
