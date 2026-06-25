# Спецификация для agent-worker: connection_id + full_code в тулах, APP-тулы через GetConnectorTools

**Дата:** 2026-06-25
**Сервис:** control-api (gRPC `AgentContext`)
**Контекст:** переход коннекторов на единый реестр экземпляров `connections` (см.
`docs/connectors/architecture.md`).

## TL;DR

1. **`ConnectorToolSpec` получил два НОВЫХ поля** — `connection_id` (8) и `full_code` (9).
   Добавление, **без сдвига номеров** — обратносовместимо по wire. Перегенерировать стабы.
2. **`GetConnectorTools` теперь обслуживает APP-коннекторы** (раньше — ошибка): тулы устройства
   отдаются per-instance из каталога, как у MCP.
3. **Неймспейс тулов**: воркер строит LLM-имя тула как `{full_code}.{name}` для глобальной
   уникальности; на `ExecuteTool` возвращает исходные `(connector_code, identity, name)` —
   **маршрутизация по wire не меняется**.

---

## 1. `ConnectorToolSpec` — новые поля (additive)

Файл: `proto/agentworker/agent_context.proto`.

```protobuf
message ConnectorToolSpec {
  string name = 1;
  string title = 2;
  string description = 3;
  bytes  input_schema  = 4 [json_name = "inputSchema"];
  bytes  output_schema = 5 [json_name = "outputSchema"];
  ToolAnnotations annotations = 6;
  map<string, string> toolMeta = 7 [json_name = "_meta"];
  string connection_id = 8 [json_name = "connectionId"];  // НОВОЕ: экземпляр (= connections.id)
  string full_code     = 9 [json_name = "fullCode"];      // НОВОЕ: handle экземпляра (mcp_context7)
}
```

- `connection_id` — id экземпляра коннектора (= `identity`, которым зовут `ExecuteTool`). Пусто для
  статических singleton-коннекторов без экземпляра.
- `full_code` — стабильный клиентский handle экземпляра: `connector_code + "_" + sub_code`
  (`mcp_context7`, `telegram_<bot>`, `app_<device>`). Для статических singleton = `connector_code`.

Старые поля (1–7) не тронуты — обновление **не** breaking; достаточно перегенерации стабов.

## 2. Неймспейс имён тулов

Два разных MCP-сервера (или два устройства) могут отдавать тулы с одинаковым `name`
(`search`, `get`). Чтобы LLM их различала, воркер строит **отображаемое** имя:

```
llmToolName = full_code + "." + name      // mcp_context7.resolve-library-id
```

При вызове тула воркер шлёт на `ToolGateway.ExecuteTool` **исходные** значения (не namespaced):

```
connector_code = <как в запросе листинга>
identity       = connection_id            // из спека тула
tool_name      = name                     // сырое имя, без префикса
```

Маппинг `llmToolName → (connection_id, name)` воркер держит у себя по результату листинга.
Wire-формат `ExecuteToolRequest` не меняется.

## 3. APP-тулы через `GetConnectorTools`

Раньше `GetConnectorTools` для APP-коннекторов возвращал ошибку; теперь — список тулов экземпляра
из его дискаверенного каталога (так же, как MCP). Вызов единообразен:

```
GetConnectorTools(connector_code=<app-код>, identity=<connection_id>) -> [ConnectorToolSpec...]
```

`identity` обязателен для динамических коннекторов (mcp, app) — набор тулов per-instance; для
статических singleton (`time`, `board`) игнорируется и `full_code = connector_code`.

> `LOOPBACK` (claude-code) тулов не отдаёт — исполняется на стороне агента.

## 4. Семантика identity

`identity` = `connections.id` во всех путях (листинг, `ExecuteTool`, триггеры). Едино для всех типов
коннекторов; для интеграций совпадает с прежним id (сохранён при миграции), для приложений = id
устройства-экземпляра.

---

## Чек-лист

- [ ] Перегенерировать стабы из `agent_context.proto` (поля 8–9 — additive).
- [ ] Читать `full_code`/`connection_id` из `ConnectorToolSpec`; строить LLM-имя `{full_code}.{name}`.
- [ ] На `ExecuteTool` слать `(connector_code, identity=connection_id, name)` — без префикса.
- [ ] Листать APP-тулы через `GetConnectorTools` (раньше падало) — поведение как у MCP.

## Источники в коде

- `proto/agentworker/agent_context.proto`, `grpc/service/AgentContextGrpcService.java`
  (`getConnectorTools`, `appConnectionTools`, `resolveFullCode`)
- `connectors/integrations/mcp/McpToolMapper.java`, `database/entities/Connection.java`,
  `connectors/core/FullCodes.java`
