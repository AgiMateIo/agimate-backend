---
status: deferred
created: 2026-06-02
---

# Mail connector (отложено)

Заметки по проектированию интеграции `mail` (тип `INTEGRATION`) для отправки и чтения писем через SMTP+IMAP.
Задача отложена — этот документ фиксирует результат предварительного анализа, чтобы вернуться к нему позже без повторного исследования.

## Цель

Добавить коннектор `code='mail'`, `type=INTEGRATION`, поддерживающий:

- отправку писем (SMTP);
- получение последних N писем / поиск (IMAP);
- (опционально) триггер «получено новое письмо».

Целевые провайдеры: Gmail, Yandex Mail, Mail.ru, плюс произвольный IMAP/SMTP-хост.

## Как это ложится на существующую архитектуру

Архитектура коннекторов уже готова к новому `INTEGRATION` — добавление чисто аддитивное:

- Запись в таблице `connectors` через Liquibase: `code='mail'`, `type=INTEGRATION`, `credential_fields=[...]`.
- Новый Handler `connectors/integrations/mail/MailConnectorService.java implements IntegrationConnectorHandler extends BaseConnectorHandler` по образцу `TelegramConnectorService` (`services/control-api/src/main/java/ru/agimate/controlapi/connectors/integrations/telegram/TelegramConnectorService.java`). Регистрация в `ConnectorRegistry` происходит автоматически через `@Component`.
- Шифрование секретов уже работает «из коробки»: `SecretEncryptionService` (AES-GCM) кладёт credentials в `integration_credentials.encrypted_data`. Никаких новых таблиц не требуется.
- REST API расширять не нужно: эндпоинты `/manage/integrations/credentials/*` универсальны, набор полей диктуется методом `getCredentialFields()` хендлера.
- Push vs Pull: телеграм поддерживает webhook или polling. Почта IMAP — pull-only, поэтому `supportsWebhooks() = false`. Для подписки на новые письма — отдельный poller-сервис (по образцу telegram-поллера в `TelegramToolService`), либо вообще не делать push, а ограничиться tool `mail.fetch_recent`.

## Получение креденшелов от пользователя — три варианта

### A. App Password — рекомендованный MVP

Пользователь сам в настройках своей почты создаёт «пароль приложения» и вставляет его в форму. Все три целевых провайдера это поддерживают:

- **Gmail** — обязательно включённый 2FA, App Password в `myaccount.google.com/apppasswords`.
- **Yandex** — пароль для внешних приложений в настройках почты, IMAP/SMTP включается там же.
- **Mail.ru** — пароль для внешних приложений в настройках безопасности.

Credentials fields: `["email", "password"]` + опционально `["imap_host", "imap_port", "smtp_host", "smtp_port"]` для произвольных почтовиков. Хосты для популярных провайдеров автодетектим по домену email-а:

| Провайдер                          | IMAP                       | SMTP                                              |
|------------------------------------|----------------------------|---------------------------------------------------|
| gmail.com                          | imap.gmail.com:993 (SSL)   | smtp.gmail.com:465 (SSL) или 587 (STARTTLS)       |
| yandex.ru / yandex.com             | imap.yandex.ru:993         | smtp.yandex.ru:465                                |
| mail.ru / list.ru / inbox.ru / bk.ru | imap.mail.ru:993         | smtp.mail.ru:465                                  |
| custom                             | задаёт пользователь        | задаёт пользователь                               |

**Плюсы**: ноль внешних регистраций приложений, работает локально, никакой OAuth-инфраструктуры.
**Минусы**: пользователю нужно включить 2FA и сходить в настройки почты — UX так себе. Gmail может в любой момент отозвать App Passwords (уже грозились).

### B. OAuth 2.0 + XOAUTH2

Пользователь жмёт «Подключить Gmail», уходит на consent screen Google, возвращается с authorization code, мы меняем его на refresh_token, и при каждой операции IMAP/SMTP делаем XOAUTH2 SASL c access_token. Аналогично для Yandex и Mail.ru — у обоих есть OAuth 2.0.

**Плюсы**: лучший UX, отзыв доступа в один клик у провайдера, без 2FA-плясок.
**Минусы**:

- Нужна регистрация OAuth-приложения в каждом из трёх провайдеров + верификация (Google требует проверку, без неё лимит 100 пользователей и баннер «unverified app»).
- В проекте сейчас OAuth-флоу для интеграций нет совсем — придётся строить инфраструктуру: redirect endpoint, state-токен, refresh-логика, обновление зашифрованных credentials с новым access_token.
- Для каждой интеграции придётся хранить `access_token`, `refresh_token`, `expires_at`, `provider` — формат credentials становится разнородным.

### C. Plain login

Большинство провайдеров уже не пускают по обычному паролю аккаунта без app password. Не вариант.

### Решение для MVP

**Вариант A (App Password) с заделом на B**:

- В credentials добавить поле `auth_type` со значением `password` (потом появится `oauth`).
- Для популярных провайдеров детектить хосты по домену email (lookup-таблица в коде Handler) — пользователь вводит только `email + password + name`. Для всех остальных — поля `imap_host/imap_port/smtp_host/smtp_port` обязательны.
- В UI выпадушка с пресетами «Gmail / Yandex / Mail.ru / Custom IMAP/SMTP» — это уровень фронта, бэк просто валидирует.
- OAuth добавляем следующим этапом, когда понадобится продакшен-UX. Это будет отдельная миграция (`auth_type=oauth`, новые поля credentials), Handler уже готов разветвляться по `auth_type`.

## Зависимости и tools

**Библиотека**: `jakarta.mail:jakarta.mail-api` + `org.eclipse.angus:angus-mail` (преемник JavaMail для Jakarta EE 10 / Spring Boot 3.x). Работает SMTP, IMAP, IMAP IDLE и XOAUTH2 SASL — на будущее.

Набор `@Tool` для агента:

| Tool                                  | Что делает                                                  |
|---------------------------------------|-------------------------------------------------------------|
| `mail.send`                           | `to, cc, bcc, subject, body, html?, attachments?` → SMTP    |
| `mail.fetch_recent`                   | `folder='INBOX', limit=20, unread_only=false` → IMAP        |
| `mail.fetch_by_id`                    | `messageUid` → одно письмо целиком                          |
| `mail.search`                         | `from?, subject_contains?, since?, unread_only?` → IMAP     |
| `mail.mark_read` / `mail.mark_unread` | флаги SEEN                                                  |
| `mail.delete`                         | флаг DELETED + expunge                                      |
| `mail.list_folders`                   | список папок (INBOX, [Gmail]/Sent и т. п.)                  |

`validateCredentials()` — делаем `Store.connect()` к IMAP с введёнными данными, выставляем `platformIdentifier = email`, `displayName = "Mail: " + email`. Это даст осмысленную ошибку при вводе кривого пароля, по образцу `TelegramHandler.validateCredentials`.

## Триггер «новое письмо» — отдельная подзадача

Если нужны триггеры (как `telegram.message_received`), у IMAP два подхода:

- **Polling**: раз в N секунд для каждой включённой mail-интеграции дергаем UID-and-newer-than-last-seen. Простой, понятный, дёшево сделать по аналогии с telegram-поллером в `TelegramToolService`. Хранить `last_seen_uid` в `integration_credentials.encrypted_data` или в отдельной колонке.
- **IMAP IDLE**: висим на сокете и получаем push от сервера. Эффективнее, но один поток на интеграцию — для 1000 пользователей не зайдёт без отдельного воркера/перебалансировки. Для MVP не делаем.

Если триггеры на старте не нужны (только tools sending/reading) — можно вообще ничего не делать с `normalizeInbound`, `getPredefinedTriggers` и polling. Это сильно меньше кода.

## Скоуп MVP (когда вернёмся)

1. Liquibase-миграция: `INSERT INTO connectors (code='mail', type=INTEGRATION, credential_fields=['auth_type','email','password','imap_host','imap_port','smtp_host','smtp_port'], features={...})`.
2. `MailHandler` с `auth_type=password`, автодетектом хостов по домену, `validateCredentials` через `Store.connect`.
3. Tools: `send`, `fetch_recent`, `fetch_by_id`, `search`, `mark_read`, `delete`, `list_folders`. Без триггеров.
4. Без OAuth, без polling, без IDLE — заделы оставлены в местах расширения.
5. Зависимость `angus-mail` в `services/control-api/build.gradle`.
6. Тест с greenmail (in-memory IMAP/SMTP) в JUnit.

## Открытые вопросы

1. App Password как старт устраивает, или сразу хотим OAuth для Gmail (это +1–2 недели работы и регистрация приложений в Google/Yandex/Mail.ru)?
2. Триггеры на новое письмо нужны на старте, или достаточно tools («агент дёрнул `mail.fetch_recent` и посмотрел»)? Это меняет объём примерно в 2 раза.
3. Произвольный IMAP/SMTP (custom-сервер) поддерживаем сразу, или MVP только трёх провайдеров с захардкоженными хостами?

## Ключевые файлы-образцы (на момент анализа)

- `services/control-api/src/main/java/ru/agimate/controlapi/connectors/integrations/telegram/TelegramHandler.java` — образец Handler-а.
- `services/control-api/src/main/java/ru/agimate/controlapi/connectors/core/BaseConnectorHandler.java` — базовый класс.
- `services/control-api/src/main/java/ru/agimate/controlapi/service/secret/SecretEncryptionService.java` — AES-GCM шифрование credentials.
- `services/control-api/src/main/java/ru/agimate/controlapi/connectors/integrations/IntegrationsRegistry.java` — авто-регистрация хендлеров.
- `services/control-api/src/main/java/ru/agimate/controlapi/database/entities/Connector.java` — сущность коннектора.
- `services/control-api/src/main/java/ru/agimate/controlapi/database/entities/IntegrationCredentials.java` — сущность credentials.
- `services/control-api/src/main/java/ru/agimate/controlapi/connectors/integrations/telegram/TelegramToolService.java` — образец poller-а на случай триггеров.
