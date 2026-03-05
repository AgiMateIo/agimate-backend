package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.ToolUseLogResponse;
import ru.agimate.deviceapi.service.ToolUseLogService;

import java.util.UUID;

@RestController
@RequestMapping(ManageToolUseLogsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Tool Use Logs", description = "Manage tool use logs")
public class ManageToolUseLogsController {

    public static final String PATH = "/manage/tool-use-logs";

    private final ToolUseLogService toolUseLogService;

    @Operation(
            summary = "List tool use logs",
            description = "Returns tool use logs with optional filtering by API key"
    )
    @GetMapping("/")
    public SuccessResponse<Page<ToolUseLogResponse>> getToolUseLogs(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID agentPubId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(toolUseLogService.getToolUseLogs(userPubId, agentPubId, page, size));
    }
}
