package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflective generator of JSON schemas for tools — with no third-party dependencies (our own engine
 * instead of langchain4j). Builds {@code inputSchema} from the method's parameters (names via
 * {@code -parameters}, descriptions and required from {@link ToolParam}) and {@code outputSchema}
 * from the return type.
 *
 * <p>{@code Map<String, V>} expands into {@code object} + {@code additionalProperties} = the value's
 * schema; {@code Map<String, Object>} → {@code additionalProperties: {}} (any). {@code void} yields
 * no {@code outputSchema} at all.
 */
final class ToolSchemaReflector {

    private ToolSchemaReflector() {
    }

    /** An {@code object} built from the method's parameters; {@code null} when there are none. */
    static JsonSchema inputSchema(Method method) {
        Parameter[] params = method.getParameters();
        if (params.length == 0) {
            // MCP requires an inputSchema even for a tool with no parameters → an empty object schema.
            return JsonSchema.object(Map.of(), null, null);
        }
        Map<String, JsonSchema> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter p : params) {
            ToolParam meta = p.getAnnotation(ToolParam.class);
            String description = meta != null && !meta.value().isBlank() ? meta.value() : null;
            boolean isRequired = meta == null || meta.required();
            properties.put(p.getName(), schemaFor(p.getParameterizedType(), description, new ArrayDeque<>()));
            if (isRequired) {
                required.add(p.getName());
            }
        }
        return JsonSchema.object(properties, required.isEmpty() ? null : required, null);
    }

    /** Schema from the return type; {@code null} for {@code void}/{@code Void}. */
    static JsonSchema outputSchema(Method method) {
        Type returnType = method.getGenericReturnType();
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        return schemaFor(returnType, null, new ArrayDeque<>());
    }

    private static JsonSchema schemaFor(Type type, String description, Deque<Class<?>> stack) {
        Class<?> raw = rawClass(type);

        if (raw == String.class || raw == char.class || raw == Character.class
                || CharSequence.class.isAssignableFrom(raw)) {
            return JsonSchema.scalar("string", description);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return JsonSchema.scalar("boolean", description);
        }
        if (raw == int.class || raw == long.class || raw == short.class || raw == byte.class
                || raw == Integer.class || raw == Long.class || raw == Short.class || raw == Byte.class
                || raw == BigInteger.class) {
            return JsonSchema.scalar("integer", description);
        }
        if (raw == float.class || raw == double.class
                || raw == Float.class || raw == Double.class || raw == BigDecimal.class) {
            return JsonSchema.scalar("number", description);
        }
        if (raw.isEnum()) {
            List<String> values = new ArrayList<>();
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
            return JsonSchema.enumString(description, values);
        }
        if (raw.isArray()) {
            return JsonSchema.array(description, schemaFor(raw.getComponentType(), null, stack));
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return JsonSchema.array(description, schemaFor(typeArg(type, 0), null, stack));
        }
        if (Map.class.isAssignableFrom(raw)) {
            // JSON keys are always strings → we describe only the value's type, through additionalProperties
            return JsonSchema.map(description, schemaFor(typeArg(type, 1), null, stack));
        }
        if (raw.isRecord()) {
            if (stack.contains(raw)) {
                return JsonSchema.scalar("object", description); // guard against self-recursion
            }
            stack.push(raw);
            try {
                Map<String, JsonSchema> properties = new LinkedHashMap<>();
                List<String> required = new ArrayList<>();
                for (RecordComponent component : raw.getRecordComponents()) {
                    properties.put(component.getName(), schemaFor(component.getGenericType(), null, stack));
                    required.add(component.getName());
                }
                return JsonSchema.object(properties, required.isEmpty() ? null : required, description);
            } finally {
                stack.pop();
            }
        }
        // Object and other unsupported types → an empty schema (any). We will extend it for real needs.
        return JsonSchema.any(description);
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized) {
            return (Class<?>) parameterized.getRawType();
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return upper.length > 0 ? rawClass(upper[0]) : Object.class;
        }
        if (type instanceof TypeVariable<?>) {
            return Object.class;
        }
        return Object.class;
    }

    private static Type typeArg(Type type, int index) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] args = parameterized.getActualTypeArguments();
            if (index < args.length) {
                return args[index];
            }
        }
        return Object.class;
    }
}
