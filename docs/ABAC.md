# Agent Tool Access Control — Backend Specification

## 1. Overview

Система контроля доступа агентов к инструментам (tools), предоставляемым через коннекторы (connectors). Реализует паттерн **most-specific-match wins** с **deny-override** на одном уровне специфичности.

### 1.1 Цель

Гибко ограничивать агентов на уровне любой комбинации: connector, connector_identity, tool — включая wildcard-разрешения и точечные запреты.

### 1.2 Термины

|Термин|Описание|
|---|---|
|`agent_name`|Идентификатор агента, запрашивающего доступ|
|`connector_name`|Идентификатор коннектора (slack, github, jira и т.д.)|
|`connector_identity`|Идентификатор учётных данных/аккаунта внутри коннектора (oauth-token-prod, api-key-ci и т.д.)|
|`tool_name`|Идентификатор конкретного инструмента внутри коннектора (send_message, list_issues и т.д.)|

### 1.3 Иерархия ресурсов

```
agent
 └── connector
      ├── connector_identity
      │    └── tool
      └── tool
```

Агент обращается к tool через connector, опционально указывая connector_identity. Политики могут ограничивать доступ на любом уровне этой иерархии.

---

## 2. Модель политики доступа (Access Policy)

### 2.1 Структура записи

```
AccessPolicy {
    id:                 UUID
    agent_name:         String          -- NOT NULL
    connector_name:     String | NULL   -- NULL = wildcard (любой connector)
    connector_identity: String | NULL   -- NULL = wildcard (любой identity)
    tool_name:          String | NULL   -- NULL = wildcard (любой tool)
    effect:             Enum(ALLOW, DENY)
    priority:           Integer | NULL  -- опциональный ручной приоритет (см. секцию 3.3)
    description:        String | NULL   -- человекочитаемое описание правила
    created_at:         Timestamp
    updated_at:         Timestamp
}
```

### 2.2 Семантика NULL (wildcard)

NULL в поле означает "любое значение". Примеры:

|agent|connector|identity|tool|effect|Смысл|
|---|---|---|---|---|---|
|bot-1|NULL|NULL|NULL|ALLOW|bot-1 может всё|
|bot-2|slack|NULL|NULL|ALLOW|bot-2 может всё в slack|
|bot-2|slack|NULL|send_message|DENY|bot-2 не может отправлять сообщения в slack (ни через какой identity)|
|bot-3|github|token-prod|NULL|ALLOW|bot-3 может всё в github, но только через token-prod|
|bot-3|github|token-ci|create_pr|ALLOW|bot-3 может создавать PR в github только через token-ci|
|bot-4|NULL|NULL|NULL|DENY|bot-4 заблокирован полностью|
|bot-4|slack|oauth-main|read_channel|ALLOW|bot-4 может только читать каналы slack через oauth-main|

### 2.3 Ограничения (constraints)

- `agent_name` всегда NOT NULL — политика без агента не имеет смысла.
- Если `connector_identity` задан, то `connector_name` тоже MUST быть задан (identity без connector не имеет смысла).
- Если `tool_name` задан, `connector_name` тоже MUST быть задан (tool без connector неоднозначен — у разных коннекторов могут быть одноимённые tool).
- `connector_identity` и `tool_name` могут быть заданы независимо друг от друга.

Валидация допустимых комбинаций NULL/NOT NULL:

```
agent  connector  identity  tool    Допустимо?
──────────────────────────────────────────────
  ✓       NULL      NULL    NULL       ✓   уровень 0 — глобальный
  ✓       ✓         NULL    NULL       ✓   уровень 1 — connector
  ✓       ✓         ✓       NULL       ✓   уровень 2 — connector + identity
  ✓       ✓         NULL    ✓          ✓   уровень 2 — connector + tool
  ✓       ✓         ✓       ✓          ✓   уровень 3 — полная спецификация
  ✓       NULL      ✓       NULL       ✗   identity без connector
  ✓       NULL      NULL    ✓          ✗   tool без connector
  ✓       NULL      ✓       ✓          ✗   identity+tool без connector
```

---

## 3. Алгоритм резолюции доступа

### 3.1 Вход

Запрос на проверку доступа (Access Request):

```
AccessRequest {
    agent_name:         String      -- кто запрашивает
    connector_name:     String      -- через какой коннектор
    connector_identity: String      -- с какими credentials
    tool_name:          String      -- какой инструмент
}
```

Все поля запроса обязательны. Вызывающий код всегда знает конкретные значения.

### 3.2 Выход

```
AccessDecision {
    allowed:    Boolean
    matched_policy_id: UUID | NULL   -- какая политика сработала (для аудита)
    reason:     String               -- человекочитаемое объяснение
}
```

### 3.3 Уровень специфичности (specificity)

Специфичность политики определяется количеством заданных (NOT NULL) полей помимо `agent_name`:

|Заданные поля|Specificity|
|---|---|
|только agent|0|
|agent + connector|1|
|agent + connector + identity|2|
|agent + connector + tool|2|
|agent + connector + identity + tool|3|

Если задан `priority` — он используется вместо вычисленной специфичности. Это позволяет вручную разрешать конфликты в нестандартных случаях.

### 3.4 Алгоритм (пошагово)

```
function evaluate(request: AccessRequest) -> AccessDecision:

    1. MATCH — найти все политики, подходящие под запрос:
       Политика P подходит, если для каждого поля:
         - P.field IS NULL (wildcard — совпадает с любым значением)
         - OR P.field == request.field (точное совпадение)

       SQL-эквивалент:
         WHERE P.agent_name = request.agent_name
           AND (P.connector_name IS NULL OR P.connector_name = request.connector_name)
           AND (P.connector_identity IS NULL OR P.connector_identity = request.connector_identity)
           AND (P.tool_name IS NULL OR P.tool_name = request.tool_name)

    2. Если подходящих политик нет → DENY (default deny).

    3. GROUP — сгруппировать по уровню специфичности.

    4. SELECT — взять группу с максимальной специфичностью.

    5. DENY-OVERRIDE — внутри этой группы:
       - Если есть хотя бы одна DENY → результат DENY.
       - Иначе → результат ALLOW.

    6. Вернуть решение с ID сработавшей политики.
```

### 3.5 Примеры резолюции

**Пример 1: DENY перекрывает ALLOW на том же уровне**

Политики:

```
P1: agent=bot-2, connector=slack, identity=NULL, tool=NULL       → ALLOW  (specificity=1)
P2: agent=bot-2, connector=slack, identity=NULL, tool=send_msg   → DENY   (specificity=2)
```

Запрос: `bot-2, slack, oauth-main, send_msg`

- P1 подходит (specificity 1)
- P2 подходит (specificity 2)
- Максимальная специфичность = 2, в этой группе только P2 (DENY)
- **Результат: DENY**

Запрос: `bot-2, slack, oauth-main, list_channels`

- P1 подходит (specificity 1)
- P2 не подходит (tool=send_msg ≠ list_channels)
- Максимальная специфичность = 1, в группе только P1 (ALLOW)
- **Результат: ALLOW**

**Пример 2: Более специфичное ALLOW побеждает общий DENY**

Политики:

```
P1: agent=bot-4, connector=NULL, identity=NULL, tool=NULL                    → DENY   (specificity=0)
P2: agent=bot-4, connector=slack, identity=oauth-main, tool=read_channel     → ALLOW  (specificity=3)
```

Запрос: `bot-4, slack, oauth-main, read_channel`

- P1 подходит (specificity 0)
- P2 подходит (specificity 3)
- Максимальная специфичность = 3, в группе только P2 (ALLOW)
- **Результат: ALLOW**

Запрос: `bot-4, slack, oauth-main, send_message`

- P1 подходит (specificity 0)
- P2 не подходит (tool ≠ read_channel)
- Максимальная специфичность = 0, в группе только P1 (DENY)
- **Результат: DENY**

**Пример 3: Deny-override внутри одного уровня**

Политики:

```
P1: agent=bot-5, connector=github, identity=token-a, tool=NULL   → ALLOW  (specificity=2)
P2: agent=bot-5, connector=github, identity=NULL,    tool=delete  → DENY   (specificity=2)
```

Запрос: `bot-5, github, token-a, delete`

- P1 подходит (specificity 2)
- P2 подходит (specificity 2)
- Максимальная специфичность = 2, в группе P1(ALLOW) и P2(DENY)
- Есть DENY → **Результат: DENY**

---

## 4. Схема хранения

### 4.1 SQL (PostgreSQL)

```sql
CREATE TABLE access_policy (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_name          TEXT NOT NULL,
    connector_name      TEXT,
    connector_identity  TEXT,
    tool_name           TEXT,
    effect              TEXT NOT NULL CHECK (effect IN ('ALLOW', 'DENY')),
    priority            INTEGER,
    description         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- identity без connector запрещён
    CONSTRAINT chk_identity_requires_connector
        CHECK (connector_identity IS NULL OR connector_name IS NOT NULL),

    -- tool без connector запрещён
    CONSTRAINT chk_tool_requires_connector
        CHECK (tool_name IS NULL OR connector_name IS NOT NULL)
);

-- Индекс для быстрого поиска политик агента
CREATE INDEX idx_policy_agent ON access_policy (agent_name);

-- Составной индекс для типичного запроса резолюции
CREATE INDEX idx_policy_lookup ON access_policy (
    agent_name, connector_name, connector_identity, tool_name
);
```

### 4.2 Запрос резолюции (один SQL)

```sql
WITH matched AS (
    SELECT
        id,
        effect,
        -- вычисляем специфичность
        COALESCE(priority,
            (CASE WHEN connector_name IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN connector_identity IS NOT NULL THEN 1 ELSE 0 END) +
            (CASE WHEN tool_name IS NOT NULL THEN 1 ELSE 0 END)
        ) AS specificity
    FROM access_policy
    WHERE agent_name = :agent_name
      AND (connector_name IS NULL OR connector_name = :connector_name)
      AND (connector_identity IS NULL OR connector_identity = :connector_identity)
      AND (tool_name IS NULL OR tool_name = :tool_name)
),
max_spec AS (
    SELECT MAX(specificity) AS max_specificity FROM matched
)
SELECT
    m.id,
    m.effect,
    m.specificity
FROM matched m
JOIN max_spec ms ON m.specificity = ms.max_specificity
ORDER BY
    CASE WHEN m.effect = 'DENY' THEN 0 ELSE 1 END
LIMIT 1;
```

Если результат пуст — default DENY. Если не пуст — effect из первой строки.

---

## 5. API

### 5.1 Проверка доступа

```
POST /api/v1/access/evaluate

Request:
{
    "agent_name": "bot-2",
    "connector_name": "slack",
    "connector_identity": "oauth-main",
    "tool_name": "send_message"
}

Response (200):
{
    "allowed": false,
    "matched_policy_id": "a1b2c3d4-...",
    "effect": "DENY",
    "specificity": 2,
    "reason": "Denied by policy: bot-2 cannot use send_message in slack"
}
```

### 5.2 CRUD политик

```
GET    /api/v1/access/policies?agent_name=bot-2          — список политик агента
GET    /api/v1/access/policies/{id}                       — одна политика
POST   /api/v1/access/policies                            — создать
PUT    /api/v1/access/policies/{id}                       — обновить
DELETE /api/v1/access/policies/{id}                       — удалить
```

### 5.3 Dry-run / explain

```
POST /api/v1/access/explain

Request:  (тело как у evaluate)

Response (200):
{
    "decision": "DENY",
    "all_matched_policies": [
        { "id": "...", "effect": "ALLOW", "specificity": 1 },
        { "id": "...", "effect": "DENY",  "specificity": 2 }
    ],
    "winning_group_specificity": 2,
    "winning_policies": [
        { "id": "...", "effect": "DENY", "specificity": 2 }
    ],
    "reason": "DENY wins at specificity level 2"
}
```

Этот эндпоинт возвращает полную трассировку резолюции для отладки и UI.

### 5.4 Bulk-check

```
POST /api/v1/access/evaluate/batch

Request:
{
    "agent_name": "bot-2",
    "checks": [
        { "connector_name": "slack", "connector_identity": "oauth-main", "tool_name": "send_message" },
        { "connector_name": "slack", "connector_identity": "oauth-main", "tool_name": "read_channel" },
        { "connector_name": "github", "connector_identity": "token-ci", "tool_name": "create_pr" }
    ]
}

Response (200):
{
    "results": [
        { "allowed": false, "matched_policy_id": "..." },
        { "allowed": true,  "matched_policy_id": "..." },
        { "allowed": false, "matched_policy_id": null, "reason": "No matching policy (default deny)" }
    ]
}
```

Используется для отрисовки матрицы доступа в UI и для prefetch при старте агента.

---

## 6. Кэширование

Результаты резолюции хорошо кэшируются, потому что ключ — фиксированная четвёрка строк.

**Ключ кэша:** `hash(agent_name, connector_name, connector_identity, tool_name)`

**Инвалидация:** при любом изменении политик для данного `agent_name` сбрасываем все записи кэша для этого агента. Точечная инвалидация возможна, но усложняет логику без большой выгоды — политик обычно мало и меняются они редко.

**Рекомендация:** начать с Caffeine (in-process) или Redis, TTL 5 минут + event-based invalidation.

---

## 7. Аудит

Каждый вызов `evaluate` логируется:

```
AccessAuditLog {
    id:                 UUID
    timestamp:          Timestamp
    agent_name:         String
    connector_name:     String
    connector_identity: String
    tool_name:          String
    decision:           ALLOW | DENY
    matched_policy_id:  UUID | NULL
    specificity:        Integer
    evaluation_time_ms: Integer
}
```

Это критично для отладки ("почему агент не смог вызвать tool X?") и для compliance.

---

## 8. Граничные случаи и решения

### 8.1 Нет политик для агента

Результат: DENY (default deny). Агент без явных разрешений не может ничего.

### 8.2 Конфликт ALLOW и DENY на одной специфичности

Результат: DENY побеждает (deny-override). Это безопаснее — если есть сомнения, запрещаем.

### 8.3 Один и тот же tool_name в разных коннекторах

Не проблема: constraint требует, чтобы tool_name всегда сопровождался connector_name. Политика `(agent=X, connector=NULL, tool=send)` невалидна и не пройдёт CHECK constraint.

### 8.4 Удаление connector_identity из коннектора

При удалении identity из системы — каскадно удалять или деактивировать связанные политики. Иначе останутся "мёртвые" правила.

### 8.5 Ручной priority конфликтует с вычисленным

Если `priority` задан — он полностью заменяет автоматическую специфичность. Ответственность за корректность лежит на администраторе. В explain-эндпоинте это должно быть явно видно.

---

## 9. Рекомендации по реализации

**Порядок реализации:**

1. Модель данных + миграции + CHECK constraints
2. Репозиторий с SQL-запросом резолюции (секция 4.2)
3. Сервис `AccessEvaluator` с методом `evaluate(AccessRequest) -> AccessDecision`
4. CRUD API для политик с валидацией (секция 2.3)
5. Эндпоинт `/evaluate`
6. Аудит-лог (асинхронно, чтобы не тормозить evaluate)
7. Кэширование
8. Эндпоинты `/explain` и `/evaluate/batch`
9. Интеграция: interceptor/filter, который вызывает evaluate перед каждым вызовом tool

**Тестирование:** таблица из секции 3.5 — готовые тест-кейсы. Дополнительно покрыть все комбинации из таблицы в секции 2.3 (валидные и невалидные).