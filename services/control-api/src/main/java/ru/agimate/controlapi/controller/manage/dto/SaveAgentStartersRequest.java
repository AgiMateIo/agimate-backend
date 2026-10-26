package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.service.dto.agent.AgentStarters;

import java.util.List;
import java.util.UUID;

@Schema(description = "The greeting and the agent's own starters, replaced whole; a skill's starters are not edited here")
public record SaveAgentStartersRequest(
        @Schema(nullable = true, description = "Up to 1000 characters; empty or absent — no greeting")
        String greeting,

        @Schema(description = "The agent's own starters in display order: up to 10, one line of at most 150 "
                + "characters each, no repeats. An entry with an id updates that starter, one without is new, "
                + "an existing starter left out is deleted")
        List<Entry> starters
) {
    @Schema(description = "One of the agent's own starters")
    public record Entry(
            @Schema(nullable = true, description = "Id of an existing starter; absent — a new one")
            UUID id,

            @Schema(description = "The message to send")
            String text
    ) {
    }

    public List<AgentStarters.OwnStarter> resolveStarters() {
        return starters == null ? List.of()
                : starters.stream().map(e -> new AgentStarters.OwnStarter(e.id(), e.text())).toList();
    }
}
