package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.ToolUseLogResponse;
import ru.agimate.deviceapi.service.ToolUseLogService;

import java.util.List;
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
    public SuccessResponse<List<ToolUseLogResponse>> getToolUseLogs(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID apiKeyPubId
    ) {
        // todo check access to this logs by user_pub_id (should be added to tool_use_log entity)
        return SuccessResponse.ok(toolUseLogService.getToolUseLogs(apiKeyPubId));
    }
}
