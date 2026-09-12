package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * What binding this skill to this agent would take — the connection wizard's input. The order the
 * wizard must follow: create the missing connection, bind it to the agent, then bind the skill with
 * the connections map keyed by requirement key; the rules are written at that last step and need the
 * binding to exist.
 */
@Schema(description = "Plan of binding a skill to an agent: per requirement, what fits and what is missing")
public record SkillBindingPlanResponse(
        UUID skillId,
        String skillName,
        @Schema(description = "Every requirement resolves to a bound instance already")
        boolean satisfied,
        List<SkillConnectorStatus> connectors
) {
    public static SkillBindingPlanResponse of(UUID skillId, String skillName, List<SkillConnectorStatus> connectors) {
        return new SkillBindingPlanResponse(skillId, skillName,
                connectors.stream().allMatch(SkillConnectorStatus::satisfied), connectors);
    }
}
