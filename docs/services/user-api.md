# user-api

Authentication service handling OAuth2 login and JWT token management.

## Configuration

| Setting         | Value        |
|-----------------|--------------|
| Port            | 8080         |
| Context Path    | `/user-api/` |
| Management Port | 8088         |
| Database        | am_user_db   |

## Authentication

- **OAuth2 Providers**: Google, Yandex
- **JWT**: ES256 (ECDSA P-256)
- Access tokens in response body
- Refresh tokens in HTTP-only cookies

## Environment Variables

| Variable                       | Description                         |
|--------------------------------|-------------------------------------|
| `JWT_PRIVATE_KEY`              | ECDSA private key (Base64, PKCS#8)  |
| `JWT_PUBLIC_KEY`               | ECDSA public key (Base64, X.509)    |
| `GOOGLE_CLIENT_ID`             | Google OAuth2 client ID             |
| `GOOGLE_CLIENT_SECRET`         | Google OAuth2 client secret         |
| `YANDEX_CLIENT_ID`             | Yandex OAuth2 client ID             |
| `YANDEX_CLIENT_SECRET`         | Yandex OAuth2 client secret         |
| `OAUTH2_COOKIE_ENCRYPTION_KEY` | AES-256 key for OAuth2 cookies      |

## API Endpoints

### User Management (JWT)

| Method | Path                      | Description                    |
|--------|---------------------------|--------------------------------|
| GET    | `/user-api/user/{pub_id}` | Get user by public ID          |
| GET    | `/user-api/user/me`       | Get current authenticated user |

### OAuth2 Authentication

| Method | Path                       | Description                       |
|--------|----------------------------|-----------------------------------|
| POST   | `/user-api/oauth2/refresh` | Refresh access token              |
| POST   | `/user-api/oauth2/logout`  | Logout (invalidate refresh token) |
| GET    | `/user-api/oauth2/error`   | OAuth2 error handler              |

### Public

| Method | Path                    | Description                 |
|--------|-------------------------|-----------------------------|
| GET    | `/user-api/`            | Application info and uptime |
| GET    | `/user-api/favicon.ico` | Empty favicon               |

## Database Tables

- `users` — User accounts
- `user_oauth_accounts` — OAuth2 provider links

Migrations: `services/user-api/src/main/resources/db/changelog/`
