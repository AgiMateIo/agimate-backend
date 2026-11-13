---
status: partial
created: 2026-11-13
implemented: 2026-11-13
---

# Пресеты подключений MCP: каталог из `seed/connectors`, форма и поиск

Фронту нужен каталог возможных подключений: пользователь выбирает знакомый сервис, а не вводит URL
руками. Данные для такого каталога уже собираются в отдельном репозитории `mcp`: 126 проверенных
MCP-серверов с URL, категорией, юрисдикцией, способом авторизации и результатом ежедневного probe;
описание есть у 66, иконка — у всех. Решение о том, что из этих файлов класть в базу, как хранить два
языка и как искать, — этот документ.

Первая спека (`docs/tmpspec/connector-presets-backend.md` во фронтенд-репозитории, 2026-10-20) была
наброском на десять записей. Здесь она пересмотрена под реальный датасет: файлы копируются в бэкенд
вручную, база — их проекция, каталог ничего не обещает сверх того, что платформа умеет подключить.

> **Реализация 2026-11-13.** Схема, сид, чтение, manage- и публичный API и юнит-тесты готовы.
> Осталось: скопировать `mcp/servers/*.json` в `seed/connectors/mcp/`, проверить миграцию на
> локальной базе, передать фронтенд-дельту и выкатить.

## Что есть и чего нет

**Датасет `mcp/servers`.** 126 файлов: 24 `up`, 90 `auth_required`, 12 `down`. Авторизация: 64 OAuth
DCR, 4 OAuth no-DCR, 27 API key/Bearer, 19 без авторизации, 12 неизвестно (все `down`). OAuth-серверов
68: 18 объявляют CIMD, 46 умеют только DCR, 4 требуют pre-registered клиента. У 21 DCR-сервера есть
статический токен как альтернатива OAuth; `declared.authMethods` объявлен у 20, у `square` пока нет.
Заголовки объявлены у 70 серверов, 82 записи: 57 `Authorization`, 25 не-Authorization (13
обязательных, 12 опциональных); единственный `api_key_or_bearer` без заголовка — `stack-overflow`.
Иконки есть у всех 126, описание — у 66 (все 24 `up`), русские `nameRu`/`descriptionRu` — у 57.
Категорий восемь, юрисдикций — тринадцать стран (58 RU, 53 US), у одной не указана.

**Бэкенд.** `GET /manage/connectors/{code}` отдаёт `IntegrationMeta.credentialFields`; у MCP это
`url` (URL, обязательный), `auth_token` (SECRET, Bearer) и `headers` (SECRET JSON).
`POST /manage/connections/` делает живой `initialize`-probe, для MCP кладёт `url` в
`connections.params`, токен и заголовки — в секрет, а `subCode` = `url.trim()`; несколько экземпляров
одного сервера разрешены. `ConnectionResponse` возвращает `subCode`. OAuth-поток обрабатывает 401,
но требует **CIMD**: `McpAuthDiscovery` отказывает серверу без
`client_id_metadata_document_supported`. DCR в коде не реализован.

**Ограничение, которое нельзя игнорировать.** Из 68 OAuth-серверов только 18 объявляют CIMD; 46
умеют только DCR, ещё 4 требуют pre-registered клиента. Но 20 DCR-серверов уже объявляют статический
токен (`declared.authMethods`), ещё у `square` есть заголовок, но нет `authMethods`. Значит, без
токена остаются 25 DCR-only + 4 pre-registered ≈ 29 карточек (30, пока `square` не исправлен). Для
остальных `availability` и причина — в модели; DCR-фолбэк остаётся отдельным решением.

Важная оговорка: CIMD — необходимое, но не достаточное условие. `McpAuthDiscovery` дополнительно
требует S256 PKCE и `authorization_endpoint`/`token_endpoint`; датасет по-прежнему не хранит PKCE и
endpoint'ы — их обнаруживает сам OAuth-поток при создании. Поэтому `availability` для OAuth остаётся
**best-effort**, а окончательная проверка происходит при подключении. Для протокола и транспорта
появились точные признаки: `probe.handshake.supported` (17 `up` — `true`, 2 — `false`, 5 — `null`) и
`probe.transportConfirmed` (24 `true`, 102 `false`); из них при сиде вычисляется `availability`, а в
базе остаётся только результат.

## ТРИЗ: противоречия и выбранный вариант

| Противоречие | Как разрешено |
|---|---|
| Свежесть ↔ автономность: данные probe меняются ежедневно, но бэкенд не должен ходить в чужой репозиторий в рантайме | Файлы копируются вручную в бэкенд; база — проекция. Свежесть = копирование + деплой, рантайм-зависимости нет |
| Богатство карточки ↔ дублирование декларации коннектора | `fields` — оверрайды полей коннектора; `header_fields`/`header_groups` — пресет-специфичные поля, которых в декларации коннектора нет; сервис собирает форму из обоих |
| Полнота ↔ честность: хочется показать все 126, но не все OAuth-серверы платформа авторизует | `availability` (best-effort по данным probe) в ответе; статический токен (`authMethods`) открывает 20 DCR-серверов, остальные остаются в каталоге без обещания |
| Полнота датасета ↔ минимализм схемы | В базу едут только поля карточки, формы и фильтра; `vendor`, `toolCount`, `lastChecked`, `note`, транспорт и handshake — входы сида, не колонки |
| Общий кэшируемый ответ ↔ «уже подключено» у пользователя | Каталог не знает пользователя; фронт сравнивает `connection.subCode` с `preset.connection.identity`. Пресет в подключении не хранится |
| Автоматизм ↔ точность: `declared.headers` — текст вендора, а не machine-readable формат токена | `valuePrefix`/`keyPrefix`/`label`/`labelRu` в `declared.headers`; `overrides.yaml` — только для исключений |
| Один словарь ↔ восемь категорий датасета | Маппинг 8 → 11 существующих кодов таксономии, без второго словаря |

**ИКР:** файлы — источник истины, база — их queryable-проекция; каталог наполняется копированием и
деплоем, не становится вторым источником истины и не участвует в работе созданного подключения.

**Рассмотренные варианты:**

- **A. Ручной сид на 10–30 записей.** Отвергнут: датасет на 126 записей руками не поддерживается,
  статус и юрисдикция теряются.
- **B. Копирование JSON в бэкенд + сидер-проекция.** Выбран: полный датасет, бэкенд самодостаточен,
  нет сетевой зависимости, пользователь контролирует момент обновления.
- **C. Рантайм-синк из `mcp`/реестра с собственным probe.** Отвергнут: сетевая зависимость,
  дублирование probe-пайплайна, SSRF и эксплуатация; для каталога-витрины это лишняя подсистема.
- **Поиск:** in-memory отвергнут; SQL `LIKE` по таблицам. `pg_trgm` — следующий шаг при росте, не v1.

## Решение

### Файлы — источник истины, база — проекция

Раскладка:

```
services/control-api/src/main/resources/seed/connectors/
    mcp/
        notion.json
        github.json
        yandex-tracker.json
        ...
        overrides.yaml
```

- Один файл на сервер, как в исходном репозитории: копирование — это `cp servers/*.json`, диффы
  видны по каждому серверу.
- Директория `mcp/` — потому что у другого типа коннектора может быть другой формат; `connectorCode`
  берётся из имени директории.
- `overrides.yaml` переживает повторное копирование JSON: в нём только то, чего нет в исходнике —
  спорный `authMethods`, редкие исключения и, при необходимости, переопределение встроенного
  маппинга категорий. `valuePrefix`/`keyPrefix`/`label`/`labelRu` теперь приходят из
  `declared.headers`; оверрайд — fallback, а не основной путь.
- Генератора нет: пользователь копирует файлы сам и обновляет их тогда, когда хочет. Никакого
  чтения чужого репозитория при старте.

### Схема

`connector_presets` — одна строка на сервер:

| Колонка | Тип | Назначение |
|---|---|---|
| `id` | UUID PK `uuidv7()` | суррогатный ключ |
| `code` | TEXT NOT NULL, unique | ключ пресета: deep link, React key, `?preset=` |
| `connector_code` | TEXT NOT NULL, FK → `connectors(code)` | в v1 `mcp` |
| `name` | TEXT NOT NULL | английское имя/бренд, источник и fallback |
| `description` | TEXT | английское описание, источник и fallback |
| `icon_url` | TEXT | иконка; на фронте fallback-монограмма |
| `category` | TEXT NOT NULL | `ContentCategory` (`@Enumerated(EnumType.STRING)`) после маппинга |
| `jurisdiction` | TEXT | ISO 3166-1 alpha-2: фильтр по стране и бейдж на карточке; NULL — страна не указана |
| `auth_methods` | JSONB NOT NULL DEFAULT `'[]'` | только **поддерживаемые** способы: `["OAUTH","TOKEN"]`; OAuth попадает сюда лишь при CIMD, отфильтровано при сиде |
| `availability` | TEXT NOT NULL | `AVAILABLE`, `DOWN`, `TRANSPORT_UNSUPPORTED`, `PROTOCOL_UNSUPPORTED`, `AUTH_UNSUPPORTED`; вычисляется при сиде |
| `docs_url` | TEXT | ссылка «где взять токен» |
| `params` | JSONB NOT NULL DEFAULT `'{}'` | несекретные значения формы, у MCP `{"url": ...}` |
| `identity` | TEXT NOT NULL | `params.url.trim()` — ровно то, что MCP кладёт в `subCode` |
| `fields` | JSONB | оверрайды полей коннектора: `{код: {label, labelRu, required}}` |
| `header_fields` | JSONB | заголовки формы: `name`, `label`, `labelRu`, `secret`, `required`, `valuePrefix`, `keyPrefix` |
| `header_groups` | JSONB | `[{id, oneOf, required}]` из `declared.headerGroups`; «ровно один из» |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE | выключатель инсталляции, сид его не пишет |
| `source_present` | BOOLEAN NOT NULL DEFAULT TRUE | файл удалён — запись скрывается, но не удаляется |
| `created_at`, `updated_at` | TIMESTAMP | как у остальных сущностей |

`auth` — не колонка, а производный бейдж в ответе: `auth_methods` хранит уже **поддерживаемые**
способы (`OAUTH` — только при CIMD, `TOKEN` и `NONE` — всегда), поэтому отдельный `oauth_support` и
фильтрация на чтении не нужны. Приоритет бейджа: `OAUTH` → `TOKEN` → `NONE` → `UNKNOWN`.

Ограничения и индексы:

- `uq_connector_presets_code` — `code`;
- `uq_connector_presets_connector_code_identity_present` — **частичный** `(connector_code, identity)
  WHERE source_present = TRUE`: он не мешает переименованию файла, когда старая строка уже скрыта
  (`source_present = false`). Партиальный индекс в JPA невыразим — описывается javadoc'ом сущности;
- `fk_connector_presets_connector_code` — `connector_code` → `connectors(code)`;
- `chk_connector_presets_availability` — одно из пяти значений `availability`;
- `chk_connector_presets_jurisdiction` — `jurisdiction IS NULL OR jurisdiction ~ '^[A-Z]{2}$'`;
- `chk_connector_presets_auth_methods` — `jsonb_typeof(auth_methods) = 'array'` и
  `auth_methods <@ '["OAUTH","TOKEN","NONE"]'::jsonb`;
- `chk_connector_presets_header_groups` — `header_groups IS NULL OR jsonb_typeof(header_groups) = 'array'`.

Отдельного индекса под листинг нет: 126 строк, поиск всё равно сканирует таблицу. Индекс появится
вместе с `pg_trgm` или когда каталог вырастет.

`connector_preset_texts` — только не-дефолтные языки:

| Колонка | Тип | Назначение |
|---|---|---|
| `id` | UUID PK `uuidv7()` | |
| `preset_id` | UUID NOT NULL, FK → `connector_presets(id)` ON DELETE CASCADE | |
| `language` | TEXT NOT NULL | `RU`; английский живёт в основной таблице |
| `name` | TEXT | nullable: нет перевода — берём английское имя |
| `description` | TEXT | nullable: нет перевода — берём английское описание |
| `created_at`, `updated_at` | TIMESTAMP | как у остальных сущностей (`BaseEntity`) |

- `uq_connector_preset_texts_preset_id_language` — `(preset_id, language)`;
- `fk_connector_preset_texts_preset` — `preset_id` → `connector_presets(id)`.

Отдельный индекс по `language` не нужен: join идёт по `preset_id`, а пара `(preset_id, language)`
уже уникальна.

### Что из файла попадает в базу, а что нет

| Источник | Куда | Зачем |
|---|---|---|
| имя файла без `.json` | `code` | стабильный ключ и deep link |
| `name` | `name` (EN) | карточка и fallback |
| `description` | `description` (EN) | карточка и fallback |
| `nameRu` | `connector_preset_texts.name` | русское имя, если вендор его дал |
| `descriptionRu` | `connector_preset_texts.description` | русское описание |
| `iconUrl` | `icon_url` | карточка |
| `category` | `category` | после маппинга в таксономию |
| `jurisdiction.country` | `jurisdiction` | фильтр по стране и бейдж |
| `url` / `probe.endpointUrl` | `params.url`, `identity` | форма и «уже подключено» |
| `docsUrl` | `docs_url` | ссылка «где взять токен» |
| `declared.authMethods` + `probe.authType` + `probe.authDetails` | `auth_methods` | поддерживаемые способы; `oauth2.1` → `OAUTH` только при CIMD |
| `probe.status`, транспорт, `probe.handshake` | `availability` | один код состояния карточки; в колонки не переезжает |
| `declared.headers` + `overrides.yaml` | `fields`, `header_fields` | форма подключения |
| `declared.headerGroups` | `header_groups` | «ровно один из» заголовков |

Не кладём:

- `probe.tools` — полный список тяжёлый и меняется; после подключения тулы и так обнаруживаются;
- `probe.attempts`, `capabilities`, `versions`, `serverName`, `serverVersion`, `protocolVersion` —
  диагностика probe, а не данные каталога; версии протокола сводятся в `availability`;
- `declared.source`, `protocolVersions` — провенанс; исходные файлы остаются в репозитории;
- сырой `declared.headers` — в базу попадает уже собранный `header_fields`, а не тексты вендора;
- `probe.authDetails` целиком — нужен только признак CIMD; scopes и endpoints OAuth-поток
  обнаруживает сам;
- `vendor`, `toolCount`, `lastChecked`, `note`, `addedAt` — витрине и форме не нужны; остаются в
  JSON и добавляются точечно, если появится продуктовая потребность;
- `status`, `transport`, `transport_source`, `legacy_sse`, `handshake_supported` — не колонки: сид
  сводит их в `availability`.

### Мультиязычность

Модель «источник + переводы»: английские `name`/`description` лежат в основной таблице и служат
fallback; русские — в `connector_preset_texts`. Ответ выбирает язык по `Accept-Language` через
`ContentLanguageResolver` (заголовок `Vary: Accept-Language` ставится автоматически).

- Это **осознанное исключение** из [language-catalogs.md](language-catalogs.md): там таблица
  переводов отвергнута для навыков и пресетов, потому что их языковые версии — самостоятельные
  записи и могут различаться набором. У MCP-пресетов набор общий, различаются только тексты;
  поэтому здесь таблица переводов уместна. Исключение надо назвать и в самом решении, и
  перекрёстной ссылкой из `language-catalogs.md` (пункт плана).
- Третий язык — строка, а не миграция. Частичный перевод (есть только `descriptionRu`) выражается
  nullable-полем и fallback по каждому полю.
- `nameRu` — не перевод бренда, а официальное русское имя («Яндекс Трекер»); поэтому RU-строка
  перекрывает `name`. Если `nameRu` нет — показываем английский бренд.
- `label`/`labelRu` у полей формы: приходят из `declared.headers[].label`/`labelRu`, оверрайд —
  fallback; сервис выбирает по языку запроса. `valuePrefix`/`keyPrefix` не переводятся: это
  технические части значения.
- `name_ru`/`description_ru` в основной таблице отвергнуты: третий язык потребует миграции.
  `seed/texts/ru/*.properties` отвергнуты: сидеру пришлось бы писать ресурсы в рантайме, а поиск по
  русскому тексту потерял бы данные.

### Поиск

SQL, не в памяти. 126 строк не требуют индексов, поэтому v1 — `LIKE` по основной таблице и `EXISTS`
по переводам; `pg_trgm` — следующий шаг.

```sql
SELECT p.*
FROM connector_presets p
WHERE p.source_present = TRUE
  AND p.enabled = TRUE
  AND (:connectorCode IS NULL OR p.connector_code = :connectorCode)
  AND (:category IS NULL OR p.category = :category)
  AND (:jurisdiction IS NULL OR p.jurisdiction = :jurisdiction)
  AND (LOWER(p.code) LIKE LOWER('%' || COALESCE(CAST(:search AS text), '') || '%') ESCAPE '\'
       OR LOWER(p.name) LIKE LOWER('%' || COALESCE(CAST(:search AS text), '') || '%') ESCAPE '\'
       OR LOWER(p.description) LIKE LOWER('%' || COALESCE(CAST(:search AS text), '') || '%') ESCAPE '\'
       OR EXISTS (
           SELECT 1 FROM connector_preset_texts x
           WHERE x.preset_id = p.id
             AND (LOWER(x.name) LIKE LOWER('%' || COALESCE(CAST(:search AS text), '') || '%') ESCAPE '\'
                  OR LOWER(x.description) LIKE LOWER('%' || COALESCE(CAST(:search AS text), '') || '%') ESCAPE '\')))
```

- Поиск идёт по обоим языкам: русский пользователь найдёт «Notion» по английскому бренду,
  англоязычный — «Трекер» по русскому переводу.
- `%`, `_` и `\` в запросе экранируются в сервисе перед подстановкой. Пустой поиск — `''`:
  `LIKE '%%'` истинно, поэтому отдельная ветка `:search IS NULL` не нужна, а nullable-параметр не
  ломает вывод типа в native-запросе (`COALESCE(CAST(:search AS text), '')`).
- Сортировка — по локализованному имени, в сервисе после выборки: join ради `ORDER BY` не нужен,
  строк десятки.
- Пагинации нет: записей десятки.
- Если каталог вырастет или поиск попадёт в slow log — `CREATE EXTENSION pg_trgm` и GIN-индексы на
  `lower(name)`, `lower(description)` и колонки переводов. Это отдельное решение: расширение
  ставится на инсталляцию, а не миграцией «на всякий случай».

### Сидер

`ConnectorPresetBootstrap` на `ApplicationReadyEvent` с
`@Order(ConnectorBootstrap.BOOTSTRAP_ORDER + 3)` — после того как `connectors` уже засеяны (нужен FK
на `mcp`).

1. Прочитать все `seed/connectors/<connectorCode>/*.json` и `overrides.yaml`. Перечисление — через
   `PathMatchingResourcePatternResolver` с `classpath*:seed/connectors/*/*.json`: `getFile()` и
   `Files.list` в bootJar падают, а guard-тест на файловой системе этого не поймает. Тот же
   загрузчик использует guard-тест.
2. Разобрать и провалидировать всё **до записи в БД**. Ошибка — падение старта: файлы едут в jar,
   это наш собственный ресурс, а guard-тест ловит ошибку ещё на сборке.
3. Взять transaction-scoped advisory-лок и пометить все строки `source_present = false`
   (bulk-обновление, `updated_at` пишется явно) — частичный уникальный индекс
   `(connector_code, identity) WHERE source_present = TRUE` тогда не мешает переименованию файла,
   когда старая строка уже скрыта. Лок снимается коммитом: на холодном старте две реплики не
   соревнуются за unique по `code`.
4. Upsert по `code`: обновить содержание, **не трогать `enabled`**, выставить `source_present = true`.
   В bulk-записи `updated_at` пишется явно: `@UpdateTimestamp` bulk/`ON CONFLICT` обходит.
5. Заменить переводы для кода: удалить старые строки, вставить RU-строку, если есть
   `nameRu`/`descriptionRu` или оверрайд.
6. Строки, оставшиеся `source_present = false`, скрыты; данные и `enabled` сохранены, если файл
   вернут.
7. Залогировать: файлов прочитано, upsert'ов, переводов, пропущено.

Вывод полей по умолчанию:

- `auth_methods`: `primaryAuth` — `probe.authType` начинается с `oauth2.1` → `OAUTH`; иначе любой
  required secret-заголовок или `declared.headers` с `Authorization` → `TOKEN` (независимо от
  `status`: Yandex Tracker, MoySklad, Yandex Forms дают `up`/`authType = none`, но звонки требуют
  токен); иначе `api_key_or_bearer` → `TOKEN`; иначе `probe.authType = none` → `NONE`; иначе
  `UNKNOWN`. Список = `declared.authMethods` (`oauth2.1` → `OAUTH`, `token` → `TOKEN`,
  `none` → `NONE`), если поля нет — `[primaryAuth]` для `OAUTH`/`TOKEN`/`NONE`, иначе `[]`.
  Затем **фильтр поддержки**: `OAUTH` остаётся, только если
  `authDetails.clientIdMetadataDocumentSupported = true`; `TOKEN` и `NONE` — всегда. Пустой
  результат — `[]`: форма не предлагает недоступный OAuth, а `availability` станет
  `AUTH_UNSUPPORTED`. Отдельного `oauth_support` в базе нет — CIMD проверяется здесь и логируется.
  Если остались и `OAUTH`, и `TOKEN`, форма предлагает выбор «OAuth или токен». Сид **не угадывает**
  `TOKEN` по наличию заголовка: `square` — известный пропуск в данных, пока там нет `authMethods`;
  временный обход — `authMethods: [OAUTH, TOKEN]` в `overrides.yaml`.
- `availability`: вычисляется из `probe.status`, транспорта (`probe.transport`,
  `declared.transport`, `transportConfirmed`), `legacy_sse` и `probe.handshake.supported` по формуле
  §«Доступность карточки». Это единственное, что попадает в базу из этих полей: `status`,
  `transport`, `transport_source`, `legacy_sse`, `handshake_supported` колонками не становятся.
- `header_fields`: обязательные и опциональные заголовки из `declared.headers` с `name`, `label`,
  `labelRu`, `secret`, `required`, `valuePrefix`, `keyPrefix`. `Authorization` с
  `valuePrefix = "Bearer "` и `auth_methods` с `TOKEN` не превращается в заголовок — вместо него
  поле `auth_token`; пустой `valuePrefix` — сырое значение заголовка (пользователь вводит всё,
  включая `krsp_` или `OAuth `); для `OAUTH`-only `Authorization` пропускается независимо от
  префикса (его заполняет OAuth-поток).
  Заголовок с другим `valuePrefix` (`OAuth `, `ApiKey `) или с `keyPrefix` (`op_`, `krsp_`)
  остаётся отдельным полем. `keyPrefix` — подсказка, backend его не добавляет.
- `header_groups`: `declared.headerGroups` (`oneOf`, `required`); `anyOf` из данных `mcp`
  нормализуется в `oneOf`.
- `fields`: для `auth_methods` с `TOKEN` и Bearer (или без объявленного заголовка — дефолт
  коннектора) — `{auth_token: {label, labelRu, required}}`; `required = true`, если `TOKEN` —
  единственный способ, иначе `false` (пользователь может выбрать OAuth). Для `OAUTH`-only/`NONE` —
  пусто; для кастомного `Authorization`/секретного заголовка — пусто, токен вводится в заголовок.
- `params.url`: `probe.endpointUrl`, если это https-URL (это рабочий адрес), иначе `url`.
  `identity = params.url.trim()`. Ручное подключение на исходный `url` не совпадёт с `identity` —
  это ожидаемо; пресет ведёт на рабочий endpoint. Исходный `url`, заканчивающийся `/sse`, — вход
  `availability`, а не колонка.
- `category`: встроенная таблица `DEFAULT_CATEGORIES` в `ConnectorPresetSeedReader` (8 категорий
  датасета → коды таксономии); `categories` в `overrides.yaml` переопределяет отдельный ключ, но
  только известный — опечатка в ключе падает, как и неизвестное значение. В `ContentCategory` код
  приводится через `valueOf(code.toUpperCase(Locale.ROOT))`. Неизвестная категория датасета —
  ошибка сида, чтобы новый раздел не потерялся молча.

`overrides.yaml` — только исключения; встроенный маппинг категорий живёт в ридере:

```yaml
# Необязательный оверрайд встроенной таблицы категорий (ключ — категория датасета):
# categories:
#   dev-tools: development

presets:
  square:
    authMethods: [OAUTH, TOKEN]   # временный обход, пока данные не исправлены
```

Для отдельного пресета доступны ключи `authMethods`, `fields` (`label`, `labelRu`, `required`) и
`headerFields` (`name`, `label`, `labelRu`, `secret`, `required`, `valuePrefix`, `keyPrefix`).
Подписи и префиксы заголовков по умолчанию приходят из `declared.headers`, поэтому оверрайд нужен
только для исключений.

Валидация сида (guard-тест и старт):

- `code` по `[a-z0-9][a-z0-9-]*`, уникален; `name` непустое;
- `params.url` — https, `identity` уникален в рамках коннектора;
- `params` — подмножество несекретных полей коннектора, `fields` — подмножество его
  `credentialFields`; `header_fields` без дублей, имена — валидные HTTP-заголовки,
  `valuePrefix`/`keyPrefix` — строки; пустой `label`/`labelRu` подставляется именем заголовка;
- `availability` — известное значение; `auth_methods` — подмножество `OAUTH`/`TOKEN`/`NONE` без
  дублей (дубли отбрасываются при сиде); список — `declared.authMethods`, иначе `primaryAuth`;
  после фильтра CIMD он может остаться пустым — это `AUTH_UNSUPPORTED`; `AVAILABLE` требует
  непустой `auth_methods`;
- `category` — известный код таксономии; `jurisdiction` — ISO 3166-1 alpha-2 или NULL;
- `auth_methods` с `OAUTH` — только при CIMD (фильтр применён при сиде: без CIMD `OAUTH` в список
  не попадает); с `TOKEN` — либо `fields.auth_token`, либо хотя бы один `header_fields` с
  `secret = true` (не обязательно `Authorization`: у MaxStat `X-API-Token`, у Telphin
  `X-Auth-Token`, у New Relic `api-key`);
  `NONE` не должен иметь обязательных секретных полей;
- `header_groups`: `oneOf` из минимум двух существующих заголовков, без пересечений, элементы не
  помечены `required = true`; `required` — boolean;
- `overrides.yaml` проверяется по точному набору ключей `authMethods`/`fields`/`headerFields`:
  неизвестный ключ — ошибка, а не молчаливое игнорирование (`SeedYaml` сам лишние ключи не ловит);
  override для несуществующего `code` — ошибка.

### API чтения

`GET /manage/connector-presets/` — новый `ManageConnectorPresetController`, путь обязательно
добавляется в `SecurityConfig.JWT_CHAIN_PATHS` (иначе 403 rightful владельцу; `ManagePathsSecurityTest`
пасёт список).

Параметры: `search`, `connectorCode`, `category`, `jurisdiction`. Ответ — массив, не страница:
записей десятки. `connectorCode` в v1 один — `mcp`, но ресурс общий.

`ConnectorPresetResponse` — три блока, а не плоский набор полей:

| Блок | Поля | Для чего |
|---|---|---|
| `card` | `name`, `description`, `iconUrl`, `category`, `jurisdiction`, `auth`, `docsUrl` | витрина и фильтры |
| `form` | `params`, `credentialFields`, `headerFields`, `headerGroups`, `authMethods` | предзаполнение формы |
| `connection` | `identity`, `availability` | «уже подключено» и доступность |

- `code`, `connectorCode` — на верхнем уровне.
- `card.auth` — производный основной способ по уже отфильтрованным `auth_methods`: `OAUTH` → `TOKEN`
  → `NONE` → `UNKNOWN`. Поэтому DCR-сервер со статическим токеном показывает `TOKEN`, а OAuth-only
  DCR даёт `auth = UNKNOWN` и `availability = AUTH_UNSUPPORTED`.
- `connection.identity` — `params.url.trim()`; `connection.availability` — **подсказка**, не гейт
  создания: `AVAILABLE` включает карточку, остальные значения — disabled с текстом по коду. Точную
  причину (`DCR` против `PRE_REGISTERED`, `false` против `null`) бэкенд пишет в debug-лог (файл
  сида, `probe.authType`, транспорт и handshake), а не в ответ.

Слияние `form.credentialFields`:

- `url` скрывается — значение лежит в `params` и показывается строкой в шапке формы;
- `headers` скрывается, если у пресета есть `header_fields` — их рисуют отдельные поля;
- `auth_token` показывается, если `form.authMethods` содержит `TOKEN` и токен идёт в Bearer;
  `required = true` только когда `TOKEN` — единственный способ, иначе `false` (можно выбрать OAuth);
  скрывается для OAuth-only/NONE и для кастомного `Authorization`/секретного заголовка;
- `label` и `required` берутся из оверрайда `fields`, иначе — из декларации коннектора; тип — из
  декларации. `label` уже на языке запроса. `valuePrefix`/`keyPrefix` относятся к `headerFields` и
  применяются фронтом.

`form.headerFields`/`headerGroups` — из `header_fields`/`header_groups`; `form.authMethods` — из
`auth_methods`.

### Доступность карточки

`availability` вычисляется **при сиде**, а не при чтении: входы в базу не переезжают, остаётся один
код. Входы: `status`; `transport = probe.transport`, иначе `declared.transport`;
`transport_source` — `probe.transportConfirmed` → `MEASURED`, иначе
`declared.transport = streamable-http` → `DECLARED`, иначе `probe.transport` не NULL → `INFERRED`,
иначе `UNKNOWN`; `legacy_sse` — исходный `url` заканчивается `/sse`; `handshake_supported` —
`probe.handshake.supported`; `auth_methods` — уже отфильтрованный список.

```
если status = DOWN                                    -> DOWN
иначе если transport != STREAMABLE_HTTP               -> TRANSPORT_UNSUPPORTED
иначе если не (transport_source = MEASURED
              или transport_source = DECLARED
              или (status = AUTH_REQUIRED и transport_source = INFERRED и legacy_sse = FALSE))
                                                      -> TRANSPORT_UNSUPPORTED
иначе если status != AUTH_REQUIRED и handshake_supported != TRUE
                                                      -> PROTOCOL_UNSUPPORTED
иначе если auth_methods пуст                         -> AUTH_UNSUPPORTED
иначе                                                 -> AVAILABLE
```

- `transport_source = MEASURED` у 24 `up`; у 90 `auth_required` источник `INFERRED` по природе 401,
  поэтому для них он допускается, но только если исходный `url` не заканчивался `/sse`
  (`legacy_sse = FALSE`). Все 13 `auth_required`-серверов с исходным `/sse` — восемь (`egnyte`,
  `globalping`, `monday-com`, `paypal`, `plaid`, `square`, `webflow`, `wix`) и пять, у которых probe
  уже нашёл `/mcp` (`cloudflare-workers-bindings`, `dodo-payments`, `invideo`, `semgrep`, `sentry`),
  — ведут на `/mcp`; 12 из них без `declared.transport` (`transport_source = INFERRED`) блокируются.
  `cloudflare-workers-bindings` объявил transport (`DECLARED`), но остаётся DCR-only. По auth прошли
  бы `monday-com`, `dodo-payments`, `invideo`, `semgrep`, `sentry` (CIMD) и `square` (токен после
  исправления `authMethods`); остальные — DCR-only.
- `handshake_supported` проверяется для `up`: 17 серверов принимают `2025-06-18` (13 handshake-
  серверов и 4 stateless), `aws-knowledge` и `vkusvill` — `false`, пять (`apify`, `yandex-calendar`,
  `yandex-disk`, `yandex-mail`, `yandex-telemost`) — `null`, потому что `initialize` закрыт 401.
  `up` с `false`/`null` не обещаем; у `auth_required` handshake неизвестен по природе, поэтому он
  не блокирует.
- `auth_methods` с `TOKEN` открывает 20 DCR-серверов со статическим токеном; `square` — известный
  пропуск в данных, пока `declared.authMethods` не добавлен. `OAUTH` без CIMD и без `TOKEN`
  остаётся заблокированным.
- CIMD — необходимое, но не достаточное условие: `McpAuthDiscovery` ещё требует S256 PKCE и
  `authorization_endpoint`/`token_endpoint`. Датасет PKCE/endpoints не хранит; окончательная
  проверка — при создании. `availability` для OAuth остаётся best-effort.
- `availability` — **подсказка для карточки**, а не политика создания: если пользователь с валидным
  токеном откроет пресет, помеченный `AUTH_UNSUPPORTED`, живой `validateCredentials` всё равно решит.
  Поэтому создание не проверяет `availability` (см. §«Создание подключения»).

`availability` — код для локализации и disabled-состояния, всего пять значений:

- `AVAILABLE` — карточка активна, форма открывается;
- `DOWN` — `status = DOWN`;
- `TRANSPORT_UNSUPPORTED` — `transport != STREAMABLE_HTTP` или `transport_source`/`legacy_sse` не
  проходят условие выше;
- `PROTOCOL_UNSUPPORTED` — `up` с `handshake_supported != TRUE` (`false` или `null`);
- `AUTH_UNSUPPORTED` — `auth_methods` пуст: ни `NONE`, ни `TOKEN`, ни `OAUTH + CIMD` не доступны.

Приоритет: `DOWN` → `TRANSPORT_UNSUPPORTED` → `PROTOCOL_UNSUPPORTED` → `AUTH_UNSUPPORTED`. Точную
причину (`DCR` против `PRE_REGISTERED`, `false` против `null`) бэкенд пишет в debug-лог, а не в
ответ: фронту она не нужна. Тексты — забота фронта; бэкенд не переводит причины.

### Создание подключения

Отдельного контракта создания у пресета нет. Фронт берёт `form` из ответа каталога, предзаполняет
обычную форму подключения и отправляет стандартный запрос — без `presetCode`:

```jsonc
{
  "connectorCode": "mcp",
  "credentials": {
    "url": "https://mcp.bitrix24.tech/mcp/",
    "auth_token": "...",
    "headers": "{\"X-Org-Id\":\"...\"}"
  },
  "name": "Bitrix24"
}
```

- `credentials` — ровно тот формат, который уже понимает `McpConnectorService`: `url`, `auth_token`,
  `headers` JSON. Фронт собирает `headers` из `form.headerFields`/`headerGroups`, применяя
  `valuePrefix`; `keyPrefix` — подсказка, её не добавляют. Бэкенд не собирает заголовки.
- `CreateConnectionRequest`, `ConnectionCreateCommand` и `ConnectionService` **не меняются**. Пресет —
  источник значений формы, а не объект запроса: доказывать, что URL пришёл из каталога, не нужно.
  После подстановки пользователь может поправить любое поле, как при ручном подключении.
- Required-поля и `header_groups` — забота формы; бэкенд не дублирует её. Неполный набор поймает
  живой `validateCredentials` — ошибкой или `authorization_required`; для API-клиентов приемлемо.
- `preset.connection.availability` — подсказка для карточки, а не политика создания: создание его не
  проверяет. Пользователь с валидным токеном вправе подключить сервер, помеченный
  `AUTH_UNSUPPORTED`.
- `connections.preset_code` не добавляется: `subCode` уже равен `url`, фронт сопоставляет
  подключение с пресетом сравнением `connection.subCode === preset.connection.identity`.
- OAuth-поток не меняется: `POST /manage/connections/` вернёт `status = authorization_required` и
  `authorizeUrl`, дальше — существующие `authorize`/`oauth/complete`.

### Что делает фронт

- Один запрос каталога при открытии страницы; поиск — дебаунсенный `?search=`, фильтры
  `?category=`/`?jurisdiction=`. Ответ — `card`/`form`/`connection`.
- Карточки пресетов идут в первой сетке рядом с коннекторами; `connection.availability` — бейдж
  доступности. `AVAILABLE` открывает форму; остальные значения — disabled с текстом по коду
  (`DOWN`, `TRANSPORT_UNSUPPORTED`, `PROTOCOL_UNSUPPORTED`, `AUTH_UNSUPPORTED`).
- Выбор пресета открывает `ConnectionSetupForm` из `form`: `params`, `credentialFields`,
  `headerFields` (с `valuePrefix`/`keyPrefix`/`label`), `headerGroups` как выбор «ровно один»,
  `authMethods`; URL показывается строкой в шапке.
- Если `form.authMethods` содержит и `OAUTH`, и `TOKEN`, форма показывает выбор «OAuth или токен»;
  для одного способа — сразу его.
- `headerFields`/`headerGroups`: фронт применяет `valuePrefix` к введённому значению, показывает
  `keyPrefix` как подсказку и собирает `credentials.headers` JSON. `keyPrefix` не добавляется.
- Создание: обычный `POST /manage/connections/` с `credentials` и `name`; `presetCode` не
  передаётся, `headers` — JSON в `credentials`, как при ручном подключении.
- OAuth-пресет: `POST` вернёт `status = authorization_required` и `authorizeUrl`; фронт делает
  `POST` по этому адресу (`/manage/connections/{id}/authorize`), открывает полученный URL, а возврат
  завершает `POST /manage/connections/oauth/complete`. Без этого коннекция остаётся `PENDING_AUTH`.
- «Уже подключено»: `connection.subCode === preset.connection.identity`; вторая учётная запись
  разрешена.
- Deep link: маршрут согласовать с фронтом; в platform-коннекторе уже используется
  `/connections/new?connector=` (см. `PlatformConnectionToolService`), в документе —
  `/dashboard/connections/create?connector=mcp&preset=github`.

Дельта для фронта выносится в `docs/tmpspec/connector-presets-frontend.md` (gitignored), как принято.

### Публичный каталог

Каталог этих пресетов нужен и без авторизации — для страницы сайта и поискового продвижения, как
[public-catalog.md](public-catalog.md). Отдельный префикс не заводим: два `GET` в существующем
`PublicCatalogController` (`PATH = /public/catalog`), публичная security-цепочка уже `permitAll`:

- `GET /public/catalog/connector-presets/` — список с `search`, `category`, `jurisdiction`;
- `GET /public/catalog/connector-presets/{code}` — карточка.

Ответ — отдельный `CatalogConnectorPresetResponse`, не `ConnectorPresetResponse` и не его урезанная
версия: `code`, `connectorCode`, `card`, `connection` (`availability`). Без `form`, `params`,
`credentialFields`, `headerFields`, `headerGroups`, `identity` — публичная страница не показывает
форму и не раскрывает внутренние поля. CTA ведёт на регистрацию/логин с `?preset=<code>`; после
авторизации приложение запрашивает `/manage/connector-presets/` и получает `form`.

`Cache-Control: public, max-age=300`, `Vary: Accept-Language` — те же правила, что у остального
публичного каталога. Язык — из `Accept-Language`, который фронт ставит по локали маршрута. Поиск и
фильтры — тот же `ConnectorPresetQueryService`; отдельной публичной копии запроса нет.

`availability` в публичном ответе — та же подсказка, а не обещание: гость всё равно уходит на логин,
а окончательное решение принимает живой probe при создании. Публичный DTO отдельный, чтобы новое
поле в manage-ответе не утекло наружу молча.

## План

**Схема**
- [x] Миграция `updates/2026/11/13-00-connector-presets.xml`: таблицы, FK, partial unique
  `(connector_code, identity) WHERE source_present`, check-констрейнты `availability`/`jurisdiction`/
  `auth_methods`/`header_groups`, `created_at`/`updated_at` у обеих таблиц; комментарий про
  денормализацию `identity = params->>'url'`.
- [x] Сущности `ConnectorPreset`, `ConnectorPresetText`, `BaseEntity`-поля, JSONB-модели
  `PresetFieldOverride`, `PresetHeaderField` (`label`/`labelRu`/`valuePrefix`/`keyPrefix`),
  `PresetHeaderGroup` (`id`/`oneOf`/`required`).
- [x] Enum'ы `ConnectorPresetAuth`, `ConnectorPresetAvailability`; `category` — `ContentCategory`;
  `auth_methods` — `List<ConnectorPresetAuth>`.
- [x] Репозитории: поиск с `LIKE`/`EXISTS` (экранирование — в сервисе), выборка переводов по
  `preset_id` и языку.

**Сид**
- [ ] Раскладка `seed/connectors/mcp/<slug>.json` + `overrides.yaml`.
- [x] Загрузчик `ConnectorPresetSeed`: `PathMatchingResourcePatternResolver` с
  `classpath*:seed/connectors/*/*.json` (jar-safe), JSON + overrides, маппинг полей:
  `authMethods` → отфильтрованный `auth_methods`, `headerGroups` → `header_groups`,
  `headers[].valuePrefix`/`keyPrefix`/`label`/`labelRu`; `status`/транспорт/handshake → `availability`.
- [x] `PresetAvailabilityPolicy`: чистая функция `availability` по `status`, транспорту,
  `transport_source`, `legacy_sse`, `handshake_supported` и `auth_methods`; вызывается при сиде.
- [x] Валидатор: `params`/`fields`/`header_fields`/`header_groups`, `identity`, категории,
  `jurisdiction`, enum'ы, `name`; `auth_methods` подмножество (дубли и `OAUTH` без CIMD
  отбрасываются), `availability`; `valuePrefix`/`keyPrefix`; точный набор ключей `overrides.yaml`
  (`categories`/`presets`, `authMethods`/`fields`/`headerFields`), запрет override для
  несуществующего `code` и ключа категории.
- [x] `ConnectorPresetBootstrap` на `ApplicationReadyEvent`: `@Order`, advisory-лок от гонки
  реплик, двухфазный `source_present`, upsert без `enabled`, явный `updated_at` в bulk, замена
  переводов, лог.
- [x] Guard-тест сида на сборке (без Spring и БД), тот же загрузчик.

**Чтение**
- [x] `ConnectorPresetQueryService`: запрос, фильтры, локализация `name`/`description`/`label`.
- [x] `PresetFormAssembler`: слияние `credentialFields` коннектора и `fields`, `headerFields`,
  `headerGroups`, `authMethods`.
- [x] `ConnectorPresetResponse` (`card`/`form`/`connection`) + `HeaderFieldResponse`
  (`valuePrefix`/`keyPrefix`) + `HeaderGroupResponse`.
- [x] `ManageConnectorPresetController` (`PATH = /manage/connector-presets`).
- [x] `SecurityConfig.JWT_CHAIN_PATHS` + `ManagePathsSecurityTest`.
- [x] Публичный каталог: два `GET` в `PublicCatalogController`, отдельный
  `CatalogConnectorPresetResponse`, `Cache-Control`/`Vary`; путь уже в `permitAll`, `JWT_CHAIN_PATHS`
  не меняется.

**Создание**
- [x] Не меняется: `CreateConnectionRequest`, `ConnectionCreateCommand`, `ConnectionService` остаются
  прежними; фронт предзаполняет стандартную форму и отправляет обычный `POST`.
- [ ] Прогнать живой `validateCredentials` на пресетах с `header_fields`/`header_groups`: токен и OAuth.

**Тесты**
- [x] `ConnectorPresetSeedReaderTest` + guard-тест — полнота и валидность сида, `auth_methods`/
  `header_groups`, `valuePrefix`/`keyPrefix`, `availability`.
- [x] `ConnectorPresetBootstrapTest` — upsert, сохранение `enabled`, `source_present`, переводы,
  переименование файла при том же `identity`, `auth_methods`/`availability`, advisory-лок перед
  скрытием.
- [x] `PresetAvailabilityPolicyTest` — CIMD/token, `transport_source`/`legacy_sse`, handshake
  false/null, четыре причины и `AVAILABLE`.
- [x] `PresetFormAssemblerTest` — слияние, локализация подписей по языку запроса,
  `auth_token.required`, `headerGroups`.
- [x] `ConnectorPresetQueryServiceTest` — поиск, фильтры `category`/`jurisdiction`, язык, fallback.
- [x] `ManageConnectorPresetControllerTest`.
- [x] `PublicCatalogController` — публичный каталог без токена, без `form`, `Cache-Control`/`Vary`.
- [ ] Проверить миграцию и ограничения на клоне локальной базы.

**Документация**
- [x] `docs/architecture/connectors.md` — раздел «Пресеты подключений MCP».
- [x] `docs/services/control-api.md` — manage- и публичный эндпойнты пресетов; создание подключения не
  меняется.
- [x] `docs/decisions/public-catalog.md` — публичные эндпойнты и DTO пресетов подключений.
- [x] `docs/decisions/language-catalogs.md` — перекрёстная ссылка на исключение для пресетов.
- [x] `docs/README.md` — строка решения.
- [x] `docs/tmpspec/connector-presets-frontend.md` — дельта для фронта.

**Выкатка**
- [ ] control-api первым; worker, drain и схема очередей не меняются.
- [ ] Скопировать `mcp/servers/*.json` в `seed/connectors/mcp/`, проверить сид на локальной базе.
- [ ] Данные MCP перед копированием: добавить `authMethods` у `square`, заголовок у
  `stack-overflow`, `declared.transport` для 12 `auth_required` с исходным `/sse`; пять `up` с
  `handshake.supported = null` остаются «не проверено».
- [ ] Проверить публичный каталог без токена: `Cache-Control`, `Vary`, отсутствие `form`.
- [ ] Прогнать `./gradlew build`.

## Вне рамок

- **DCR-фолбэк и pre-registered клиенты.** 25 DCR-серверов без статического токена (плюс `square`,
  пока данные не исправлены) и 4 pre-registered остаются `AUTH_UNSUPPORTED`. Ещё 20 DCR-серверов
  уже открыты через `auth_methods`. Это самый большой рычаг каталога, но отдельное решение: меняется
  `McpAuthDiscovery`/`McpOAuthClient`.
- **Клиент MCP на 2026-07-28 (stateless).** Текущий `McpClient` говорит `2025-06-18`; `aws-knowledge`
  и `vkusvill` его не принимают, пять stateless-серверов не проверены. Отдельное решение: научить
  клиент новой эре, иначе эти карточки останутся закрытыми.
- Админская форма пресетов: `enabled` правится в базе, отдельного API нет.
- Рантайм-синк с `mcp` и собственный probe.
- `connections.preset_code` и связь подключения с пресетом в DTO.
- Предпросмотр тулов на карточке (полный `tools` не храним).
- Копирование иконок в статику бэкенда: иконки есть у всех 126, но это хотлинк на чужие хосты
  (включая `icon.horse`, `yastatic.net`, `i0.wp.com`, `gstatic`); при необходимости — копировать в
  статику по `code`, монограмма остаётся fallback.
- Новые категории таксономии: только маппинг в существующие 11.
- Пагинация каталога.
- `vendor`, `toolCount`, `lastChecked`, `note` — остаются в JSON датасета, в БД и ответ не входят:
  для выбора и предзаполнения формы не нужны. Вернуть точечно, если появится продуктовый бейдж.
- `official`, теги, `credentialsUrl`.
- `pg_trgm`: включается, когда каталог вырастет или поиск станет горячим.
- Поддержка SSE: транспорт MCP-коннектора — только Streamable HTTP.

## Открытые хвосты

- DCR: когда делать и в каком объёме — отдельным решением; от него зависит, станет ли
  `availability = AVAILABLE` у 25 DCR-карточек без токена и 4 pre-registered. Ещё 20 DCR-серверов уже
  открыты статическим токеном.
- `availability` — подсказка, не гейт: PKCE и endpoint'ы OAuth-поток обнаруживает сам; протокол и
  транспорт сводятся в `availability` при сиде, но `auth_required` не позволяет подтвердить их без
  креденшелов. Окончательное решение — `validateCredentials` при создании.
- **Данные MCP:** `square` без `authMethods`; `stack-overflow` без заголовка; 12
  `auth_required`-серверов с исходным `/sse` без `declared.transport` — восемь (`egnyte`,
  `globalping`, `monday-com`, `paypal`, `plaid`, `square`, `webflow`, `wix`) и четыре, у которых
  probe нашёл `/mcp` (`dodo-payments`, `invideo`, `semgrep`, `sentry`); если добавить
  `declared.transport`, `transport_source = DECLARED` снимет транспортный блок (но не OAuth DCR).
  Из них `monday-com`, `dodo-payments`, `invideo`, `semgrep`, `sentry` (CIMD) и `square` (токен)
  прошли бы по auth. Ещё 5 `up` с `handshake.supported = null` (`apify`, `yandex-calendar`,
  `yandex-disk`, `yandex-mail`, `yandex-telemost`). `cloudinary` с опциональным API-key не
  выражается текущей схемой: `auth_methods` с `TOKEN` требует обязательного secret-заголовка.
- `toolCount` измерен/объявлен у 24 из 126; в БД и на карточке не нужен — остаётся в JSON.
- Иконки: хотлинк на CDN вендора — это утечка факта просмотра и зависимость от чужой доступности;
  если станет важно, копировать в статику по `code`.
- `note` остаётся английским текстом в JSON; в ответ не входит. Если предупреждения понадобятся
  пользователю, вернуть отдельным структурированным полем с переводом, а не свободным текстом.
- RU-переводы есть у 57 из 126 серверов; остальные показывают английский бренд/описание. Полнота
  перевода — вопрос наполнения `overrides.yaml`/`declared.headers`, а не схемы.
