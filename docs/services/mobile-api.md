# mobile-api

Mobile device API for device registration, action delivery, and trigger submission.

## Configuration

| Setting         | Value         |
|-----------------|---------------|
| Port            | 8080          |
| Context Path    | `/mobile-api` |
| Management Port | 8088          |
| gRPC Port       | 9090          |
| Database        | am_mobile_db  |

## Authentication

- **Device Auth**: Header `X-Device-Auth-Key` for device endpoints
- **JWT**: Bearer token for management endpoints

## Environment Variables

| Variable                | Description                       |
|-------------------------|-----------------------------------|
| `JWT_PUBLIC_KEY`        | ECDSA public key (Base64, X.509)  |
| `CENTRIFUGO_API_KEY`    | Centrifugo HTTP API key           |
| `CENTRIFUGO_PRIVATEKEY` | Centrifugo JWT private key        |
| `CENTRIFUGO_PUBLICKEY`  | Centrifugo JWT public key         |
| `MOBILE_API_KEY_1`      | API key for mobile authentication |

## API Endpoints

### Device Endpoints (Device Auth)

| Method | Path                                   | Description                             |
|--------|----------------------------------------|-----------------------------------------|
| GET    | `/mobile-api/device/actions/get`       | Get pending actions for device          |
| GET    | `/mobile-api/device/actions/test`      | Test endpoint (publishes to Centrifugo) |
| POST   | `/mobile-api/device/trigger/new`       | Submit trigger from device              |
| POST   | `/mobile-api/device/registration/link` | Link device to auth key                 |
| POST   | `/mobile-api/device/centrifugo/token`  | Get Centrifugo subscription token       |

### Device Management (JWT)

| Method | Path                                                   | Description                   |
|--------|--------------------------------------------------------|-------------------------------|
| GET    | `/mobile-api/manage/devices/`                          | List all device auth keys     |
| POST   | `/mobile-api/manage/devices/`                          | Create new device auth key    |
| GET    | `/mobile-api/manage/devices/{connectionId}`            | Get specific device auth key  |
| PUT    | `/mobile-api/manage/devices/{connectionId}`            | Update device auth key        |
| DELETE | `/mobile-api/manage/devices/{connectionId}`            | Delete device auth key (soft) |
| POST   | `/mobile-api/manage/devices/{connectionId}/regenerate` | Regenerate auth key           |

### Public

| Method | Path                      | Description                 |
|--------|---------------------------|-----------------------------|
| GET    | `/mobile-api/`            | Application info and uptime |
| GET    | `/mobile-api/favicon.ico` | Empty favicon               |

## Centrifugo Integration

mobile-api integrates with Centrifugo for real-time messaging to mobile devices:
- Publishes actions to device channels
- Handles connection token generation
- gRPC server for connectors-api to push actions

## Database Tables

- `device_auth_keys` — Device authentication keys and metadata

Migrations: `services/mobile-api/src/main/resources/db/changelog/`
