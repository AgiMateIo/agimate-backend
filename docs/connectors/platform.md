# Platform connector

Внутренний коннектор `platform` — инструменты для **мета-агента**, который управляет самой платформой AgiMate: создаёт и настраивает агентов, пишет навыки (SKILL.md), привязывает навыки и подключает интеграции. Java-реализация в `controlapi/connectors/internal/platform/`.

## Модель

- **Тип:** internal (не integration). Привязывается к агенту **через навык** `platform-admin` (`connectors: [platform]`), а не через connection. Навык сидится в БД `SystemSkillBootstrap` (`resources/skills/platform/SKILL.md`, seed-only-if-missing).
- **Owner операций:** человек-владелец агента (`ConnectorEnv.userId`). Все листинги и мутации user-scoped — мета-агент управляет только ресурсами своего владельца.
- **Реализация:** тонкий адаптер поверх существующих сервисов. Чтение — из репозиториев, запись — через `AgentService`/`SkillService`/`AgentSkillService`/`ConnectionBindingService` (service-layer command-перегрузки, без зависимости от `controller/**`). Доменные `*StatusException` транслируются в `ConnectorException`.

## Инструменты (14)

| Тул | Назначение |
|---|---|
| `list_connectors` | Каталог коннекторов (`integration` флаг: нужна ли connection) |
| `get_connector` | Детали коннектора + его тулы и триггеры (для написания навыка) |
| `list_skills` | Навыки: `scope` MINE (свои) / PUBLIC, `search`, `connectorCode` |
| `get_skill` | Полный SKILL.md навыка |
| `list_agents` | Агенты владельца (`search`) |
| `get_agent` | Конфиг агента: инструкции, тип, привязанные навыки + их коннекторы |
| `create_agent` | Создать агента (type по умолчанию GENERIC; `skillIds` — привязать сразу) |
| `update_agent` | Правка name/description/instructions/enabled |
| `create_skill` | Навык из полного SKILL.md (`isPublic` по умолчанию false) |
| `update_skill` | Заменить SKILL.md (поднимает версию) |
| `bind_skill` | Привязать навык к агенту (+ его коннекторы и политики) |
| `unbind_skill` | Отвязать навык |
| `create_connection` | Начать подключение интеграции → **deep-link** (см. ниже) |
| `bind_connection` | Привязать существующее подключение к агенту |

Каналы (входящий роутинг), политики вручную, LLM-провайдеры, delete/regenerate, смена команды — намеренно вне коннектора (YAGNI).

## Границы безопасности

- **Guard «не сам себя»:** любой тул с `agentId` реджектит `ConnectorException`, если цель == агент-инициатор вызова (`ConnectorEnv.agentId`). Мета-агент не может менять/отвязывать/подключать сам себя.
- **type=WEBHOOK** через `create_agent` запрещён (webhook требует конфигурации, недоступной коннектору).
- **Ключ агента** из `create_agent` не возвращается тулом — показывается один раз в UI.

## Deep-link для подключений (контракт фронта)

Секреты (токены) **не проходят через LLM**. `create_connection` **не пишет в БД** — возвращает ссылку на штатный экран создания подключения:

```json
{
  "status": "setup_required",
  "setupUrl": "https://<app>/connections/new?connector=telegram&name=<name>",
  "connectorCode": "telegram"
}
```

База ссылки — `app.frontend.base-url` (env `APP_FRONTEND_BASE_URL`, дефолт `http://localhost:3000`).

**Что делает фронт:**
1. По `setupUrl` открывает форму создания подключения с преселектом коннектора из query `connector` (и опц. `name`).
2. Пользователь вводит учётные данные (или проходит OAuth) — это штатный флоу `POST /manage/connections/` с валидацией.
3. После успеха мета-агент перечитывает `list_connections`, находит новое подключение и вызывает `bind_connection`.

Так переиспользуется существующая форма подключения (в т.ч. будущий OAuth), без частичного состояния в БД.

## Связанное

- Общая архитектура SPI — [architecture.md](../architecture/connectors.md).
- Возврат тулов: record-типы дают корректный MCP `outputSchema`. `BaseConnectorHandler.invoke` разворачивает record в плоскую Map (camelCase-ключи = имена компонентов), чтобы рантайм-вывод совпадал со схемой из `ToolSchemaReflector`.
