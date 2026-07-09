package ru.agimate.controlapi.connectors.core.annotation;

import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает {@code @Tool}-метод как декларативную фоновую задачу коннектора: метод образует
 * {@link JobSpec} в {@code getJobs()} с расписанием из атрибутов аннотации. При материализации
 * экземпляра коннектора reconcile-синк заводит на неё строку {@code connector_jobs}
 * ({@code kind=SYSTEM}, по одной на connectionId, без агента-инициатора).
 *
 * <p>Декларативная задача всегда скрыта от LLM (нет в {@code getTools()}, недоступна через
 * {@code executeTool}) — это фоновый процесс, а не тула агента. Для скрытой цели диспатча,
 * которую планируют динамически (строки {@code kind=AGENT}, напр. {@code time.fire}), {@code @Job}
 * не нужен — пометьте обычный {@code @Tool} как {@code @Tool(internal = true)}, иначе reconcile завёл бы
 * на неё фоновую SYSTEM-строку без инициатора.
 *
 * <p>{@code executeJob} умеет вызывать любой {@code @Tool}-метод, поэтому «вызов тулы по расписанию»
 * не требует отдельного метода-джобы.
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
}
