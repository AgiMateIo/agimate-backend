# CI/CD: как устроено в agimate-backend

## Общая схема

```
Push в master (с фильтром по путям)
    |
    v
validate-config         — проверка секретов и переменных
    |
    v
detect-changes          — определение, какие сервисы изменились
    |
    v
build-*-api (параллельно) — сборка Docker-образов и push в registry
    |
    v
update-infra            — обновление версий образов в infra-репозитории
```

## Файловая структура

```
.github/workflows/
  build-deploy.yml        # Основной workflow

ci/
  build-and-push.sh       # Сборка и push Docker-образа
  update-infra.sh         # Обновление infra-репозитория

services/<service>/
  Dockerfile              # Multi-stage Dockerfile для каждого сервиса
```

## Workflow: build-deploy.yml

### Триггеры

- Push в `master` с фильтром по путям (только если изменились файлы сервисов)
- Ручной запуск (`workflow_dispatch`)

```yaml
on:
  workflow_dispatch:
  push:
    branches: [master]
    paths:
      - 'services/user-api/**'
      - 'services/device-api/**'
      - 'services/connectors-api/**'
      - 'services/libs/**'
      - 'services/build.gradle.kts'
      - 'services/settings.gradle.kts'
```

### Job 1: validate-config

Проверяет наличие всех необходимых переменных и секретов. Если чего-то нет — пайплайн падает сразу.

**Необходимые переменные (vars):**
- `REGISTRY` — URL контейнер-реджистри
- `INFRA_REPO_SSH` — SSH-адрес infra-репозитория

**Необходимые секреты:**
- `CR_USERNAME`, `CR_PASSWORD` — логин/пароль реджистри
- `INFRA_DEPLOY_KEY` — SSH-ключ для push в infra-репо

### Job 2: detect-changes

Сравнивает `HEAD~1` с `HEAD` через `git diff`. Выставляет boolean-флаги: какие сервисы пересобирать.

Логика:
- Если изменились корневые build-файлы (`build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `libs/`) — пересобираются **все** сервисы
- Иначе — только те, чьи файлы изменились

### Jobs 3-5: build-{service}

Запускаются параллельно, только для изменённых сервисов. Каждый вызывает:

```bash
./ci/build-and-push.sh <service-name>
```

### Job 6: update-infra

Запускается после всех сборок. Условие: хотя бы одна сборка завершилась успешно. Собирает список успешных сервисов и вызывает:

```bash
./ci/update-infra.sh <service1> [service2] ...
```

## Скрипт: ci/build-and-push.sh

Что делает:
1. Определяет тег: `git describe --tags --always` (или из env `TAG`)
2. Собирает Docker-образ из `services/<service>/Dockerfile`
3. Тегирует как `<TAG>` и `latest`
4. Логинится в реджистри
5. Пушит оба тега

```bash
docker build -t "${IMAGE}:${TAG}" -t "${IMAGE}:latest" \
  -f "services/${SERVICE}/Dockerfile" "services"
```

## Скрипт: ci/update-infra.sh

Реализует **GitOps-паттерн**: вместо деплоя напрямую — обновляет версии образов в отдельном infra-репозитории.

Что делает:
1. Создаёт временную директорию, пишет туда SSH-ключ (удаляется через `trap`)
2. Клонирует infra-репозиторий по SSH
3. Для каждого сервиса вызывает `./scripts/update-image.sh <service> <tag>`
4. Коммитит и пушит изменения

Ключевая деталь: SSH-ключ **не** попадает в `~/.ssh` — хранится во временном файле и удаляется на exit.

## Dockerfile: Multi-stage паттерн

Все сервисы используют одинаковый двухэтапный Dockerfile:

**Build-этап** (`eclipse-temurin:21-jdk`):
- Копирует gradle wrapper и build-файлы всех модулей
- Скачивает зависимости (кешируемый слой)
- Копирует исходники и собирает `bootJar`

**Runtime-этап** (`eclipse-temurin:21-jre-alpine`):
- Лёгкий Alpine-образ с JRE
- Non-root пользователь `spring`
- Health check на `/actuator/health` (порт 8088)
- JVM-флаги: ZGC, 75% RAM, secure random

```dockerfile
ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0",
    "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
```

## Runner: self-hosted

Используется self-hosted runner (не GitHub-hosted). Для него есть свой Docker-образ в `ops/runner/`:
- На базе `ubuntu:22.04`
- Установлены: docker, git, Node.js, kustomize, act_runner
- Скрипт `runner.sh` для управления (build, register, start, stop)

## Как адаптировать для agimate-frontend

### 1. Создать структуру

```
.github/workflows/
  build-deploy.yml

ci/
  build-and-push.sh       # Скопировать, адаптировать путь к Dockerfile
  update-infra.sh          # Скопировать без изменений
```

### 2. Адаптировать workflow

- Изменить `paths` в триггерах под структуру фронтенд-проекта
- Убрать detect-changes если сервис один (не нужна мульти-сервисная логика)
- Оставить один build-job вместо трёх

### 3. Написать Dockerfile

Типичный multi-stage для фронтенда:
```dockerfile
# Build
FROM node:22-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Runtime
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

### 4. Настроить секреты

В репозитории agimate-frontend добавить те же vars и secrets:
- `REGISTRY`, `INFRA_REPO_SSH` (vars)
- `CR_USERNAME`, `CR_PASSWORD`, `INFRA_DEPLOY_KEY` (secrets)

### 5. Обновить infra-репозиторий

Добавить конфигурацию для фронтенд-сервиса, чтобы `update-image.sh` знал о нём.
