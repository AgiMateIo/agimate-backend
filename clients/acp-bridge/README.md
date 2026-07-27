# @agimate/acp — ACP bridge

Мост между IDE с поддержкой [ACP (Agent Client Protocol)](https://agentclientprotocol.com)
и агентом Agimate: JSON-RPC фреймы пробрасываются между stdio (NDJSON) и WebSocket-эндпоинтом
control-api `/control/acp`.

Помимо транспорта мост работает **MCP-хостом**: MCP-серверы, которые IDE передаёт в `session/new`
(`mcpServers`), поднимаются здесь локально (`@modelcontextprotocol/sdk`), их тулы дискаверятся и
прокидываются серверу (`_agimateMcp`), а вызовы приходят обратным `mcp/call_tool` и проксируются в
нужный локальный сервер. Так удалённый агент получает доступ к локальным (stdio) MCP-серверам
пользователя, до которых сам дотянуться не может. Тулы доступны агенту, пока открыт этот IDE-чат.

Конфиги MCP-серверов (`mcpServers`: command/env с токенами) — секреты этой машины: мост **вырезает**
их из форвардимого фрейма, на бэкенд уходит только список тулов.

## Настройка

Нужен агентский API-ключ (`X-Api-Key`): один ключ = один агент, от его имени идёт диалог.

Переменные окружения:

| Переменная | Обязательна | Описание |
|---|---|---|
| `AGIMATE_API_KEY` | да | агентский API-ключ |
| `AGIMATE_URL` | нет | WebSocket URL, по умолчанию `wss://api.agimate.io/control/acp` |

control-api живёт за context-path `/control`, поэтому путь эндпоинта — **`/control/acp`**
(локально: `ws://localhost:8180/control/acp` для профиля `local`, `ws://localhost:8080/control/acp` без него).

### Zed

`settings.json` → `agent_servers`:

```json
{
  "agent_servers": {
    "agimate": {
      "type": "custom",
      "command": "node",
      "args": ["/path/to/agimate-backend/clients/acp-bridge/index.js"],
      "env": {
        "AGIMATE_API_KEY": "agnt…",
        "AGIMATE_URL": "wss://api.agimate.io/control/acp"
      }
    }
  }
}
```

После публикации пакета в npm: `"command": "npx", "args": ["@agimate/acp"]`.

## Локальная проверка без IDE

```bash
cd clients/acp-bridge && npm install
AGIMATE_URL=ws://localhost:8180/control/acp AGIMATE_API_KEY=agnt… node index.js
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"/tmp","mcpServers":[]}}
{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"<из ответа>","prompt":[{"type":"text","text":"привет!"}]}}
```

Ответ агента придёт нотификациями `session/update` и финальным `{"result":{"stopReason":"end_turn"}}`.

## Поведение при разрыве

Мост реконнектится сам (backoff до ~3 минут) — рестарт control-api не роняет IDE-чат. После
реконнекта мост реплеит `initialize` (ответ глотает) и восстанавливает сессии на сервере
нотификацией `_agimate/restore` (привязки + MCP-тулы); локальные MCP-серверы живут непрерывно.
`401/403` на handshake — выход сразу (код 2): ключ от ретраев валидным не станет. Исчерпаны
ретраи — выход с кодом 1, IDE перезапустит агента.

Prompt, висевший в момент рестарта бэкенда, на клиенте не восстанавливается — ран доработает,
ответ ляжет в историю сессии (видно после `session/load` или в следующем сообщении).
