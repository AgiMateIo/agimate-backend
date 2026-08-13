---
status: deferred
created: 2026-08-02
---

# Плагинные коннекторы (отложено)

Результат анализа (июль 2026): насколько слой коннекторов изолирован и что нужно, чтобы
сторонний разработчик мог написать коннектор и добавить его на платформу — вплоть до установки
в уже развёрнутую систему. Задача отложена — документ фиксирует оценку, план и развилку
«JAR против внешнего сервиса», чтобы вернуться без повторного исследования.

Текущее устройство слоя: [`../../architecture/connectors.md`](../../architecture/connectors.md).

## Вердикт

Концептуально SPI уже спроектирован «плагинно» — добавление коннектора не требует править ни
одного потребителя. Физически изоляции нет: SPI живёт внутри модуля control-api и протекает
хостовыми типами, а конкретные коннекторы ходят в репозитории и сервисы хоста напрямую. До
плагинов — 3–4 шага, первый из которых полезен независимо от того, дойдёт ли дело до плагинов.

## Что уже хорошо

- **Регистрация — уже plug-in style.** `ConnectorRegistry` собирает все Spring-бины
  `ConnectorHandler`; ни enum'а, ни switch'а — новый коннектор = один `@Component`.
  `ConnectorBootstrap` сам апсертит каталог `connectors` в БД, миграций на коннектор не нужно.
- **Потребители не знают конкретных коннекторов.** Execution-пути (`ToolExecutionService`,
  `JobExecutionService`, `TriggerRouterService`) и листинги (UI, gRPC) работают только через
  registry + capability-интерфейсы.
- **UI частично metadata-driven.** `IntegrationMeta` отдаёт фронту `getCredentialFields()` —
  форма подключения рендерится по данным.
- **Схемы тулов — рефлексией** (`ToolSchemaReflector`): плагину достаточно аннотаций
  `@Tool`/`@ToolParam`/`@Job`, никаких внешних библиотек.
- **Контрактные границы уже есть**: `ConnectorException` — единственное исключение слоя,
  `ConnectorEnv` собирается только в `ConnectorEnvFactory`.

## Что мешает вынести коннектор в отдельный артефакт

1. **Нет отдельного артефакта SPI** — всё внутри control-api; внешнему разработчику пришлось бы
   зависеть от всего сервиса.
2. **SPI протекает внутренностями хоста**: `ConnectorHandler.traits()` возвращает
   `database.model.ConnectorTraits` (+ enums из `database.enums`); `IntegrationConnectorHandler`
   тянет `service.trigger.Trigger` и `jakarta.servlet.HttpServletRequest` (валидация вебхука);
   `JobSpec` использует `ConnectorJobType`.
3. **Коннекторы ходят в хост напрямую, портов нет.** По грепу импортов (2026-07-30): telegram →
   `storage.FileStorageService`; mcp → `ConnectionToolRepository`, `SecretService`; time →
   `ConnectorJobRepository`, `TriggerRouterService`; media → `service.llm.media`; platform →
   `AgentService`/`SkillService`/репозитории агентов; sheets, board, persistent-memory — свои
   JPA-entities и репозитории. Контрактных интерфейсов к возможностям хоста (файлы, секреты,
   планировщик, триггеры, персистентность) не существует — только прямые инъекции, и со временем
   связанность растёт.
4. **Channel-handlers живут вне слоя** (`service/channel/handler/`): мессенджер-подобный плагин
   должен привозить и канал, а это не часть SPI. Смягчение — `GenericChannelHandler` как fallback.
5. **Свои таблицы.** persistent-memory/board/sheets владеют таблицами и Liquibase-миграциями —
   плагинам этот механизм недоступен.
6. **Management-поверхность бедная**: `credentialFields` с августа 2026 несёт тип поля, secret-флаг
   и `required`, но на этом всё — ни placeholder'ов, ни валидации, ни произвольных действий
   коннектора для UI.

**Оговорка.** board, persistent-memory, webchat, acp, platform, sheets — не «коннекторы», а
продуктовые фичи, оформленные через SPI. Они останутся first-party, и это нормально: плагинный
SPI нужен для integration-класса (telegram-подобные, обёртки над внешними API) — именно их будут
писать сторонние разработчики. Тянуть first-party коннекторы на «чистый SPI» не нужно.

## План

### Шаг 1 — физический модуль `libs/connector-spi` (полезен сам по себе)

- Переезжают: capability-интерфейсы, аннотации, `core/dto/*`, `ConnectorException`,
  `ConnectorEnv`. `ConnectorTraits` и четыре enum-оси становятся SPI-типами; entity `Connector`
  мапится из них — направление зависимости разворачивается (control-api → SPI).
- Чистка сигнатур: вместо `Trigger` — SPI-DTO `TriggerEvent`; вместо `HttpServletRequest` —
  нейтральный `WebhookRequest` (method/headers/body).
- **Порты хоста** — интерфейсы в SPI, реализации в control-api, инъектируются коннектору:
  `TriggerPublisher`, `JobScheduler`, `FileStore` (agf_-ссылки), `ConnectorKvStore`
  (JSON-состояние per-connection — закрывает большинство случаев «плагину нужна таблица» без
  миграций).

После шага 1 «внутренний» и «внешний» коннектор компилируются против одного маленького артефакта
и ничем не отличаются для registry.

### Шаг 2 — management-API коннектора для UI, декларативно

Плагины **не** регистрируют свои REST-контроллеры (дыра в security chain, конфликты путей,
невозможность единого ABAC). Вместо этого:

- `credentialFields` → полноценная `ConfigSchema`: тип поля, secret и required уже есть
  (`CredentialField`), не хватает placeholder'ов, валидации и OAuth-подсказки — фронт рендерит форму
  generically, как уже делает с `IntegrationMeta`.
- Новая capability `ManagementActionProvider`: методы `@Action` со схемами через тот же
  `ToolSchemaReflector`; хост даёт один generic-endpoint
  `POST /manage/connections/{id}/actions/{name}`. UI получает список действий со схемами и рисует
  кнопки/формы; авторизация, аудит и rate-limit — в одном месте у хоста.

### Шаг 3 — собственно плагины: развилка

**Вариант A: in-JVM JAR.** Classpath-drop (Spring Boot `loader.path`) либо PF4J-стиль:
child-first `URLClassLoader` + `ServiceLoader`, без Spring внутри плагина — хост инстанцирует
сам и передаёт порты. Минусы: рестарт при установке (или мутабельный registry со всей
classloader-болью горячей выгрузки), конфликты зависимостей и — главное — **отсутствие границы
доверия**: чужой код в нашей JVM с доступом ко всему (SecurityManager мёртв). Приемлемо для
self-hosted и доверенных партнёров; для маркетплейса «любой разработчик» в SaaS — нет.

**Вариант B: коннектор как внешний сервис (remote connector protocol).** Половина уже есть:
MCP-коннектор (динамические тулы per-instance из `connection_tools`) и apps (`execution_kind = APP`,
push в канал приложения). Обобщение: манифест регистрации (code, traits, ConfigSchema,
tools/triggers/jobs/prompt blocks — те же MCP-совместимые схемы, что уже хранятся сырым JSON) +
вызовы `executeTool`/`executeJob` по HTTP/gRPC к плагину + эмиссия триггеров плагином через наш
webhook-вход. Даёт изоляцию, независимый деплой/версии, любой язык разработки и честную границу
доверия. Это же — путь к каталогу интеграций (главный разрыв по конкурентному анализу).

**Рекомендация:** шаги 1–2 — как рефакторинг ядра; целевой механизм для сторонних
разработчиков — вариант B; вариант A — опционально позже для self-hosted/first-party. После
шага 1 оба варианта ложатся на один SPI: remote-коннектор для registry — обычный
`ConnectorHandler`-прокси.

## Открытые вопросы

- Форма `ConnectorKvStore`: одна JSONB-таблица `connector_kv (connection_id, key, value)` или
  namespace в существующем хранилище; лимиты на объём.
- Канал как capability: вносить ли `ChannelHandler` в SPI (нужно мессенджер-подобным плагинам)
  или на первом этапе плагины живут без каналов, только generic.
- Remote protocol: аутентификация плагина (mTLS? ключ per-plugin?), версионирование манифеста,
  health/дискавери, биллинг вызовов.
- Судьба вебхуков в SPI: `normalizeInbound`/`validateWebhookRequest` для remote-плагина должны
  выполняться на стороне плагина (у него секрет платформы) — значит, в протоколе нужен
  webhook-passthrough.
