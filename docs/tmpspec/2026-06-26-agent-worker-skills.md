# Agent-worker: скиллы как источник истины (connections + триггеры по навыкам)

> Временная спека для команды воркера. Две части:
> 1. **Breaking-изменение gRPC** `AgentContext.GetSkills`/`GetSkill` (скиллы свёрнуты в одну сущность).
> 2. **Новая семантика воркера:** доступные агенту коннекшены и реакция на триггеры выводятся
>    из **навыков** (skills) агента, а не из «всего, что привязано».
>
> Proto: `services/control-api/src/main/proto/agentworker/agent_context.proto`. `ExecuteTool`,
> `GetConnections`/`GetConnectionTools`, реестр ранов — без изменений.

---

## 0. TL;DR

| Было | Стало |
|---|---|
| `SkillRef.connectors: [SkillConnectorRef{connector_code,type,name}]` | `SkillRef.connector_codes: repeated string` |
| `SkillSpec.skill_md` = полный SKILL.md (с frontmatter) | `skill_md` = **тело без frontmatter** (имя/описание/версия — отдельными полями) |
| Тулсет агента = тулы всех привязанных коннекшенов | **Тулсет агента = коннекшены, требуемые его навыками** |
| Триггер будит агента «как есть» | **Триггер активирует навык, объявивший этот коннектор, + его тулы** |

Модель скилла: `skill = { name, description, md (тело), connector_codes[] }`. Коннектор объявляется
в навыке кодом (`board`, `time`, `mcp`, `telegram`, ...). Скилл — это «способность»: текст-инструкция
+ набор коннекторов, которые она использует.

---

## 1. Proto-изменения `GetSkills` / `GetSkill`

```proto
message SkillRef {
  string skill_id = 1;
  string name = 2;
  string description = 3;
  repeated string connector_codes = 4 [json_name = "connectorCodes"];  // было: repeated SkillConnectorRef
}

message SkillSpec {
  string skill_id = 1;
  string version = 2;
  string name = 3;
  string description = 4;
  bytes  definition_json = 5;   // {name, description, version}
  string skill_md = 6;          // ТЕЛО SKILL.md без frontmatter
}
```

- Сообщение `SkillConnectorRef` **удалено**. У навыка больше нет гранулярности «конкретный тул/триггер» —
  навык объявляет коннектор **целиком** (его кодом). Все тулы/триггеры этого коннектора доступны навыку.
- `GetSkill.skill_md` теперь содержит только тело (markdown без `---`-заголовка). Имя/описание берите из
  полей `SkillSpec`, а не из frontmatter.

> Перегенерить стабы и убрать обращения к `SkillRef.connectors[*].type/name`.

---

## 2. Поведение A — коннекшены агента поднимаются ТОЛЬКО из его навыков

**Принцип:** навыки — источник истины того, что агент умеет. Коннекшен включается в рабочий набор
агента, **только если хотя бы один навык агента объявляет его `connector_code`**. Привязанный, но не
объявленный ни одним навыком коннекшен (например, заведённый каналом) в тулсет агента **не попадает**.

Алгоритм сборки тулсета:

```
skills      = GetSkills(agent_id)                       // [{skill_id, connector_codes[]}]
required    = ∪ skill.connector_codes                   // объединение по всем навыкам
connections = GetConnections(agent_id)                  // [{id, connector_code, namespace, name}]

active = [c ∈ connections if c.connector_code ∈ required]   // фильтр по навыкам
for c in active:
    tools += GetConnectionTools(c.id)                   // {namespace}.{name} для LLM, как прежде
```

Замечания:
- Мульти-инстанс коннекторы: навык объявляет **код** (`mcp`), значит подходят **все** коннекшены агента
  с этим кодом (`mcp_context7`, `mcp_foo` — оба активны).
- Навык объявил `connector_code`, а коннекшена этого типа у агента нет → способность «не обеспечена»:
  тулов нет, в `md` навыка обычно сказано, что нужен коннектор. (Бэкенд для UI отдаёт это как
  `connectionId=null` в `/manage/agents/{id}/skills`.)
- Фильтрация — **на стороне воркера** (пересечение `GetConnections` × `required`). Новый RPC не нужен:
  бэкенд по-прежнему отдаёт в `GetConnections` все привязанные коннекшены, а скоуп по навыкам считает воркер.

---

## 3. Поведение B — триггер активирует навык и его тулы

**Принцип:** когда приходит триггер от коннектора `X`, воркер активирует **навык, объявивший `X`**, и
**тулы коннектора `X`** — агент обрабатывает событие в контексте правильного навыка, а не «вслепую».

Алгоритм обработки триггера:

```
trigger.connector_code = X            // см. §4 — откуда берётся
skills   = GetSkills(agent_id)
matched  = [s ∈ skills if X ∈ s.connector_codes]

if matched is empty:
    // ни один навык агента не отвечает за коннектор X → не активируем
    // (агент не «обучен» этому коннектору; обрабатывать как no-op/generic по политике воркера)
else:
    for s in matched:
        md  += GetSkill(s.skill_id).skill_md            // инструкции навыка в системный контекст
    conn  = connection of agent where connector_code == X   // из GetConnections
    tools += GetConnectionTools(conn.id)                // тулы коннектора X
    // запускаем агента с этим навыком(ами) + тулами по триггеру
```

Замечания:
- «если этот коннектор есть в навыке» = guard `X ∈ s.connector_codes`. Нет навыка с `X` → агент на
  триггер этого коннектора не реагирует осмысленно (его способности туда не распространяются).
- Для мульти-инстанс коннектора триггер несёт конкретный коннекшен (identity); навык матчится по коду,
  а тулы берём у того коннекшена, с которого пришёл триггер.

---

## 4. Откуда воркер берёт `connector_code` триггера (предпосылка)

Воркеру нужно знать коннектор-источник триггера, чтобы смэтчить навык. Источники:
- Триггер привязан к **каналу**; `ChannelGateway.ListChannels(agent_id)` отдаёт
  `ChannelDescriptor{channel_id, connector_code, identity}` — резолв `channel → connector_code → connection`.
- Полезная нагрузка триггера (`trigger_input_json`) идентифицирует коннекшен.

> **Требование к бэкенду (проверить на стороне воркера):** доставка триггера должна позволять воркеру
> однозначно определить `connector_code` + `connection.id` источника. Если текущая доставка этого не
> даёт явно — это бэкенд-предпосылка к поведению B (нужно прокинуть в payload/контекст рана). Новых
> RPC для §2/§3 не требуется — всё собирается из `GetSkills` + `GetConnections` + `GetConnectionTools`.

---

## 5. Чек-лист миграции воркера

- [ ] Перегенерить стабы; убрать `SkillConnectorRef` и обращения к `.type/.name` навыка.
- [ ] `GetSkill.skill_md` трактовать как тело (без frontmatter); имя/описание — из полей `SkillSpec`.
- [ ] Сборку тулсета вести через навыки: `required = ∪ connector_codes`, активны только коннекшены из `required` (§2).
- [ ] Обработку триггера привязать к навыку: матч `connector_code` источника по `connector_codes` навыков; подгружать `skill_md` + тулы коннектора (§3).
- [ ] Убедиться, что доставка триггера даёт `connector_code` + `connection.id` источника (§4); если нет — поднять как бэкенд-задачу.
