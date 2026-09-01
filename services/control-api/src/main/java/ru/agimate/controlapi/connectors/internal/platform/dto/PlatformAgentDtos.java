package ru.agimate.controlapi.connectors.internal.platform.dto;

import java.util.List;

/**
 * View models of the agent/skill/file tools of the platform connector ({@code PlatformAgentToolService}).
 * Flat and LLM-friendly (public ids as strings). See {@link PlatformDtos} for the shared-file rules;
 * this file holds only the records the agent module owns.
 *
 * <p>Records for the tools added by the agents-module task ({@code AgentKeyRegenerated},
 * {@code AgentSkillList}, {@code AgentSkillBinding}) are declared there.
 */
public final class PlatformAgentDtos {

    private PlatformAgentDtos() {
    }

    public record AgentList(List<PlatformDtos.AgentBrief> agents, boolean truncated) {
    }

    public record AgentDetail(String id, String name, String description, String instructions,
                              String type, boolean enabled, String teamId, String webhookUrl,
                              boolean hasWebhookAuth, List<BoundSkill> skills) {
    }

    /** {@code keyUrl} opens the UI page where the key is shown once; tools never return secrets. */
    public record CreatedAgent(String id, String name, String keyUrl) {
    }

    /** {@code keyUrl} opens the UI page where the regenerated key is shown once; tools never return secrets. */
    public record AgentKeyRegenerated(String id, String name, String keyUrl) {
    }

    public record AgentSkillList(List<AgentSkillBinding> skills) {
    }

    /**
     * One skill bound to an agent.
     *
     * @param connectorCodes the connectors the skill declares
     * @param satisfied      whether every declared connector has a bound connection — an unsatisfied
     *                       skill is not given to the agent (see {@code AgentSkillService.satisfiedSkillInstances})
     */
    public record AgentSkillBinding(String skillId, String name, List<String> connectorCodes,
                                    boolean satisfied) {
    }

    public record BoundSkill(String skillId, String name, List<String> connectorCodes) {
    }

    public record SkillBrief(String id, String name, String title, String description,
                             List<String> connectorCodes, int version, boolean isPublic, boolean system) {
    }

    public record SkillList(List<SkillBrief> skills, boolean truncated) {
    }

    public record SkillDetail(String id, String name, String title, String description,
                              List<String> connectorCodes, int version, boolean isPublic, boolean system,
                              String mdContent) {
    }

    /**
     * A file of the owner as the agent sees it. {@code id} is the {@code agf_} reference: it goes
     * into another tool's parameter or into an {@code [[attach:…]]} marker. No URL — a link would be
     * signed, short-lived and useless to a model.
     *
     * @param name {@code null} where the file never had one (a photo from a chat, a generated image)
     */
    public record FileBrief(String id, String name, String mime, long size, String createdAt) {
    }

    public record FileList(List<FileBrief> files, boolean truncated) {
    }
}
