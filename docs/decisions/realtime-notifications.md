---
status: partial
created: 2026-09-22
updated: 2026-09-22
---

# Уведомления через Centrifugo — в одном месте и только после коммита

Сейчас общий у всех издателей только `CentrifugoService`: публикация в канал через HTTP API и подпись
клиентских токенов. Остальное каждое место решает само, и от этого разъезжается:

- **имена каналов** — строковые литералы в девяти местах (`"user:" + id` у четырёх издателей и
  контроллера токена, `"app:"` и `"agent:"` — по два раза); опечатка молча уводит события в канал,
  на который никто не подписан;
- **полезные нагрузки** — в шести пакетах, строки листингов берутся из `controller/manage/dto`;
- **время** — `board.task.*`, `agent.request.*`, `webchat_message`, `webchat_activity` публикуются до
  коммита, `session.*` — после, через `ApplicationEventPublisher`; событие может обогнать запись, а
  откаченное изменение — всё равно разойтись клиентам;
- **сбой** — четыре одинаковых `try/catch`, `webchat_message` откатывает запись, а сам
  `CentrifugoService` бросает `ServiceUnavailableStatusException` — HTTP-исключение из домена.

За этим стоит и вторая беда, не про Centrifugo: **у сессии нет одного владельца.** `agent_sessions`
пишут четыре класса — `AgentSessionService` и мимо него через репозиторий `AgentSessionResolver`
(сессия коннекции, `touch`), `SessionCompactionWriter` (заголовок) и `SubagentService` (`touch`). Поэтому
событие сессии публикуют шесть мест в пяти пакетах.

## Решение

### Правило: канал уведомлений не влияет на логику

Любая публикация в Centrifugo — **после коммита** транзакции, в которой сделано изменение; вне
транзакции — сразу. Сбой публикации логируется и больше ничего не делает: не откатывает, не
помечает, не повторяет. Потеря закрывается тем, что и так есть: клиент перечитывает список или
историю, вызов тула и ран доживают до своих таймаутов.

Правило одно для всех каналов, включая транспорт команд (`agent:`, `app:`) и сообщение веб-чата.
`webchat_message` сейчас откатывает строку при сбое публикации, и воркер повторяет доставку, — этот
повтор уходит сознательно: сообщение остаётся в истории, клиент увидит его при перечитывании.

### Один владелец сессии

`AgentSessionService` — единственный, кто пишет `agent_sessions`, и единственный, кто сообщает об
изменении сессии. Остальные сообщают ему факт вызовом метода — так же, как `WebchatService` уже
зовёт `bumpLastActivityAt`:

| Метод | Кто зовёт | Было |
|---|---|---|
| `createNew`, `createChild` | веб-чат, ACP, субагенты | так и есть |
| `forConnection` | роутер триггеров | `AgentSessionResolver` — растворяется, его `REQUIRES_NEW` переезжает на метод |
| `rename`, `setTitleIfEmpty`, `close`, `markRead` | `/manage`, веб-чат, ACP | так и есть |
| `writeGeneratedTitle` | джоба компакции | `SessionCompactionWriter` через репозиторий |
| `touch` | субагенты, путь коннекции | `SubagentService` и `AgentSessionResolver` через репозиторий |
| `messageRecorded` | `WebchatMessagePublisher` | публиковал событие сам |
| `runStateChanged` | `MessageLogPersistence` | публиковал событие сам |

Правила «при прочтении сообщать, только если указатель сдвинулся» и «заголовок, данный
пользователем, не переписывать» живут рядом с данными, которые охраняют.

### Пакет `ru.agimate.controlapi.realtime`

```
realtime/
  RealtimeChannels   имена каналов user(id) · app(id) · agent(id); ими пользуются и публикация, и токены
  RealtimeEvent      sealed-интерфейс; события — записи-факты без поведения:
                       SessionChanged(sessionId, created) · WebchatMessageRecorded(...) ·
                       BoardTaskChanged(userId, boardId, type, task) · AgentRequestChanged(threadId, type) ·
                       AgentDelivery(agentId, message) · AppToolCall(appId, payload)
  RealtimeMessages   один exhaustive switch: событие → канал, тип, теги, нагрузка
  RealtimePublisher  publish(RealtimeEvent): буфер транзакции, повторы схлопнуты, после коммита —
                     рендер в новой read-only транзакции и отправка; сбой — в лог
  CentrifugoTokens   токены подключения и подписки
```

- **Домен видит один метод** — `realtimePublisher.publish(new SessionChanged(id, false))`. Каналов,
  типов и тегов он не знает.
- **Весь контракт каналов — один `switch`** в `RealtimeMessages`. Интерфейс `sealed`, поэтому
  компилятор не даст добавить событие и забыть его отрендерить; ответ на «что уходит в `user:`» —
  один экран кода.
- **Строки листингов** (сессия, контакт, поручение) собирают читающие компоненты домена —
  `SessionRows` (`service/session`), `ContactRows` (`service/webchat`), существующий
  `AgentRequestQueryService`. Ими же пользуются листинги `/manage` — одна сборка строки на список и
  событие. Они зависят только от репозиториев, поэтому цикла
  `AgentSessionService → RealtimePublisher → … → AgentSessionService` нет.
- **Схлопывание — по равенству записей.** Буфер — `LinkedHashSet<RealtimeEvent>`: два одинаковых
  `SessionChanged` одной транзакции (закрытие = прочтение + закрытие) дают одно событие. Ключи
  заводить не нужно — `equals` у записи уже есть.
- **Буфер — синхронизация текущей транзакции**, а не ресурс, привязанный по ключу: вложенная
  `REQUIRES_NEW` приостанавливает её вместе с остальными синхронизациями, и чужие события не
  попадают в пачку внешней транзакции. Откат — пачка не отправляется.
- **Рендер — после коммита, в новой read-only транзакции**: контекст завершённой ещё привязан, и
  сущность, загруженная до массового `UPDATE`, вернулась бы из кэша устаревшей. Нагрузка, которая уже
  в руках (задача доски, сообщение, команда агенту), лежит в самом событии.
- **Транспорты команд** (`agent:`, `app:`) идут тем же путём: публикация после коммита, сбой в лог,
  отдельного механизма нет.
- **`ApplicationEventPublisher` для уведомлений не используется**; `SessionChanged` как
  Spring-событие, `SessionEventPublisher`, `AgentRequestEventPublisher` и `CentrifugoService` уходят.
  Пуш на телефон (`WebchatNotificationListener`) остаётся — это отдельный выход через user-api.

### Канал `webchat:` сливается в `user:`

Сообщение переписки едет в `user:{userId}` событием `webchat.message` с тегами
`entity=webchat.message`, `agentId`, `sessionId`. Клиенту — одна подписка на всё приложение вместо
переподписки при каждом открытии чата; эндпойнт токена сессии и проверка владения ею не нужны —
канал `user:` и так личный.

- **Восстановление после разрыва.** У `webchat` история 100 публикаций на переписку за 24 часа и
  `force_recovery`, у `user` — 100 на пользователя за 10 минут. В общем канале строки `progress`
  одного длинного ответа вытеснили бы остальное, поэтому история неймспейса `user` растёт (порядок —
  1000 публикаций, несколько часов) и получает `force_recovery`.
- **Фильтр — на клиенте.** Серверный фильтр тегов задаётся на подписку, и смена открытого чата
  потребовала бы переподписки. Получатель один, лишний трафик — `progress` чужих чатов того же
  пользователя; клиент раскладывает события по `sessionId`.
- **Переход живёт в одной ветке `switch`.** Пока клиенты не обновились, `WebchatMessageRecorded`
  рендерится в `webchat.message` для `user:`, в `webchat_message` для `webchat:{id}` и, для ответа
  агента, в `webchat_activity`; удаление старого — удаление двух строк.

## Ревью: KISS, DRY, SOLID

Первый набросок плана был сложнее. Что убрано и почему:

| Принцип | Было в наброске | Стало |
|---|---|---|
| KISS | два механизма «после коммита»: `AfterCommit` для транспортов и `UserNotifier` для уведомлений | один `RealtimePublisher` для всех каналов |
| KISS | ключ схлопывания `key()` у каждого события | равенство записей, `LinkedHashSet` |
| KISS, YAGNI | `CentrifugoPublisher` — обёртка над клиентом библиотеки | `RealtimePublisher` зовёт клиент библиотеки сам |
| YAGNI | `RealtimeException` | не нужен: сбой ловит и логирует сам `RealtimePublisher`, наружу ничего не выходит |
| SRP | события сами читают базу (`render(projections)`) | события — данные; рендер в `RealtimeMessages`, сборка строк — в домене |
| SRP | `agent_sessions` пишут четыре класса | один `AgentSessionService` |
| DRY | строка сессии и контакта собирается и в листинге, и в событии | `SessionRows`, `ContactRows` на оба |
| DRY | имена каналов в девяти местах | `RealtimeChannels` |
| OCP | новое событие — новый класс-издатель со своим `try/catch` | новая запись и ветка `switch`; компилятор проверяет полноту |
| ISP, DIP | домен знал канал, тип, теги и Centrifugo | домен знает `publish(RealtimeEvent)` и записи-факты |

**Как это читается в итоге** — на каждый вопрос один класс:

- *что и куда уходит* — `RealtimeMessages`;
- *когда уходит и что при сбое* — `RealtimePublisher`;
- *что меняет сессию* — `AgentSessionService`;
- *как выглядит строка* — `SessionRows`, `ContactRows`.

**Что осталось осознанно:**

- `AgentSessionService` растёт — но это все операции одного агрегата, связность у них общая.
- Отправка синхронная, на потоке запроса после коммита: чтение строки и HTTP к Centrifugo.
  Асинхронная — не одна аннотация `@Async`: пул переставляет пачки, и строка, прочитанная раньше,
  может прийти позже свежей и затереть её на клиенте (`isRunning: true` после `false`). Понадобится —
  однопоточные исполнители по ключу сущности (`sessionId`, `boardId`, …) и `afterCommit`, кладущий
  пачку в исполнитель; до заметной задержки не делаем.
- Строки листингов остаются в `controller/manage/dto`, и `SessionRows` возвращает их тип. Перенос в
  сервисный слой — отдельная уборка.

## Не входит

- **Надёжная доставка команд** `agent:`/`app:` — повтор, outbox. Если потеря команды станет
  заметной, это отдельное решение.
- **Пуш на телефон** — остаётся своим слушателем.

## План

Каждый шаг выкатывается отдельно и не меняет того, что видит клиент, кроме шагов 4 и 7.

1. [x] **Один владелец сессии.** В `AgentSessionService` переезжают `forConnection` (из
   `AgentSessionResolver`, удаляется), `writeGeneratedTitle` (из `SessionCompactionWriter`), `touch` (из
   `SubagentService`); появляются `messageRecorded` и `runStateChanged`. Событие сессии публикует
   только он — пока прежним механизмом.
2. [x] **Строки листингов.** `SessionRows` и `ContactRows` из `ManageSessionService` и
   `WebchatService`; листинги пользуются ими.
3. [x] **Пакет `realtime`.** `RealtimeChannels`, `CentrifugoTokens` (контроллеры токенов — через них),
   `RealtimeEvent` и записи, `RealtimeMessages`, `RealtimePublisher`.
4. [x] **Перевод издателей.** Сессия, доска, поручения, сообщение веб-чата (в `user:` и параллельно
   в `webchat:`), транспорты `agent:`/`app:` — через `RealtimePublisher`. Удаляются
   `CentrifugoService`, `SessionEventPublisher`, `AgentRequestEventPublisher`. Все публикации — после
   коммита.
5. [x] **Centrifugo.** История и `force_recovery` неймспейса `user` в
   `ops/templates/centrifugo.config.yaml`.
6. [x] **Документы.** `contracts/centrifugo-channels.md`, `connectors/webchat.md`; дельта клиентам в
   `docs/tmpspec/` — `realtime-notifications-clients.md` (все события `user:` и как на них
   реагировать) и `sessions-frontend.md` (заголовки, `ROUTINE`, навык).
7. [ ] **Удаление старого** после выпуска веба и Android: `webchat:`, его неймспейс и эндпойнт токена,
   `webchat_activity`.
