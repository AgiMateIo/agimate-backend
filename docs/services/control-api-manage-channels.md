# control-api — Channel Endpoints

API specification for `/manage/channels/**` — управление **каналами**: декларативная связка между диалоговым триггером (сообщение пользователя в Telegram-чат, веб-форме и т.п.) и обратным каналом доставки ответа агента (tool-call на коннекторе с шаблоном параметров).

Канал отличает «сообщение от пользователя» от обычного сервисного триггера. Когда триггер с привязанным каналом срабатывает, backend:
1. находит активную сессию (или создаёт новую — sliding window 12 ч),
2. сохраняет входящее сообщение (`direction=IN`) с полным `trigger.data`,
3. передаёт агенту payload триггера, дополненный полями `_channel_id` и `_channel_session_id`.

Когда worker / агент хочет ответить, он использует gRPC-методы `ChannelGateway.ListChannels` / `ChannelGateway.SendChannelMessage` (см. `agimate-worker-protocol-spec.md`). UI на этот путь не влияет — он только описывает каналы и просматривает историю сессий.

> Все пути ниже — относительно context path `/control`.

## Authentication

| Группа | Механизм | Заголовок |
|---|---|---|
| `/manage/channels/**` | **JWT** | `Authorization: Bearer <jwt>` |

**Стандартные ошибки:**

| Status | Body | Когда |
|---|---|---|
| 400 | `{ "error": { "message": "Trigger 'X' not available on connector 'Y'" } }` | Несуществующий триггер / tool / message_field / blank поле |
| 401 | `{ "error": { "message": "Authentication credentials not found or invalid" } }` | Невалидный JWT |
| 403 | `{ "error": { "message": "Access denied" } }` | Канал принадлежит другому пользователю |
| 404 | `{ "error": { "message": "Channel not found" } }` | Канал/сессия не существует или soft-deleted |
| 409 | `{ "error": { "message": "Channel for this agent and trigger already exists: <pubId>" } }` | Дубль `(user, agent, connector, identity, triggerName)` |
| 409 | `{ "error": { "message": "Conflicting agent trigger policy already linked to another channel" } }` | Для триггера уже существует ALLOW-policy, привязанная к другому каналу |

---

## Модель данных (для UI)

### `Channel`

Триггер + reply-конфиг. Один канал = один агент = один триггер у одного connector+identity.

| Поле | Тип | Описание |
|---|---|---|
| `pubId` | UUID | Public ID канала |
| `agentPubId` | UUID | Какому агенту принадлежит |
| `name` | string | Отображаемое имя (произвольное) |
| **triggerConfig** | | Откуда канал слушает входящие |
| `triggerConnectorCode` | string | Код connector'а (например, `telegram`) |
| `triggerIdentity` | string | `pubId` instance'а — `App.pubId` (для APP) или `IntegrationCredentials.pubId` (для INTEGRATION) |
| `triggerIdentityName` | string? | Денормализованное человекочитаемое имя identity: `App.name` (для APP) или `IntegrationCredentials.name`/`platformIdentifier` (для INTEGRATION). `null`, если identity удалён или невалидный UUID. UI может использовать без доп. запросов. |
| `triggerName` | string | Имя триггера на коннекторе (должно быть в `App.triggers` или `ConnectorHandler.getTriggers()`) |
| `triggerMessageField` | string | Dot-path внутри `trigger.data`, по которому достаётся **текст сообщения пользователя**. Например `data.message.text` для Telegram |
| **replayConfig** | | Куда канал отправляет исходящие |
| `replyConnectorCode` | string | Код connector'а для ответа (обычно совпадает с `triggerConnectorCode`, но не обязательно) |
| `replyIdentity` | string | Identity reply-коннектора |
| `replyIdentityName` | string? | Денормализованное имя reply-identity (так же, как `triggerIdentityName`). `null`, если удалён. |
| `replyToolName` | string | Имя tool'а, который шлёт ответ (`sendMessage`, `mail.send`, …) |
| `replyToolParams` | object | JSON-шаблон параметров tool'а с **плейсхолдерами** — см. ниже |
| `inputFilter` | object? | Опциональный фильтр по `trigger.data` (см. ниже). Хранится на связанной policy, но возвращается здесь для удобства UI. `null`, если не задан. |
| `createdAt` | datetime | |
| `updatedAt` | datetime | |

#### Плейсхолдеры в `replyToolParams`

Backend перед вызовом tool'а проходит по `replyToolParams` рекурсивно (Map, List, скаляры) и подставляет:

| Плейсхолдер | Значение |
|---|---|
| `{text}` | Текст ответа агента (приходит в gRPC `SendChannelMessage.text`) |
| `{trigger.<dot.path>}` | Поле из `triggerInput` **последнего IN-сообщения текущей сессии**. Например `{trigger.data.message.chat_id}` достанет id чата из исходного триггера |

Правила рендеринга:
- Если строка целиком состоит из одного плейсхолдера (`"{trigger.data.message.chat_id}"`), результат имеет **исходный JSON-тип** (number, boolean, object). Так что числовой `chat_id` дойдёт как number, а не как строка.
- Если строка содержит плейсхолдер вместе с другим текстом (`"Re: {text}"`), результат интерполируется как строка.
- Нерешённый `{trigger.*}` (поля нет) превращается в пустую строку при интерполяции; даёт `null` при «чистом» плейсхолдере.

Пример для Telegram-канала:

```json
{
  "chat_id": "{trigger.data.message.chat_id}",
  "text": "{text}",
  "parse_mode": "HTML"
}
```

При триггере `{"data":{"message":{"chat_id":12345,"text":"Hi"}}}` и ответе агента `"Hello!"` backend вызовет `telegram.sendMessage({"chat_id": 12345, "text": "Hello!", "parse_mode": "HTML"})`.

#### Опциональный `inputFilter`

Хранится **не** в самом канале, а на ассоциированной `AgentTriggerPolicy`, но **денормализуется в `ChannelResponse.inputFilter`** — UI не нужно отдельно ходить в `/manage/agent-trigger-policies/`. На вход принимается через Create/Update тело.

Структура: плоский объект `{ "<dot.path>": <expected scalar> }`. Сцепляется по AND. Пример — отсекать триггеры от конкретного чата:

```json
{ "data.message.chat_id": 12345 }
```

Сравнение скаляров: строки/булевы — строгое равенство; числа — нумерически (Long vs Integer не проблема); сложные структуры (object/array) — через `Object.equals` / `toString` fallback.

---

### `ChannelSession`

12-часовое окно общения. Создаётся автоматически при первом IN-сообщении, продлевается при каждом IN или OUT (`lastMessageAt` обновляется). Можно закрыть явно через POST `/sessions/{id}/close` — тогда следующий триггер откроет новую сессию.

| Поле | Тип | Описание |
|---|---|---|
| `pubId` | UUID | |
| `title` | string? | Автогенерируется из первых 80 символов первого IN-сообщения |
| `lastMessageAt` | datetime | Обновляется при каждом IN/OUT |
| `closedAt` | datetime? | `null` — активна; `not null` — закрыта вручную или системой |
| `createdAt` | datetime | |

Условие «сессия активна»: `closedAt IS NULL AND lastMessageAt > NOW() - 12h`.

---

### `ChannelSessionMessage`

Одно сообщение в сессии — либо входящее (IN, от пользователя через триггер), либо исходящее (OUT, ответ агента).

| Поле | Тип | Описание |
|---|---|---|
| `pubId` | UUID | |
| `direction` | enum | `IN` или `OUT` |
| `message` | string | Текст. Для IN — извлечён по `triggerMessageField` |
| `createdAt` | datetime | |

> Поле `triggerInput` (полный JSON исходного триггера) хранится в БД для IN-сообщений и используется бэкендом при рендере плейсхолдеров `{trigger.*}` — в API ответах **не выдаётся**.

---

## Endpoints

### GET `/control/manage/channels/`

Список всех каналов пользователя (без пагинации, soft-deleted исключены).

**Query parameters:**

| Имя | Тип | Обязательно | Описание |
|---|---|---|---|
| `agentPubId` | UUID | нет | Когда указан, возвращает только каналы этого агента (среди принадлежащих текущему пользователю) |

**Response 200:**
```json
{
  "response": [
    {
      "pubId": "018f...",
      "agentPubId": "018f...",
      "name": "Telegram support bot",
      "triggerConnectorCode": "telegram",
      "triggerIdentity": "018f...",
      "triggerIdentityName": "My Telegram Bot",
      "triggerName": "trigger.message.new",
      "triggerMessageField": "data.message.text",
      "replyConnectorCode": "telegram",
      "replyIdentity": "018f...",
      "replyIdentityName": "My Telegram Bot",
      "replyToolName": "sendMessage",
      "replyToolParams": {
        "chat_id": "{trigger.data.message.chat_id}",
        "text": "{text}"
      },
      "inputFilter": { "data.message.chat_id": 12345 },
      "createdAt": "2026-05-11T12:00:00",
      "updatedAt": "2026-05-11T12:00:00"
    }
  ]
}
```

Отсортировано по `createdAt` DESC.

---

### GET `/control/manage/channels/{pubId}`

Один канал по pubId.

---

### POST `/control/manage/channels/`

Создаёт канал и одновременно `AgentTriggerPolicy(effect=ALLOW, channel_id=<new>, input_filter=<optional>)`. Если для тех же `(agent, connector, identity, triggerName)` уже была ALLOW-policy **без** channel_id, она апгрейдится — присваивается `channel_id` и `input_filter`.

**Request body:**
```json
{
  "agentPubId": "018f...",
  "name": "Telegram support bot",

  "triggerConnectorCode": "telegram",
  "triggerIdentity": "018f...",
  "triggerName": "trigger.message.new",
  "triggerMessageField": "data.message.text",

  "replyConnectorCode": "telegram",
  "replyIdentity": "018f...",
  "replyToolName": "sendMessage",
  "replyToolParams": {
    "chat_id": "{trigger.data.message.chat_id}",
    "text": "{text}"
  },

  "inputFilter": { "data.message.chat_id": 12345 }
}
```

Все поля кроме `inputFilter` — обязательны (`@NotBlank` / `@NotNull`). `inputFilter` — null, если фильтрация по payload не нужна.

**Валидация бэкенда:**

1. Агент существует, принадлежит текущему пользователю → иначе `404` / `403`.
2. Reply-Trigger connector существует → иначе `404`.
3. `triggerIdentity` парсится как UUID и существует:
   - APP: `App.findByPubIdAndUserIdNotDeleted` + `isActive()`
   - INTEGRATION: `IntegrationCredentials.findByPubIdAndUserIdNotDeleted` + `connectorCode` совпадает + `isActive()`
   - INTERNAL_SERVICE / LOOPBACK: `400` (триггеры/инструменты не поддерживаются)
4. `triggerName` присутствует в:
   - `App.triggers` (для APP), либо
   - `ConnectorHandler.getTriggers()` (для INTEGRATION).
5. `replyToolName` присутствует в `App.tools` или `ConnectorHandler.getTools()`.
6. `triggerMessageField` не blank.
7. Нет существующего активного канала с тем же `(user, agent, connector, identity, triggerName)` → иначе `409`.
8. Нет конфликтующей ALLOW-policy с другим `channel_id` → иначе `409`.

**Response 200:** `ChannelResponse`.

---

### PATCH `/control/manage/channels/{pubId}`

Частичное обновление. Все поля опциональны.

**Request body:**
```json
{
  "name": "...",
  "triggerMessageField": "...",
  "replyToolParams": { ... },
  "inputFilter": { ... },
  "clearInputFilter": false
}
```

- `name`, `triggerMessageField`, `replyToolParams` — обычное частичное обновление (`null` = не трогать).
- `inputFilter` — обновляет фильтр на ассоциированной policy.
- `clearInputFilter: true` (вместе с `inputFilter: null`) — снимает фильтр на policy. Без этого флага `inputFilter: null` означает «не трогать».

**Что НЕ меняется через PATCH:**
- `agentPubId`, `triggerConnectorCode`, `triggerIdentity`, `triggerName`, `replyConnectorCode`, `replyIdentity`, `replyToolName` — это «ключевые» поля. Если нужно изменить — удалить канал и создать новый.

**Response 200:** `ChannelResponse`.

---

### DELETE `/control/manage/channels/{pubId}`

Soft delete (`deletedAt = NOW()`). Канал перестаёт получать триггеры (FK `agent_trigger_policies.channel_id` остаётся, но в SQL-фильтре `deletedAt IS NULL` канал исключается). Связанная policy не удаляется автоматически — если нужно, удаляйте отдельно через `/manage/agent-trigger-policies/`.

Сессии и сообщения остаются — их можно прочитать (история не теряется), но новые входящие в этот канал больше не приходят.

**Response 200:** `{ "response": null }`

---

## Sessions

### GET `/control/manage/channels/{pubId}/sessions/`

Все сессии канала, отсортированы по `lastMessageAt` DESC (активные сверху).

**Response 200:**
```json
{
  "response": [
    {
      "pubId": "018f...",
      "title": "Привет, как дела?",
      "lastMessageAt": "2026-05-11T14:30:00",
      "closedAt": null,
      "createdAt": "2026-05-11T12:00:00"
    }
  ]
}
```

---

### GET `/control/manage/channels/sessions/{sessionPubId}/messages/`

Сообщения сессии в хронологическом порядке (старые сверху).

**Response 200:**
```json
{
  "response": [
    {
      "pubId": "018f...",
      "direction": "IN",
      "message": "Привет, как дела?",
      "createdAt": "2026-05-11T12:00:00"
    },
    {
      "pubId": "018f...",
      "direction": "OUT",
      "message": "Привет! Всё хорошо, чем могу помочь?",
      "createdAt": "2026-05-11T12:00:05"
    }
  ]
}
```

Доступ к сессии проверяется по владельцу канала.

---

### POST `/control/manage/channels/sessions/{sessionPubId}/close`

Закрывает сессию (выставляет `closedAt = NOW()`). Идемпотентно — повторный вызов на уже закрытой сессии возвращает то же состояние, ничего не меняя.

**Response 200:** `ChannelSessionResponse`.

---

## UX-сценарии для UI

### 1. Просмотр всех каналов агента

На странице агента — секция «Каналы»: `GET /manage/channels/?agentPubId=<current>` (фильтр на бэкенде). Карточка показывает:
- название канала,
- кто слушает (`<triggerConnectorCode> · <triggerName>`),
- идентити: `<triggerIdentityName>` (приходит денормализованно, доп. запрос не нужен; fallback на `triggerIdentity` если пришёл `null`),
- сколько активных сессий (`GET /sessions/` и фильтр `closedAt === null && lastMessageAt > now-12h`).

### 2. Мастер создания канала

Шаги:
1. **Выбор агента** — из списка агентов пользователя.
2. **Источник триггера**:
   - Connector (выпадающий список из `/manage/connectors/` фильтр `type in (APP, INTEGRATION)`).
   - Identity (APP / IntegrationCredentials под выбранным коннектором).
   - TriggerName — выпадающий из `App.triggers` или `IntegrationMeta.triggers` (можно подгружать из `/manage/connectors/{code}` для INTEGRATION или из карточки App для APP).
   - MessageField — текстовое поле с подсказкой по дефолтам:
     - Telegram → `data.message.text`
     - Web webhook → `data.text` или подобное (зависит от формы триггера).
3. **Reply tool**:
   - Connector + Identity (по умолчанию совпадают с триггером, можно перекрыть).
   - ToolName — из `App.tools` / `IntegrationMeta.tools`.
   - ParamsTemplate — JSON-редактор. UI должен подсказывать доступные плейсхолдеры:
     - `{text}` — всегда есть.
     - `{trigger.*}` — UI может предложить «извлечь поля из примера триггера»: пользователь вставляет пример JSON триггера, UI разворачивает все dot-path-ключи как кликабельные варианты.
4. **Optional фильтр** — две колонки key/value (всё AND).
5. **Submit** → POST. На 409/400 — показать ошибку рядом с конфликтным полем.

### 3. Просмотр диалогов

На странице канала — таблица сессий (LEFT pane) + history-pane (RIGHT). По клику на сессию — `GET /sessions/{id}/messages/`. Сообщения отрисовать как чат: IN слева, OUT справа.

Управление:
- «Закрыть сессию» — `POST /sessions/{id}/close`. UI после успеха показывает плашку «Закрыта 11.05 14:30; новый IN откроет новую сессию».

### 4. Тестирование канала из UI (опционально)

Можно дать кнопку «Тест ответа»: вызвать `SendChannelMessage` через прокси-эндпоинт (если такой будет — сейчас он только в gRPC). На момент этой спеки UI ограничивается просмотром.

---

## Связь с другими сущностями

- **AgentTriggerPolicy** (`/manage/agent-trigger-policies/`): у policy появилось поле `channelId` и `inputFilter`. Если `channelId != null`, policy управляется через `/manage/channels/` — UI не должен позволять её редактировать напрямую (или должен предупредить).
- **TriggerLog** (`/manage/trigger-logs/`): не меняется. Триггер в логе всё тот же, что и пришёл; обогащённый payload передаётся агенту, но в `trigger_input` лога — оригинал.
- **ToolUseLog** (`/manage/tool-use-logs/`): отправка ответа через канал создаёт обычный `ToolUseLog` с `accessEffect=ALLOW`. Его `agentSessionId` пустой; идентификатор `toolUseId` — это `tool_call_id`, который worker передал в `SendChannelMessage` (или сгенерированный UUID, если worker не указал).

---

## Notes

- Канал — на агента; один агент может иметь N каналов с разными триггерами / identity'ями.
- Один триггер `(connector, identity, triggerName)` для одного агента — **один** канал. Если нужно отвечать в разные чаты Telegram-бота, используйте `inputFilter` — но тогда нужны разные агенты или каскад с DENY-policies, чтобы один и тот же триггер не маршрутизировался дважды.
- Soft delete канала не удаляет историю — сессии и сообщения остаются в БД и доступны по `GET`. При желании можно их периодически архивировать.
- Сессии без явного закрытия не «истекают» — поле `closedAt` остаётся null. «Активность» определяется только условием `lastMessageAt > now-12h` в логике find-or-create.
