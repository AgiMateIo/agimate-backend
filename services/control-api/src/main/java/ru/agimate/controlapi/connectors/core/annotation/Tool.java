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
    ToolMeta[] meta() default {};

    /**
     * Бюджет ожидания результата воркером, секунды. {@code 0} — дефолт воркера
     * ({@code agent.tool.poll-timeout}, 60s). Для долгих тулов (генерация медиа и т.п.) —
     * до 30 минут: большее значение воркер клампит. Бюджет ограничивает только ожидание,
     * само выполнение на бэке не отменяется.
     */
    int timeoutSeconds() default 0;

    /**
     * {@code true} — метод скрыт от LLM (нет в {@code getTools()}, недоступен через {@code executeTool}),
     * но остаётся целью диспатча через {@code executeJob} (динамические строки {@code connector_jobs},
     * напр. {@code time.fire}). Для декларативных фоновых задач используйте {@link Job} — они скрыты сами.
     */
    boolean internal() default false;
}
