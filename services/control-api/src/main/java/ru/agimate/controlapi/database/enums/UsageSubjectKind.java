package ru.agimate.controlapi.database.enums;

/**
 * Subject of an LLM usage counter or quota: USER — per user (the platform provider's free tier),
 * AGENT — per agent, TOTAL — per provider in aggregate (the ceiling of a BYOK wallet). For TOTAL,
 * {@code subject_id} is the zero UUID.
 */
public enum UsageSubjectKind {
    USER,
    AGENT,
    TOTAL
}
