package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import ru.agimate.controlapi.database.enums.Disclosure;

/**
 * Changing a binding. The axis is a tri-state on the wire because {@code null} already means «keep»
 * in the PATCH convention and an enum has no empty string to clear with — {@link Choice#INHERIT}
 * is the explicit «back to the skill's default».
 */
@Schema(description = "Update an agent-skill binding")
public record UpdateAgentSkillRequest(
        @NotNull
        @Schema(description = "EAGER / LAZY override the skill's axis for this agent; INHERIT drops the override")
        Choice disclosure
) {
    public enum Choice {
        INHERIT, EAGER, LAZY;

        /** The binding's stored override: {@code null} for {@link #INHERIT}. */
        public Disclosure toOverride() {
            return switch (this) {
                case INHERIT -> null;
                case EAGER -> Disclosure.EAGER;
                case LAZY -> Disclosure.LAZY;
            };
        }
    }
}
