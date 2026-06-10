package ru.agimate.controlapi.connectors.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Описание параметра тула для {@code inputSchema}. Имя параметра берётся из рефлексии
 * (javac компилируется с {@code -parameters}), поэтому в аннотации только описание и required.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {

    /** Описание параметра (shorthand: {@code @ToolParam("...")}). */
    String value() default "";

    boolean required() default true;
}
