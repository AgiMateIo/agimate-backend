# Конвенции кода

Как писать код в этом репозитории. Это свод правил, которые нельзя вывести из соседнего файла
за одну минуту; устройство подсистем описывают `architecture/`, `contracts/` и `services/`, а
историю решений — `decisions/`.

Точка входа для ИИ-агентов — [AGENTS.md](../../AGENTS.md); для людей — [CONTRIBUTING.md](../../CONTRIBUTING.md).
Если правило здесь разошлось с кодом, прав код, а документ нужно поправить тем же изменением.

## Язык

- **Код, комментарии, имена, коммиты — по-английски.** Русский в коде остаётся только как данные:
  цитата литерала или пример не-ASCII, где сам пример и есть предмет.
- **`docs/` — по-русски**, это документация команды.
- **`@DisplayName` в тестах — по-русски.** Так написано ~85% тестов; английское имя в новом тесте
  будет инородным.
- **Всё, что уезжает агенту из декларации** (`@Tool(description)`, `@ToolParam`, значения внутри,
  `TriggerSpec.description`), пишется только по-английски: у этих строк нет бандла перевода, они
  уезжают на любую инсталляцию как есть.

## Сборка и проверка

- Нужен **JDK 25**: во всех модулях `languageVersion = 25`. Gradle 9.8 находит его сам, но при
  нескольких JDK надёжнее указать `JAVA_HOME`.
- `./gradlew build` и `./gradlew test` **не требуют Docker, БД и `services/.env`**: тесты — обычные
  юнит-тесты без Spring-контекста. Инфраструктура нужна только для `bootRun` (см.
  [operations/local-stack.md](../operations/local-stack.md)).
- Форматтеров и линтеров в сборке нет: ни spotless, ни checkstyle, ни `.editorconfig`, ни
  pre-commit. Единственный автоматический гейт — компиляция и тесты; стиль берётся из соседнего
  файла.

## Модули и слои

Модули и их ответственность — в [AGENTS.md](../../AGENTS.md#what-this-is) и
[architecture/overview.md](../architecture/overview.md). Общий слой:
`controller` → `service` → `database/{repositories,entities}`, плюс `config`, `security`, `util`.

- `user-api`: DTO в `controller/dto/{request,response}`, преобразование сущность↔DTO — в `mappers/`.
- `control-api`: контроллеры по аудитории — `agent/`, `app/`, `manage/`, `webhook/`; DTO живут
  рядом с областью (`controller/<area>/dto/…`), центрального `mappers` нет. Дополнительные
  пакеты: `abac`, `acp`, `connectors`, `grpc`, `realtime`, `storage`.
- `agent-worker`: чистая логика в `agent/`, `dto/`, `llm/`; поверхность DBOS — в `workers/` и
  `config/DbosRuntime`; gRPC-клиент к control-api — в `grpc/`.

## HTTP-слой

- `@RestController` + `@RequestMapping(XController.PATH)`, где `PATH` — `public static final String`:
  на него ссылаются security-конфиг и тесты. Эталон —
  `control-api/.../controller/manage/ManageAgentStarterController.java`.
- `@Tag(name = …, description = …)` на классе, `@Operation(summary = …)` на методе.
- DTO — records: request с аннотациями валидации, response с `@Schema` на компонентах.
- Ответ всегда `SuccessResponse.ok(...)` или `SuccessResponse.empty()`; тело ошибки —
  `{"error":{"message":…,"details":{…}}}`. Для ошибок не собирайте `ResponseEntity` руками:
  бросайте `*StatusException` из `libs/common` (`NotFoundStatusException`,
  `BadRequestStatusException`, `ValidationErrorStatusException`, `ConflictStatusException`,
  `ForbiddenStatusException`, `UnauthorizedStatusException`, `TooManyRequestsStatusException`,
  `InternalServerErrorStatusException`, `ServiceUnavailableStatusException`, …), их рендерит
  `BaseErrorHandlerControllerAdvice`.
- Идентичность: `@AuthenticationPrincipal AgimateUserPrincipal principal`, затем
  `UUID.fromString(principal.id())`.
- Коллекция живёт на пути с `/` в конце; листинги с пагинацией возвращают
  `SuccessResponse<PageResponse<T>>` с `page` (по умолчанию 0) и `size` (по умолчанию 20).
  `PageResponse` — собственный record, а не сериализованный `PageImpl`.
- **Новый `/manage/**`-контроллер обязан попасть в `SecurityConfig.JWT_CHAIN_PATHS`.** Цепочка
  JWT матчится перечислением, а не префиксом; забытый путь проваливается в следующую цепочку и
  отвечает 403 законному владельцу. Полноту сторожит `ManagePathsSecurityTest`.

## Данные и миграции

- Сущности наследуют `BaseEntity`; id генерирует БД:
  `@Generated(event = INSERT) @ColumnDefault("uuidv7()")` на `UUID id`. `UUIDUtils.generateUUIDv8()`
  — только там, где id нужен **до** вставки, потому что уже адресует другой объект: ключ
  подключения, ссылка `agf_`, ключ блоба, trace-id. См. [decisions/uuid-primary-keys.md](../decisions/uuid-primary-keys.md).
- **Сущность плоская:** внешний ключ — обычная `UUID`-колонка, а не ассоциация. Связей в проекте
  единицы, и все `FetchType.LAZY`; по умолчанию их не заводите.
- `@Enumerated(EnumType.STRING)` для enum; `TEXT` вместо `VARCHAR(n)`; в `@Column` —
  `columnDefinition = "TEXT"`.
- Уникальность дублируется в `@Table(uniqueConstraints = …)` с тем же именем, что в changelog'е.
  Партиальный уникальный индекс JPA не выражает — правило описывается javadoc'ом на сущности.
- **Миграции:** `src/main/resources/db/changelog/updates/YYYY/MM/DD-NN-name.xml`, где `NN` —
  порядковый номер на дату. Мастер-чейнджлог подключает `updates/` через `includeAll … recursive:
  true`, поэтому регистрировать файл не нужно. В `user-api` пустой `updates/placeholder.xml`
  держит каталог в git — не удалять.
- `updated_at` всегда `NOT NULL DEFAULT CURRENT_TIMESTAMP`. Комментарии — XML `<!-- -->`, не
  элемент `<comment>`, и обязательны там, где DDL себя не объясняет: партиальный уникальный
  индекс, `COALESCE` в ключе, денормализация. Имена индексов и ограничений —
  [decisions/schema-conventions.md](../decisions/schema-conventions.md).
- Транзакции: `@Transactional(readOnly = true)` на классе сервиса, `@Transactional` на мутирующих
  методах. JSONB — `@JdbcTypeCode(SqlTypes.JSON)`.

## Коннекторы

Полное устройство — [architecture/connectors.md](../architecture/connectors.md). Коротко:

- SPI — композиция: `ConnectorHandler` (идентичность и капабилити) плюс интерфейсы-капабилити,
  реализуемые по мере надобности: `ToolProvider`, `TriggerProvider`, `JobProvider`,
  `PromptBlockProvider`, `ViewProvider`. Потребители резолвят их через
  `ConnectorRegistry.findCapability(code, X.class)` или статический
  `ConnectorRegistry.capability(handler, X.class)`.
- Коннектор = `<Name>ConnectorService` (фасад) + `<Name>ToolService` с методами `@Tool`/`@Job` и
  параметрами `@ToolParam`. Схемы тулов и джоб строятся рефлексией (`ToolSchemaReflector`) —
  руками JSON-схемы не пишутся. Регистрация автоматическая: Spring-бин, а каталожную строку
  поднимает `ConnectorBootstrap`; отдельного ручного реестра нет.
- Внутри коннекторного слоя бросается только `ConnectorException` (его текст видит агент);
  `*StatusException` остаётся на HTTP-границе. Коннекторный слой не зависит от `controller/**`:
  внутренние продюсеры используют service-DTO (`service/dto/ToolResult`).
- `@Service` — только для транзакционных доменных сервисов; SPI-фасады, `<Name>ToolService` и
  инфраструктурные исполнители (реестр, фабрика, планировщик) — `@Component`.
- Тексты каталога (`connectorName`/`connectorDescription`) и `ContextDirectives.guidance`
  локализуются через `seed/texts/<lang>/`; значение в коде — первоисточник и фолбэк, поэтому
  `seed/texts/en/` намеренно нет. Новый код коннектора без перевода валит `ConnectorTextsTest`.

## Навыки, ABAC и каналы

- Навык — markdown-инструкция, привязанная к агенту: `Skill` (каталог) → `AgentSkill` (привязка) →
  `AgentSkillConnection` (какой экземпляр подключения закрывает требование). Навык доставляется
  только когда все его требования резолвятся в удовлетворённые экземпляры; иначе он скрывается
  целиком. См. [decisions/skill-connector-requirements.md](../decisions/skill-connector-requirements.md),
  [decisions/skills-for-connection.md](../decisions/skills-for-connection.md).
- Seed-контент (навыки, пресеты, тексты) — **seed-only-if-missing**: правка `SKILL.md` не меняет
  уже засеянное окружение. Языковая версия — отдельная запись, а не перевод; навык ищется только
  `SkillRepository.findByNamesInLanguage`. См. [architecture/content-language.md](../architecture/content-language.md).
- ABAC — одна таблица `AgentConnectionPolicy` с `PolicyKind.TOOL|TRIGGER`; внутри активной привязки
  модель default-allow, точное имя тула важнее правила на всю привязку. Политика решает «кому
  можно», канал — «как построен разговор»; это разные слои. См.
  [architecture/channels-and-triggers.md](../architecture/channels-and-triggers.md).
- Один активный канал на `(agent, connector, connection_id)` — партиальный уникальный индекс
  `WHERE deleted_at IS NULL`, который JPA не выражает; правило описано на сущности.

## Воркер и протокол

- `control-api` кладёт ран в DBOS, `agent-worker` его исполняет. Контракт: класс
  `AgentRunWorkflow` — **интерфейс**, реализация `AgentRunner`; workflow `run_agent`, очередь
  `agent_exec`, partition key — `sessionId`. Protos — в `libs/agentworker-proto`, правятся только
  `.proto`, не сгенерированные классы.
- Результаты durable-шагов DBOS — только простые сериализуемые типы (records/примитивы): не
  protobuf (Jackson ломается на рекурсивных дескрипторах) и не секреты.
- Воркер не владеет базой control-api: контекст рана приходит одним `GetRunContext`, содержимое
  при реплее перечитывается по id.
- В `control-api` сосуществуют два Jackson: Spring Boot 4 инжектит Jackson 3 (`tools.jackson`), а
  `libs/common`'s `JsonUtils` статически на Jackson 2 (`com.fasterxml`). Инжект
  `com.fasterxml` `ObjectMapper` роняет контекст на старте.

## Время, логи, комментарии

- `LocalDateTime` в БД — UTC; каждый сервис пинит JVM к UTC в `main()`, тесты — через
  `user.timezone` в корневой сборке. Пользовательская зона (cron `zone`, таймзона агента)
  конвертируется явно; агенту timestamp отдаётся с зоной.
- Логирование — Lombok `@Slf4j`. Логируйте id (`agentId`, `connectionId`, `runId`), но не
  credentials, токены и пользовательский контент.
- Комментарий объясняет **почему**, а не что. Дефолт — одна строка прозой. `@param` — на
  компонентах record'а и там, где ограничение не видно из типа; `@return` — только когда сигнатура
  не отвечает на вопрос. Javadoc-задачи и doclint в сборке нет намеренно.
- Упоминая класс или метод, пишите `{@link}`, а не текстом: ссылка ломается видимо, имя в тексте
  протухает молча.

## Тесты

- JUnit 5 + Mockito; **никакого Spring-контекста**, БД и Testcontainers. Тест, которому нужен
  контекст, — признак недостаточного разделения кода.
- `@ExtendWith(MockitoExtension.class)`, `@Mock` на репозиториях, `@InjectMocks` на тестируемом
  классе; класс теста package-private; группировка `@Nested`; `@DisplayName` по-русски;
  статические импорты `Assertions` и `Mockito`; `@BeforeEach` для подготовки.
- Тест живёт рядом с фичей в том же модуле и входит в тот же `feat`-коммит.
- Часть тестов — стражи конвенций: `ManagePathsSecurityTest`, `ChangelogParsesTest`,
  `ConnectorTextsTest`, `PromptTextsTest`, `ChannelTextsTest`, `TaxonomyTextsTest`. Если такой тест
  упал, чините код/данные, а не тест.

## Документация и коммиты

- Долгая работа начинается с `docs/decisions/<тема>.md`: статус в шапке
  (`analysis | accepted | partial | implemented | deferred`) и `## План` с чекбоксами. Правила —
  в [docs/README.md](../README.md#как-ведётся-документация).
- Изменили поведение, эндпойнт, переменную окружения или архитектуру — обновите соответствующий
  документ в `docs/` и строку в индексе. Схемы запросов/ответов в документации не дублируются:
  они генерируются из кода.
- `docs/roadmap.md` — только «что впереди». `docs/tmpspec/` — одноразовые дельты соседней
  команде, в git не входят; документацией не считаются.
- Коммиты: `<тип>: <объект> — <дельта>`, по-английски, ≤72 символов, без scope в скобках, без
  `Co-Authored-By`. Полный формат — [CONTRIBUTING.md](../../CONTRIBUTING.md#commits).

## Чего не делать

- Не добавлять Spring-контекстные тесты, H2 и Testcontainers «для проверки».
- Не писать JSON-схемы тулов руками и не регистрировать коннекторы вручную.
- Не тащить в один коммит форматтер, новый HTTP-клиент, маппер или тестовый фреймворк.
- Не логировать секреты и содержимое пользователя; не коммитить `services/.env`,
  `application-local.yaml`, отрендеренные конфиги `ops/`.
- Не дублировать в `docs/` схемы API, списки тулов и перечни эндпойнтов — они меняются, канон в
  коде и Swagger.
