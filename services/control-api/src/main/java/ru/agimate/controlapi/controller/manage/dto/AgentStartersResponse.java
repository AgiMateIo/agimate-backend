package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.service.dto.agent.AgentStarters;

import java.util.List;
import java.util.UUID;

@Schema(description = "What an empty chat with the agent shows: the greeting and the starters, the agent's own first")
public record AgentStartersResponse(
        @Schema(nullable = true, description = "Shown above an empty chat; not part of the conversation")
        String greeting,

        @Schema(description = "Visible starters: the agent's own, then those of the skills the agent gets, "
                + "in binding order. Hidden ones are left out")
        List<Starter> starters
) {
    @Schema(description = "A starter — sent as the user's own message on a tap")
    public record Starter(
            @Schema(nullable = true, description = "Set on the agent's own starter, which is edited through PUT")
            UUID id,

            @Schema(nullable = true, description = "Set on a skill's starter: the binding it comes from. "
                    + "Hidden with this id and the text")
            UUID agentSkillId,

            @Schema(nullable = true, description = "The skill's display title, for a skill's starter")
            String skillTitle,

            @Schema(description = "The message to send")
            String text
    ) {
    }

    public static AgentStartersResponse from(AgentStarters starters) {
        return new AgentStartersResponse(starters.greeting(), starters.starters().stream()
                .map(s -> new Starter(s.id(), s.agentSkillId(), s.skillTitle(), s.text()))
                .toList());
    }
}
