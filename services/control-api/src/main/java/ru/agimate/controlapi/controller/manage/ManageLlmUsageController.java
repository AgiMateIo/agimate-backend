package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmUsageResponse;
import ru.agimate.controlapi.service.llm.LlmUsageQueryService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageLlmUsageController.PATH)
@RequiredArgsConstructor
@Tag(name = "LLM Usage", description = "Token usage and remaining quota per provider (current windows, UTC)")
public class ManageLlmUsageController {

    public static final String PATH = "/manage/llm-usage";

    private final LlmUsageQueryService llmUsageQueryService;

    @Operation(summary = "Usage per provider for the current user",
            description = "Own (BYOK) providers show whole-provider usage; the platform provider shows this user's usage")
    @GetMapping("/")
    public SuccessResponse<List<LlmUsageResponse>> usage(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmUsageQueryService.usageForUser(userId));
    }
}
