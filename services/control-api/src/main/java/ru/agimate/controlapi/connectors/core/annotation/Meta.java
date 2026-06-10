package ru.agimate.controlapi.connectors.core.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Одна пара ключ-значение для MCP {@code _meta}. Значения только строковые — ограничение
 * Java-аннотаций (нельзя {@code Map}/произвольный JSON). Для богатого {@code _meta} место в БД/рантайме.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Meta {

    String key();

    String value();
}
