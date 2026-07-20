package ru.agimate.controlapi.controller.manage.dto;

/**
 * Статус исполнения tool-вызова, выводимый из {@code finish_at}/{@code error}:
 * <ul>
 *   <li>{@code SUCCESS} — {@code finish_at} есть, {@code error} нет;</li>
 *   <li>{@code ERROR} — {@code finish_at} есть, {@code error} есть (тул выполнился с ошибкой);</li>
 *   <li>{@code PENDING} — {@code finish_at} нет, {@code error} нет (результат ещё не пришёл).</li>
 * </ul>
 * DENY-логи ({@code finish_at} нет, {@code error} = причина отказа) сюда не попадают —
 * они отбираются отдельным фильтром {@code accessEffect=DENY}.
 */
public enum ToolCallStatus {
    SUCCESS,
    ERROR,
    PENDING
}
