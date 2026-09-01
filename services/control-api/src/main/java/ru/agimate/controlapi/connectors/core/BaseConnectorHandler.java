package ru.agimate.controlapi.connectors.core;

import com.fasterxml.jackson.databind.JavaType;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.annotation.ToolMeta;
import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.jobs.JobSchedule;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base of a connector facade with {@code @Tool} methods: identity ({@link ConnectorHandler}, with
 * {@code connectorCode} left to the facade) plus the single reflection dispatcher
 * {@link ToolProvider}/{@link JobProvider} — it scans the tool services' {@code @Tool} methods,
 * builds the specs and performs calls with {@link ConnectorEnv} bound through
 * {@link ConnectorEnvHolder} (set/clear happens only here). Connectors without a tool service
 * (webchat, MCP) do not use this base — they implement the interfaces they need directly.
 *
 * <p>A declarative job ({@link Job}) and an internal method ({@code @Tool(internal = true)}) are
 * hidden from the LLM — they are absent from {@link #getTools()} and unreachable through
 * {@link #executeTool}; {@link #executeJob}, however, dispatches into any {@code @Tool} method, so a
 * job can also be «a tool call on a schedule».
 */
public abstract class BaseConnectorHandler implements ConnectorHandler, ToolProvider, JobProvider {

    /** Dispatch target of each {@code @Tool} method: several tool services may share one facade. */
    private final Map<Method, Object> ownerByMethod;
    private final Map<String, Method> methodsByName;
    private final Map<String, ConnectorToolSpec> toolSpecs;

    protected BaseConnectorHandler(Object... toolServices) {
        if (toolServices.length == 0) {
            throw new IllegalStateException(getClass().getSimpleName()
                    + " must be built with at least one tool service");
        }
        for (Object service : toolServices) {
            if (service == null) {
                throw new IllegalStateException(getClass().getSimpleName()
                        + " received a null tool service");
            }
        }
        Map<Method, Object> owners = new LinkedHashMap<>();
        this.methodsByName = scanToolMethods(toolServices, owners);
        this.ownerByMethod = Map.copyOf(owners);
        this.toolSpecs = buildToolSpecs(this.methodsByName);
    }

    /**
     * Collects every {@code @Tool} method across all tool services, remembering which service owns
     * each method. A {@code @Tool} name is unique per facade — a duplicate across two services is a
     * wiring error and fails fast, naming both declaring classes.
     */
    private static Map<String, Method> scanToolMethods(Object[] toolServices, Map<Method, Object> owners) {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Object toolService : toolServices) {
            for (Method method : toolService.getClass().getMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                Method previous = methods.putIfAbsent(tool.name(), method);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate @Tool name '" + tool.name()
                            + "' across " + previous.getDeclaringClass().getName()
                            + " and " + toolService.getClass().getName());
                }
                owners.put(method, toolService);
            }
        }
        return methods;
    }

    /** Tool specs are static (annotations and signatures do not change) — built once at construction. */
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
                toMeta(tool.meta()),
                tool.timeoutSeconds() > 0 ? tool.timeoutSeconds() : null);
    }

    private static ToolAnnotationsSpec toAnnotationsSpec(ToolAnnotations a) {
        // destructiveHint is meaningful only for a writing tool (MCP says so), and the annotation's
        // default is the pessimistic true — so a read-only tool that never mentions it would advertise
        // itself as destructive. Normalised here rather than restated on every read-only declaration.
        return new ToolAnnotationsSpec(
                a.readOnlyHint(), !a.readOnlyHint() && a.destructiveHint(),
                a.idempotentHint(), a.openWorldHint());
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
            case ONETIME -> JobSchedule.onetimeConfig();
            case PERIODIC -> JobSchedule.periodicConfig(task.intervalSeconds());
            case CRON -> JobSchedule.cronConfig(task.cron(), task.zone());
        };
        return new JobSpec(name, task.type(), config, Map.of(), task.timeoutSeconds());
    }

    @Override
    public Map<String, Object> executeTool(ConnectorEnv env, String toolName, Map<String, Object> args) {
        Method method = methodsByName.get(toolName);
        if (method == null || hiddenFromLlm(method)) {
            throw new ConnectorException("Unknown tool: " + toolName);
        }
        return invoke(env, method, args);
    }

    /**
     * A method is hidden from the LLM (absent from {@link #getTools()}, unreachable through
     * {@link #executeTool}) when it is a declarative job ({@link Job}) or an internal dispatch target
     * ({@code @Tool(internal = true)}). In both cases the method is still callable through
     * {@link #executeJob}, by name or on a schedule.
     */
    private static boolean hiddenFromLlm(Method method) {
        return method.isAnnotationPresent(Job.class)
                || method.getAnnotation(Tool.class).internal();
    }

    @Override
    public Map<String, Object> executeJob(ConnectorEnv env, String name, Map<String, Object> args) {
        Method method = methodsByName.get(name);
        if (method == null) {
            throw new ConnectorException("Unknown task: " + name);
        }
        return invoke(env, method, args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(ConnectorEnv env, Method method, Map<String, Object> args) {
        ConnectorEnvHolder.set(env);
        try {
            Object result = method.invoke(ownerByMethod.get(method), buildMethodArgs(method, args));
            if (result == null) {
                return Map.of();
            }
            if (result instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            // A record return is expanded into a flat Map (camelCase keys = the component names) so the
            // runtime output matches the outputSchema from ToolSchemaReflector. Anything else has no
            // shape the reflector would agree with — a scalar return is described as a scalar there,
            // so wrapping it here would contradict the declared schema.
            if (result.getClass().isRecord()) {
                return JsonUtils.objectToMap(result);
            }
            throw new ConnectorException("Tool " + method.getName() + " must return a Map, a record or void, got "
                    + result.getClass().getSimpleName());
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new ConnectorException("Execution failed: " + method.getName(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new ConnectorException("Method not accessible: " + method.getName(), e);
        } finally {
            ConnectorEnvHolder.clear();
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
     * Coerces an argument value to the parameter's type. Supports every type
     * {@link ToolSchemaReflector} can describe: primitives and wrappers, enums, records,
     * collections, maps, nested objects — through Jackson. String is the fast path (a number becomes
     * its string representation).
     */
    private static Object convertArg(Object value, Type targetType) {
        JavaType javaType = JsonUtils.MAPPER.getTypeFactory().constructType(targetType);
        // The fast path is for parameterless types only: the raw class of List<ColumnSpec> is List, so a
        // List<Map> arriving from the LLM would pass the check wholesale and leave its elements as maps (with a
        // ClassCastException later, inside the tool). Containers always go to Jackson element by element.
        if (!javaType.hasGenericTypes() && javaType.getRawClass().isInstance(value)) {
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
