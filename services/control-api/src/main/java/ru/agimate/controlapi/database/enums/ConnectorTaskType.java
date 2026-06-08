package ru.agimate.controlapi.database.enums;

/**
 * Какой backend исполняет задачу. Однозначно соответствует подтипу {@code TaskDescriptor}.
 */
public enum ConnectorTaskType {
    LONG_RUNNING,
    PERIODIC,
    CRON
}
