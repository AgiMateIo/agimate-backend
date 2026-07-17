# Astro & Divination (internal connectors)

Коды: `astro`, `divination`. Пакеты: `controlapi.connectors.internal.{astro,divination}`.

Расчётная база пресета «Астролог» (`presets/astrologer/PRESET.md`, персона Селена). Продуктовый
инвариант: **LLM никогда не считает сама** — позиции планет, арканы и вытянутые карты приходят из
детерминированных тулов, модель только интерпретирует (ответ на главную боль AI-астрологов —
галлюцинации в расчётах). Скилы: `skills/astro/SKILL.md` («AgiMate Astro»), `skills/divination/SKILL.md`
(«AgiMate Divination»).

## astro — настоящая астрономия

Эфемериды — [Astronomy Engine](https://github.com/cosinekitty/astronomy)
(`io.github.cosinekitty:astronomy:2.1.19`, **MIT**, self-contained, ±1 угл. минута; публикуется
только на JitPack — repository добавлен в `control-api/build.gradle.kts`). Swiss Ephemeris не
используется сознательно: AGPL-3.0 / платная коммерческая лицензия.

Система координат: истинная эклиптика даты (тропический зодиак). Дома — **Whole Sign**; ASC/MC —
сферическая тригонометрия от звёздного времени (`calc/ChartAngles`), за полярным кругом (>66.5°) —
ошибка. Лунные узлы — средние (полином Меёса, `calc/LunarNode`). Ретроградность — конечная разность
долготы ±12ч. Место рождения передаётся как `latitude`/`longitude` + IANA `tzid` (подставляет LLM из
знаний о городе; исторические смещения решает tzdb JDK). Орбисы аспектов — `calc/OrbPolicy`
(наталь 6–8°, +2° светилам; транзиты 3°; синастрия 4–6°).

| Тула | Назначение |
|------|------------|
| `natal_chart(birthDate, birthTime?, tzid?, latitude?, longitude?)` | натальная карта: планеты (знак/градус/дом/ретро), узлы, ASC/MC, дома, аспекты. Без времени — `timeKnown:false`, углы/дома null, Луна помечена `uncertain` |
| `transits(date?, birthDate?…)` | «небо сейчас» + при переданном натале транзитные аспекты |
| `synastry(first*, second*)` | обе карты + межкартные аспекты `interAspects` |

Точность закреплена тестами по эталонам **JPL Horizons** (2000-01-01T12:00Z: Sun 280.3689°,
Moon 223.3238°, Mars 327.9633°) — `astro/calc/EphemerisTest`; ASC/MC — независимый эталон от
опубликованного GMST J2000 (`ChartAnglesTest`).

## divination — детерминированная эзотерика

Чистая арифметика + датасет; внешних зависимостей нет.

| Тула | Назначение |
|------|------------|
| `matrix_of_destiny(birthDate)` | Матрица судьбы: арканы 1..22, редукция сумм только при >22 (`calc/DestinyMatrix`, все формулы школы изолированы там) |
| `numerology(birthDate)` | число жизненного пути (мастер-числа 11/22/33 сохраняются — правило редукции **другое**, чем у Матрицы), число дня рождения, персональный год |
| `tarot_card_of_day(date?)` | карта дня: seed = SHA-256(`userId:date`) → одна карта весь день на любой ноде; требует userId в env |
| `tarot_draw_spread(spread, question?)` | случайный расклад (THREE_CARD / CELTIC_CROSS / YES_NO) без повторов, reversed 50%; **не** idempotent |

Колода — `resources/datasets/tarot/rider_waite.json`: 78 карт (id 0..77), `nameEn`/`nameRu`,
`arcana`, `suit`, `number`, ключевые слова обеих ориентаций (структура по мотивам
metabismuth/tarot-json, MIT; тексты свои). Развёрнутые значения карт отданы LLM — датасет защищает
только от выдумывания того, «что выпало».

## Общее

- Оба — `InternalConnectorHandler` без триггеров и джоб: «карта дня по расписанию» реализуется
  инструкцией пресета через `time.schedule` (cron → `time.due`).
- Регистрация автоматическая (`ConnectorBootstrap`), миграций и ABAC-правил нет; доступ —
  стандартный default-allow при binding агента к connection.
- Тяжёлая математика — в чистых `calc/`-пакетах без Spring; наружу только `ConnectorException`.
