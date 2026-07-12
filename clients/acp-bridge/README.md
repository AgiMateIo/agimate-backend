# @agimate/acp — ACP bridge

Тонкий мост между IDE с поддержкой [ACP (Agent Client Protocol)](https://agentclientprotocol.com)
и агентом Agimate: JSON-RPC фреймы пробрасываются как есть между stdio (NDJSON) и
WebSocket-эндпоинтом control-api `/acp`. Логики протокола в мосте нет — она на сервере.

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
        "AGIMATE_API_KEY": "apik…",
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
AGIMATE_URL=ws://localhost:8180/control/acp AGIMATE_API_KEY=apik… node index.js
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}}
{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"/tmp","mcpServers":[]}}
{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"<из ответа>","prompt":[{"type":"text","text":"привет!"}]}}
```

Ответ агента придёт нотификациями `session/update` и финальным `{"result":{"stopReason":"end_turn"}}`.

## Поведение при разрыве

Мост не реконнектится: при закрытии WebSocket процесс завершается с кодом 1, IDE перезапускает
агента. Незавершённый `session/prompt` при этом теряется на клиенте, но ран доработает на
сервере — его ответ виден после `session/load` той же сессии.
