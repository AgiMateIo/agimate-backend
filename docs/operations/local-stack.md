# Local stack

Первый запуск — две команды:

```bash
cd ops
./dev-init.sh                      # ключи, services/.env и конфиги из него
docker compose --profile infra up -d
```

Дальше сервисы поднимаются из IDE или `bootRun` — ключи уже на местах.

## `dev-init.sh`

Скрипт генерирует всё, что нужно стенду, складывает значения в `services/.env` и рендерит из
него конфиги, которые эти значения читают. Три пары значений должны совпасть между процессами —
именно они и разъезжаются, когда ключи собирают руками:

| Пара | Кто чем владеет |
| --- | --- |
| JWT ES256 | user-api подписывает токены, control-api проверяет публичной половиной |
| Centrifugo ES256 | control-api подписывает клиентские токены, Centrifugo проверяет их публичной половиной |
| Ключ воркер-пула | agent-worker предъявляет полный ключ, control-api хранит его хэш (authkey) |

Что генерится:

| Файл | Содержимое |
| --- | --- |
| `services/.env` | источник истины: ключи, OAuth-креды, машинно-зависимые URL |
| `ops/centrifugo/config.yaml` | из `ops/templates/centrifugo.config.yaml`, с PEM публичного ключа |
| `application-local.yaml` ×3 | из `ops/templates/<service>.application-local.yaml` |

Всё это в `.gitignore` — значения локальные для машины.

Скрипт идемпотентен: значения, уже лежащие в `services/.env`, он не трогает и генерит только
недостающие, поэтому повторный запуск чинит полуготовый чекаут, а не ротирует ключи. Если
`.env` ещё нет, а `application-local.yaml` уже написаны руками, значения вычитываются оттуда —
настроенный до появления скрипта чекаут не теряет ключи и не разъезжается с конфигом Centrifugo.
`application-local.yaml` он по умолчанию не перезаписывает — для этого `--force`:

```bash
./dev-init.sh            # добрать недостающее
./dev-init.sh --force    # плюс перерендерить application-local.yaml из services/.env
./dev-init.sh --yes      # без вопросов, все дефолты
```

Спрашивает три вещи, у всех есть дефолт: язык системного контента (`ru`), публичный
WebSocket-URL Centrifugo (`ws://localhost:9000`; для доступа с телефона — LAN-IP машины, потому
что `*.agimate.lc` там не резолвится) и OAuth-креды Google, Yandex, GitHub и VK ID (у VK спрашивается
только id приложения — секрета у него нет, обмен кода закрыт PKCE). Пропущенного OAuth-провайдера
инсталляция просто не предлагает: его регистрация выбрасывается на старте, сервис поднимается без
него, не работает только вход через него. Добавить потом — вписать креды в `services/.env`
(`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ПРОВАЙДЕР>_CLIENT_ID`), дальше `--force`.

Требования: `openssl`, `python3`, `awk`, `fold`.

Полный каталог переменных (S3, файловый слой, TLS для gRPC) — в `services/.env.example`; скрипт
пишет только то, без чего стенд не поднимается.

## Профили compose

| Профиль | Что содержит | Для чего |
| --- | --- | --- |
| `infra` | PostgreSQL, Centrifugo | Ежедневный цикл: сервисы идут из IDE или `gradle bootRun` |
| `edge` | Caddy | Добавляет хостнеймы `*.agimate.lc` на `:8000` перед сервисами хоста |
| `full` | infra + user-api, control-api, agent-worker | Смоук: образы собираются и стек стартует |

```bash
cd ops

docker compose --profile infra up -d                  # postgres + centrifugo
docker compose --profile infra --profile edge up -d   # + caddy
docker compose --profile full up -d                   # всё в контейнерах
```

Под `full` контейнеры конфигурируются только из `services/.env`: `application-local.yaml`
исключён из образа (`.dockerignore`), чтобы LAN-адреса и ключи с машины разработчика не
запекались в имидж.

Из этого следует, что профили не смешиваются: половина стека в контейнерах, половина из
`bootRun` работает, только пока `.env` и `application-local.yaml` содержат одни и те же ключи.
Если yaml писался руками до появления `.env`, JWT-пары разъедутся и control-api отвергнет токены
user-api. Приводит оба набора к одному `./dev-init.sh --force`.

## Ежедневный цикл

```bash
cd ops && docker compose --profile infra up -d
cd ../services
./gradlew :user-api:bootRun
./gradlew :control-api:bootRun
./gradlew :agent-worker:bootRun
```

Порт control-api (8180, чтобы не спорить с user-api на 8080) задан в его
`application-local.yaml` — тот же порт, куда Caddyfile проксирует `/control/*`.

## Базы

`ops/postgres/init-databases.sh` создаёт все три при первом старте пустого тома:

| База | Владелец |
| --- | --- |
| `am_user_db` | user-api |
| `am_control_db` | control-api |
| `dbos` | системная база DBOS — **общая**: control-api кладёт `run_agent`, agent-worker его забирает. Оба должны смотреть сюда, иначе у очереди молча нет консьюмера |

Пользователь `agimate`, пароль `agimate_dev_password`. Пересоздать с нуля:
`docker compose --profile infra down -v`.

## Конфиг Centrifugo

Правится шаблон `ops/templates/centrifugo.config.yaml`, а не отрендеренный
`ops/centrifugo/config.yaml` — последний перезапишется следующим запуском скрипта.

Centrifugo молча игнорирует ключи, которых не знает, поэтому опция из v5 не ломает старт, а
тихо выключает функцию. Проверять так:

```bash
docker run --rm -v "$PWD/centrifugo/config.yaml:/c.yaml" \
  centrifugo/centrifugo:v6 centrifugo checkconfig -c /c.yaml
```

Содержимое смонтированного файла для compose не изменение — `up -d` контейнер не пересоздаст,
нужен `docker compose --profile infra restart centrifugo`.

## Хостнеймы `.lc`

Профиль `edge` ожидает их в `/etc/hosts`:

```
127.0.0.1 www.agimate.lc api.agimate.lc centrifugo.agimate.lc
```

Caddy работает в контейнере, а сервисы за ним — на хосте, поэтому Caddyfile ходит в
`host.docker.internal`. На Linux это имя даёт маппинг `host-gateway` из `compose.yaml`, на
Docker Desktop оно встроенное.

## Порты

| | Хост |
| --- | --- |
| PostgreSQL | 5432 |
| Centrifugo | 9000 → 8000 |
| Caddy | 8000 |
| user-api | 8080 |
| control-api | 8180 |
| agent-worker | — (наружу не публикует; health на 8089 внутри неймспейса control-api) |

Под `full` agent-worker делит сетевой неймспейс с control-api: он отказывается ходить plaintext
gRPC куда-либо кроме loopback, и так `localhost:9091` действительно указывает на control-api —
проверку не пришлось ослаблять.
