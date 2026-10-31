package ru.agimate.controlapi.controller.manage.dto.llm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.agimate.controlapi.service.PlatformLlmService;

import java.util.List;

@Schema(description = "Replace the ordered chain of a platform purpose (the purpose is in the path)")
public record UpdatePlatformLlmRequest(
        @NotNull
        @Size(max = PlatformLlmService.MAX_BINDINGS_PER_PURPOSE)
        @Schema(description = "Bindings in priority order, at most five; an empty list removes the "
                + "purpose and is equivalent to DELETE")
        List<@Valid PlatformLlmBindingRequest> bindings
) {
}
