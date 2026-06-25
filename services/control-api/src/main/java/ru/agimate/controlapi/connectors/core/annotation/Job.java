package ru.agimate.controlapi.connectors.core.annotation;

import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает {@code @Tool}-метод как фоновую задачу коннектора: метод образует
 * {@link JobSpec} в {@code getJobs()} с расписанием по умолчанию из атрибутов аннотации.
 *
 * <p>{@link #isJobOnly()} (по умолчанию {@code true}) управляет видимостью метода для LLM:
 * task-only метод не попадает в LLM-спеки ({@code getTools()}) и недоступен через
 * {@code executeTool}. При {@code isJobOnly = false} метод доступен и как тула, и как таска.
 *
 * <p>Обратное всегда верно: {@code executeJob} умеет вызывать и обычные {@code @Tool}-методы,
 * поэтому «вызов тулы по расписанию» не требует отдельного task-метода.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Job {

    ConnectorJobType type() default ConnectorJobType.PERIODIC;

    /** Интервал для {@code PERIODIC}; {@code 0} — немедленный повтор (long-poll паттерн). */
    long intervalSeconds() default 0;

    /** Cron-выражение Spring (6 полей, с секундами) для {@code CRON}. */
    String cron() default "";

    String zone() default "UTC";

    /** Лимит одной итерации в секундах; по истечении lease строка подхватывается заново. */
    int timeoutSeconds() default 300;

    /** {@code true} — метод только таска (скрыт от LLM); {@code false} — метод доступен и как тула. */
    boolean isJobOnly() default true;
}
