# user-api

Authentication service handling OAuth2 login, JWT token management, and user profiles.

## Configuration

| Setting         | Value      |
|-----------------|------------|
| Port            | 8080       |
| Context Path    | `/user/`   |
| Management Port | 8088       |
| Database        | am_user_db |

## Authentication

- **OAuth2 Providers**: Google, Yandex, GitHub, VK ID (см. [Провайдеры входа](#провайдеры-входа))
- **JWT**: ES256 (ECDSA P-256)
- Access tokens in response body
- Refresh tokens in HTTP-only cookies — в браузере; нативный клиент получает их в теле ответа
  (см. [Вход нативного приложения](#вход-нативного-приложения))
- Живые сессии лежат в `auth_sessions`: логаут — это отзыв строки, а не только удаление cookie

## Environment Variables

| Variable                          | Description                                          |
|-----------------------------------|------------------------------------------------------|
| `JWT_PRIVATEKEY`                  | ECDSA private key (Base64, PKCS#8)                   |
| `JWT_PUBLICKEY`                   | ECDSA public key (Base64, X.509)                     |
| `APP_OAUTH_COOKIE_ENCRYPTION_KEY` | AES-256 key for OAuth2 cookies                       |
| `APP_OAUTH_COOKIE_DOMAIN`         | Default cookie domain for refresh tokens             |
| `APP_OAUTH_COOKIE_SECURE`         | Set to `true` in production (HTTPS)                  |
| `APP_OAUTH_FRONTEND_REDIRECT_URL` | Default frontend redirect URL after OAuth2 login     |
| `APP_OAUTH_ALLOWED_REDIRECT_URLS` | Comma-separated whitelist for multi-domain redirects  |
| `APP_OAUTH_NATIVE_REDIRECT_URLS`  | Comma-separated whitelist of native redirect targets |

## Провайдеры входа

Креденшелы провайдеров в конфиге не лежат: в `application.yaml` объявлено только то, что одинаково
везде — эндпойнты, скоупы, шаблон redirect-uri, — а `client-id`/`client-secret` приходят из
окружения под теми же именами свойств, без промежуточных коротких переменных:

| Переменная | Что это |
|---|---|
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` (`_CLIENT_SECRET`) | Google |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_ID` (`_CLIENT_SECRET`) | Yandex |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID` (`_CLIENT_SECRET`) | GitHub |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_VK_CLIENT_ID` | VK ID; секрета нет — PKCE |

**Провайдер без `client-id` инсталляцией не предлагается.** `OAuth2RegistrationsConfig` выбрасывает
такую регистрацию до валидации: иначе одно только объявление в yaml не давало бы сервису
подняться — `OAuth2ClientProperties` валидирует себя сам и пустой id не принимает. Именно ради этого
раньше в конфиге жили фальшивые id-заглушки; теперь «не настроен» — законное состояние, и в логе при
старте видно, что реально поднялось: `OAuth2 login providers: [google, yandex]`.


Всё, что у провайдера своего, живёт в одном классе на провайдера — `security/oauth2/providers/*UserAdapter`.
Адаптер отвечает на два вопроса: как из ответа провайдера достать профиль (`extract`) и надо ли
перед этим переложить ответ в форму, понятную Spring (`normalize`). Добавление пятого провайдера —
это регистрация в `application.yaml` плюс один такой класс.

**Связывание аккаунтов идёт по подтверждённой почте.** Если привязки `(провайдер, id у провайдера)`
ещё нет, пользователь ищется по email — так вход через второго провайдера ведёт в тот же аккаунт,
а не заводит дубль. Именно поэтому почта, за которую провайдер не ручается, к связыванию не
допускается: иначе достаточно зарегистрировать у любого провайдера чужой адрес, чтобы получить
чужой аккаунт. Провайдер без почты (VK-аккаунт на телефоне, GitHub без подтверждённого адреса)
получает отказ с текстом, что делать — 400 и `ErrorResponse`, как и прочие ошибки OAuth-флоу.

Уникальность в `user_oauth_accounts` — по `(oauth_provider, provider_user_id)`. На `email` её
намеренно нет: у одного человека несколько строк с одной почтой — это норма, по одной на провайдера.
Один аккаунт на адрес держит `users.email`.

| Провайдер | Что своего |
|---|---|
| Google | OpenID Connect, поэтому идёт мимо `OAuth2UserService` — принципал собирает `OidcUserService`. Подтверждённость почты — из `email_verified` |
| Yandex | Обычный OAuth2. `default_email` — адрес, куда Яндекс доставляет почту, отдельного флага подтверждения нет |
| GitHub | Профиль `/user` несёт только публичную почту, а её скрывает большинство аккаунтов, поэтому `normalize` дозапрашивает `/user/emails` и берёт `primary && verified`. Имя у GitHub одно, на имя и фамилию не делится |
| VK ID | OAuth 2.1: обязательный PKCE (включается сам — клиент публичный, `client-authentication-method: none`), свой формат `state` (32 hex вместо Base64 с `=`), `device_id` с колбэка в обмене кода, `user_info` через POST с телом, а не Bearer, и профиль вложен в `user` |

Три отклонения VK, которые не лечатся конфигом, собраны в `config/OAuth2ProvidersConfig`.
Поле `device_id` берётся из текущего HTTP-запроса: в OAuth2-ответе такого параметра нет, и к моменту
обмена кода это единственное место, где оно ещё существует.

## API reference

Paths and schemas are generated from the code. See Swagger at **`/user/docs/ui`**
(`develop` profile); the auth contour per group is in the [Authentication](#authentication)
table above.

## Админский раздел `/admin`

Зеркало `/manage/admin` в control-api: гейт — путь, `ROLE_ADMIN` требует правило цепочки на
префиксе, `@PreAuthorize` в контроллерах раздела нет. Правило стоит **выше** остальных, потому что
`/user/**` допускает и `GUEST`, а выигрывает первое совпавшее.

Префикс не повторяет контекст-путь сервиса (`/user/`), поэтому снаружи раздел виден как
`/user/admin/...`. Удвоение в `/user/user/me` у `UserController` — его собственная особенность, и
новый раздел её не наследует.

| Эндпойнт (снаружи) | Что делает |
|---|---|
| `GET /user/admin/users/` | Постраничный список от новых к старым; фильтры `search` (подстрока email или display name, регистронезависимо) и `role` |
| `PATCH /user/admin/users/{id}/role` | Смена роли (`GUEST` → `USER` — это же и есть approve из вейтлиста) |

Свою роль сменить нельзя. Это не только защита от случайного self-lockout: она же делает
недостижимым состояние «админов ноль» — последний админ не может понизить себя, а больше некому.
Изменение пишется в лог (`кто кому с чего на что`); отдельной таблицы аудита нет.

**Понижение роли доходит до control-api не сразу.** user-api читает роль из БД на каждом запросе
(`JwtDbAuthenticationFilter`), а control-api доверяет claim'у `roles` в access-токене, живущему
`jwt.accessExpiration` (сутки по умолчанию; у нативных сессий — `jwt.nativeAccessExpiration`, час).
До следующего refresh понижённый пользователь сохраняет прежние права в `/manage/**`. Отобрать
доступ немедленно нынешней схемой нельзя — для этого нужен либо короткий access TTL, либо версия
роли в claim'ах.

## Пуш-уведомления

Устройства и транспорт принадлежат этому сервису, содержание уведомления — control-api
([decisions/push-notifications.md](../decisions/push-notifications.md)).

| Эндпойнт | Кто зовёт | Что делает |
|---|---|---|
| `PUT /push/subscriptions` | приложение | регистрирует токен устройства; идемпотентно, токен чужого аккаунта **переезжает** к вызывающему |
| `DELETE /push/subscriptions` | приложение | снимает свою подписку (выход из аккаунта); токен в теле, не в query — иначе он оседает в access-логах |
| `POST /internal/notifications` | control-api | реле: `{userId, data, ttlSeconds}`, `data` не разбирается; отвечает `202`, доставка асинхронная |

Подписка принадлежит **входу**: `auth_session_id` берётся из claim'а `asid`, полем запроса его не
передать. Отзыв сессии сносит подписки явным DELETE в той же транзакции — каскад по внешнему ключу
сработал бы только на смёте истёкших сессий (отзыв ставит `revoked_at` и строку не удаляет), а до
него потерянный телефон получал бы превью переписок. Смёт по `last_seen_at` (60 дней) остаётся
страховкой для подписок, у которых сессии нет вовсе.

`POST /internal/notifications` закрыт общим секретом (`app.s2s.key`, заголовок `X-S2S-Key`, отдельная
цепочка фильтров, сравнение постоянное по времени): это не пользовательский токен, и роли из базы про
сервис ничего не говорят. Открытым это реле было бы примитивом «отправить произвольный текст на
телефон произвольного пользователя».
**Payload в лог не пишется** — там текст переписки, а рядом в этом сервисе лежат почты и токены
доступа; в лог идут `userId` и ключи, никогда значения.

**Что ещё едет в access-токене.** Кроме `roles` — `asid`: идентификатор сессии входа, из которой
токен выписан (`auth_sessions.id`). Его читает control-api, чтобы привязать подписку на пуши к
устройству и снести её при отзыве сессии; в токенах, выпущенных до появления claim'а, его нет, и это
не ошибка. Не `sid`, хотя в OIDC это ровно оно: в control-api `sessionId` — переписка, и короткое имя
читалось бы как она. См. [decisions/push-notifications.md](../decisions/push-notifications.md).

Расходы LLM по пользователю отдаёт control-api (`GET /manage/admin/llm-usage/{userId}/`): таблицы
`users` и `llm_usage_*` лежат в разных БД, между сервисами нет ни вызовов, ни репликации, поэтому
связывает их фронт — по `userId`.

## Multi-domain OAuth2 Redirect

Supports OAuth2 login from multiple frontend domains (e.g. `agimate.ru` and `agimate.io`).

**How it works:**
1. Frontend appends `?redirect_to=https://www.agimate.io/login` to the OAuth2 authorization URL
2. The value is saved in a temporary `oauth2_redirect_to` cookie (15 min TTL)
3. After successful OAuth2 authentication, the handler reads the cookie and validates the URL against `allowed-redirect-urls`
4. If valid — redirects to the specified URL with the correct cookie domain. If not — falls back to the default `frontend-redirect-url`

For `refresh` and `logout` endpoints, the cookie domain is resolved from the request's `Host` header by matching against `allowed-redirect-urls`.

## Вход нативного приложения

Приложению нельзя отдать refresh-токен в cookie: Custom Tabs и `ASWebAuthenticationSession` держат
свой cookie jar, до которого HTTP-клиент приложения не дотягивается. Поэтому вход заканчивается не
cookie, а одноразовым кодом, который обменивается на пару токенов в теле ответа. Решение и
отвергнутые варианты — [Авторизация нативного приложения](../decisions/native-auth.md).
**Веб-флоу не меняется ни в одной точке.**

Ветка выбирается по `redirect_to`: адрес из `app.oauth.native-redirect-urls` — нативная, из
`allowed-redirect-urls` — прежняя браузерная. Списки раздельные, потому что из веб-адреса
вычисляется домен cookie, а у `agimate://auth` его нет; перепутанные списки роняют старт сервиса,
а не логин.

```
1. GET /user/oauth2/authorization/google?redirect_to=agimate://auth&code_challenge=<S256>
2. …круг к провайдеру…
3. 302 agimate://auth?code=<одноразовый код>          ← никаких Set-Cookie
4. POST /user/oauth2/native/token {code, codeVerifier, redirectUri, deviceName}
   → {accessToken, refreshToken, refreshTokenId, expiresIn, sessionId}
```

`code_challenge` — только S256 (43 символа base64url); на время круга к провайдеру он лежит в cookie
`oauth2_code_challenge` рядом с `oauth2_redirect_to`. Это **не** тот `code_verifier`, что внутри
`oauth2_auth_request`: тот принадлежит нашему обмену с провайдером, этот — обмену приложения с нами.
Код живёт 60 секунд, хранится хешем, гасится первым обменом; повторный обмен отзывает сессию,
которую выдал первый.

`POST /user/oauth2/refresh` и `/logout` принимают токен **сначала из cookie, потом из тела**: браузер
работает как работал, приложение шлёт `refreshToken` в теле и получает новый там же. Нативная ветка
cookie не ставит и не удаляет. Токен, выданный браузеру, в теле не принимается и наоборот — иначе
XSS обменял бы httpOnly-cookie на токен, доступный скрипту.

### Реестр сессий

`auth_sessions` — строка на устройство. Без неё логаут не был бы отзывом: список погашенных `jti`
жил в памяти инстанса, терялся при рестарте и не разделялся между репликами. Реестр общий для обеих
веток — веб-вход заводит такую же строку.

Строка хранит **два** `jti`, текущий и предыдущий. Рефреш ротирует токен, но ответ на мобильной сети
может не доехать, и клиент повторит запрос с тем, что у него осталось:

| Что предъявлено | Ответ |
|---|---|
| текущий `jti` | ротация: новая пара, поколение сдвигается |
| предыдущий `jti`, прошло < 60 c с ротации | та же текущая пара ещё раз, поколение **не** двигается |
| предыдущий `jti` позже, или любой более старый | 403 и отзыв всей сессии — токен оказался у двоих (RFC 9700) |
| параллельный рефреш проиграл гонку | 409: клиент обязан сериализовать обновление |

Повторная выдача текущей пары вместо новой ротации — сознательно: так переживается и вторая подряд
потерянная сеть, тогда как цепочка ротаций оставила бы клиента с парой, которую уже отменили.

Реестр читается только на рефреше — access-токен проверяется подписью. Поэтому у нативной сессии он
короткий (час против суток у веба): отзыв устройства догоняет его не раньше, чем истечёт текущий
access. Refresh нативной сессии живёт 60 дней и продлевается при каждой ротации — сессия умирает от
простоя, а не от возраста.

Свои устройства пользователь видит и гасит сам:

| Эндпойнт (снаружи) | Что делает |
|---|---|
| `GET /user/sessions/` | Активные сессии, свежие сверху |
| `DELETE /user/sessions/{id}` | Отзыв одной сессии |

Путь лежит вне `/user/**`, но в цепочке назван явно и пускает в том числе `GUEST`: аккаунт, ждущий
одобрения и потерявший телефон, — тот, кому это нужнее всего.

**При выкатке все живут ровно один разлогин.** Токены, выданные до реестра, строки не имеют и
принимаются не будут: сессия, которую нельзя отозвать, не усыновляется. Это одноразовая цена, и она
дешевле постоянного исключения в коде.

## Реферальные ссылки

Код есть у каждого пользователя (`users.referral_code`), а у каждого пришедшего по ссылке —
пригласивший (`users.referred_by`). Решение и отвергнутые варианты — [Реферальные
ссылки](../decisions/referrals.md).

**Контракт с фронтом.** Код добавляется к authorization-URL рядом с `redirect_to`:

```
GET /user/oauth2/authorization/google?redirect_to=https://www.agimate.ru/login&ref=K7M2QX9F
```

Дальше он на время круга к провайдеру ложится в cookie `oauth2_ref` (15 минут, как и
`oauth2_redirect_to`) и читается уже на колбэке. Довезти код с лендинга до кнопки «Войти» — задача
клиента: бэкенд видит его только в момент старта OAuth, а не в момент клика по ссылке. Значение
фильтруется до записи в cookie (`[A-Za-z0-9]{1,16}`) — в отличие от `redirect_to`, позже его никто
не проверяет.

Свой код отдаёт `GET /user/referral` вместе с числом приведённых. Путь намеренно лежит вне
`/user/**`, который пускает и `GUEST`, поэтому цепочка требует `USER` — аккаунт, ждущий одобрения,
приглашать не может.

**Атрибуция ставится один раз, при создании аккаунта.** Вход существующего пользователя по чужой
ссылке ничего не меняет, поэтому ссылка приводит только новых людей. Роль она при этом не меняет:
приглашённый заводится как `GUEST` и одобряется администратором.

**Production example:**
```
APP_OAUTH_ALLOWED_REDIRECT_URLS=https://www.agimate.ru/login,https://www.agimate.io/login
APP_OAUTH_FRONTEND_REDIRECT_URL=https://www.agimate.ru/login
APP_OAUTH_COOKIE_DOMAIN=agimate.ru
```

## Database Tables

- `users` — User accounts
- `user_oauth_accounts` — OAuth2 provider links
- `auth_sessions` — живые сессии: строка на устройство, пара `jti`, отзыв
- `auth_codes` — одноразовые коды нативного входа (хеш, не код)

Migrations: `services/user-api/src/main/resources/db/changelog/`

Ключей приложений здесь нет — они живут в control-api (`apps.key_hash`, формат — [Формат ключа
AGM](../contracts/api-keys.md)), и наружу user-api не отдаёт ничего, кроме HTTP: gRPC-сервера у него
нет.
