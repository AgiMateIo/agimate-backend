package ru.agimate.controlapi.connectors.core.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP {@code ToolAnnotations} — поведенческие хинты для агента. Это именно хинты (advisory):
 * клиент им доверять не обязан. Дефолты — пессимистичные, как в спецификации MCP: считаем, что
 * тула пишет, разрушительна, неидемпотентна и ходит во внешний мир, пока не сказано обратное.
 *
 * <p>{@code @Target({})} — используется только как вложенное значение в {@link Tool}, отдельно не вешается.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ToolAnnotations {

    /** Тула не меняет состояние → агент может звать свободно/параллельно/повторно. */
    boolean readOnlyHint() default false;

    /** Может выполнять разрушительные изменения (delete/overwrite). Значимо только при {@code !readOnly}. */
    boolean destructiveHint() default true;

    /** Повторный вызов с теми же аргументами не добавляет эффекта. */
    boolean idempotentHint() default false;

    /** Взаимодействует с внешним миром (сеть/внешние системы) vs замкнутый домен. */
    boolean openWorldHint() default true;
}
