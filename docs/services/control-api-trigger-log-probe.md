# control-api — Trigger Log Probe (discovery)

Stateless-механизм полуавтоматического создания канала / диагностики доставки триггеров. UI запрашивает у бэка случайный probe-код, пользователь отправляет сообщение, содержащее этот код, через любую свою интеграцию (Telegram-бот, Slack, app endpoint). Бэк находит соответствующую запись в `trigger_logs` и возвращает её UI — на основе TriggerLog UI заполняет форму канала или показывает диагностику доставки.

Бэк **не** хранит probe-состояние: код самодостаточен (префикс кодирует режим, хвост — энтропию), match — это поиск подстроки в `trigger_logs.input::text`.

> Все пути ниже — относительно context path `/control`.

## Authentication

| Группа | Механизм | Заголовок |
|---|---|---|
| `/manage/trigger-logs/probe**` | **JWT** | `Authorization: Bearer <jwt>` |

## Формат probe-кода

`agm-probe-<mode>-<10 alnum chars>`, где `<mode>` — `block` или `pass`.

- `block` (по умолчанию): триггеры с этим кодом сохраняются в `trigger_logs`, но **не** доставляются агентам (`findAllowedAgents`/`sendTrigger` пропускается в `TriggerRouterService`). Чисто настройка без побочных эффектов.
- `pass`: триггеры идут обычным путём — агенты получают, `trigger_log_agents` заполняются. Полезно для «проверь, доходит ли мой триггер до агентов».

Распознавание происходит в `TriggerRouterService` через `JsonUtils.toJson(trigger.data()).contains("agm-probe-block-")`.

## Endpoints

### `POST /manage/trigger-logs/probe`

Выдаёт probe-код.

Request:
```json
{ "blockDelivery": true }
```
`blockDelivery` — опционально, default `true`.

Response 200:
```json
{
  "response": {
    "code": "agm-probe-block-7f3kx9q2ab",
    "issuedAt": "2026-05-12T14:33:01"
  }
}
```

UI должен запомнить `code` и `issuedAt`, и передавать `issuedAt` как `since` в `match`.

### `GET /manage/trigger-logs/probe/match?code=...&since=...`

Ищет первый `trigger_log` текущего пользователя, созданный начиная с `since`, у которого `input` содержит `code` (подстрочное совпадение).

Параметры:
- `code` — обязателен, должен соответствовать regex `^agm-probe-(block|pass)-[a-z0-9]{10}$`, иначе 400.
- `since` — обязателен, ISO-8601 datetime.

Response 200 (найдено):
```json
{
  "response": {
    "id": "<UUID>",
    "connectorCode": "telegram",
    "connectionId": "<UUID>",
    "externalId": "...",
    "name": "trigger.message.new",
    "occurredAt": "2026-05-12T14:33:10",
    "input": { ... raw JSONB ... },
    "createdAt": "2026-05-12T14:33:11",
    "agentsCount": 0
  }
}
```

Response 404 (ещё не сматчилось — UI продолжает поллить):
```json
{ "error": { "message": "No matching trigger log yet" } }
```

## Поток (Channel discovery)

1. UI: `POST /manage/trigger-logs/probe` `{ "blockDelivery": true }` → получает `code`, `issuedAt`. Показывает пользователю «отправь это сообщение в свою интеграцию».
2. Пользователь отправляет в Telegram-бот (или Slack, или через app endpoint) сообщение, содержащее `code`.
3. UI поллит `GET /manage/trigger-logs/probe/match?code=...&since=<issuedAt>` каждые 1–2 сек.
4. При матче UI получает полный `TriggerLog` и сам предзаполняет форму `POST /manage/channels/`:
   - `connectorCode = triggerLog.connectorCode`, `connectionId = triggerLog.connectionId`
   - `channelHandler = "generic"` (по умолчанию), `config.triggers = [triggerLog.name]`
   - reply-сторону в `config` (`replyConnectionId`/`replyToolName`) по умолчанию можно подставить из trigger-стороны (UI решает).
   - `input` показывается пользователю как образец payload — он может выбрать поля для `input_filter`.

## Поток (Delivery diagnostic, future)

`blockDelivery: false` пропускает probe-триггер через обычный роутинг. В ответе `triggerLog.agentsCount` показывает, сколько агентов получили этот триггер — это можно использовать как диагностику политик («должен дойти до 1 агента, а дошло до 3 — где-то лишний ALLOW»).
