package ru.agimate.controlapi.connectors.core;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import ru.agimate.controlapi.connectors.core.dto.TaskSpecification;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Единственный reflection-диспатчер коннекторов: сканирует {@code @Tool}-методы tool-сервиса,
 * строит спеки и выполняет вызовы с привязкой {@link ConnectorContext} через
 * {@link ConnectorContextHolder} (set/clear только здесь).
 *
 * <p>Методы с {@link TaskOnly} не попадают в {@link #getTools()} и недоступны через
 * {@link #executeTool}; {@link #executeTask} диспатчит в любой {@code @Tool}-метод, поэтому
 * таска может быть и «вызовом тулы по расписанию».
 */
public abstract class BaseConnectorHandler implements ConnectorHandler {

    private final Object toolService;
    private final Map<String, Method> methodsByName;

    protected BaseConnectorHandler(Object toolService) {
        this.toolService = toolService;
        this.methodsByName = scanToolMethods(toolService);
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

    @Override
    public Map<String, ToolSpecification> getTools() {
        Map<String, ToolSpecification> specs = new LinkedHashMap<>();
        methodsByName.forEach((name, method) -> {
            if (!method.isAnnotationPresent(TaskOnly.class)) {
                specs.put(name, ToolSpecifications.toolSpecificationFrom(method));
            }
        });
        return specs;
    }

    @Override
    public Map<String, TaskSpecification> getTasks() {
        Map<String, TaskSpecification> specs = new LinkedHashMap<>();
        methodsByName.forEach((name, method) -> {
            TaskOnly task = method.getAnnotation(TaskOnly.class);
            if (task != null) {
                specs.put(name, toTaskSpecification(name, task));
            }
        });
        return specs;
    }

    private static TaskSpecification toTaskSpecification(String name, TaskOnly task) {
        Map<String, Object> config = switch (task.type()) {
            case ONETIME -> Map.of();
            case PERIODIC -> Map.of("intervalSeconds", task.intervalSeconds());
            case CRON -> Map.of("cron", task.cron(), "zone", task.zone());
        };
        return new TaskSpecification(name, task.type(), config, Map.of(), task.timeoutSeconds());
    }

    @Override
    public Map<String, Object> executeTool(ConnectorContext context, String toolName, Map<String, Object> args) {
        Method method = methodsByName.get(toolName);
        if (method == null || method.isAnnotationPresent(TaskOnly.class)) {
            throw new ConnectorException("Unknown tool: " + toolName);
        }
        return invoke(context, method, args);
    }

    @Override
    public Map<String, Object> executeTask(ConnectorContext context, String taskName, Map<String, Object> args) {
        Method method = methodsByName.get(taskName);
        if (method == null) {
            throw new ConnectorException("Unknown task: " + taskName);
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
                values[i] = convertArg(value, parameters[i].getType());
            }
        }
        return values;
    }

    private static Object convertArg(Object value, Class<?> targetType) {
        if (targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        return value;
    }
}
