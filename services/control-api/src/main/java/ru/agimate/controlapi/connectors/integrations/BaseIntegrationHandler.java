package ru.agimate.controlapi.connectors.integrations;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import lombok.RequiredArgsConstructor;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public abstract class BaseIntegrationHandler implements IntegrationHandler {

    private static final ThreadLocal<ToolExecutionContext> CONTEXT = new ThreadLocal<>();

    private final IntegrationEncryptionService encryptionService;

    protected IntegrationCredentials integrationCredentials() {
        return CONTEXT.get().integrationCredentials();
    }

    protected Map<String, String> credentials() {
        return CONTEXT.get().credentials();
    }

    @Override
    public Map<String, ToolSpecification> getPredefinedTools() {
        return ToolSpecifications.toolSpecificationsFrom(getClass()).stream()
                .collect(Collectors.toMap(
                        ToolSpecification::name, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeTool(IntegrationCredentials integrationCredentials,
                                           String toolName,
                                           Map<String, Object> params) {
        CONTEXT.set(new ToolExecutionContext(integrationCredentials, encryptionService));
        try {
            Method method = findToolMethod(toolName);
            Object[] args = buildMethodArgs(method, params);
            return (Map<String, Object>) method.invoke(this, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException("Tool execution failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Tool method not accessible", e);
        } finally {
            CONTEXT.remove();
        }
    }

    private Method findToolMethod(String toolName) {
        for (Method m : getClass().getMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool != null && toolName.equals(tool.name())) return m;
        }
        throw new BadRequestStatusException("Unknown tool: " + toolName);
    }

    private Object[] buildMethodArgs(Method method, Map<String, Object> params) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            String paramName = parameters[i].getName();
            Object value = params.get(paramName);
            if (value != null) {
                args[i] = convertArg(value, parameters[i].getType());
            }
        }
        return args;
    }

    private Object convertArg(Object value, Class<?> targetType) {
        if (targetType.isInstance(value)) return value;
        if (targetType == String.class) return String.valueOf(value);
        return value;
    }

    private record ToolExecutionContext(
            IntegrationCredentials integrationCredentials,
            IntegrationEncryptionService encryptionService
    ) {
        private Map<String, String> credentials() {
            return encryptionService.decryptCredentials(integrationCredentials.getEncryptedData());
        }
    }
}
