# Channels & Triggers — Agent Integration Guide

> Документ для разработчика воркера / агента. Описывает что изменилось в triggers-пайплайне после введения сущности **Channel** и какие новые возможности появились на gRPC.

---

## 1. Что появилось

Бэкенд научился отличать **диалоговые триггеры** (сообщение от пользователя в мессенджере) от обычных сервисных триггеров и группировать их в сессии. Управляется это через сущность `Channel`, которую конечный пользователь создаёт через `/manage/channels/` UI.

Для агента это значит две вещи:

1. **Payload триггера** иногда обогащается полями `_channel_id` и `_channel_session_id` — это сигнал, что триггер диалоговый и у агента есть «обратный канал» для ответа.
2. **Новый gRPC-сервис `ChannelGateway`** даёт агенту способ перечислить доступные каналы и отправить ответ пользователю одной RPC — backend сам разворачивает это в tool-call с подстановкой параметров.

Старые сервисные триггеры (`sensor.door.open`, `inventory.low_stock` и т.п.) продолжают работать без изменений.

---

## 2. Маршрутизация триггера — что меняется

Цепочка стандартная: connector присылает trigger → `TriggerRouterService` → `AgentTriggerPolicy` → доставка агенту. После изменений добавлены два шага:

1. **Filter check**. У `AgentTriggerPolicy` появилось поле `input_filter` (JSONB, пути типа `data.message.chat_id` → ожидаемое значение). Если у matched-policy фильтр задан и не сматчился по `trigger.data` — этой policy для этого триггера как будто нет. Это позволяет одному и тому же триггеру `telegram.trigger.message.new` маршрутизироваться к разным агентам в зависимости от `chat_id`.
2. **Channel inbound pipeline**. Если у выбранной ALLOW-policy есть `channel_id`, backend:
   - находит/создаёт активную `ChannelSession` (sliding window 12h);
   - извлекает текст по `channel.trigger_message_field` (dot-path в `trigger.data`);
   - сохраняет `ChannelSessionMessage(direction=IN, message=…)`;
   - вкладывает в payload агенту поля `_channel_id` и `_channel_session_id` (см. §3).

После этих шагов триггер доставляется агенту любым из трёх каналов (`CENTRIFUGO`, `WEBHOOK`, `GENERIC`/DBOS) — путь зависит от `Agent.type`, обогащение payload одинаковое во всех трёх.

---

## 3. Формат payload триггера

### 3.1 GENERIC (DBOS) — `AgentEvent`

```jsonc
{
  "eventId": "<triggerLog.pubId>:<agent.pubId>",
  "agentId": "018f...",
  "eventType": "telegram.trigger.message.new",     // connectorCode + "." + triggerName
  "occurredAt": "2026-05-11T12:00:00Z",
  "data": {
    // оригинальный trigger.data, как пришёл от коннектора
    "data": { "message": { "chat_id": 12345, "text": "Привет" } },

    // ⬇️ ДОБАВЛЯЮТСЯ ТОЛЬКО ЕСЛИ ТРИГГЕР ПРИШЁЛ ЧЕРЕЗ CHANNEL
    "_channel_id": "018f-...",          // pubId канала
    "_channel_session_id": "018f-..."   // pubId активной сессии
  }
}
```

### 3.2 CENTRIFUGO — `Trigger` (канал `agent:{agentPubId}`, type=`trigger`)

```jsonc
{
  "connectorCode": "telegram",
  "identity": "018f-...",                  // pubId инстанса (App / IntegrationCredentials)
  "id": "<triggerLog.externalId>",
  "name": "trigger.message.new",
  "data": {
    "data": { "message": { "chat_id": 12345, "text": "Привет" } },
    "_channel_id": "018f-...",
    "_channel_session_id": "018f-..."
  },
  "occurredAt": "2026-05-11T12:00:00"
}
```

### 3.3 WEBHOOK

```jsonc
{
  "type": "trigger",
  "payload": { /* тот же Trigger из §3.2 */ }
}
```

### 3.4 Правила использования полей `_channel_*`

- **Если оба поля присутствуют** — это диалоговый триггер. Агент должен:
  - сохранять `_channel_session_id` как идентификатор разговора в своём workflow state (используется для отправки ответа);
  - использовать `data.data.message...` (или что там кладёт connector) для извлечения текста сообщения. Backend уже сохранил его в session, но самому агенту payload приходит как есть.
- **Если полей нет** — это обычный сервисный триггер. Логика прежняя: реагировать как реагировал, отвечать как реагировал (`ToolGateway.ExecuteTool` напрямую).
- **Никогда не отправляйте этим триггерам ответ через `ToolGateway`** напрямую — у вас, скорее всего, нет права на нужный reply-tool. Используйте `ChannelGateway.SendChannelMessage` (см. §4).
- Поля `_channel_*` — **строки UUID**, не объекты.

---

## 4. Новый gRPC сервис `ChannelGateway`

Proto: `services/control-api/src/main/proto/agentworker/channel_gateway.proto`. Package — `ru.agimate.agentworker` (тот же, что и `ToolGateway`). Аутентификация — стандартный worker-pool Bearer-токен через `WorkerPoolAuthInterceptor` (см. agimate-worker-protocol-spec.md §1.3).

```proto
service ChannelGateway {
  rpc ListChannels(ListChannelsRequest) returns (ListChannelsResponse);
  rpc SendChannelMessage(SendChannelMessageRequest) returns (SendChannelMessageResponse);
}
```

### 4.1 `ListChannels`

```proto
message ListChannelsRequest {
  string agent_id = 1;     // pubId агента
}
message ChannelDescriptor {
  string channel_id = 1;             // pubId канала
  string name = 2;
  string reply_connector_code = 3;
  string reply_tool_name = 4;
}
message ListChannelsResponse {
  repeated ChannelDescriptor channels = 1;
}
```

Возвращает все каналы агента (soft-deleted исключены). Использовать когда:
- агент хочет **инициировать** диалог сам (не в ответ на триггер) — например, послать proactive-сообщение в Telegram-чат, привязанный к каналу;
- агент хочет узнать «есть ли у меня вообще обратный канал в Telegram прежде, чем я попытаюсь что-то ответить».

Если триггер уже пришёл с `_channel_id` — отдельный вызов `ListChannels` не нужен, `channel_id` есть в payload.

### 4.2 `SendChannelMessage`

```proto
message SendChannelMessageRequest {
  string agent_id = 1;
  string channel_id = 2;       // pubId канала из payload или ListChannels
  string session_id = 3;       // pubId сессии; пусто = найти активную или создать новую
  string text = 4;             // текст ответа
  string message_id = 5;     // для идемпотентности
}
message SendChannelMessageResponse {
  string session_id = 1;       // pubId сессии (особенно полезно, если session_id был пуст)
  string message_id = 2;      // pubId созданного ToolCallLog (для трейсинга)
}
```

Что делает backend под капотом, **за один вызов**:

1. Валидирует, что `channel_id` принадлежит `agent_id`.
2. Резолвит сессию: если `session_id` указан — берёт её; иначе `findOrCreateActive(channel)` со sliding-window 12h.
3. Извлекает `trigger_input` из последнего IN-сообщения этой сессии — нужен для подстановки `{trigger.*}` плейсхолдеров.
4. Рендерит `channel.reply_tool_params` (см. §5).
5. Сохраняет `ChannelSessionMessage(direction=OUT, message=text)` и обновляет `session.last_message_at`.
6. Создаёт `ToolCallLog` с `accessEffect=ALLOW` (канал — уже авторизованный путь, ABAC не оценивается).
7. Вызывает `ConnectorService.pushToConnector(...)` — для INTEGRATION выполняется сразу, для APP уходит в Centrifugo на устройство, и т.д.

### 4.3 Идемпотентность

`message_id` уникален в паре `(agent_pub_id, message_id)` в БД. Повторный вызов с тем же `message_id`:
- сохраняет новое OUT-сообщение в сессии (БД не препятствует, но это побочный эффект);
- **переиспользует существующий `ToolCallLog`** — повторного tool-call в connector не будет.

Это поведение защитит от ретраев на сетевых сбоях, **но не делает SendChannelMessage полностью idempotent** в отношении session messages. Если ваш workflow рестартится — генерируйте детерминированный `message_id` (например, hash от `(session_id, agent_step_id)`), чтобы хотя бы не дублировать tool-вызов.

### 4.4 Маппинг ошибок (gRPC Status)

| Status | Когда |
|---|---|
| `INVALID_ARGUMENT` | `agent_id` / `channel_id` пустой, не UUID; `session_id` указан, но не UUID |
| `NOT_FOUND` | Канал не существует, soft-deleted, или принадлежит другому агенту; сессия с указанным `session_id` не найдена либо принадлежит другому каналу |
| `PERMISSION_DENIED` | Зарезервировано (сейчас не выбрасывается — авторизация через владение каналом) |
| `ABORTED` | Конфликт `message_id` с другим input (общая логика ToolCallLog) |
| `INTERNAL` | Всё остальное |

---

## 5. Подстановка плейсхолдеров (что увидит connector)

`channel.reply_tool_params` — JSON-шаблон, который пользователь задал при создании канала. Backend проходит по нему рекурсивно (Map → List → скаляры) и подставляет:

| Плейсхолдер | Значение |
|---|---|
| `{text}` | значение `text` из `SendChannelMessageRequest` |
| `{trigger.<dot.path>}` | поле из `triggerInput` **последнего IN-сообщения сессии** (= оригинальный `trigger.data`) |

**Сохранение типа**: если строка целиком состоит из одного плейсхолдера (`"{trigger.data.message.chat_id}"`), результат имеет тип исходного значения (number, boolean, null, object). Если плейсхолдер вкраплён в строку (`"Re: {text}"`) — интерполяция как string.

Пример канала на Telegram:

```json
"reply_tool_params": {
  "chat_id": "{trigger.data.message.chat_id}",
  "text": "{text}",
  "parse_mode": "HTML"
}
```

При IN-сообщении `{"data":{"message":{"chat_id":12345,"text":"Привет"}}}` и вызове `SendChannelMessage(text="Здорово!")` backend выполнит `telegram.sendMessage({"chat_id": 12345, "text": "Здорово!", "parse_mode": "HTML"})`.

Агенту **не нужно знать формат reply-tool'а** — это полностью инкапсулировано каналом.

---

## 6. Как применить на стороне агента

### 6.1 ReAct-loop psevdo-code

```python
def on_trigger(event: AgentEvent) -> None:
    data = event.data
    channel_session_id = data.get("_channel_session_id")
    channel_id = data.get("_channel_id")

    if channel_session_id:
        # Диалоговый триггер: пользователь нам пишет.
        user_text = pluck(data, "data.message.text")   # путь зависит от connector
        save_to_workflow_state(channel_id=channel_id, session_id=channel_session_id)

        reply = run_react_loop(prompt=user_text, conversation_history=...)

        channel_gateway.SendChannelMessage(
            agent_id=event.agentId,
            channel_id=channel_id,
            session_id=channel_session_id,
            text=reply,
            message_id=stable_id_for(workflow_id, step="final_reply"),
        )
    else:
        # Сервисный триггер: реагируем как раньше — напрямую через ToolGateway.
        run_react_loop_with_tool_call(event)
```

### 6.2 Что хранить в DBOS workflow state

Минимум на каждый диалоговый workflow:
- `channel_id`, `channel_session_id` — чтобы ответить позже (например, после долгого tool-call'а);
- история сообщений ReAct-loop — **не передаётся backend'ом**, см. agimate-worker-protocol-spec.md §6 (open issue). На PoC — в DBOS state.

`SendChannelMessage` можно вызывать **многократно** в рамках одной сессии (split на несколько коротких ответов / стриминг). Каждый вызов добавит OUT-сообщение в сессию и пошлёт tool-call.

### 6.3 Inactive session

Backend не присылает уведомление о закрытии сессии (12h TTL или явное `closedAt`). Если агент решит отправить ответ позже, чем через 12h после последнего IN — `SendChannelMessage(session_id=<old>)` вернёт `NOT_FOUND` (сессия закрыта или истекла). Тогда:
- либо вызвать с **пустым `session_id`** — backend создаст новую сессию;
- либо принять, что разговор закончен, и завершить workflow.

Рекомендация: при срабатывании long-running tool в диалоговом workflow проверять, что от первого IN прошло < 12h, и при необходимости попросить пользователя «напомнить позже».

### 6.4 Initiating dialog (proactive)

Чтобы агент сам начал разговор без предыдущего триггера:

1. `ListChannels(agent_id)` — найти подходящий канал.
2. `SendChannelMessage(channel_id, session_id="", text=...)` — backend создаст новую сессию.

⚠️ В этой ситуации `{trigger.*}` плейсхолдеры **резолвятся в null**, поскольку IN-сообщений в сессии ещё нет. Если `reply_tool_params` зависит от `{trigger.data.message.chat_id}` — отправка упадёт уже в connector с ошибкой (например, Telegram вернёт 400 «chat_id is required»). На стороне канала это известно как ограничение; будущая версия (когда появится) даст способ передать chat_id в `SendChannelMessage` явно.

---

## 7. Конфигурация на стороне UI — что увидит пользователь

Просто чтобы понимать контекст. Пользователь в UI создаёт канал, заполняя:

- какому агенту канал принадлежит,
- `channelHandler` (например `generic`), `connectorCode` + `identity` источника,
- `config` обработчика — для `generic`: список `triggers`, `messageField` (dot-path до текста), reply-цель (`replyConnectorCode`/`replyIdentity`/`replyToolName`) и `replyToolParams` (шаблон),
- опциональный `inputFilter` (фильтр по полям `trigger.data`).

Подробнее: [`control-api-manage-channels.md`](control-api-manage-channels.md).

С точки зрения агента эти настройки прозрачны — он видит результат через payload триггера и через поведение `SendChannelMessage`.

---

## 8. Чек-лист миграции существующего агента

1. **Триггер-handler** — добавить ветку «если есть `_channel_session_id` — это диалог»:
   - извлекать текст сообщения из `data` по тому пути, который оговорён с пользователем (обычно `data.message.text` для Telegram);
   - запоминать `channel_id` и `session_id` в state workflow.
2. **Ответы пользователю** — заменить попытки `ToolGateway.ExecuteTool(telegram.sendMessage, ...)` на `ChannelGateway.SendChannelMessage(channel_id, session_id, text)`. Удалить из кода жёстко-зашитые `chat_id` — backend подставит сам через `{trigger.*}`.
3. **gRPC stubs** — перегенерировать из новых proto (`channel_gateway.proto`). Идентичные настройки auth interceptor'а, что и для `ToolGateway`.
4. **State retention** — убедиться, что `channel_id` и `session_id` сериализуются в DBOS workflow state.
5. **Идемпотентность** — генерировать стабильный `message_id` для финального ответа (например, `f"{workflow_id}:reply:{step_n}"`).
6. **Тесты** — добавить fixture для триггера с полями `_channel_id` / `_channel_session_id` и проверить, что workflow вызывает `SendChannelMessage`, а не tool'у пишет напрямую.

---

## 9. Что осталось за рамками этой итерации

- **Streaming reply**. Сейчас `SendChannelMessage` — единичный unary call. Если LLM генерирует длинный ответ кусками, агент может звать `SendChannelMessage` несколько раз — каждый кусок попадёт OUT в сессию и в connector отдельным сообщением. Серверного агрегатора пока нет.
- **Proactive с динамическим `chat_id`**. См. §6.4 — на текущей версии proactive-сценарий работает только если `replyToolParams` не зависит от данных IN-сообщения.
- **Уведомления о новом IN в той же сессии (multi-turn)**. Backend просто шлёт следующий триггер с тем же `_channel_session_id` — workflow должен сам понимать, что это продолжение разговора (либо завести нового instance, либо использовать DBOS message-передачу в существующий instance).
- **Канал-инициатор ChannelGateway-вызовов**. Сейчас ничто на стороне agent runtime не блокирует «не тот» `agent_id` в RPC: проверяется только что `channel_id` принадлежит указанному `agent_id`. Если воркер исполняет нескольких агентов в одном worker pool, ответственность за «звать с правильным agent_id» — на воркере.
