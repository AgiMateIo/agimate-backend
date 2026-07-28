# ops

Локальный стенд: мастер первичной настройки `dev-init.sh`, `compose.yaml` с профилями
`infra` | `edge` | `full`, шаблоны конфигов, Caddyfile и инициализация баз.

Документация — [`docs/operations/local-stack.md`](../docs/operations/local-stack.md).

```bash
./dev-init.sh
docker compose --profile infra up -d
```

`centrifugo/config.yaml` и `../services/.env` генерятся скриптом и в git не хранятся; правится
то, из чего они рендерятся, — `templates/`.
