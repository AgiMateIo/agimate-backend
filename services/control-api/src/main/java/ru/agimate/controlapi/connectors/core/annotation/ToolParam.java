package ru.agimate.controlapi.connectors.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Description of a tool's parameter for {@code inputSchema}. The parameter's name comes from
 * reflection (javac is compiled with {@code -parameters}), so the annotation carries only the
 * description and required.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {

    /** Parameter description (shorthand: {@code @ToolParam("...")}). */
    String value() default "";

    boolean required() default true;
}
