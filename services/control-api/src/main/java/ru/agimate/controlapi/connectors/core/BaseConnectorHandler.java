package ru.agimate.controlapi.connectors.core;

import com.fasterxml.jackson.databind.JavaType;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.annotation.ToolMeta;
import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Единственный reflection-диспатчер коннекторов: сканирует {@code @Tool}-методы tool-сервиса,
 * строит спеки и выполняет вызовы с привязкой {@link ConnectorContext} через
 * {@link ConnectorContextHolder} (set/clear только здесь).
 *
 * <p>Декларативная джоба ({@link Job}) и внутренний метод ({@code @Tool(internal = true)}) скрыты
 * от LLM — не попадают в {@link #getTools()} и недоступны через {@link #executeTool}; при этом
 * {@link #executeJob} диспатчит в любой {@code @Tool}-метод, поэтому таска может быть и «вызовом
 * тулы по расписанию».
 */
public abstract class BaseConnectorHandler implements ConnectorHandler {

    private final Object toolService;
    private final Map<String, Method> methodsByName;
    private final Map<String, ConnectorToolSpec> toolSpecs;

    protected BaseConnectorHandler(Object toolService) {
        this.toolService = toolService;
        this.methodsByName = scanToolMethods(toolService);
        this.toolSpecs = buildToolSpecs(this.methodsByName);
    }

    private static Map<String, Method> scanToolMethods(Object toolService) {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Method method : toolService.getClass().getMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            Method previous = methods.putIfAbsent(tool.name(), method);
            if (previous != null) {
                throw new IllegalStateException("Duplicate @Tool name '" + tool.name()
                        + "' in " + toolService.getClass().getName());
            }
        }
        return methods;
    }

    /** Спеки тулов статичны (аннотации + сигнатуры не меняются) — строим один раз при создании. */
    private static Map<String, ConnectorToolSpec> buildToolSpecs(Map<String, Method> methodsByName) {
        Map<String, ConnectorToolSpec> specs = new LinkedHashMap<>();
        methodsByName.forEach((name, method) -> {
            if (!hiddenFromLlm(method)) {
                specs.put(name, toToolSpec(name, method));
            }
        });
        return Collections.unmodifiableMap(specs);
    }

    @Override
    public Map<String, ConnectorToolSpec> getTools() {
        return toolSpecs;
    }

    private static ConnectorToolSpec toToolSpec(String name, Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        return new ConnectorToolSpec(
                name,
                tool.title().isBlank() ? null : tool.title(),
                tool.description().isBlank() ? null : tool.description(),
                ToolSchemaReflector.inputSchema(method),
                ToolSchemaReflector.outputSchema(method),
                toAnnotationsSpec(tool.annotations()),
                toMeta(tool.meta()));
    }

    private static ToolAnnotationsSpec toAnnotationsSpec(ToolAnnotations a) {
        return new ToolAnnotationsSpec(
                a.readOnlyHint(), a.destructiveHint(), a.idempotentHint(), a.openWorldHint());
    }

    private static Map<String, String> toMeta(ToolMeta[] toolMeta) {
        if (toolMeta.length == 0) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (ToolMeta m : toolMeta) {
            map.put(m.key(), m.value());
        }
        return map;
    }

    @Override
    public Map<String, JobSpec> getJobs() {
        Map<String, JobSpec> specs = new LinkedHashMap<>();
        methodsByName.forEach((name, method) -> {
            Job task = method.getAnnotation(Job.class);
            if (task != null) {
                specs.put(name, toJobSpecification(name, task));
            }
        });
        return specs;
    }

    private static JobSpec toJobSpecification(String name, Job task) {
        Map<String, Object> config = switch (task.type()) {
            case ONETIME -> Map.of();
            case PERIODIC -> Map.of("intervalSeconds", task.intervalSeconds());
            case CRON -> Map.of("cron", task.cron(), "zone", task.zone());
        };
        return new JobSpec(name, task.type(), config, Map.of(), task.timeoutSeconds());
    }

    @Override
    public Map<String, Object> executeTool(ConnectorContext context, String toolName, Map<String, Object> args) {
        Method method = methodsByName.get(toolName);
        if (method == null || hiddenFromLlm(method)) {
            throw new ConnectorException("Unknown tool: " + toolName);
        }
        return invoke(context, method, args);
    }

    /**
     * Метод скрыт от LLM (нет в {@link #getTools()}, недоступен через {@link #executeTool}), если это
     * декларативная джоба ({@link Job}) или внутренняя цель диспатча ({@code @Tool(internal = true)}).
     * В обоих случаях метод всё ещё вызывается через {@link #executeJob} по строке/расписанию.
     */
    private static boolean hiddenFromLlm(Method method) {
        return method.isAnnotationPresent(Job.class)
                || method.getAnnotation(Tool.class).internal();
    }

    @Override
    public Map<String, Object> executeJob(ConnectorContext context, String name, Map<String, Object> args) {
        Method method = methodsByName.get(name);
        if (method == null) {
            throw new ConnectorException("Unknown task: " + name);
        }
        return invoke(context, method, args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(ConnectorContext context, Method method, Map<String, Object> args) {
        ConnectorContextHolder.set(context);
        try {
            Object result = method.invoke(toolService, buildMethodArgs(method, args));
            if (result == null) {
                return Map.of();
            }
            if (result instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of("result", result);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new ConnectorException("Execution failed: " + method.getName(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new ConnectorException("Method not accessible: " + method.getName(), e);
        } finally {
            ConnectorContextHolder.clear();
        }
    }

    private static Object[] buildMethodArgs(Method method, Map<String, Object> args) {
        Parameter[] parameters = method.getParameters();
        Object[] values = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Object value = args == null ? null : args.get(parameters[i].getName());
            if (value != null) {
                values[i] = convertArg(value, parameters[i].getParameterizedType());
            }
        }
        return values;
    }

    /**
     * Приводит значение аргумента к типу параметра. Поддерживает любые типы, которые описывает
     * {@link ToolSchemaReflector}: примитивы/обёртки, enum, record, коллекции, мапы, вложенные
     * объекты — через Jackson. String — быстрый путь (число → его строковое представление).
     */
    private static Object convertArg(Object value, Type targetType) {
        JavaType javaType = JsonUtils.MAPPER.getTypeFactory().constructType(targetType);
        if (javaType.getRawClass().isInstance(value)) {
            return value;
        }
        if (javaType.hasRawClass(String.class)) {
            return String.valueOf(value);
        }
        try {
            return JsonUtils.MAPPER.convertValue(value, javaType);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException(
                    "Cannot convert argument to " + targetType.getTypeName() + ": " + e.getMessage(), e);
        }
    }
}
