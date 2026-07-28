package ru.agimate.controlapi.controller.manage.dto;

/**
 * Execution status of a tool call, derived from {@code finish_at}/{@code error}:
 * <ul>
 *   <li>{@code SUCCESS} — {@code finish_at} is set, {@code error} is not;</li>
 *   <li>{@code ERROR} — {@code finish_at} is set and {@code error} is too (the tool ran and failed);</li>
 *   <li>{@code PENDING} — neither {@code finish_at} nor {@code error} (the result has not arrived yet).</li>
 * </ul>
 * DENY logs (no {@code finish_at}, {@code error} = the reason for refusal) never reach here — they are
 * selected by the separate filter {@code accessEffect=DENY}.
 */
public enum ToolCallStatus {
    SUCCESS,
    ERROR,
    PENDING
}
