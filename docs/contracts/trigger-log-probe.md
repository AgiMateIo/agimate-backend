# Probe-код обнаружения каналов

Stateless-механизм полуавтоматического создания канала / диагностики доставки триггеров. UI запрашивает у бэка случайный probe-код, пользователь отправляет сообщение, содержащее этот код, через любую свою интеграцию (Telegram-бот, Slack, app endpoint). Бэк находит соответствующую запись в `trigger_logs` и возвращает её UI — на основе TriggerLog UI заполняет форму канала или показывает диагностику доставки.

Бэк **не** хранит probe-состояние: код самодостаточен (префикс кодирует режим, хвост — энтропию), match — это поиск подстроки в `trigger_logs.input::text`.

> Все пути ниже — относительно context path `/control`.

## Authentication

| Группа | Механизм | Заголовок |
|---|---|---|
| `/manage/trigger-logs/probe**` | **JWT** | `Authorization: Bearer <jwt>` |

## Формат probe-кода

`^agm-probe-(block|pass)-[a-z0-9]{10}$` — константы `TriggerLogProbeService.BLOCK_PREFIX`/`PASS_PREFIX`.

Это соглашение трёх сторон: код выдаёт бэкенд, показывает фронт, а пользователь вставляет его в
сообщение в своей интеграции — то есть по пути код едет внутри чужого мессенджера. Поменять
префикс на одной стороне значит сломать обнаружение молча.

- `block` (по умолчанию): триггеры с этим кодом сохраняются в `trigger_logs`, но **не** доставляются агентам (`findAllowedAgents`/`sendTrigger` пропускается в `TriggerRouterService`). Чисто настройка без побочных эффектов.
- `pass`: триггеры идут обычным путём — агенты получают, `agent_runs` заполняются. Полезно для «проверь, доходит ли мой триггер до агентов».

Распознаёт `TriggerLogProbeService.isBlockProbe(trigger.data())`, который `TriggerRouterService`
вызывает до подбора получателей.

## Эндпойнты

`POST /manage/trigger-logs/probe` и `GET /manage/trigger-logs/probe/match` — схемы в Swagger,
`/control/docs/ui` (профиль `develop`).

## Поток (Channel discovery)

1. UI: `POST /manage/trigger-logs/probe` `{ "blockDelivery": true }` → получает `code`, `issuedAt`. Показывает пользователю «отправь это сообщение в свою интеграцию».
2. Пользователь отправляет в Telegram-бот (или Slack, или через app endpoint) сообщение, содержащее `code`.
3. UI поллит `GET /manage/trigger-logs/probe/match?code=...&since=<issuedAt>` каждые 1–2 сек.
4. При матче UI получает полный `TriggerLog` и сам предзаполняет форму `POST /manage/channels/`:
   - `connectorCode = triggerLog.connectorCode`, `connectionId = triggerLog.connectionId`
   - `channelHandler = "generic"` (по умолчанию), `config.triggers = [triggerLog.name]`
   - reply-сторону в `config` (`replyConnectionId`/`replyToolName`) по умолчанию можно подставить из trigger-стороны (UI решает).
   - `input` показывается пользователю как образец payload — он может выбрать поля для `input_filter`.

## Диагностика доставки

`blockDelivery: false` пропускает probe-триггер через обычный роутинг. В ответе `triggerLog.agentsCount` показывает, сколько агентов получили этот триггер — это диагностика политик («должен дойти до 1 агента, а дошло до 3 — где-то лишний ALLOW»).
