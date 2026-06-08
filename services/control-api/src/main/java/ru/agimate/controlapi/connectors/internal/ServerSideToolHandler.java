package ru.agimate.controlapi.connectors.internal;

import dev.langchain4j.agent.tool.ToolSpecification;
import ru.agimate.controlapi.connectors.tasks.TaskDescriptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ServerSideToolHandler {

    String getConnectorCode();

    Map<String, ToolSpecification> getToolDefinitions();

    Map<String, Object> executeTool(String toolName, Map<String, Object> params,
                                     UUID agentId, UUID userId);

    /**
     * Фоновые задачи коннектора. В отличие от {@code IntegrationHandler}, scope задаётся
     * самим handler'ом — Global или User(uuid). Bootstrap при старте приложения регистрирует
     * Global‑задачи в {@code connector_tasks} (идемпотентно по бизнес‑ключу).
     *
     * <p>USER‑scope задачи бутстрэп пропускает — для них нужен явный lifecycle hook (новый
     * пользователь / новая сущность). Когда такая задача появится, handler сам вызывает
     * {@code ConnectorTaskService.upsert(...)} в нужный момент.
     */
    default List<TaskDescriptor> getBackgroundTasks() {
        return List.of();
    }
}
