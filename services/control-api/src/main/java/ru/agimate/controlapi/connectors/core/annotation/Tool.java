package ru.agimate.controlapi.connectors.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP-совместимое описание тула коннектора. Статически на методе задаются
 * {@code name}/{@code title}/{@code description}/{@link ToolAnnotations}/{@code _meta};
 * {@code inputSchema} и {@code outputSchema} строятся рефлексией ({@link ToolSchemaReflector})
 * по сигнатуре метода (параметры с {@link ToolParam}) и типу возврата.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    /** Уникальное имя тула (диспатч-ключ). */
    String name();

    /** Человекочитаемое название для UI; по умолчанию пусто → fallback на {@code name}. */
    String title() default "";

    String description() default "";

    /** Поведенческие хинты для агента (MCP {@code annotations}). */
    ToolAnnotations annotations() default @ToolAnnotations;

    /** Произвольные строковые метаданные (MCP {@code _meta}). */
    Meta[] meta() default {};
}
