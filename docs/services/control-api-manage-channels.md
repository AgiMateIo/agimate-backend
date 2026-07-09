# control-api — Channel Endpoints

API specification for `/manage/channels/**` — управление **каналами**: связка между диалоговым триггером (сообщение пользователя в Telegram-чат, веб-форме и т.п.) и обратной доставкой ответа агента.

Канал отличает «сообщение от пользователя» от обычного сервисного триггера. Когда триггер с привязанным каналом срабатывает, backend:
1. находит активную сессию (или создаёт новую — sliding window 12 ч),
2. сохраняет входящее сообщение (`direction=IN`) с полным `trigger.data`,
3. передаёт агенту payload триггера, дополненный полями `_channel_id` и `_channel_session_id`.

Когда worker / агент хочет ответить, он использует gRPC-методы `ChannelGateway.ListChannels` / `ChannelGateway.SendChannelMessage` (см. `agimate-worker-protocol-spec.md`).

> Все пути ниже — относительно context path `/control`.

## Модель: handler + config

Канал больше **не** хранит trigger/reply-поля по отдельности. Вместо этого у него:

- `channelHandler` — имя обработчика (`ChannelHandler`), реализованного в коде; задаёт логику преобразования триггеров во входящие сообщения и ответа модели в вызовы тулов;
- `connectorCode` + `connectionId` — источник триггеров (и, как правило, ответов);
- `config` — произвольная карта настроек, которую интерпретирует конкретный handler.

Набор триггеров и тулов канала вычисляет сам handler (`listOfTriggers(config)` / `listOfTools(config)`); под них при создании генерируются `AgentTriggerPolicy` / `AgentToolPolicy` (по одной на каждый триггер/тул, с `channel_id`).

### Handler `generic`

Универсальный data-driven обработчик, воспроизводящий прежнее поведение каналов (подходит для `app` и любых динамических коннекторов, где код-handler написать нельзя). Все существующие каналы мигрированы на него. Ключи его `config`:

| Ключ | Тип | Описание |
|---|---|---|
| `triggers` | string[] | Имена триггеров на `connectorCode` (например `["message_received"]`) |
| `messageField` | string | Dot-path внутри `trigger.data` до текста сообщения пользователя |
| `replyConnectorCode` | string | Коннектор для ответа (обычно совпадает с `connectorCode`) |
| `replyIdentity` | string | Identity reply-коннектора |
| `replyToolName` | string | Тул, отправляющий ответ |
| `replyToolParams` | object | JSON-шаблон параметров тула с плейсхолдерами (см. ниже) |

#### Плейсхолдеры в `replyToolParams`

Backend перед вызовом тула рекурсивно проходит `replyToolParams` (Map, List, скаляры) и подставляет:

| Плейсхолдер | Значение |
|---|---|
| `{text}` | Текст ответа агента (gRPC `SendChannelMessage.text`) |
| `{trigger.<dot.path>}` | Поле из `triggerInput` **последнего IN-сообщения сессии**, например `{trigger.data.message.chat_id}` |

Правила рендеринга:
- Строка целиком из одного плейсхолдера (`"{trigger.data.message.chat_id}"`) → результат сохраняет **исходный JSON-тип** (number/boolean/object).
- Плейсхолдер с текстом (`"Re: {text}"`) → интерполяция в строку.
- Нерешённый `{trigger.*}` → пустая строка при интерполяции; `null` при «чистом» плейсхолдере.

#### Опциональный `inputFilter`

Хранится на ассоциированной `AgentTriggerPolicy`, но денормализуется в `ChannelResponse.inputFilter`. Плоский объект `{ "<dot.path>": <expected scalar> }`, сцепляется по AND. Пример — отсекать триггеры от конкретного чата: `{ "data.message.chat_id": 12345 }`.

### Handler `telegram`

Код-handler для Telegram. Требует `connectorCode = "telegram"`. Обрабатывает все пять триггеров (`message_received`, `photo_received`, `document_received`, `command_received`, `callback_query`) и отвечает текстом через `send_message` (адресат — `chatId` из исходного входящего).

`config` (см. `GET /handlers/` для схемы):

| Ключ | Тип | Описание |
|---|---|---|
| `allowedChatIds` | integer[]? | Если задано — обрабатываются только сообщения из этих `chat_id`; пусто/нет — из всех. Фильтр применяется при роутинге: отсечённый триггер **не доходит до агента** и не создаёт сессию. |
| `defaultChatId` | integer? | `chat_id` для проактивных ответов, когда в сессии нет входящего (например агента разбудил `time.due`). `handleOutput` берёт адрес: из входящего → иначе `defaultChatId`. |

Пример создания (только конкретный чат):
```json
{
  "agentId": "018f...",
  "name": "TG bot",
  "channelHandler": "telegram",
  "connectorCode": "telegram",
  "connectionId": "018f...",
  "config": { "allowedChatIds": [12345] }
}
```

Входящие приводятся к тексту:
- текст/команда — как есть;
- callback — `[Нажата кнопка] <data>`;
- фото/документ — **файл не скачивается**, подставляется описание (`[Пользователь отправил изображение]` / `[Пользователь отправил документ: <file_name>]`) + подпись, если есть.

Скачивание/транскрибация медиа — отдельный этап.

---

## Authentication

| Группа | Механизм | Заголовок |
|---|---|---|
| `/manage/channels/**` | **JWT** | `Authorization: Bearer <jwt>` |

**Стандартные ошибки:**

| Status | Body | Когда |
|---|---|---|
| 400 | `{ "error": { "message": "Unknown channel handler: X" } }` | Неизвестный `channelHandler` |
| 400 | `{ "error": { "message": "config.messageField is required" } }` | Невалидный `config` (сообщение от `handler.validateConfig`) |
| 400 | `{ "error": { "message": "Trigger 'X' not available on connector 'Y'" } }` | Триггер/тул не существует на коннекторе |
| 400 | `{ "error": { "message": "Changing the trigger/tool set is not allowed; recreate the channel instead" } }` | PATCH меняет набор триггеров/тулов |
| 401 | `{ "error": { "message": "Authentication credentials not found or invalid" } }` | Невалидный JWT |
| 403 | `{ "error": { "message": "Access denied" } }` | Канал принадлежит другому пользователю |
| 404 | `{ "error": { "message": "Channel not found" } }` | Канал/сессия не существует или soft-deleted |
| 409 | `{ "error": { "message": "Trigger policy for 'X' already linked to another channel" } }` | Для триггера уже есть ALLOW-policy, привязанная к другому каналу |
| 409 | `{ "error": { "message": "Tool policy for 'X' already linked to another channel" } }` | Для тула уже есть ALLOW-policy, привязанная к другому каналу |

---

## Модель данных (для UI)

### `Channel` (`ChannelResponse`)

| Поле | Тип | Описание |
|---|---|---|
| `id` | UUID | ID канала |
| `agentId` | UUID | Какому агенту принадлежит |
| `name` | string | Отображаемое имя |
| `channelHandler` | string | Имя обработчика (например `generic`) |
| `connectorCode` | string | Коннектор источника триггеров |
| `connectionId` | string | `App.id` (для APP) или `IntegrationCredentials.id` (для INTEGRATION) |
| `connectionName` | string? | Денормализованное имя connectionId (`App.name` / `IntegrationCredentials.name`/`platformIdentifier`). `null`, если удалён/невалиден |
| `config` | object | Настройки handler-а (для `generic` — см. таблицу выше) |
| `inputFilter` | object? | Денормализованный фильтр со связанной `AgentTriggerPolicy`. `null`, если не задан |
| `createdAt` | datetime | |
| `updatedAt` | datetime | |

### `ChannelSession`

12-часовое окно общения. Создаётся при первом IN-сообщении, продлевается при каждом IN/OUT (`lastMessageAt`). Можно закрыть явно — следующий триггер откроет новую сессию.

| Поле | Тип | Описание |
|---|---|---|
| `pubId` | UUID | |
| `title` | string? | Автоген из первых 80 символов первого IN |
| `lastMessageAt` | datetime | |
| `closedAt` | datetime? | `null` — активна |
| `createdAt` | datetime | |

Активность: `closedAt IS NULL AND lastMessageAt > NOW() - 12h`.

### `ChannelSessionMessage`

| Поле | Тип | Описание |
|---|---|---|
| `pubId` | UUID | |
| `direction` | enum | `IN` / `OUT` |
| `message` | string | Текст. Для IN — извлечён control-api через `handler.handleInput` (`generic` — по `config.messageField`, при пустом результате — JSON `trigger.data`) |
| `createdAt` | datetime | |

> `triggerInput` (полный JSON триггера) хранится для IN и используется при рендере `{trigger.*}`; в API не выдаётся.

---

## Endpoints

### GET `/control/manage/channels/handlers/`

Список доступных обработчиков и JSON Schema их `config` — для мастера создания канала (UI рендерит форму по схеме).

**Response 200:**
```json
{
  "response": [
    {
      "name": "generic",
      "configFields": { "type": "object", "properties": { "triggers": { "type": "array", "items": { "type": "string" }, "title": "Триггеры", "description": "..." }, "messageField": { "type": "string", "title": "Поле сообщения", "description": "..." } }, "required": ["triggers", "messageField", "replyConnectorCode", "replyIdentity", "replyToolName", "replyToolParams"] }
    },
    {
      "name": "telegram",
      "configFields": { "type": "object", "properties": { "allowedChatIds": { "type": "array", "items": { "type": "integer" }, "title": "Разрешённые чаты", "description": "..." } }, "required": [] }
    }
  ]
}
```

### GET `/control/manage/channels/`

Список каналов пользователя (soft-deleted исключены), сортировка по `createdAt` DESC.

**Query:** `agentId` (UUID, опц.) — фильтр по агенту.

**Response 200:**
```json
{
  "response": [
    {
      "id": "018f...",
      "agentId": "018f...",
      "name": "Telegram support bot",
      "channelHandler": "generic",
      "connectorCode": "telegram",
      "connectionId": "018f...",
      "connectionName": "My Telegram Bot",
      "config": {
        "triggers": ["message_received"],
        "messageField": "data.message.text",
        "replyConnectorCode": "telegram",
        "replyIdentity": "018f...",
        "replyToolName": "send_message",
        "replyToolParams": { "chat_id": "{trigger.data.message.chat_id}", "text": "{text}" }
      },
      "inputFilter": { "data.message.chat_id": 12345 },
      "createdAt": "2026-06-16T12:00:00",
      "updatedAt": "2026-06-16T12:00:00"
    }
  ]
}
```

### GET `/control/manage/channels/{id}`

Один канал по id.

### POST `/control/manage/channels/`

Создаёт канал и `AgentTriggerPolicy`/`AgentToolPolicy` (effect=ALLOW, `channel_id`=<new>) — по одной на каждый триггер/тул handler-а. Если для тех же `(agent, connector, connectionId, name)` уже была ALLOW-policy **без** `channel_id`, она апгрейдится (присваивается `channel_id`, а триггерной — `input_filter`).

**Request body:**
```json
{
  "agentId": "018f...",
  "name": "Telegram support bot",
  "channelHandler": "generic",
  "connectorCode": "telegram",
  "connectionId": "018f...",
  "config": {
    "triggers": ["message_received"],
    "messageField": "data.message.text",
    "replyConnectorCode": "telegram",
    "replyIdentity": "018f...",
    "replyToolName": "send_message",
    "replyToolParams": { "chat_id": "{trigger.data.message.chat_id}", "text": "{text}" }
  },
  "inputFilter": { "data.message.chat_id": 12345 }
}
```

Обязательны `agentId`, `name`, `channelHandler`, `connectorCode`, `connectionId`, `config`. `inputFilter` — опционален.

**Валидация бэкенда:**

1. Агент существует и принадлежит пользователю → иначе `404`/`403`.
2. `channelHandler` зарегистрирован → иначе `400`.
3. `handler.validateConfig(config)` → иначе `400` (сообщение от handler-а).
4. Для каждого триггера из `handler.listOfTriggers(config)`: коннектор существует, `connectionId` валиден и активен, триггер есть в `App.triggers` (APP) или `ConnectorHandler.getTriggers()` (INTEGRATION); `INTERNAL_SERVICE`/`LOOPBACK` → `400`.
5. Для каждого тула из `handler.listOfTools(config)`: аналогично, тул есть в `App.tools` / `ConnectorHandler.getTools()`.
6. Нет ALLOW trigger/tool-policy, привязанной к другому каналу → иначе `409`.

**Response 200:** `ChannelResponse`.

### PATCH `/control/manage/channels/{id}`

Частичное обновление. Все поля опциональны.

**Request body:**
```json
{
  "name": "...",
  "config": { ... },
  "inputFilter": { ... },
  "clearInputFilter": false
}
```

- `name` — `null` = не трогать.
- `config` — **полная замена** (валидируется через handler). Набор триггеров/тулов должен совпасть с текущим, иначе `400` («recreate the channel»).
- `inputFilter` — обновляет фильтр на связанных trigger-policy. `clearInputFilter: true` (+ `inputFilter: null`) снимает фильтр.

**Не меняется через PATCH:** `agentId`, `channelHandler`, `connectorCode`, `connectionId` и набор триггеров/тулов. Для смены — пересоздать канал.

**Response 200:** `ChannelResponse`.

### DELETE `/control/manage/channels/{id}`

Soft delete (`deletedAt = NOW()`). Связанные trigger/tool-policy **удаляются** (hard delete по `channel_id`). Сессии и сообщения остаются.

**Response 200:** `{ "response": null }`

---

## Sessions

### GET `/control/manage/channels/{id}/sessions/`

Сессии канала, сортировка по `lastMessageAt` DESC.

```json
{ "response": [ { "pubId": "018f...", "title": "Привет", "lastMessageAt": "2026-06-16T14:30:00", "closedAt": null, "createdAt": "2026-06-16T12:00:00" } ] }
```

### GET `/control/manage/channels/sessions/{sessionPubId}/messages/`

Сообщения сессии в хронологическом порядке. Доступ проверяется по владельцу канала.

```json
{ "response": [
  { "pubId": "018f...", "direction": "IN", "message": "Привет, как дела?", "createdAt": "2026-06-16T12:00:00" },
  { "pubId": "018f...", "direction": "OUT", "message": "Привет! Чем помочь?", "createdAt": "2026-06-16T12:00:05" }
] }
```

### POST `/control/manage/channels/sessions/{sessionPubId}/close`

Закрывает сессию (`closedAt = NOW()`). Идемпотентно.

**Response 200:** `ChannelSessionResponse`.

---

## Связь с другими сущностями

- **AgentTriggerPolicy / AgentToolPolicy**: у policy есть `channelId` (и у trigger-policy — `inputFilter`). Если `channelId != null`, policy управляется через `/manage/channels/` — напрямую её редактировать не следует. Один канал может порождать несколько policy (по числу триггеров/тулов handler-а).
- **TriggerLog**: не меняется — в `input` лога лежит оригинал.
- **ToolCallLog**: ответ через канал идёт через `AgentToolCallService.processToolCall` — та же проверка ABAC, что и для обычных вызовов агента (effect берётся из tool-policy, созданной при создании канала). `messageId` — это `message_id` из `SendChannelMessage` (или сгенерированный UUID).

## Notes

- Один активный канал на `(agent, connector, connectionId)` — частичный UNIQUE-индекс `uq_channels_agent_connector_identity_active` (`WHERE deleted_at IS NULL`). Маршрут триггера определяется наличием такого канала, а не `policy.channel_id`.
- Soft delete канала не удаляет историю сессий/сообщений.
- Извлечение текста входящего выполняет control-api для **всех** handler-ов через `handler.handleInput()`: `generic` — по `config.messageField`, при пустом результате — JSON `trigger.data`; код-handler'ы (`telegram`) — собственная логика. **Воркеру** отдаётся готовый `InboundMessage` + `Channels` в `AgentMessage`; `messageField`/`triggerMessageField` больше не передаются. Скачивание медиа — Фаза 2.
- `AgentMessage` для воркера минимизирован: `InboundMessage` = `{ text, parts }` (без `replyContext`/`conversationKey` — адрес ответа control-api берёт из `ChannelSessionMessage.triggerInput`); wire-DTO сериализуются с `NON_NULL`. **Воркеру**: `triggerInput` для gRPC `append` возвращать из `payload.data`, а не из `inbound`.
