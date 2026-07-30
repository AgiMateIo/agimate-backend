package ru.agimate.controlapi.controller.manage.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmUsageResponse;
import ru.agimate.controlapi.service.llm.LlmUsageQueryService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAdminLlmUsageController.PATH)
@RequiredArgsConstructor
@Tag(name = "Admin: LLM Usage", description = "Token usage of an arbitrary user (admins only)")
public class ManageAdminLlmUsageController {

    public static final String PATH = ManageAdminPaths.PREFIX + "/llm-usage";

    private final LlmUsageQueryService llmUsageQueryService;

    @Operation(summary = "Usage per provider for a given user",
            description = "The same shape as /manage/llm-usage, but for the user in the path. An unknown "
                    + "user id is not an error: control-api does not own the user directory, so the answer "
                    + "is simply the platform provider with zero usage")
    @GetMapping("/{userId}/")
    public SuccessResponse<List<LlmUsageResponse>> usage(@PathVariable UUID userId) {
        return SuccessResponse.ok(llmUsageQueryService.usageForUser(userId));
    }
}
