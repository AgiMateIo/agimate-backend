package ru.agimate.controlapi.connectors.internal.platform.dto;

import java.util.List;

/**
 * View-модели platform-коннектора — типы возврата {@code @Tool}-методов. Плоские, LLM-дружелюбные
 * (public id строками), собираются коннектором из репозиториев. Живут в коннекторном слое (не
 * {@code controller/**}): record'ы дают рефлектору нормальный {@code outputSchema}, в отличие от
 * {@code Map<String,Object>}. Списки обёрнуты в объект — верхний уровень MCP-результата всегда object.
 */
public final class PlatformDtos {

    private PlatformDtos() {
    }

    public record ConnectorBrief(String code, String name, String description, boolean integration) {
    }

    public record ConnectorList(List<ConnectorBrief> connectors) {
    }

    public record ToolBrief(String name, String description) {
    }

    public record TriggerBrief(String name, String description) {
    }

    public record ConnectorDetail(String code, String name, String description, boolean integration,
                                  List<ToolBrief> tools, List<TriggerBrief> triggers) {
    }

    public record SkillBrief(String id, String name, String title, String description,
                             List<String> connectorCodes, int version, boolean isPublic, boolean system) {
    }

    public record SkillList(List<SkillBrief> skills) {
    }

    public record SkillDetail(String id, String name, String title, String description,
                              List<String> connectorCodes, int version, boolean isPublic, boolean system,
                              String mdContent) {
    }

    public record BoundSkill(String skillId, String name, List<String> connectorCodes) {
    }

    public record AgentBrief(String id, String name, String description, String type,
                             boolean enabled, String teamId) {
    }

    public record AgentList(List<AgentBrief> agents) {
    }

    public record AgentDetail(String id, String name, String description, String instructions,
                              String type, boolean enabled, String teamId, List<BoundSkill> skills) {
    }

    public record CreatedAgent(String id, String name) {
    }

    public record ConnectionBrief(String id, String connectorCode, String name,
                                  boolean enabled, String subCode) {
    }

    public record ConnectionList(List<ConnectionBrief> connections) {
    }

    /** Deep-link: тул не пишет в БД — человек создаёт подключение штатной формой, вводя секрет вне LLM. */
    public record ConnectionSetup(String status, String setupUrl, String connectorCode) {
    }

    public record OperationResult(boolean ok, String message) {
    }
}
