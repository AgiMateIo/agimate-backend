# Architecture

## System Overview

```mermaid
graph TB
    subgraph "Clients"
        WebApp[Web Application]
        MobileApp[Mobile App]
        External[External Systems]
        Agents[AI Agents]
        Workers[Generic Workers]
    end

    subgraph "Services"
        UserAPI[user-api<br/>:8080/user/]
        DeviceAPI[device-api<br/>:8080/device + :9091 gRPC TLS]
    end

    subgraph "Databases"
        UserDB[(am_user_db)]
        DeviceDB[(am_device_db)]
    end

    subgraph "External"
        Centrifugo[Centrifugo<br/>Real-time messaging]
        OAuth[OAuth Providers<br/>Google, Yandex]
    end

    WebApp --> UserAPI
    MobileApp --> DeviceAPI
    Agents --> DeviceAPI
    Agents --> Centrifugo
    Workers -.->|gRPC :9091| DeviceAPI

    UserAPI --> UserDB
    DeviceAPI --> DeviceDB

    UserAPI --> OAuth
    DeviceAPI --> Centrifugo
    DeviceAPI -.->|gRPC :9090| UserAPI
```

## Services

### user-api
Authentication service handling OAuth2 login (Google, Yandex), JWT token management, user profiles, and API key management. Exposes gRPC IntrospectApiKey endpoint for API key validation by other services.

### device-api
Device API for device registration, tool delivery, trigger submission, and AI agent integration. Integrates with Centrifugo for real-time push to devices and agents. Agents authenticate via API Key, invoke tools on devices, receive tool results and trigger events through Centrifugo channels.

### libs/common
Shared library containing exception hierarchy, REST response wrappers (`SuccessResponse`, `ErrorResponse`), JWT/API Key utilities, and `UUIDUtils` (UUIDv8 generation).

## Authentication Flows

| Method               | Description                                                  | Used By                             |
|----------------------|--------------------------------------------------------------|-------------------------------------|
| **JWT**              | Bearer token authentication for users                        | All services (management endpoints) |
| **API Key**          | Header `X-Api-Key` for agent calls                           | device-api                          |
| **Application Auth** | Header `X-App-Auth-Key` for application/devices              | device-api                          |
| **Worker Pool Key**  | gRPC `authorization: Bearer wrkp...`, hash stored in config  | device-api gRPC `:9091`             |
| **OAuth2**           | Google/Yandex social login                                   | user-api                            |

### JWT Flow
- Access tokens returned in response body
- Refresh tokens stored in HTTP-only cookies
- Algorithm: ES256 (ECDSA with P-256 curve)

## Databases

| Database         | Owner          | Tables                                                                      |
|------------------|----------------|-----------------------------------------------------------------------------|
| am_user_db       | user-api       | `users`, `user_oauth_accounts`, `service_api_keys`, `waitlist_entries`      |
| am_device_db     | device-api     | `connectors`, `trigger_logs`, `trigger_log_agents`, `tool_use_logs`, `agents`, `agent_tools`, `agent_triggers`, `webhook_delivery_logs`, `platforms`, `integrations`, `agentic_teams` |

All migrations managed via Liquibase in each service's `src/main/resources/db/changelog/`.

## Inter-Service Communication

### gRPC (device-api → user-api)
- Port: **9090**
- device-api acts as gRPC client
- user-api acts as gRPC server
- Used for API key introspection (IntrospectApiKey)

## Ports

| Port | Purpose                                    |
|------|--------------------------------------------|
| 8080 | HTTP (all services)                        |
| 8088 | Management/actuator (all services)         |
| 9090 | gRPC (user-api server)                     |
