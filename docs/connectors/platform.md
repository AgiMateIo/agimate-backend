# Platform connector

Внутренний коннектор `platform` — инструменты для **мета-агента**, который управляет самой платформой AgiMate: создаёт и настраивает агентов, пишет навыки (SKILL.md), привязывает навыки и подключает интеграции, заводит каналы и ABAC-политики, управляет LLM-провайдерами, командами, досками и джобами, наблюдает за работой агентов. Java-реализация в `controlapi/connectors/internal/platform/`.

## Модель

- **Тип:** internal (не integration). Привязывается к агенту **через навык** `platform` (сид `resources/seed/skills/<lang>/platform/SKILL.md`; пресет `platform-admin` привязывает этот навык готовым набором), а не через connection. Навык сидится в БД `SystemSkillBootstrap` (`resources/seed/skills/<lang>/platform/SKILL.md`, seed-only-if-missing): файлы на диске — источник для свежих установок, существующие инсталляции сохраняют свою строку в БД.
- **Owner операций:** человек-владелец агента (`ConnectorEnv.userId`). Все листинги и мутации user-scoped — мета-агент управляет только ресурсами своего владельца.
- **Реализация:** тонкий адаптер поверх существующих сервисов. Пять тул-сервисов — `PlatformAgentToolService`, `PlatformConnectionToolService`, `PlatformLlmToolService`, `PlatformWorkspaceToolService`, `PlatformObservabilityToolService` — плюс статические хелперы `PlatformToolsSupport`; один фасад `PlatformConnectorService` передаёт все модули в `BaseConnectorHandler` (дубль имени тула между модулями — ошибка на конструкторе). Чтение — из репозиториев, запись — через сервисные command-перегрузки (без зависимости от `controller/**`). Доменные `*StatusException` транслируются в `ConnectorException`.

## Инструменты (79)

Новые тулы помечены **(новый)**, расширенные — **(расширен)**; 13 прежних тулов перенесены без изменения поведения и не помечены.

Все листинги владельца (22 тула: `list_agents`, `list_skills`, `list_files`, `list_connectors`, `list_connections`, `list_connection_tools`, `list_connection_agents`, `list_agent_connections`, `list_channels`, `list_policies`, `list_llm_providers`, `list_llm_provider_models`, `list_llm_quotas`, `list_agent_llms`, `list_runs`, `list_sessions`, `list_tool_call_logs`, `list_trigger_logs`, `list_webhook_deliveries`, `list_teams`, `list_boards`, `list_connector_jobs`) возвращают `{items: [...], truncated: true|false}`. Каждый ограничен первыми 100 записями; `truncated` показывает, что результат неполный. Статические каталоги (`list_channel_handlers`, `list_llm_provider_catalog`, `list_presets`) не капятся — их объём ограничен системой; `get_run_turns` — осознанное исключение, объявленное в описании тула.

### Агенты, навыки, файлы — `PlatformAgentToolService` (17)

| Тул | Назначение |
|---|---|
| `list_agents` | Агенты владельца (`search`) |
| `get_agent` **(расширен)** | Конфиг агента: инструкции, тип, webhook (`webhookUrl`, `hasWebhookAuth`), привязанные навыки + их коннекторы |
| `create_agent` **(расширен)** | Создать агента: type GENERIC (по умолчанию) / CENTRIFUGO / MCP / WEBHOOK; `webhookUrl` для WEBHOOK, `teamId` — в команду, `skillIds` — привязать сразу (внутренние коннекторы навыков открываются автоматически); возвращает `keyUrl` на страницу UI `/dashboard/agents/<id>`, где ключ показывается один раз |
| `update_agent` **(расширен)** | Правка name/description/instructions/type/webhook/enabled (PATCH-семантика; смена типа с WEBHOOK чистит webhook-поля; пустой `type` означает «не прислан», очистки типа нет) |
| `delete_agent` **(новый)** | Удалить агента (soft delete): снимает привязки, политики и джобы; себя — нельзя |
| `regenerate_agent_key` **(новый)** | Перевыпустить ключ агента: старый перестаёт работать сразу; возвращает `keyUrl` на страницу UI `/dashboard/agents/<id>`, где новый ключ показывается один раз; себя — нельзя |
| `list_agent_skills` **(новый)** | Навыки агента со статусом удовлетворённости коннекторов (что не хватает — видно в `list_agent_connections`; чтение собственного агента разрешено) |
| `mark_skills_installed` **(новый)** | Принять текущую версию навыков агента и снять `needsReinstall` (после обновления навыков мимо тулов) |
| `get_skill` | Полный SKILL.md навыка |
| `list_skills` | Навыки: `scope` MINE (свои) / PUBLIC, `search`, `connectorCode` |
| `create_skill` | Навык из полного SKILL.md (`isPublic` по умолчанию false) |
| `update_skill` | Заменить SKILL.md (поднимает версию) |
| `delete_skill` **(новый)** | Удалить свой навык (soft delete, снимает все привязки); системные навыки — отказ |
| `bind_skill` | Привязать навык к агенту; **внутренние коннекторы навыка открываются автоматически** (как UI-флоу); внешние требуют выбранного инстанса (`bind_connection`) |
| `unbind_skill` | Отвязать навык |
| `list_files` | Файлы владельца: по умолчанию — этого разговора, `allConversations` снимает сужение (`docs/connectors/files.md`) |
| `delete_file` **(новый)** | Удалить файл владельца до TTL (id `agf_…` из `list_files`); ссылки перестают резолвиться |

### Подключения, каналы, политики — `PlatformConnectionToolService` (22)

| Тул | Назначение |
|---|---|
| `list_connectors` | Каталог коннекторов (`integration` флаг: нужна ли connection) |
| `get_connector` | Детали коннектора + его тулы и триггеры (для написания навыка) |
| `list_connections` | Подключения владельца (`connectorCode`) |
| `create_connection` | Начать подключение интеграции → **deep-link** (см. ниже): креды не через LLM |
| `update_connection` **(новый)** | Включить/выключить или переименовать подключение (PATCH-семантика; пустое имя не меняет текущее) |
| `delete_connection` **(новый)** | Удалить подключение: вебхук и кэш джобов/тулов чистятся; привязки агентов не снимаются автоматически — сначала `unbind_connection` |
| `test_connection` **(новый)** | Проверить подключение: доступность и учётные данные интеграции (`valid`, ошибка, нужна ли авторизация) |
| `list_connection_tools` **(новый)** | Тулы подключения со схемами входов (перед написанием политик) |
| `list_connection_agents` **(новый)** | Агенты, привязанные к подключению (инвентарь перед удалением/сменой кредов) |
| `bind_connection` | Привязать существующее подключение к агенту |
| `unbind_connection` **(новый)** | Закрыть коннектор для агента: снимает привязку и политики; себя — нельзя |
| `list_agent_connections` **(новый)** | Подключения агента (гейт доступности): внешние инстансы по id, internal-строки по коду; `managedBySkills`; чтение собственного агента разрешено |
| `list_channels` **(новый)** | Каналы владельца (входящий роутинг); фильтр по агенту |
| `get_channel` **(новый)** | Детали канала: конфиг и inputFilter |
| `create_channel` **(новый)** | Создать канал для push-агента (GENERIC/CENTRIFUGO); хендлер — из `list_channel_handlers`, `connectorCode` выводится из подключения |
| `update_channel` **(новый)** | Правка имени/конфига/фильтра канала (PATCH-семантика; смена тулов — пересозданием) |
| `delete_channel` **(новый)** | Удалить канал (привязки сохраняются) |
| `list_channel_handlers` **(новый)** | Хендлеры каналов с полями конфига (перед `create_channel`) |
| `list_policies` **(новый)** | ABAC-политики привязки агент-подключение (TOOL/TRIGGER, ALLOW/DENY, paramsFilter) |
| `create_policy` **(новый)** | Создать политику на привязке; о себе — нельзя |
| `update_policy` **(новый)** | Правка эффекта/фильтра/описания политики (PATCH-семантика); о себе — нельзя |
| `delete_policy` **(новый)** | Удалить политику; о себе — нельзя |

### LLM — `PlatformLlmToolService` (16, все новые)

| Тул | Назначение |
|---|---|
| `list_llm_providers` **(новый)** | Свои LLM-провайдеры (BYOK); платформенный не показывается; ключ не возвращается — только `apiKeyMask` |
| `get_llm_provider` **(новый)** | Детали провайдера: приоритеты целей, extraBody, mediaTransport |
| `create_llm_provider` **(новый)** | Создать провайдера (OPENAI/ANTHROPIC/GEMINI/OPENAI_COMPATIBLE); `apiKey` обязателен, хранится шифрованно, не возвращается |
| `update_llm_provider` **(новый)** | Частичная правка провайдера (не переданное — сохраняется; пустой map — очистка) |
| `delete_llm_provider` **(новый)** | Удалить провайдера (каскад на привязки агентов); платформенный — отказ |
| `refresh_llm_provider_models` **(новый)** | Синхронизировать реестр моделей из `/models` по сохранённому ключу (заодно проверяет ключ) |
| `list_llm_provider_models` **(новый)** | Модели провайдера со статусами |
| `list_llm_provider_catalog` **(новый)** | Каталог известных LLM-шлюзов: точный baseUrl, модели по целям (`purposePriority`), где взять ключ (`apiKeyUrl`) — перед `create_llm_provider` |
| `list_llm_quotas` **(новый)** | Квоты провайдера (субъект USER/AGENT/TOTAL × окно DAY/MONTH) |
| `create_llm_quota` **(новый)** | Создать квоту с обязательным `limitTokens` типа Long (дубль субъект+окно → конфликт) |
| `update_llm_quota` **(новый)** | Сменить обязательный `limitTokens` типа Long |
| `delete_llm_quota` **(новый)** | Удалить квоту |
| `list_agent_llms` **(новый)** | LLM-привязки агента: цель (purpose) → провайдер + модель |
| `set_agent_llm` **(новый)** | Создать или заменить привязку модели провайдера для цели агента (upsert; CHAT по умолчанию) |
| `delete_agent_llm` **(новый)** | Снять LLM-привязку агента под целью |
| `get_llm_usage` **(новый)** | Расход токенов и остаток квоты по провайдерам за день/месяц (платформенный — только свой расход) |

### Команды, доски, джобы — `PlatformWorkspaceToolService` (13, все новые)

| Тул | Назначение |
|---|---|
| `list_teams` **(новый)** | Команды (agentic teams) владельца |
| `get_team` **(новый)** | Команда + её участники (агенты) |
| `create_team` **(новый)** | Создать команду (имя уникально для владельца) |
| `update_team` **(новый)** | Правка имени/описания команды (PATCH-семантика) |
| `delete_team` **(новый)** | Удалить команду (отказ, пока есть доска или агенты) |
| `list_presets` **(новый)** | Галерея пресетов ролей для мастера создания (read-only; управляются администратором) |
| `list_boards` **(новый)** | Доски владельца: только краткие сведения, без задач |
| `create_board` **(новый)** | Создать доску в команде |
| `list_connector_jobs` **(новый)** | Джобы коннекторов владельца (фильтры по коннектору/виду/подключению; SYSTEM видны, но не управляются) |
| `pause_job` **(новый)** | Поставить джоб на паузу |
| `resume_job` **(новый)** | Возобновить джоб |
| `run_job_now` **(новый)** | Запустить джоб сейчас (отказ для paused/running) |
| `delete_job` **(новый)** | Удалить джоб (SYSTEM-джобы — отказ, вместо удаления пауза) |

### Наблюдаемость — `PlatformObservabilityToolService` (11, все новые)

| Тул | Назначение |
|---|---|
| `list_runs` **(новый)** | Прогоны с фильтрами (агент, сессия, коннектор, подключение, триггер, статус) |
| `get_run` **(новый)** | Прогон по id |
| `cancel_run` **(новый)** | Попросить прогон остановиться на ближайшем стыке (идемпотентно) |
| `list_sessions` **(новый)** | Сессии каналов (фильтры по агенту/каналу/коннектору) |
| `get_session` **(новый)** | Сессия по id |
| `cancel_session` **(новый)** | Остановить все живые прогоны сессии (текущий + очередь) |
| `list_tool_call_logs` **(новый)** | Журнал вызовов тулов (фильтры по агенту/коннектору/эффекту ALLOW-DENY/статусу) |
| `list_trigger_logs` **(новый)** | Журнал срабатываний триггеров коннекторов |
| `list_webhook_deliveries` **(новый)** | Доставки вебхуков агента (статус, код ответа, ошибка, длительность) |
| `get_run_turns` **(новый)** | Полный транскрипт прогона из журнала ходов, старые первыми (аудит) |
| `get_run_prompt` **(новый)** | Сообщения, ушедшие в первый LLM-вызов прогона (system-блоки, история, триггер) |

## Границы

**Теперь в коннекторе** (ранее намеренно вне): входящие каналы (роутинг), ручные ABAC-политики, LLM-провайдеры (BYOK), delete/regenerate, команды (agentic teams) — плюс создание и краткий листинг досок, джобы коннекторов и наблюдаемость (прогоны, сессии, журналы). Ключи агентов — через UI, не в ответе тула.

**Вне коннектора (намеренно):**

- **`/manage/admin` (ADMIN-роль)** — в `ConnectorEnv` нет роли, ключ агента не доказывает ADMIN. Сюда же: платформенный LLM-провайдер, create/update пресетов, системные навыки.
- **Приложения (`/manage/apps`)** — жизненный цикл устройства: ключ бесполезен до паринга на физическом устройстве.
- **Webchat send / start-session / контакты / токены сессий** — разговор агента с самим собой; наблюдение покрывают листинги сессий.
- **Centrifugo-токен** — realtime-токен UI, бессмысленен для LLM.
- **Загрузка файлов (multipart)** — не влезает в JSON-аргументы тула; `list_files`/`delete_file` есть, upload остаётся в UI.
- **Учётные данные подключений** — `create_connection` с секретом, `update_connection_secret`, oauth-complete: секреты через LLM не ходят; deep-link остаётся (см. ниже).
- **Перевод агента в команду** (`update_agent(teamId)`) — такой операции нет в manage-API (команда задаётся только при создании).
- **Пресеты: create/update/delete** — ADMIN; get-by-id — эндпоинта нет (галерея покрывает данные).
- **Правка/удаление досок и работа с задачами/комментариями досок** — эндпоинтов нет; мета-агент конфигурирует платформу, а работу на доске выполняет коннектор `board`.
- **История сообщений сессии / rename / close / mark-read** — контент чата и удобства UI; листинги сессий есть.
- **Per-connector маппинг инстансов привязанного навыка** — `bind_skill` + `list_agent_connections` покрывают общий флоу.
- **Trigger probe (issue/match)** — dev-инструмент UI.
- **`list_connection_triggers`** — типовые триггеры уже видны через `get_connector`.
- **`getSkillAgents`** (обратный листинг «кто использует навык») — `list_connection_agents` покрывает аналогичную нужду для подключений.
- **`upsertModelExtraBody`** (per-model extra_body) — глубокая конфигурация; реестр моделей виден через `list_llm_provider_models`.

## Границы безопасности

- **Owner scope.** Все листинги и мутации user-scoped по `env.userId` — мета-агент управляет только ресурсами своего владельца; чужая сущность читается как «not found», никогда «forbidden» (без утечки существования).
- **Guard «не сам себя».** Тулы, управляющие агентом как субъектом (`update_agent`, `delete_agent`, `regenerate_agent_key`, bind/unbind навыков и подключений, агентские LLM-тулы, каналы о себе), реджектят `ConnectorException`, если цель == агент-инициатор (`ConnectorEnv.agentId`). `list_agent_skills` и `list_agent_connections` — read-only исключения: листинг собственного агента разрешён. Guard null-safe и **распространён на мутации ABAC-политик**: агент не может создавать/менять/удалять политики, чей субъект — он сам (иначе DENY владельца на мета-агента был бы самоустранимым). Исключения — фильтры листингов.
- **Секреты write-only.** `webhookAuthHeader` (агенты) и `apiKey` (LLM-провайдеры) принимаются на вход и никогда не возвращаются: `get_agent` даёт булев `hasWebhookAuth`, провайдеры — `apiKeyMask` (префикс + первые 4 + `…` + последние 4). `webhookUrl` эхо-ится — URL не секрет.
- **Ключ агента выдаётся только через UI.** `create_agent` и `regenerate_agent_key` возвращают `keyUrl` на `/dashboard/agents/<id>`, где ключ показывается один раз; секреты через тулы не ходят. Как защита в глубину `list_tool_call_logs` и `get_run_turns` вырезают `plaintextKey`/`apiKey`/`webhookAuthHeader` из JSON аргументов и результатов перед возвратом (сырой `output` всё же лежит в `tool_call_logs` и доступен владельцу в UI).
- **PATCH-семантика — одно правило для всех update-тулов.** Параметр не прислан (`null`) = не трогаем; пустая строка / пустой объект / пустой список = очистить. Тул сам резолвит «не трогаем» через текущую запись, где сервис не умеет null-семантику; где сервис умеет (`AgentService.patch`, `LlmProviderService.update`, `AgenticTeamService.patch`) — сырые значения передаются как есть. Исключения из «пусто = очистить»: **enum-параметры** (`update_agent.type`, `update_policy.effect`) — пустое значение = не прислано (очистки для enum не существует, иначе пустая строка молча переключала бы тип агента); **name** — пустая строка это ошибка (`update_agent`, `update_team`, `update_channel`, `update_llm_provider`) либо «не трогаем» (`update_connection`).
- **Листинги.** Все листинги владельца (22 тула — список в шапке раздела) отдают `{items, truncated}`; каждая выдача ограничена 100 первыми записями, а `truncated` сообщает о неполном результате. Статические каталоги (`list_channel_handlers`, `list_llm_provider_catalog`, `list_presets`) не капятся — их объём ограничен системой; `get_run_turns` — осознанное исключение, объявленное в описании тула. Rate-limit общий MCP (120/мин на агента).
- **Deep-link для подключений — без изменений** (см. ниже).

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
- Администрирование платформы по MCP — [mcp-server.md](../contracts/mcp-server.md); решение — [platform-admin-mcp.md](../decisions/platform-admin-mcp.md).
- Возврат тулов: record-типы дают корректный MCP `outputSchema`. `BaseConnectorHandler.invoke` разворачивает record в плоскую Map (camelCase-ключи = имена компонентов), чтобы рантайм-вывод совпадал со схемой из `ToolSchemaReflector`.
