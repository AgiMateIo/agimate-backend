package ru.agimate.controlapi.database.enums;

/**
 * Субъект счётчика/квоты LLM-расхода: USER — на каждого пользователя (free-tier платформенного
 * провайдера), AGENT — на каждого агента, TOTAL — суммарно на провайдер (потолок BYOK-кошелька).
 * Для TOTAL {@code subject_id} — нулевой UUID.
 */
public enum UsageSubjectKind {
    USER,
    AGENT,
    TOTAL
}
