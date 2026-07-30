# user-api

Authentication service handling OAuth2 login, JWT token management, API key management, and waitlist.

## Configuration

| Setting         | Value      |
|-----------------|------------|
| Port            | 8080       |
| Context Path    | `/user/`   |
| Management Port | 8088       |
| Database        | am_user_db |

## Authentication

- **OAuth2 Providers**: Google, Yandex
- **JWT**: ES256 (ECDSA P-256)
- Access tokens in response body
- Refresh tokens in HTTP-only cookies

## Environment Variables

| Variable                          | Description                                          |
|-----------------------------------|------------------------------------------------------|
| `JWT_PRIVATEKEY`                  | ECDSA private key (Base64, PKCS#8)                   |
| `JWT_PUBLICKEY`                   | ECDSA public key (Base64, X.509)                     |
| `GOOGLE_CLIENT_ID`                | Google OAuth2 client ID                              |
| `GOOGLE_CLIENT_SECRET`            | Google OAuth2 client secret                          |
| `YANDEX_CLIENT_ID`                | Yandex OAuth2 client ID                              |
| `YANDEX_CLIENT_SECRET`            | Yandex OAuth2 client secret                          |
| `APP_OAUTH_COOKIE_ENCRYPTION_KEY` | AES-256 key for OAuth2 cookies                       |
| `APP_OAUTH_COOKIE_DOMAIN`         | Default cookie domain for refresh tokens             |
| `APP_OAUTH_COOKIE_SECURE`         | Set to `true` in production (HTTPS)                  |
| `APP_OAUTH_FRONTEND_REDIRECT_URL` | Default frontend redirect URL after OAuth2 login     |
| `APP_OAUTH_ALLOWED_REDIRECT_URLS` | Comma-separated whitelist for multi-domain redirects |

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
`jwt.accessExpiration` (сутки по умолчанию). До следующего refresh понижённый пользователь
сохраняет прежние права в `/manage/**`. Отобрать доступ немедленно нынешней схемой нельзя — для
этого нужен либо короткий access TTL, либо версия роли в claim'ах.

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

**Production example:**
```
APP_OAUTH_ALLOWED_REDIRECT_URLS=https://www.agimate.ru/login,https://www.agimate.io/login
APP_OAUTH_FRONTEND_REDIRECT_URL=https://www.agimate.ru/login
APP_OAUTH_COOKIE_DOMAIN=agimate.ru
```

## gRPC Endpoints

| RPC              | Description                       | Port |
|------------------|-----------------------------------|------|
| IntrospectApiKey | Validate API key, return user info | 9090 |

## Database Tables

- `users` — User accounts
- `user_oauth_accounts` — OAuth2 provider links
- `service_api_keys` — API keys for connector/agent access
- `waitlist_entries` — Waitlist registrations

Migrations: `services/user-api/src/main/resources/db/changelog/`
