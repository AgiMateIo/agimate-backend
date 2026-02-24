# Agimate Documentation

Agimate is an automation platform that connects external services (marketplaces, APIs) and devices with AI agents for workflow automation.

## Tech Stack

- **Java 21** with virtual threads
- **Spring Boot 4.0**
- **PostgreSQL** for persistence
- **Centrifugo** for real-time messaging
- **gRPC** for inter-service communication

## Quick Start

```bash
# Build entire project
./gradlew build

# Run tests
./gradlew test

# Run services locally
./gradlew :user-api:bootRun
./gradlew :device-api:bootRun
```

## Documentation

| Document                                      | Description                                           |
|-----------------------------------------------|-------------------------------------------------------|
| [Architecture](architecture.md)               | System architecture, service interactions, databases  |
| [Deployment](deploy.md)                       | Environment variables, key generation, configuration  |
| **Services**                                  |                                                       |
| [user-api](services/user-api.md)              | Authentication service (OAuth2, JWT)                  |
| [device-api](services/device-api.md)          | Device API                                     |

## Services Overview

| Service        | Port  | Context Path       |
|----------------|-------|--------------------|
| user-api       | 8080  | `/user/`         |
| device-api     | 8080  | `/device`        |

All services expose management endpoints on port **8088**.
