---
status: deferred
created: 2026-07-19
---

# Terminal-коннектор + sandbox-runner (control-api) — дизайн

Даёт агенту инструмент **исполнять bash в серверной песочнице** и рабочий каталог (workspace),
файлы которого пользователь **просматривает отдельно** от чата. Статус — дизайн (не реализовано).

## Позиционирование: BACKEND-двойник ACP

Тулы `run_command`/`read_file`/`write_file`/`list_dir` уже есть у ACP-коннектора
(`docs/contracts/acp.md`), но ACP их **делегирует в IDE пользователя** (обратный JSON-RPC,
песочница = машина юзера). Terminal — тот же набор тулов, но **`execution_locus = BACKEND`**: исполнение
в песочнице **на нашей инфраструктуре**. Переиспользуем без изобретения заново:

- формы тулов и поведенческие хинты (`destructiveHint`/`openWorldHint` → воркер оборачивает вывод
  untrusted-маркером);
- бюджеты команд под `agent.tool.poll-timeout` (дефолт 60s) — как ACP-`run_command` (~45s);
- «обрыв исполнителя = валидный error tool-result, а не зависание».

Отличие: у ACP песочница эфемерна и живёт в IDE; у terminal песочница и **workspace принадлежат нам** —
поэтому появляется вторая, отсутствующая у ACP, полоса: **отдельный просмотр файлов workspace**.

## Отношение к существующим слоям

| Слой | Переиспользуем | Что нового |
|---|---|---|
| Connector SPI (`connectors/core`) | весь dispatch/gRPC/ABAC/listing — без изменений | новый `internal/terminal/` |
| ACP (`internal/acp`) | формы тулов, хинты, бюджеты, trust boundary | исполнение в BACKEND-песочнице вместо делегации в IDE |
| Файлы (`docs/connectors/files.md`, `controlapi/storage`) | `BlobStore`/`s3` для durable-снапшотов (фаза 2); промоушен файла workspace → `agf_` для вложений в канал | workspace = **иерархическое дерево**, не плоские `agf_`-блобы по ссылке |
| DBOS/worker-протокол | без изменений — файлы не ходят через `output_json` | — |

**Ключевой инвариант дизайна: compute ≠ workspace.** Песочница (где крутится bash) — эфемерная
вычислялка; workspace (файлы) — durable-каталог, адресуемый ключом коннекшена. Песочницу можно снести —
файлы остаются. Это даёт три бесплатных выигрыша:

1. просмотр файлов **не зависит** от живости песочницы — ровно требование «просматривать отдельно»;
2. файлы **не проталкиваются** через `output_json` тул-результата (это сожгло бы контекст и уперлось в
   лимиты — та же причина, что у pass-by-reference в `files.md`) — UI читает их отдельным REST-запросом;
3. технологию изоляции можно менять, **не трогая ни коннектор, ни эндпоинты просмотра** (см. §Изоляция).

## Топология: отдельный сервис `sandbox-runner`

`execution_locus = BACKEND` означает, что `ToolExecutionService` (`@Async`) зовёт коннектор in-proc. Но
control-api **не должен** держать привилегированный доступ к докер-сокету и форкать контейнеры — это
недопустимая attack surface на главном API. Поэтому:

```
worker ──gRPC──► control-api ──(BACKEND, in-proc)──► TerminalConnectorService
                                                          │ HTTP/gRPC-клиент
                                                          ▼
                                                    sandbox-runner  (headless, как agent-worker)
                                                     ├─ владеет рантаймом контейнеров (gVisor)
                                                     ├─ владеет workspace-volume
                                                     └─ узкий API (exec + fs)
```

`sandbox-runner` — headless-сервис по образцу `agent-worker`. Узкий контракт:

| Метод | Назначение |
|---|---|
| `POST /exec` `{workspaceKey, command, timeoutMs, cwd?}` | выполнить команду → `{stdout, stderr, exitCode, truncated, timedOut}` |
| `GET /fs/tree?workspaceKey&path` | листинг узла: `[{name, type, size, mtime}]` |
| `GET /fs/file?workspaceKey&path` | контент файла (cap по размеру, binary-detection) |
| `PUT /fs/file` | запись из UI (опц.) |
| `DELETE /workspace?workspaceKey` | снос песочницы+каталога (reaper/lifecycle) |

`TerminalConnectorService` — просто клиент этого контракта. Привилегированные операции остаются вне
control-api; апгрейд изоляции меняет только внутренность runner'а.

- **Auth**: runner — это API «выполни произвольную команду», без аутентификации это готовый
  RCE-эндпоинт во внутренней сети. Контур тот же, что у gRPC воркера (P0-харденинг): TLS +
  shared-token (или mTLS); секрет — из secrets, не из yaml.
- **Shell-модель — stateless**: каждый `exec` — свежий shell от корня workspace (`cwd?` —
  опциональный параметр вызова). Состояние (`export`, активация venv, `cd`) **не переживает**
  вызов — модель обходит это цепочками `cd build && make` (ровно так работает Claude Code;
  отдельного пер-сессионного состояния сервер не хранит). Долгоживущий shell per-session
  сознательно не берём: вернул бы топтание env/cwd между сессиями и PTY-менеджмент.
- **Base image** фиксируется в runner'е (одна на всех в v1): debian/ubuntu-slim + bash,
  coreutils, git, curl, python3, node — состав определяет и полезность, и attack surface;
  расширение/кастомные образы — по потребности (roadmap).

## Деплой: v1 — выделенная VM вне кластера

Песочницы — untrusted-нагрузка; запуск её внутри общего k8s-кластера тянет privileged pod,
выделенный node pool, CNI с egress-NetworkPolicy, блокировку metadata API, RWX-storage. Для v1
этого не делаем: **runner живёт на отдельной VM вне кластера** (паттерн E2B — песочницы не в
vanilla-k8s):

- обычная Linux-VM (KVM/nested-virt **не** нужен — gVisor userspace): Docker + runsc, runner —
  systemd-сервис, workspace — локальный диск VM;
- egress deny — nftables на хосте; ingress — только control-api (firewall + TLS + token);
  кластер не трогается вообще, control-api ходит по HTTP из своего контура;
- provisioning VM — воспроизводимый (cloud-init/ansible), не руками — иначе «pet»-дрифт;
- **отказ VM — мягкая деградация**: тулы terminal возвращают `ConnectorException` → `isError`,
  раны продолжаются (тот же контракт, что «обрыв IDE» у ACP); недоступен и просмотр workspace —
  UI показывает ошибку, данные не теряются (диск переживает рестарт runner'а);
- потолок — вертикальный; второй хост = шардирование по `workspaceKey` (roadmap, не v1).

Лестница масштабирования (контракт runner'а неизменен на всех ступенях):
VM + Docker/runsc (v1) → k8s: privileged runner-pod со вложенным containerd+runsc на tainted
node pool → Kata + Firecracker на bare-metal пуле (ужесточение threat model).

## Изоляция

Для v1 — **gVisor (runsc)**: user-space kernel, перехватывающий syscalls; сильной-достаточно изоляции
для произвольного LLM-bash (голый Docker с общим kernel для untrusted мультитенанта недостаточен),
тривиальные bind-mount workspace-каталога, docker-эргономика. Managed-сервисы (E2B cloud / Fly)
исключены выбором self-hosted; **self-hosted E2B** (open-source, Firecracker) существует, но
исключается по операционной цене — Nomad-оркестрация ради одного коннектора; лёгкие
nsjail/bubblewrap — по слабости границы (общий kernel, seccomp-профили руками).

**Dev-режим**: runsc — Linux-only, на macOS-машине разработчика не запускается. Рантайм —
конфиг-переключатель `app.sandbox.runtime = runsc | runc` (тот же паттерн, что
`app.files.backend: local|s3`): prod — gVisor, локальная разработка — обычный runc через
Docker Desktop. Контракт runner'а от рантайма не зависит.

Апгрейд без изменения контракта коннектора и эндпоинтов просмотра (следствие compute≠workspace):

| Технология | Граница | Bind-mount | Старт | Опер. цена |
|---|---|---|---|---|
| **gVisor (runsc)** — v1 | user-space kernel | тривиально (drop-in runtime) | 0.1–0.3s | средняя |
| Kata + Firecracker | аппаратный VM-барьер, OCI | virtio-fs | ~0.15s | высокая |

Raw Firecracker сознательно **не** берём: низкоуровневый API (создать VM, диски, tap-сеть, свой
оркестратор), требует KVM/bare-metal/nested-virt, нет простого bind-mount. Путь к VM-изоляции —
Kata (Firecracker как VMM под containerd), когда gVisor перестанет устраивать по threat model.

## Workspace

**Ключ — коннекшен terminal-коннектора** (`workspaceKey = connection_id`). Terminal — контекстный
коннектор уровня `time`/`board`: `supported_scopes = [AGENT]`, коннекшен материализуется find-or-create
на агента (привязка через скилл). Значит `connection_id ↔ agentId` 1:1 — ключ workspace эквивалентен
паре `(agent, connection)`.

- **v1 — ephemeral volume**: per-`workspaceKey` каталог на network-volume, которым владеет runner. Без
  S3-зависимости. `idle-TTL` + reaper убирают простаивающие песочницы; файлы живут best-effort до чистки
  каталога по TTL. Удаление коннекшена (`deleted_at`) — тоже событие lifecycle: reaper сносит
  песочницу и каталог осиротевшего `workspaceKey` (`DELETE /workspace`).
- **фаза 2 — durable + resume**: снапшот каталога в MinIO/S3 через `BlobStore` (`s3`) из `controlapi/storage`,
  workspace переживает всё и восстанавливается. Добавляет синк-семантику — вводим, только если понадобится.

### Параллельные сессии — **не** сериализуем

Один `(agent, connection)` может иметь несколько параллельных сессий; все пишут в **один** workspace.
Это **фича, а не баг** — как несколько сессий IDE делят один репозиторий. Разбор:

- инвариант **single-writer-per-session** у вас про **DBOS-очередь** (`sessionId` = ключ партиции,
  упорядоченная обработка сообщений сессии) — это контракт транспорта сообщений, **не** файловой системы;
  общий workspace его не нарушает (у каждой сессии свой упорядоченный поток);
- параллельный доступ к каталогу — чистый POSIX: процессы, пишущие разные файлы, не конфликтуют.

Поэтому **не сериализуем и не плодим копии**. Каждая команда — **независимый `exec`** (свежий
stateless-shell от корня workspace, см. §Топология) — не единый долгоживущий shell на workspace,
иначе `cwd`/env двух сессий топтали бы друг друга. `docker exec` конкурентен. File-level — last-writer-wins, как у команды в одном
репо. Единственное реальное отличие от мультисессии человека — нет живого координатора, возможна
деструктивная интерференция (`rm`/`git checkout` в момент чтения соседней сессии); это управляемый редкий
риск уровня «двое коллег в одном репо», спец-механизм в v1 не нужен (YAGNI; advisory-лок — только если
гонки всплывут по факту).

## Коннектор (`internal/terminal/`)

**Traits** (`execution_locus = BACKEND`, `transport_direction = OUTBOUND`, `definition_binding = STATIC`,
`supported_scopes = [AGENT]`) — как `time`/`board`, но locus BACKEND.

```
TerminalConnectorService  extends BaseConnectorHandler
                          implements InternalConnectorHandler
      CONNECTOR_CODE = "terminal"
      traits(): OUTBOUND / BACKEND / STATIC / scope=[AGENT]

TerminalToolService  (@Component)  — HTTP/gRPC-клиент sandbox-runner'а
```

Тулы (голые snake_case имена; agent-facing — `terminal.<name>`, namespace = `connector_code`, синглтон):

| Тул | runner-вызов | Хинты |
|---|---|---|
| `run_command(command, cwd?)` | `POST /exec` | destructive, openWorld |
| `read_file(path, line?, limit?)` | `GET /fs/file` | readOnly, openWorld |
| `list_dir(path?)` | `GET /fs/tree` | readOnly, openWorld |
| `write_file(path, content)` | `PUT /fs/file` | destructive, openWorld |

Формы — как у ACP (`read_file` с `line?`/`limit?` включительно). Инвариант «файлы не идут через
`output_json`» относится к browsing-полосе (UI); `read_file`/`run_command` **идут** через output —
поэтому у них жёсткие caps на размер (сверх трункейта воркера), крупное читается кусками.

- `workspaceKey` = `ConnectorEnvHolder.current().connectionId()`; per-session состояния нет —
  exec stateless от корня workspace (см. shell-модель в §Топология).
- Ошибки — только `ConnectorException` (её message безопасно уходит агенту). Обрыв/таймаут runner'а →
  `ConnectorException` → `error` в `tool_call_logs` → воркер отдаёт модели `isError`, ран продолжается.
- **Бюджет** `run_command` — под `agent.tool.poll-timeout` (по таймауту: `kill` + частичный вывод с
  `timedOut: true`). Тул блокирует поток `toolExecutor` на время `/exec` — как ACP-тулы.

**ABAC** — штатно: доступ = binding агента (`agent_connections`) на terminal-коннекшен (заводит terminal-скилл);
default-allow, точечный DENY в `agent_connection_policies` режет конкретный тул (напр. `run_command`) до
исполнения. Вывод `openWorld` → воркер оборачивает untrusted.

## Просмотр workspace «отдельно» (app-поверхность)

Новые read-эндпоинты в `controller/app/` (напр. `AppWorkspaceController`), проксирующие fs-API runner'а —
**отдельный REST-path, минуя тул-результат**:

| Endpoint | Отдаёт |
|---|---|
| `GET /app/workspace/{connectionId}/tree/?path=` | дерево узла: `[{name, type, size, mtime}]` |
| `GET /app/workspace/{connectionId}/file?path=` | контент файла |

- **Граница доступа** — ownership по `user_id` (как `files`: коннекшен принадлежит владельцу, резолв чужого
  workspace невозможен по построению); резолв субъекта — как остальные app-эндпоинты.
- Изображения — inline; активный контент — `octet-stream` + `nosniff` + CSP-sandbox (те же правила, что
  выдача `files`).
- **Стык с файловым слоем**: чтобы агент **приложил** файл workspace к ответу в канал/чат — файл
  промоутится в `files` (`FileStorageService` → `agf_<uuid>`) и уходит attach-конвенцией
  (`[[attach:agf_…]]`, см. `files.md`). Просмотр в UI workspace — прямой (не через `agf_`); вложение в чат —
  через `agf_`. Две полосы не конкурируют.

## Долгоживущие команды — главное известное ограничение v1

`run_command` ограничен бюджетом под `poll-timeout` (~45s). Для ACP этого хватает (IDE-сценарии:
запустить тест, grep), для терминала — **нет**: `pip install` / `npm install` / `git clone` / сборка —
это не редкий будущий случай, а первое, что агент сделает, и почти всё из этого в 45s не влезает.
Поэтому это не YAGNI-сноска, а осознанно вырезанный из v1 скоуп со следующим шагом сразу за ним
(фаза 5 в §Порядок работ):

- **detached-run**: `run_command(background=true)` стартует процесс в песочнице и возвращает handle;
  `check_command(handle)` — статус + накопленный вывод; kill по handle. Runner и так владеет
  процессами — механика дешёвая;
- либо фоновая строка `connector_jobs` (`kind = AGENT`) с доставкой результата триггером — как
  `time.schedule`; выбор между вариантами — при проектировании фазы 5.

До фазы 5 v1 честно пригоден только для коротких команд; операторам terminal-агентов — поднимать
`agent.tool.poll-timeout` при необходимости.

## Безопасность

- **Symlink-эскейп в fs-полосе — обязательный guard**: runner обслуживает `GET /fs/*` с
  хост-стороны volume, поэтому `ln -s /etc/passwd leak.txt` внутри песочницы без защиты отдал бы
  UI/`read_file` **хостовый** файл. Все fs-операции runner'а резолвят пути с конфайнментом корнем
  workspace: `openat2(RESOLVE_BENEATH)` (fallback — покомпонентный резолв с `O_NOFOLLOW`), `path`
  нормализуется (`..` не выводит за корень). Внутри песочницы симлинки при этом легальны — bash их
  видит как обычно; guard стоит только на хост-стороне fs-API.
- **Egress по умолчанию deny**: LLM-bash + сеть = экфильтрация и SSRF во внутреннюю сеть (созвучно
  SSRF-guard'у MCP-коннектора). Это режет и полезность (pip/npm — см. §Долгоживущие команды), в v1 —
  осознанно; развязка в roadmap: allowlist per-connection или локальный прокси на пакетные зеркала
  (pypi/npm/apt) — закрывает основную потребность, не открывая экфильтрацию.
- **Auth runner-API**: TLS + shared-token/mTLS (см. §Топология) — API «выполни команду» не должен
  быть доступен внутренней сети анонимно.
- **Никаких секретов в env песочницы** — платформенные LLM-ключи и пр. недосягаемы (созвучно правилу
  «секреты не в DBOS-чекпоинт»). Креды коннектора у internal-terminal отсутствуют по определению.
- **Лимиты**: CPU/mem/pids/disk-quota на workspace, wall-clock timeout, cap на stdout и размер файла;
  rate-limit на `exec` per-user (рядом с `InboundRateLimiter`, как квоты `files`) — повторные
  wall-clock-бёрсты не должны превращаться в бесплатный компьют.
- **Мультитенант-изоляция**: отдельная песочница + отдельный каталог на коннекшен; резолв чужого workspace
  закрыт ownership'ом.
- **Trust boundary**: вывод команд `openWorld` → воркер оборачивает untrusted-маркером (как ACP).

## Порядок работ

1. **`sandbox-runner`** (headless, gVisor + runc-dev-режим): warm-контейнер per `workspaceKey`,
   workspace = каталог на volume, API `exec` + `fs tree/read/file` (TLS + token, path-конфайнмент
   с symlink-guard'ом), egress deny, ресурс-лимиты, idle-TTL + reaper; прод-деплой — выделенная VM
   вне кластера (см. §Деплой).
2. **`internal/terminal/`** коннектор: `TerminalConnectorService` + `TerminalToolService` (клиент runner'а),
   `terminal`-скилл (заводит binding). Никаких изменений в dispatch/gRPC/scheduler.
3. **`AppWorkspaceController`**: `tree`/`file`, ownership-ABAC, правила выдачи контента как у `files`.
4. **Промоушен workspace → `agf_`** для вложений в канал (переиспользует `FileStorageService`).
5. **Долгоживущие команды**: detached-run (`background=true` + `check_command`) либо `connector_jobs`
   (`kind = AGENT`) — см. одноимённый раздел; планируется сразу за v1, не «по потребности».

## Roadmap / открытые вопросы

- durable workspace (MinIO-снапшоты через `BlobStore`, resume) — фаза 2;
- изоляция gVisor → Kata/Firecracker при ужесточении threat model;
- egress-развязка: allowlist per-connection / прокси на пакетные зеркала (см. §Безопасность);
- кастомные/расширенные base image (per-connection выбор образа);
- `channelOnly`-ограничение (тулы terminal видны только из своего канала) — общий roadmap-пункт с ACP,
  см. `docs/architecture/connectors.md`;
- запись из UI (`PUT /fs/file`) и multipart-загрузка в workspace — по потребности.
