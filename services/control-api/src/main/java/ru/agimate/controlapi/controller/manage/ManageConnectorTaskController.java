package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.ConnectorTaskResponse;
import ru.agimate.controlapi.database.enums.ConnectorTaskKind;
import ru.agimate.controlapi.service.ConnectorTaskManageService;

import java.util.UUID;

@RestController
@RequestMapping(ManageConnectorTaskController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connector Tasks", description = "Manage background connector tasks")
public class ManageConnectorTaskController {

    public static final String PATH = "/manage/connector-tasks";

    private final ConnectorTaskManageService connectorTaskManageService;

    @Operation(summary = "List background tasks with optional connector and kind filters")
    @GetMapping("/")
    public SuccessResponse<Page<ConnectorTaskResponse>> getTasks(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(required = false) ConnectorTaskKind kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(connectorTaskManageService.getTasks(userId, connectorCode, kind, page, size));
    }

    @Operation(summary = "Pause a task: scheduler stops picking it up until resumed")
    @PostMapping("/{id}/pause")
    public SuccessResponse<Void> pause(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        connectorTaskManageService.pause(id, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Resume a paused task; next run is recomputed from now")
    @PostMapping("/{id}/resume")
    public SuccessResponse<Void> resume(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        connectorTaskManageService.resume(id, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Delete a task (USER/AGENT only; SYSTEM tasks are managed by the connector)")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        connectorTaskManageService.delete(id, userId);
        return SuccessResponse.empty();
    }
}
