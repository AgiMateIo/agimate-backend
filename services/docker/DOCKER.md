# Docker Setup Guide

This guide explains how to run the Agimate backend services using Docker and docker-compose.

## Prerequisites

- Docker 20.10 or higher
- Docker Compose 2.0 or higher

## Quick Start

1. **Copy and configure environment variables**

```bash
cd backend/docker
cp ../.env.example ../.env
```

Edit `../.env` file and set the required values:
- `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY` (see JWT Key Generation below)
- `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`
- `CONNECTORS_ENCRYPTION_KEY` (see below)

2. **Generate required keys**

```bash
# Generate JWT keys (ES256 - ECDSA P-256)
./generate-jwt-keys.sh

# Generate encryption key for connectors
openssl rand -base64 32
```

Add the generated keys to your `../.env` file.

3. **Build and start all services**

```bash
docker-compose up -d
```

This will start:
- PostgreSQL (port 5432) with 3 databases: `am_core_db`, `am_device_db`, `am_connectors_db`
- Centrifugo (ports 8000, 8001)
- user-api (port 8080)
- device-api (port 8081)
- connectors-api (ports 8280, 8288)

4. **Check service status**

```bash
docker-compose ps
docker-compose logs -f
```

## Individual Service Commands

### Build specific service

```bash
docker-compose build user-api
docker-compose build device-api
docker-compose build connectors-api
```

### Start/stop specific service

```bash
docker-compose up -d user-api
docker-compose stop user-api
docker-compose restart user-api
```

### View logs

```bash
# All services
docker-compose logs -f

# Specific service
docker-compose logs -f user-api
docker-compose logs -f device-api
docker-compose logs -f connectors-api
```

## JWT Key Generation
Use the provided script:

```bash
./generate-jwt-keys.sh
```

Set the contents of these files as environment variables in `../.env`.

## Service Endpoints

### User API (port 8080, 8088)
- Health: http://localhost:8088/actuator/health
- Info: http://localhost:8080/user-api/

### Mobile API (port 8081)
- Health: http://localhost:8188/actuator/health
- Info: http://localhost:8081/device-api/

### Connectors API (port 8280)
- Health: http://localhost:8288/actuator/health
- Info: http://localhost:8280/connectors-api/
- Swagger UI (local profile): http://localhost:8280/connectors-api/docs/ui

### PostgreSQL (port 5432)
- Databases: `am_core_db`, `am_device_db`, `am_connectors_db`
- User: `agimate`
- Password: `agimate_dev_password` (change in production)

### Centrifugo
- HTTP API: http://localhost:8000
- WebSocket: ws://localhost:8001

## Database Access

Connect to PostgreSQL:

```bash
docker-compose exec postgres psql -U agimate -d am_core_db
docker-compose exec postgres psql -U agimate -d am_device_db
docker-compose exec postgres psql -U agimate -d am_connectors_db
```

## Troubleshooting

### Rebuild services from scratch

```bash
docker-compose down
docker-compose build --no-cache
docker-compose up -d
```

### Clear all data and restart

```bash
docker-compose down -v  # Warning: deletes all volumes
docker-compose up -d
```

### Check container logs

```bash
docker-compose logs user-api
docker-compose logs device-api
docker-compose logs connectors-api
docker-compose logs postgres
docker-compose logs centrifugo
```

### Enter container shell

```bash
docker-compose exec user-api sh
docker-compose exec device-api sh
docker-compose exec connectors-api sh
docker-compose exec postgres sh
```

## Production Considerations

Before deploying to production:

1. Change all default passwords in `docker-compose.yml`
2. Use Docker secrets or external secret management
3. Configure proper volumes for PostgreSQL data persistence
4. Set up proper networking and firewall rules
5. Enable HTTPS/TLS
6. Configure resource limits (CPU, memory)
7. Set up monitoring and logging
8. Use production-grade Centrifugo configuration
9. Review and harden security settings

## File Structure

```
backend/
├── .env.example                # Environment variables template
├── .dockerignore               # Docker build exclusions
├── docker/
│   ├── docker-compose.yml           # Main orchestration file
│   ├── DOCKER.md                    # This documentation file
│   ├── init-multiple-databases.sh   # PostgreSQL init script
│   ├── centrifugo-config.json       # Centrifugo configuration
│   └── generate-jwt-keys.sh         # JWT key generation script
├── user-api/
│   └── Dockerfile              # User API container definition
├── device-api/
│   └── Dockerfile              # Mobile API container definition
└── connectors-api/
    └── Dockerfile              # Connectors API container definition
```
