package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.dto.TaskSpecification;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помечает {@code @Tool}-метод как task-only: метод не попадает в LLM-спеки ({@code getTools()})
 * и недоступен через {@code executeTool}, но образует {@link TaskSpecification} в
 * {@code getTasks()} с расписанием по умолчанию из атрибутов аннотации.
 *
 * <p>Обратное неверно: {@code executeTask} умеет вызывать и обычные {@code @Tool}-методы,
 * поэтому «вызов тулы по расписанию» не требует отдельного task-метода.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TaskOnly {

    ConnectorTaskType type() default ConnectorTaskType.PERIODIC;

    /** Интервал для {@code PERIODIC}; {@code 0} — немедленный повтор (long-poll паттерн). */
    long intervalSeconds() default 0;

    /** Cron-выражение Spring (6 полей, с секундами) для {@code CRON}. */
    String cron() default "";

    String zone() default "UTC";

    /** Лимит одной итерации в секундах; по истечении lease строка подхватывается заново. */
    int timeoutSeconds() default 300;
}
