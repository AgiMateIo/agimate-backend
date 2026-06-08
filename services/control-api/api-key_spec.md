## Спецификация API-ключа AGM

### Формат

```
apikZ3h5YWJjZGVмxQ8pJvLmN5rT3sU2vWxYzAbCdEfGhIjKlMnOpQrStUvWxYz
```

**Без разделителей, 64 символа, фиксированные позиции**

### Компоненты

| Компонент  | Описание                           | Длина | Позиция | Пример         |
|:-----------|:-----------------------------------|:------|:--------|:---------------|
| `prefix`   | Тип ключа (4 строчные латинские)   | 4     | 0-4     | `apik`, `dvck` |
| `keyid`    | Timestamp + random для поиска в БД | 12    | 4-16    | `Z3h5YWJjZGVм` |
| `payload`  | Закодированный secret + checksum   | 48    | 16-64   | `xQ8pJvLm...`  |

### Используемые префиксы

| Префикс | Назначение               |
|:--------|:-------------------------|
| `apik`  | Service API key          |
| `dvck`  | Connector key (device)   |

### Генерация ключа

```
prefix   = "apik"                                              → 4 символа (строчные латинские)
keyid    = base64url(unix_timestamp_4bytes || random_5bytes)    → 12 символов
secret   = random(32 bytes)                                    → 32 байта
checksum = crc32(prefix || keyid || secret)                    → 4 байта
payload  = base64url(secret || checksum)                       → 48 символов

Итого: fullKey = prefix + keyid + payload                      → 64 символа
```

### Структура keyid

```
keyid (9 байт = 12 символов base64url):
┌─────────────────────┬─────────────────────────┐
│  unix timestamp     │        random           │
│     (4 байта)       │       (5 байт)          │
└─────────────────────┴─────────────────────────┘
```

- 9 байт кратно 3 → base64url даёт ровно 12 символов без паддинга
- Коллизия в пределах 1 секунды: 1 / 2^40 ≈ 1 на триллион
- Ключи сортируются по времени создания
- Можно извлечь время создания из keyid

### Пример

```
apikZ3h5YWJjZGVмxQ8pJvLmN5rT3sU2vWxYzAbCdEfGhIjKlMnOpQrStUvWxYz
└──┘└──────────┘└──────────────────────────────────────────────────┘
prefix(4) keyid(12)  payload(48)
```

### Валидация на сервере

1. Проверить длину = 64 символа
2. Извлечь компоненты по позициям:
   - `prefix` = `substring(0, 4)`
   - `keyid` = `substring(4, 16)`
   - `payload` = `substring(16, 64)`
3. Проверить формат:
   - `prefix` — 4 строчные латинские буквы (`^[a-z]{4}$`)
   - `keyid` — base64url (`^[A-Za-z0-9_-]+$`, 12 символов)
   - `payload` — base64url (`^[A-Za-z0-9_-]+$`, 48 символов)
4. Декодировать `base64url(payload)` → 36 байт
5. Разделить: `secret` (первые 32 байта) + `checksum` (последние 4 байта)
6. Пересчитать `crc32(prefix || keyid || secret)`
7. Сравнить с `checksum` → формат валиден ✓
8. Найти запись по `keyid` в БД
9. Сравнить `sha256(secret) == stored_hash` (case-insensitive) → ключ валиден ✓

### Хранение в БД (service_api_keys)

| Поле           | Тип          | Описание                                              |
|:---------------|:-------------|:------------------------------------------------------|
| `id`           | BIGSERIAL    | Первичный ключ (auto-increment)                       |
| `pub_id`       | UUID         | Уникальный публичный ID (UUIDv8)                      |
| `user_pub_id`  | UUID         | Владелец ключа                                        |
| `name`         | TEXT         | Пользовательское имя ключа                            |
| `description`  | TEXT         | Описание (nullable)                                   |
| `key_hash`     | TEXT         | SHA256 hex hash от secret                             |
| `key_id`       | TEXT         | 12-char base64url ID (индекс для поиска)              |
| `enabled`      | BOOLEAN      | Включён/выключен (default: true)                      |
| `deleted_at`   | TIMESTAMP    | Soft delete (nullable)                                |
| `created_at`   | TIMESTAMP    | Дата создания (NOT NULL, DEFAULT CURRENT_TIMESTAMP)   |
| `updated_at`   | TIMESTAMP    | Дата обновления (NOT NULL, DEFAULT CURRENT_TIMESTAMP) |

### Безопасность

- Ключ показывается пользователю **только один раз** при создании
- В БД хранится только SHA256 hash — утечка БД не компрометирует ключи
- SHA256 достаточен для 256-бит секрета (brute-force нереален)
- CRC32 защищает целостность всех частей ключа
- keyid содержит timestamp — можно определить время создания (не секрет)

### Преимущества формата

1. **Простота парсинга** — только `substring()`, без `split()`
2. **Компактность** — 64 символа
3. **Фиксированная длина** — всегда ровно 64 символа
4. **Без разделителей** — чистый позиционный формат
5. **Читаемость** — можно визуально определить тип по prefix
