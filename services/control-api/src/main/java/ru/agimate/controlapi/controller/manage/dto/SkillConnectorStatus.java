package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.abac.SkillPolicySync.DesiredPolicy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One requirement of a skill, seen from a particular agent — the same record before the skill is
 * bound (the wizard's plan) and after (the binding's listing): which instance the skill means, what
 * the user could pick or create, and what rules come with it.
 *
 * <p>{@link #connectionId} comes from the skill's own reference, and where there is none — from a
 * bound instance the requirement matches by identity, or any bound instance of the code, so an agent
 * does not turn red for something the user was never asked to choose. That is the standing rule, not
 * a migration step: a key loses its reference whenever the skill's author adds a connector to a skill
 * someone has already bound. Where several instances answer, the status shows the first while the
 * gate lets all of them through.
 *
 * @param satisfied the instance is bound to the agent. {@code false} means the skill declares
 *                  something the agent cannot reach — its tools will not be in the context
 */
@Schema(description = "Connector required by a skill: which instance it means and whether the agent has it")
public record SkillConnectorStatus(
        @Schema(description = "Requirement key within the skill — what the connections map is keyed by")
        String key,
        @Schema(description = "Connector code required by the skill")
        String connectorCode,
        @Schema(description = "Caption for the wizard: the skill's title, else the key, else the connector name")
        String title,
        @Schema(description = "Internal connector: the instance is forced (one per user), nothing to choose")
        boolean internal,
        @Schema(description = "Non-secret credential values the skill declares — pre-fill the create form", nullable = true)
        Map<String, String> params,
        @Schema(description = "The integration's credentials form, in the order to render", nullable = true)
        Map<String, CredentialFieldResponse> credentialFields,
        @Schema(description = "The instance identity the params resolve to (an MCP server's URL); null when unknown", nullable = true)
        String identity,
        @Schema(description = "The user's connections that fit: by identity when known, else every instance of the code")
        List<ConnectionMatch> matches,
        @Schema(description = "Instance the skill works with; null — none chosen and none available", nullable = true)
        UUID connectionId,
        @Schema(description = "Human-readable name of that instance", nullable = true)
        String connectionName,
        @Schema(description = "The instance is bound to the agent — the skill's tools will be there")
        boolean satisfied,
        @Schema(description = "Access rules the skill declares, as the rows they become on the binding")
        List<DesiredPolicy> policies,
        @Schema(description = "Declared rules not applied because a rule of another origin holds the same (kind, name)")
        List<String> policyConflicts
) {
    @Schema(description = "A connection of the user that fits the requirement")
    public record ConnectionMatch(
            UUID connectionId,
            String name,
            @Schema(description = "Already bound to this agent")
            boolean boundToAgent
    ) {
    }
}
