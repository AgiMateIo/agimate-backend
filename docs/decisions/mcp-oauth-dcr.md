---
status: partial
created: 2026-11-24
updated: 2026-11-24
---

# DCR-фолбэк в OAuth MCP-коннектора: клиент в гранте

[mcp-oauth.md](mcp-oauth.md) реализовал CIMD и сознательно отказался от DCR. Этот документ
возвращает DCR как фолбэк для серверов без CIMD. DCR-клиент живёт в шифрованном секрете
коннекции вместе с грантом: отдельной таблицы регистраций, её жизненного цикла и фоновой уборки
нет. CIMD остаётся основным путём.

Повод — не принцип, а два факта. В каталоге `mcp/servers` из 68 OAuth-серверов CIMD объявляют 18,
DCR-only — 46, pre-registered требуют 4 ([connector-presets.md](connector-presets.md)). Живой
пример: `legaldatahunter.com` отдаёт `registration_endpoint`, PKCE S256 и `scopes: ["mcp"]`, но не
объявляет CIMD, — единственный автоматический путь к нему сегодня закрыт.

DCR объявлена устаревшей в MCP `2026-07-28`; это не отменяет её здесь, а задаёт бюджет:
реализация минимальная, без management-API, который завтра выпиливать. CIMD остаётся первым
выбором, DCR — вторым.

**Что не входит:** RFC 7592 (`registration_access_token`, update/delete), pre-registered клиенты,
поля ручного ввода `client_id`/`client_secret`, DCR на стороне нашего MCP-сервера — это отдельное
решение [mcp-server-oauth.md](mcp-server-oauth.md).

## Что показал спайк

2026-11-24 на `legaldatahunter.com` вручную проверен весь путь до страницы согласия:

| Шаг | Результат |
|---|---|
| PRM | `resource: https://legaldatahunter.com/mcp`, AS — он же, scope `mcp` |
| Метаданные AS | `registration_endpoint`, grant types `authorization_code` + `refresh_token`, PKCE `S256`, auth methods `none` / `client_secret_basic` / `client_secret_post`, CIMD нет |
| Регистрация | `POST /oauth/register` с `application_type: web`, https-redirect, `client_secret_basic`, scope `mcp` → `201` |
| Тело ответа | только `client_id`, `client_secret`, `redirect_uris`, `client_name` |
| Token endpoint | dummy code + Basic → `400 invalid_grant` («code unknown or expired»): клиентская аутентификация прошла |
| Authorize | URL с `client_id`, redirect, PKCE, `resource`, `scope=mcp` → `200`, страница «Authorize — Legal Data Hunter», показывает имя клиента и просит войти |

Ответ регистрации не содержит `token_endpoint_auth_method`, `client_secret_expires_at`,
`grant_types`, `response_types`, `scope`, `application_type`, `registration_access_token` или
`registration_client_uri`. Это не ошибка AS, а реальный минимализм: парсер обязан быть терпимым.
Секрет тестового клиента удалён; клиент остался сиротой у AS, без гранта. Полный обмен кода не
проверялся — для него нужен логин пользователя; это живой прогон, а не спайк.

## Почему не таблица

Первоначальный вариант хранил DCR-регистрацию в `connection_oauth_clients` — строке на коннекцию.
Три ревью — протокол, архитектура, безопасность — нашли там 1 блокер и 14 мажоров, и почти все они
были про жизненный цикл этой строки: `stale`, `force`, `PENDING`, замена `client_id`, условный
`UPDATE`, гонка «строка без секрета», уборка, уникальные индексы. Это сигнал не «починить строку», а
«строке здесь не место».

Причина в том, что одна изменяемая строка вынуждена быть двумя фактами сразу:

- идентичностью клиента, которым выдан **активный грант** (refresh-токен привязан к нему);
- идентичностью клиента, которым начинается **новая попытка** авторизации.

Пока пользователь не переавторизуется, это совпадает. В момент переподключения — нет. Все флаги и
условные обновления были попыткой примирить два факта в одном месте.

Секрет коннекции уже шифрован и уже хранит грант. Клиентская идентичность — такой же факт
коннекции, как refresh-токен. Поэтому:

- **грант владеет клиентом**, которым выдан: `oauth_client_source` (маркер механизма),
  `oauth_client_id`, optional `oauth_client_secret`, метод, scope регистрации, срок секрета;
- **поток владеет клиентом**, которым начата конкретная попытка: те же ключи с префиксом
  `oauth_flow_`;
- при успешном обмене flow-ключи переезжают в грант;
- при неуспешном грант не трогается, кроме точечной очистки отвергнутого клиента (ниже).

`oauth_client_source` — маркер механизма, а не креденшл: при очистке отвергнутого клиента он
сохраняется, иначе DCR-грант прочитается как CIMD.

Ни таблицы, ни миграции, ни фоновой уборки. Одна развилка — `client_secret` оказывается в
`ConnectionCredentialStore` и, значит, в `ConnectorEnv.credentials()`. Раньше этот барьер держали;
здесь он снят осознанно: в том же map уже лежат access/refresh-токены, шифрование то же
(`SecretService`), наружу и агенту map не отдаётся, `updateSecret` для OAuth закрыт, а клиентский
секрет теперь per-connection, то есть радиус — одна коннекция. Если барьер когда-нибудь станет
обязательным, секрет можно вынести отдельной строкой `secrets`, а в map держать только id; это
добавит lifecycle секрета, но не таблицу регистраций.

## Принцип и ИКР

**Пользователь владеет согласием и подключением; система владеет клиентской регистрацией.** Он не
видит `client_id`, секрет, регистрацию и перерегистрацию. Он видит подключение и одну кнопку:
«Авторизовать» или «Переподключить».

**ИКР.** Поток авторизации не знает, откуда взялась клиентская идентичность: CIMD — из конфига,
DCR — из гранта или из новой регистрации. Регистрация появляется только тогда, когда её нельзя
переиспользовать, и живёт вместе с грантом.

**Ресурсы.** `OAuthSetup` как снимок discovery, `SecretService` для шифрования, `OAuthHttpClient`
как единственная HTTP-дверь, stateless `McpOAuthClient`, refresh-джоба, `state`/verifier и
`dcrSupported` в probe сида. Новых механизмов не заводим.

**Две модели идентичности названы явно.** CIMD stateless и принадлежит установке; DCR принадлежит
коннекции и живёт в её гранте. Это не непоследовательность, а разные механизмы.

## Discovery

`McpAuthDiscovery` перестаёт требовать CIMD безусловно:

1. `client_id_metadata_document_supported: true` **и** `app.connectors.mcp.oauth.client-id` задан →
   CIMD;
2. иначе `registration_endpoint` есть **и** `app.connectors.mcp.oauth.dcr-enabled` → DCR;
3. иначе — отказ с текстом про статический токен.

Пустой `client-id` отключает только CIMD, а не OAuth: если AS умеет DCR, коннекция создаётся и
работает. Если не умеет ни того, ни другого, отказ происходит **при создании**, а не первым кликом:
мёртвая `PENDING_AUTH`-коннекция не появляется. Отказ различает причины: нет ни CIMD, ни DCR —
«нужен статический токен»; CIMD объявлен, но `client-id` пуст и DCR недоступен — «CIMD не настроен,
задайте client-id или включите DCR»; `dcr-enabled=false` — «DCR выключен на установке». Один текст
«does not support CIMD» не годится.

PKCE S256 остаётся обязательным: DCR снимает гейт CIMD, но не гейт PKCE.

`OAuthSetup` получает `ClientIdentification`: выбранный источник (`CIMD`/`DCR`),
`registration_endpoint` и `token_endpoint_auth_methods_supported`; объявлен ли CIMD — вход
discovery, после выбора source отдельно не хранится. В секрете setup это плоские ключи:
`oauth_client_source`, `oauth_registration_endpoint`, `oauth_token_endpoint_auth_methods`
(space-separated; пусто = список не объявлен). Это снимок discovery, как и остальные поля setup.
Старые секреты новых ключей не содержат; отсутствие `oauth_client_source` читается как `CIMD` —
OAuth-коннекция не могла родиться до этой фичи. Новый discovery всегда пишет source явно, поэтому
«нет source» и «source = DCR» различимы; миграции данных нет.

Снимок заморожен: `registration_endpoint` и флаги не перечитываются при переавторизации. Переезд
эндпойнта или AS лечится пересозданием коннекции; ленивый re-discovery — отдельное улучшение, не
часть этого решения.

## Регистрация

`DynamicClientRegistrar` — stateless HTTP-половина: POST JSON на `registration_endpoint` через
`OAuthHttpClient.postJson` с теми же гарантиями: https, `PublicOnlyHttp`, отказ от редиректов,
`User-Agent` атрибуции.

### Запрос

- `application_type` — **MUST** по MCP `2026-07-28`. `web` для https-redirect, `native` для
  http-loopback (`localhost`, `127.0.0.1`, `[::1]`). Для http вне loopback — отказ до запроса:
  такой redirect не является ни web, ни native.
- `client_name`: `AgiMate`.
- `redirect_uris`: [адрес возврата установки].
- `grant_types`: `["authorization_code", "refresh_token"]`.
- `response_types`: `["code"]`.
- `token_endpoint_auth_method`: для `web` наш control-api — серверное приложение, поэтому
  предпочитаем `client_secret_basic`, затем `client_secret_post`, и только если AS не умеет ничего
  другого — `none`. Для `native` (http-loopback) метод — `none`: native-клиент по RFC 8252
  публичный, `client_secret_basic` с ним противоречит RFC 7591, и строгий AS ответит
  `invalid_client_metadata`. Если AS не поддерживает `none` для native, это не production-путь —
  отказ. Если список методов не объявлен, дефолт RFC 8414 — `client_secret_basic`. Спайк
  подтвердил: AS поддерживает все три, Basic принят, секрет выдан.
- `scope`: `setup.scope()` без `offline_access`; если пусто — параметр не отправляем.
  `offline_access` — это authorization/token-запрос, не метаданные клиента; отправка его в
  регистрацию рискует `invalid_client_metadata` у строгого OIDC-AS.

### Ответ

Успех — любой 2xx (RFC 7591 отвечает `201 Created`). Обязателен только JSON-объект с непустым
строковым `client_id`. Остальное — по фактическому поведению, а не по формальному RFC:

- `client_secret` есть → клиент confidential. Метод — запрошенный, если ответ не объявил свой. Если
  ответ объявил `none` при наличии секрета или `client_secret_basic`/`client_secret_post` без
  секрета — несогласованный ответ, отказ. Если ответ объявил basic/post и секрет есть — используем
  объявленный; если объявленный метод нами не поддерживается — отказ. Секрет, выданный на запрос
  `none` без объявленного метода, сохраняется, но не используется: так делают некоторые AS.
- `client_secret` нет → клиент публичный; метод должен быть `none`. Если запрошен basic/post или
  ответ объявил basic/post без секрета — отказ.
- `token_endpoint_auth_method` отсутствует → используем запрошенный метод. Спайк показал, что так
  делает реальный AS.
- `client_secret_expires_at`: отсутствует → срок неизвестен, `NULL`; `0` → `NULL`; положительное
  значение — epoch seconds. Храним в flow-ключах и переносим в грант при успехе. Суточную джобу по
  сроку не заводим: истёкший секрет проявится как `invalid_client`.
- `redirect_uris` присутствует → нормализуем scheme/host/port/path и проверяем, что наш URI там
  есть; точное строковое сравнение отвергнет AS, который нормализовал URI. Отсутствует — доверяем
  запросу. `scope` в ответе, если он есть, становится `oauth_client_scope`; иначе запрошенный.
  `grant_types` и `response_types` не требуем.
- `registration_access_token` и `registration_client_uri` игнорируем, не храним и не логируем.

Тела ответов не логируются; успешное тело содержит секрет. `ClientIdentity.toString()` редактирует
секрет; на компонент секрета стоит `@JsonIgnore`, чтобы сериализация record'а не обошла redaction.

### Ошибки и границы

- 4xx на регистрации — постоянная ошибка: `invalid_redirect_uri`, `invalid_client_metadata`,
  защищённый registration endpoint (initial access token мы не поддерживаем). Наружу — issuer,
  HTTP-статус и OAuth-код, без тела, `client_id` и секрета.
- 5xx и сетевые ошибки — транзиентные; пользователь может повторить.
- `registration_endpoint` — remote-controlled metadata. Хост может отличаться от issuer, как у
  token/authorization endpoints; это осознанная модель доверия AS. Origin логируется через
  `McpUtils.logTarget`, не сырым URL с query.

## Идентичность и токен-эндпойнт

`ClientIdentity` — record: `source` (`CIMD`/`DCR`), `clientId`, `clientSecret` (nullable),
`tokenEndpointAuthMethod`.

`McpOAuthClient` остаётся stateless и без БД. Он принимает `ClientIdentity` аргументом:

- `authorizationUrl(setup, identity, state, verifier)` — только `client_id`;
- `exchangeCode`/`refresh` — `client_secret_basic` (заголовок `Authorization: Basic …`,
  form-urlencoding `client_id` и секрета по RFC 6749 §2.3.1, `client_id` в теле не дублируется),
  `client_secret_post` (поля в теле) или `none` (только `client_id`).

Два `@Value`-поля (`client-id`, `redirect-uri`) переезжают в `McpOAuthProperties`. `client-id`
обязателен только для CIMD; `redirect-uri` — всегда. `requireConfigured` больше не требует
`client-id` безусловно: для DCR достаточно `redirect-uri` и `dcr-enabled`.

`OAuthHttpClient.postForm` получает вариант с заголовками (для Basic); `postJson` — для регистрации.
Оба используют общий дедлайн (`PublicOnlyHttp.restClient(readTimeout, deadline)`) и ограниченное
чтение тела: 64 КиБ для метаданных и регистрации, 1 МиБ для токенов. `Content-Length` недостаточно —
тело может быть chunked; читаем поток с потолком и обрываем превышение. Это отдельная небольшая
правка общей HTTP-двери, не часть модели DCR.

## Поток

### Ключи

Грант (в секрете коннекции):

- `oauth_client_source` — `CIMD`/`DCR`; маркер механизма, не очищается при отвергнутом клиенте;
- `oauth_client_id`, `oauth_client_secret` (DCR), `oauth_client_auth_method`;
- `oauth_client_scope` — scope, с которым DCR-клиент зарегистрирован (nullable);
- `oauth_client_secret_expires_at` — epoch seconds или отсутствует.

Поток (там же, на время авторизации):

- `oauth_flow_client_source`, `oauth_flow_client_id`, `oauth_flow_client_secret`,
  `oauth_flow_client_auth_method`, `oauth_flow_client_scope`,
  `oauth_flow_client_secret_expires_at`.

Setup (там же, снимок discovery): `oauth_client_source`, `oauth_registration_endpoint`,
`oauth_token_endpoint_auth_methods` (space-separated).

Остальные ключи (`oauth_code_verifier`, `oauth_scope_requested`, токены, `oauth_scope_granted`)
остаются как есть.

### `startAuthorization`

1. CIMD (`oauth_client_source` setup = `CIMD` или ключа нет — старый секрет): идентичность из
   конфига, метод `none`, секрета нет; flow-ключи заполняются из конфига.
2. DCR (`oauth_client_source` setup = `DCR`):
   - клиент гранта, если он есть, срок секрета не истёк (отсутствие срока — не истёк) и
     `oauth_client_scope` покрывает запрошенный scope без `offline_access`; пустой
     `oauth_client_scope` — «неизвестно» и годится только при пустом запрошенном scope;
   - иначе клиент потока (брошенная попытка) при тех же условиях;
   - иначе — зарегистрировать нового и заполнить flow-ключи.
3. Сгенерировать `state` и PKCE-verifier; записать `state`/expiry, verifier и flow-ключи одной
   короткой транзакцией. Грант не трогать.
4. Построить URL авторизации с `client_id` потока и `setup.scope()`; `resource` — как сейчас.

Источник механизма берётся из setup; `oauth_client_source` гранта описывает, чем выдан грант, и
нужен refresh. Поэтому очистка клиента не превращает DCR-коннекцию в CIMD.

Регистрация происходит только когда ни грант, ни flow-клиент не годятся: клиента нет, срок секрета
истёк, scope не покрыт или зарегистрированный scope неизвестен при непустом запросе. Смерть клиента
у AS старт не видит: её обнаруживает refresh или очистка на `complete` (ниже); до этого старт может
переиспользовать мёртвого клиента — см. «Риски». Обычное переподключение работающей коннекции не
создаёт нового клиента и не показывает новое согласие.

### `completeAuthorization`

1. Взять flow-идентичность и verifier из секрета; если их нет — «начните авторизацию заново».
2. Проверить `iss` (RFC 9207), обменять код идентичностью потока.
3. Успех — записать токены, перенести flow-ключи (включая срок секрета) в грант, очистить
   flow-ключи и verifier, `AUTHORIZED`, expiry. Старый refresh-токен и `oauth_scope_granted` не
   наследуются: `withGrant` удаляет их, новые ставятся только из ответа.
4. `invalid_grant` — код мёртв: транзакция откатывается, flow-ключи остаются и будут перезаписаны
   следующим стартом; грант не тронут.
5. `invalid_client` — клиент потока отвергнут. Транзакция откатывается; flow-ключи остаются. Затем
   **вне откатившейся транзакции** вызвать `McpOAuthStore.clearRejectedClient(connectionId)` с
   `REQUIRES_NEW`: он читает секрет напрямую, а строку коннекции — без `FOR UPDATE`, чтобы не встать
   на lock внешнего `complete`; пока внешний держит этот lock, другие писатели секрета ждут, поэтому
   гонки с refresh нет. Он сравнивает `oauth_flow_client_id` и `oauth_client_id`;
   если совпали — удаляет клиентские креденшлы гранта (`id`, secret, method, `oauth_client_scope`,
   expiry, refresh-токен и `oauth_scope_granted`), сохраняя `oauth_client_source`; если flow-клиент
   новый — грант не трогает. Для CIMD очистка ограничивается
   flow-ключами: идентичность приходит из конфига, а не из гранта. Flow-клиентские ключи очищаются
   в любом случае. Метод пишет только секрет через `SecretService.update` (или новый
   `ConnectionCredentialStore.updateSecretOnly`), **не** через `write`: `write` меняет
   `connection.params` и встанет на row lock внешнего `complete`. Статус соединения не меняется —
   его выставит следующий 401/refresh или успешный `complete`. Следующий старт увидит source=DCR без
   клиента и зарегистрирует нового. Это закрывает случай, когда refresh вообще не запускается (нет
   `expires_in`/refresh-токена).
6. `error=invalid_scope` (и `invalid_client` в error-callback) — та же очистка flow-клиента и, если он
   совпал с грантом, клиентских креденшлов гранта; следующий старт зарегистрирует клиента под текущий
   scope. Если в `oauth_scope_requested` был `offline_access`, он убирается: иначе AS, считающий
   зарегистрированный scope потолком, получил бы тот же отказ после новой регистрации. Если AS
   показал страницу ошибки без редиректа, callback не приходит — остаётся пересоздание коннекции
   (редкий случай).

Успешный обмен публикует `ConnectorCreatedEvent` внутри транзакции, как сейчас: слушатель
синхронизирует джобы после коммита.

### `refreshIfNeeded`

1. Взять идентичность гранта по `oauth_client_source` (ключа нет — старый CIMD-грант). CIMD —
   сохранённый `oauth_client_id` или конфиг, метод `none`. DCR — сохранённые `client_id`/секрет/метод.
   Нет DCR-клиента у DCR-гранта — `AUTH_EXPIRED` без сети, погасить expiry и опубликовать
   `ConnectorModifiedEvent`, чтобы джоба перестала тикать.
2. Refresh с этой идентичностью. `invalid_grant` — стереть refresh-токен и `oauth_expires_at`,
   `AUTH_EXPIRED`, клиента оставить: он может быть жив, следующий старт переиспользует его.
3. `invalid_client` (и `invalid_scope`) — стереть refresh-токен, expiry и клиентские
   **креденшлы** гранта (`oauth_client_id`, secret, method, scope, expiry), **сохранив
   `oauth_client_source`**; `AUTH_EXPIRED`. Если flow-клиент — тот же отвергнутый клиент, стереть и
   flow-ключи: иначе следующий старт переиспользует мёртвого клиента вместо регистрации нового.
   При `invalid_scope` также убрать `offline_access` из запрошенного scope. Следующий старт увидит
   DCR без клиента и зарегистрирует нового.
4. Транзиентные ошибки — ретрай джобой. Регистрации в refresh нет: новый клиент не обменяет старый
   refresh-токен.

Если у гранта нет `oauth_expires_at` или refresh-токена, refresh не запускается вообще; поэтому
основная очистка мёртвого клиента — `clearRejectedClient` на `complete`, а не refresh. Refresh
остаётся страховкой для грантов с expiry.

`markExpired`/`widenScope` не меняются. `widenScope` расширяет `oauth_scope_requested`; следующий
старт увидит, что `oauth_client_scope` его не покрывает, и зарегистрирует нового клиента.

### Старые секреты

Грант без `oauth_client_source` — старый CIMD-грант: `client_id` из конфига, метод `none`, секрета
нет. Новые CIMD-гранты сохраняют использованный `client_id`, чтобы смена конфига не ломала refresh;
если ключа нет, читаем конфиг. `oauth_client_source` пишется уже при discovery; DCR-грант после
очистки клиента сохраняет source=DCR и не имеет `oauth_client_id` — это не «нет source», а «нужен
новый клиент». Миграции нет.

## Транзакции и гонки

- `ConnectionAuthorizationService.startAuthorization` сегодня `@Transactional`. Регистрация — сеть,
  и внутри транзакции ей не место: метод помечается `@Transactional(propagation = NOT_SUPPORTED)`.
  `McpOAuthService.startAuthorization` делает сеть, затем `McpOAuthStore.startFlow(connectionId, …)`
  в короткой транзакции.
- `startFlow(connectionId, …)` перечитывает строку коннекции **под row lock**
  (`SELECT … FOR UPDATE`), читает секрет и пишет `state`, expiry, verifier и flow-ключи. Никакого
  `merge` снимка, снятого до сети: иначе `save()` перезапишет `deleted_at`, `enabled`, `auth_status`,
  `oauth_expires_at`.
- `complete` остаётся транзакционным: `burnOauthState` блокирует строку коннекции и очищает
  persistence context (`clearAutomatically`), после чего строка перечитывается. Это обязательно:
  callback может идти, пока пользователь удаляет или выключает коннекцию, и `save()` старого снимка
  воскресил бы `deleted_at`/`enabled`. Исчезнувшая строка даёт «not found», а не грант. Затем идёт
  обмен и запись гранта. `startFlow` и refresh ждут эту блокировку. Сеть под блокировкой — текущее
  поведение; вынос обмена из транзакции — отдельное улучшение, не обязательное для DCR.
- `storeRefreshed` — короткая транзакция: row lock, перечитывание секрета, сверка пары
  (`oauth_client_id`, обменянный refresh-токен), запись новых токенов. Это закрывает гонку с
  `startFlow`, которая иначе затирает ротированный refresh-токен, и не даёт refresh старого клиента
  записать токены в грант нового.
- `rejectRefresh`, `widenScope`, `markExpired` — короткие транзакции с row lock.
- `clearRejectedClient` — `REQUIRES_NEW` только над секретом, через `SecretService.update` или новый
  `ConnectionCredentialStore.updateSecretOnly`. `ConnectionCredentialStore.write` не годится: он
  пишет `connection.params` и встанет на row lock внешнего `complete`. Метод на `McpOAuthStore`
  (отдельный бин), поэтому прокси `REQUIRES_NEW` срабатывает; source сохраняется, статус соединения
  не меняется.
- `OAuthCredentials.withTokens` делится на два:
  - `withRefreshedTokens` обновляет только access/refresh/`oauth_scope_granted`; **сохраняет**
    verifier, flow-ключи и клиентские ключи гранта. Refresh посреди начатой авторизации больше не
    стирает PKCE-verifier.
  - `withGrant` переносит flow-ключи (включая срок секрета) в грант, обновляет токены, удаляет
    flow-ключи/verifier и **старый `oauth_refresh_token`/`oauth_scope_granted`**. Новый refresh/scope
    ставятся только из ответа; если AS не выдал refresh, его нет. Это закрывает наследование
    refresh-токена старого клиента.
- Удаление коннекции — soft delete; секреты коннекций сегодня не удаляются, это существующий
  пробел, не DCR-специфичный. Клиент у AS остаётся сиротой — удалять его без RFC 7592 нечем. Строк
  регистраций, которые надо убирать, нет.

## Ошибки, логи, границы

- Регистрация и обмен — только `ConnectorException`; наружу issuer, HTTP-статус и OAuth-код, без
  тела ответа, `client_id`, секрета и токенов.
- Новый `OAuthClientAuthenticationException extends ConnectorException` для `invalid_client` (имя
  `OAuthClientRejectedException` занято серверной стороной). `OAuthGrantRejectedException` остаётся
  для `invalid_grant`, `OAuthScopeRejectedException` — для `invalid_scope` на обмене: клиент жив, но
  повторять его бессмысленно, поэтому следующий старт регистрирует нового.
- Классификация: сначала `error` из JSON-тела. Если token-запрос шёл с `client_secret_basic`/
  `client_secret_post` и ответ — `401`, это `invalid_client` даже при пустом или не-JSON теле: по
  RFC 6749 §5.2 при аутентификации через `Authorization` AS обязан ответить `401` +
  `WWW-Authenticate`, а тело может отсутствовать. `WwwAuthenticate.bearer` для этого не годится — он
  фильтрует bearer; заголовок, если нужен, разбирается как Basic-схема, но не является обязательным
  шагом. Спайк проверял только `invalid_grant` с валидным клиентом; `invalid_client` закрывается
  этим правилом, а не наблюдением.
- `OAuthHttpClient` логирует адреса через `McpUtils.logTarget`, а тексты исключений — через
  `loggable`; сырой URL с query/path и тела ответов в лог не попадают.
- `ClientIdentity.toString()` редактирует секрет; на компонент секрета — `@JsonIgnore`, чтобы
  сериализация record'а не обошла redaction. Тело успешной регистрации не логируется.
- Новый флаг `app.connectors.mcp.oauth.dcr-enabled` (по умолчанию `true`): установка может запретить
  новые DCR-регистрации, оставив CIMD и статические токены. Уже выданные DCR-гранты продолжают
  refresh; если для переавторизации нужен новый клиент, будет понятный отказ. Пустой `client-id`
  отключает только CIMD; `application.yaml` и `.env.example` это больше не отрицают.
- Фронт не меняется: `OAUTH` уже отдаётся как способ авторизации. `agent-worker`, протокол и DBOS не
  трогаем, drain не нужен; но control-api выкатывается одномоментно (или `dcr-enabled=false` до
  конца выката): старые реплики не знают новых ключей и могут отправить DCR-грант с CIMD-клиентом.
  Закрытый контур DCR не открывает: https и `PublicOnlyHttp` остаются, а приватные адреса требуют
  глобального флага, выключающего SSRF-гард.

## Каталог и сид

`ConnectorPresetSeedReader.authMethods` перестаёт выбрасывать `OAUTH` без CIMD: условие
`CIMD || dcrSupported`. `dcrSupported` уже лежит в `probe.authDetails`. `PresetAvailabilityPolicy`
не меняется: она получает уже отфильтрованный список.

Смысл `OAUTH` в каталоге: «клиентскую идентичность можно разрешить, если установка настроена» — из
конфига или из DCR. `availability` остаётся best-effort: живой probe и конфигурация установки —
последнее слово.

Сид — overwriting projection, перезапуск сам обновит `auth_methods`; миграции данных нет. В текущем
сиде нет ни одной OAUTH-карточки; 19 DCR-пресетов станут первыми `[OAUTH, TOKEN]` — карточку и форму
фронта нужно прогнать до включения сида.

PKCE не входит в гейт сида: в `probe.authDetails` нет `code_challenge_methods_supported`. Замер
покажет, нужно ли добавлять поле и фильтр `(CIMD || dcrSupported) && S256`.

## Фоновая работа

- **Refresh-токен**: `oauth_refresh` раз в 5 минут на коннекцию с `oauth_expires_at`; сеть — только
  когда до истечения ≤10 минут. Это частота проверки, а не обновления.
- **Регистрация**: только в `startAuthorization`; refresh и `complete` не регистрируют. В фоне
  регистраций нет.
- **Остановка работы**: после `rejectRefresh` джоба должна перестать тикать. Сейчас refresh-токен
  стирается, но `oauth_expires_at` остаётся, а `ConnectorModifiedEvent` не публикуется. Нужно
  гасить expiry и публиковать событие: `tracksExpiry` станет `false`, listener уберёт строку джобы.
  `markExpired` (401 посреди вызова) — другой случай: refresh-токен может быть жив, джоба должна
  попытаться починить.
- **Не делать**: не опрашивать AS «жив ли клиент» (стандартного эндпойнта нет), не гонять discovery
  по расписанию, не обновлять токен чаще нужного, не заводить суточную проверку
  `client_secret_expires_at` — `invalid_client` на refresh делает это по факту.

## Почему не другие варианты

**Одна общая строка на issuer для всех пользователей.** Минимальный патч, но общий singleton:
волна перерегистраций, общий секрет, радиус поражения на установку, невозможность локального
восстановления. Отвергнуто.

**Таблица `connection_oauth_clients` (вариант B после первого ревью).** Изоляция есть, но
жизненный цикл строки — `stale`, `force`, `PENDING`, условный `UPDATE`, уборка — и есть источник
сложности. Ревью показало, что почти все мажоры лечатся удалением таблицы, а не её улучшением.
Отвергнуто.

**Регистрация на каждый старт без переиспользования.** Проще кода, но спайк показал страницу
авторизации, которая уже показывает имя клиента: новый `client_id`, вероятно, добавляет новый экран
согласия и новую запись в списке приложений AS. Переиспользование клиента гранта убирает эту цену;
редкий `invalid_client` при обмене переживаем без отдельного флага.

**Ручной ввод `client_id`/`client_secret` сразу.** Стена для prosumer и переделка
`getCredentialFields()`; не закрывает 46 DCR-only серверов. Отложено, как и было.

**RFC 7592.** Управлять клиентом некому; спайк подтвердил, что AS не возвращает
`registration_access_token`. Истечение секрета означает реавторизацию этой коннекции; радиус — одна
коннекция. Если замер покажет короткоживущие секреты у целевых AS, RFC 7592 придётся пересмотреть
отдельным решением.

**Отдельная строка `secrets` под клиентский секрет.** Сохраняет барьер «секрет не в
`ConnectorEnv`», но добавляет lifecycle секрета: создать, связать, удалить, убрать сироту. Пока
барьер не признан обязательным, это цена без выгоды. Вернуться, если политика изменится.

**Обобщённый OAuth-слой в `connectors/core` сейчас.** Второго потребителя нет; решение отложено.

## Концептуальная целостность

1. **Пользователь владеет согласием и подключением; система — регистрацией клиента.**
2. **Один факт — одно место:** CIMD — конфиг, DCR-клиент гранта — в гранте, клиент попытки — в
   flow-ключах, грант — в секрете коннекции.
3. **Одна HTTP-дверь:** регистрация и токены идут через `OAuthHttpClient`.
4. **Один словарь ошибок:** `ConnectorException`, generic-тексты, без секретов и тел ответов.
5. **Один поток:** discover → identity → authorize → exchange → grant; DCR — шаг внутри identity.
6. **`McpOAuthClient` stateless и DB-free;** resolver/store живут в сервисном слое.
7. **Никакого преждевременного обобщения:** DCR остаётся в `integrations/mcp/oauth`.
8. **Две модели идентичности названы явно:** CIMD — установка, DCR — коннекция.
9. **Документация не врёт:** «DCR — нет» в [mcp-oauth.md](mcp-oauth.md) получает датированную
   пометку; таблица регистраций в дизайне отсутствует, а не «отложена».

## Риски и открытые вопросы

- **Согласие и список приложений.** Переиспользование клиента гранта убирает повторное согласие в
  обычном переподключении, но новый клиент при расширении scope или после смерти клиента может
  снова показать экран и добавить «AgiMate» в список. `client_name` уникальным не делаем — это снимок и
  вынос имени коннекции в AS. Замерить на целевых AS.
- **`invalid_client` в `complete`.** Транзакция откатывается, flow-ключи остаются;
  `clearRejectedClient` в `REQUIRES_NEW` очищает flow-клиент и, если он совпал с грантом, креденшлы
  гранта, сохраняя source. Следующий старт регистрирует нового. Если AS показал страницу ошибки без
  callback, очистки нет — помогает пересоздание коннекции. Редкий случай.
- **Потеря клиента у AS при живом access-токене.** Пользователь жмёт «Переподключить» — AS может
  показать страницу ошибки, `complete` не вызывается. `clearRejectedClient` сработает, если AS
  довёл до token exchange; иначе refresh очистит клиента, только когда дойдёт до сети (а без expiry
  и refresh-токена не дойдёт). В этом хвосте помогает пересоздание коннекции; сознательно не
  автоматизируем.
- **Клиентский секрет в секрете коннекции.** Отмена прежнего барьера. Шифрование и радиус — как у
  refresh-токена; map не отдаётся агенту, DTO и логам. Если политика ужесточится — отдельная строка
  `secrets`.
- **Срок секрета.** Часто AS не объявляет `client_secret_expires_at` (спайк). Истечение проявится
  как `invalid_client` на refresh; суточной джобы нет. Это осознанно.
- **Сироты у AS.** Без RFC 7592 клиента не удалить. Наши строки не копятся (таблицы нет); клиенты у
  AS остаются. Замерить retention и лимиты.
- **Scope при регистрации.** Потолок не «снят»: если AS трактует зарегистрированный scope как
  потолок, расширение требует новой регистрации этой коннекции — это и делает `oauth_client_scope`.
  Пустой зарегистрированный scope = «неизвестно»: при непустом запросе регистрируем нового, иначе
  возможен цикл `invalid_scope`. `invalid_scope` очищает клиента. Замерить.
- **Снапшот discovery.** `registration_endpoint` заморожен; переезд лечится пересозданием
  коннекции. Ленивый re-discovery — открытый вопрос.
- **Лимит регистраций.** Одна на коннекцию в обычном случае; новые — при расширении scope/смерти
  клиента. Нужен per-user и per-installation/per-AS предел в памяти на ноду с backoff и метрикой;
  «доверие rate limit AS» — только backstop, потому что блокировка IP бьёт по всей установке. При
  abuse — DB-счётчик, не раньше. Всплеск при выкатке — только первый клик по каждой DCR-коннекции.
- **Сеть.** Общий дедлайн и потолок тела — отдельная правка `OAuthHttpClient`; без неё
  remote-controlled `registration_endpoint` может «капать» или прислать гигабайты.
- **Фронт.** Текущий сид не отдаёт ни одной OAUTH-карточки; 19 DCR-пресетов станут первыми
  `[OAUTH, TOKEN]` — прогнать карточку и форму.
- **Приватность.** AS видит клиента на коннекцию, время и IP регистраций; `client_name` и
  `redirect_uri` раскрывают платформу и домен. Это свойство DCR, не дефект; CIMD такой дельты не
  даёт.

## Что осталось от ревью

Модель убирает жизненный цикл таблицы: `stale`, `force`/эвристику, `PENDING`-строки, уборку,
`ON CONFLICT`, условный `UPDATE`, гонку «строка + секрет» и жизненный цикл вокруг потолка scope.
Сам потолок scope не «снят»: он обрабатывается `oauth_client_scope` и остаётся замером. Остаются:

- CIMD-ветка в `complete`/refresh (источник идентичности; старый грант без ключей — CIMD);
  `oauth_client_source` — маркер, сохраняется при очистке клиента;
- классификация `invalid_client` по телу и по `401`/Basic; `clearRejectedClient` в `REQUIRES_NEW`
  только над секретом, чтобы refresh без expiry/refresh-токена не оставлял мёртвого клиента;
- `withGrant` не наследует refresh/scope старого клиента; flow-клиент переиспользуется;
- setup-ключи (`registration_endpoint`, auth methods) и `oauth_flow_client_secret_expires_at`;
- пустой зарегистрированный scope = «неизвестно»; `invalid_scope` очищает клиента;
- row lock и разделение `withRefreshedTokens`/`withGrant`, чтобы refresh не стирал verifier и не
  затирал ротированный токен;
- `rejectRefresh` — гасить expiry и публиковать событие;
- `application_type` (native → `none`, web → basic/post), нормализованный redirect, scope без
  `offline_access`, терпимый разбор ответа;
- `dcr-enabled` и разные тексты отказа (пустой `client-id`, выключенный DCR, нет ни CIMD, ни DCR);
- общий дедлайн и потолок тела `OAuthHttpClient`;
- лимит регистраций per-user и per-installation/AS, логи через `logTarget`;
- сид `CIMD || dcrSupported` и фронтовый smoke test.

## План

- [x] **Спайк `legaldatahunter.com`.** Метаданные, регистрация `201`, ответ без эха полей, Basic на
  token endpoint, страница authorize. Результат — в разделе «Что показал спайк»; тестовый клиент
  остался сиротой у AS, его секрет удалён.
- [x] **Discovery.** `McpAuthDiscovery`: CIMD → DCR → отказ с разными текстами (пустой `client-id`,
  выключенный DCR, нет ни CIMD, ни DCR); `OAuthSetup` + `OAuthCredentials`: `ClientIdentification`,
  `oauth_client_source` (маркер), `oauth_registration_endpoint`,
  `oauth_token_endpoint_auth_methods` (space-separated), `oauth_client_id`, `oauth_client_scope`,
  `oauth_client_secret_expires_at`, flow-ключи включая `oauth_flow_client_secret_expires_at`;
  правило старых секретов (нет source — CIMD, только для секретов до фичи); `McpOAuthProperties` с
  `client-id`, `redirect-uri`, `dcr-enabled`.
- [x] **Регистрация.** `DynamicClientRegistrar` + `OAuthHttpClient.postJson` (https,
  `PublicOnlyHttp`, без редиректов, дедлайн, потолок тела); `application_type`; метод, связанный с
  типом (web: basic → post → none, дефолт basic; native: none); scope без `offline_access`;
  нормализованное сравнение `redirect_uris`; терпимый разбор ответа; `0 → NULL`; redaction;
  классификация 4xx/5xx; `401` + basic/post → `invalid_client`.
- [x] **Идентичность.** `ClientIdentity` с redacted `toString` и `@JsonIgnore`; `McpOAuthClient` на
  аргументе вместо `@Value`-клиента; `client_secret_basic` с form-urlencoding и заголовком;
  `client_secret_post`; `none`; `requireConfigured` без обязательного `client-id`.
- [x] **Store.** `startFlow(connectionId, …)` под row lock, без detached merge; `storeGrant` с
  переносом flow→grant и удалением старого refresh/scope (не наследовать); `storeRefreshed` с row
  lock и сохранением verifier/flow-ключей; `rejectRefresh`/`rejectRefreshClient` с очисткой expiry
  и креденшлов, но сохранением source; `clearRejectedClient` — `REQUIRES_NEW` только над секретом
  через `SecretService.update`/`updateSecretOnly`; `widenScope`; row-lock метод в
  `ConnectionRepository`; `withRefreshedTokens`/`withGrant`.
- [x] **Поток.** `startAuthorization`: CIMD/DCR, порядок грант → flow-клиент → новая регистрация;
  пустой зарегистрированный scope годится только при пустом запросе; `complete`: flow-идентичность,
  успех/`invalid_grant`/`invalid_client` + `clearRejectedClient`, `error=invalid_scope`; refresh:
  CIMD/DCR, `invalid_grant` оставляет клиента, `invalid_client` очищает креденшлы и сохраняет
  source; `ConnectionAuthorizationService.startAuthorization` — `NOT_SUPPORTED`.
- [x] **Ошибки и фон.** `OAuthClientAuthenticationException`; разбор ошибки из тела и `401`/Basic;
  логи `OAuthHttpClient` через `McpUtils.logTarget`; `rejectRefresh` гасит expiry и публикует
  `ConnectorModifiedEvent`; `markExpired` не трогаем.
- [x] **Конфиг и сид.** `dcr-enabled` (default `true`), `.env.example` и `application.yaml`:
  пустой `client-id` отключает только CIMD; `ConnectorPresetSeedReader`: `CIMD || dcrSupported`;
  обновить тесты и числа в docs; лимит per-user и per-installation/AS с backoff и метрикой;
  одномоментный выкат control-api (или DCR выключен до конца выката).
- [x] **Тесты.** Discovery (CIMD/DCR/ни одного, пустой `client-id`, `dcr-enabled`, разные тексты);
  регистратор (запрос, терпимый ответ, секрет/без, native/web, нормализация redirect, ошибки);
  клиент (basic/post/none, form-urlencoding, redaction, Jackson, `401` → `invalid_client`);
  store/resolver (reuse гранта и flow, новая регистрация, перенос flow→grant, `withGrant` не
  наследует refresh, `storeRefreshed` сверяет пару client_id/refresh, refresh сохраняет verifier,
  source после `rejectRefresh`, `invalid_grant`/`invalid_client`, `clearRejectedClient`); сервис (CIMD, DCR, старый секрет, две брошенные
  попытки — один клиент, пустой scope → новая регистрация, удаление во время callback не воскрешает
  коннекцию); секрет не в `params`/`ConnectionResponse`
  и `updateSecret` закрыт для OAuth; сид; `rejectRefresh`; границы тела.
- [ ] **Ручная проверка SQL/row lock** на клоне базы: `startFlow` ждёт `complete`, `storeRefreshed`
  не затирает ротированный refresh, `clearRejectedClient` не встаёт на lock внешнего `complete`.
  Интеграционных тестов в репозитории нет.
- [x] **Документы.** Датированная пометка в [mcp-oauth.md](mcp-oauth.md), обновление
  [connector-presets.md](connector-presets.md) и [../architecture/connectors.md](../architecture/connectors.md),
  строка в [../README.md](../README.md), пункт roadmap, `.env.example`, `application.yaml`,
  javadoc `ConnectorPreset`/`ConnectorPresetAuth`, тестовые фикстуры.
- [ ] **Живой прогон.** `legaldatahunter.com` + два DCR-сервера из сида: авторизация через фронт,
  `tools/list`, refresh; переподключение без нового согласия; две брошенные попытки — один клиент;
  расширение scope с новой регистрацией; `invalid_scope`; несколько коннекций к одному AS; удаление
  коннекции.
- [ ] **Замер каталога.** По 19 DCR-серверам первой партии и полному датасету: эхо полей
  регистрации, `token_endpoint_auth_methods_supported`, `client_secret_expires_at`, поведение
  `application_type`, потолок scope, согласие, лимиты/владение клиентом; результат — в
  [connector-presets.md](connector-presets.md).
