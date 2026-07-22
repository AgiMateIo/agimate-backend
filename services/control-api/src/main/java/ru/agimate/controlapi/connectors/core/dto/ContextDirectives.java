package ru.agimate.controlapi.connectors.core.dto;

import lombok.Builder;

/**
 * Директивы контекста триггера — overlay поверх route-пресета ({@code ContextSpec}):
 * {@code null}-поле = «как в базе». Объявляются <b>только кодом коннектора</b> в статическом
 * {@link TriggerSpec} ({@code TriggerProvider.getTriggers()}); динамические декларации
 * ({@code connection_triggers}) и payload события источником директив быть не могут —
 * незнакомый триггер получает базовый пресет (default-safe).
 *
 * <p>Поля двух классов риска:
 * <ul>
 *   <li><b>trust</b> — {@link #presentation}/{@link #promptParam}, {@link #guidance}: меняют доверие
 *       к тексту в промпте. Разрешены только internal-коннекторам — fail-fast валидация в
 *       {@code ConnectorBootstrap}. {@code guidance} — статическая константа кода, без
 *       интерполяции данных события;</li>
 *   <li><b>scope</b> — остальные: меняют объём контекста (тулы/тела скиллов/история), доверие
 *       не трогают.</li>
 * </ul>
 *
 * @param presentation       как рендерить событие: {@code EVENT} (untrusted JSON, дефолт) или
 *                           {@code PROMPT} — trusted-текст из {@code data[promptParam]}
 *                           (первый потребитель — {@code time.due}: промпт авторства самого агента)
 * @param promptParam        для {@code PROMPT}: имя параметра {@code data} с текстом промпта
 * @param guidance           trusted user-блок непосредственно перед блоком события: провенанс/что
 *                           делать (первый потребитель — {@code time.due})
 * @param skillTools         {@code false} — не собирать тулы скиллов агента (первый потребитель —
 *                           memory-триггеры: минимальный контекст); дефолт {@code true}
 * @param ownConnectionTools {@code true} — добавить тулы connection события независимо от скиллов;
 *                           скоуп — именно connection триггера, не все connections его кода
 *                           (INSTANCE-коннекторы). Первые потребители — {@code time.due}
 *                           (отменить/перепланировать) и memory-триггеры
 * @param historyLimit       окно истории сессии; {@code 0} — без истории (первый потребитель —
 *                           memory-триггеры: сообщения уже в {@code data}); {@code null} — база
 */
@Builder
public record ContextDirectives(
        Presentation presentation,
        String promptParam,
        String guidance,
        Boolean skillTools,
        Boolean ownConnectionTools,
        Integer historyLimit
) {

    /** Рендер основного user-блока события. */
    public enum Presentation { EVENT, PROMPT }
}
