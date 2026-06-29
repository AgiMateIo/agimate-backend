package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.ToolCallLogResponse;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

import java.util.UUID;

@RestController
@RequestMapping(ManageToolCallLogsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Tool Use Logs", description = "Manage tool use logs")
public class ManageToolCallLogsController {

    public static final String PATH = "/manage/tool-call-logs";

    private final ToolCallLogService toolCallLogService;

    @Operation(
            summary = "List tool use logs",
            description = "Returns the current user's tool use logs, optionally filtered by agent id"
    )
    @GetMapping("/")
    public SuccessResponse<Page<ToolCallLogResponse>> getToolCallLogs(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(toolCallLogService.getToolCallLogs(userId, agentId, page, size));
    }
}
