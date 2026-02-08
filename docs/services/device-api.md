# device-api

Device API for device registration, action delivery, and trigger submission.

## Configuration

| Setting         | Value         |
|-----------------|---------------|
| Port            | 8080          |
| Context Path    | `/device-api` |
| Management Port | 8088          |
| gRPC Port       | 9090          |
| Database        | am_device_db  |

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
| `DEVICE_API_KEY_1`      | API key for device authentication |

## API Endpoints

### Device Endpoints (Device Auth)

| Method | Path                                  | Description                             |
|--------|---------------------------------------|-----------------------------------------|
| GET    | `/device-api/actions/get`             | Get pending actions for device          |
| GET    | `/device-api/actions/test`            | Test endpoint (publishes to Centrifugo) |
| POST   | `/device-api/trigger/new`             | Submit trigger from device              |
| POST   | `/device-api/registration/link`       | Link device to auth key                 |
| POST   | `/device-api/centrifugo/token`        | Get Centrifugo subscription token       |

### Device Management (JWT)

| Method | Path                                                       | Description                   |
|--------|----------------------------------------------------------  |-------------------------------|
| GET    | `/device-api/manage/devices/`                              | List user devices             |
| POST   | `/device-api/manage/devices/{connectionId}/disconnect`     | Disconnect device             |

### Device Auth Key Management (JWT)

| Method | Path                                                       | Description                   |
|--------|----------------------------------------------------------  |-------------------------------|
| GET    | `/device-api/manage/device-keys/`                          | List all device auth keys     |
| POST   | `/device-api/manage/device-keys/`                          | Create new device auth key    |
| GET    | `/device-api/manage/device-keys/{connectionId}`            | Get specific device auth key  |
| PUT    | `/device-api/manage/device-keys/{connectionId}`            | Update device auth key        |
| DELETE | `/device-api/manage/device-keys/{connectionId}`            | Delete device auth key (soft) |
| POST   | `/device-api/manage/device-keys/{connectionId}/regenerate` | Regenerate auth key           |

### Public

| Method | Path                      | Description                 |
|--------|---------------------------|-----------------------------|
| GET    | `/device-api/`            | Application info and uptime |
| GET    | `/device-api/favicon.ico` | Empty favicon               |

## Centrifugo Integration

device-api integrates with Centrifugo for real-time messaging to devices:
- Publishes actions to device channels
- Handles connection token generation
- gRPC server for connectors-api to push actions

## Database Tables

- `device_auth_keys` — Device authentication keys and metadata

Migrations: `services/device-api/src/main/resources/db/changelog/`
