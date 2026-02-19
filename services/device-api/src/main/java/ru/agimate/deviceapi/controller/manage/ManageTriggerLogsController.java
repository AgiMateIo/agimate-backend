package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.TriggerLogResponse;
import ru.agimate.deviceapi.service.TriggerLogService;

import java.util.UUID;

@RestController
@RequestMapping(ManageTriggerLogsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Trigger Logs", description = "Manage trigger logs")
public class ManageTriggerLogsController {

    public static final String PATH = "/manage/trigger-logs";

    private final TriggerLogService triggerLogService;

    @Operation(
            summary = "List trigger logs",
            description = "Returns trigger logs for the current user with optional filtering"
    )
    @GetMapping("/")
    public SuccessResponse<Page<TriggerLogResponse>> getTriggerLogs(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) UUID appPubId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(triggerLogService.getTriggerLogs(userPubId, deviceId, appPubId, page, size));
    }
}
