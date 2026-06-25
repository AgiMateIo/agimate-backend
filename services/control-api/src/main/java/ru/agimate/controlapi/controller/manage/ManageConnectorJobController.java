package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.ConnectorJobResponse;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.service.ConnectorJobManageService;

import java.util.UUID;

@RestController
@RequestMapping(ManageConnectorJobController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connector Jobs", description = "Manage background connector jobs")
public class ManageConnectorJobController {

    public static final String PATH = "/manage/connector-jobs";

    private final ConnectorJobManageService connectorJobManageService;

    @Operation(summary = "List background jobs with optional connector and kind filters")
    @GetMapping("/")
    public SuccessResponse<Page<ConnectorJobResponse>> getJobs(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(required = false) ConnectorJobKind kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(connectorJobManageService.getJobs(userId, connectorCode, kind, page, size));
    }

    @Operation(summary = "Pause a job: scheduler stops picking it up until resumed")
    @PostMapping("/{id}/pause")
    public SuccessResponse<Void> pause(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        connectorJobManageService.pause(id, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Resume a paused job; next run is recomputed from now")
    @PostMapping("/{id}/resume")
    public SuccessResponse<Void> resume(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        connectorJobManageService.resume(id, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Run a pending job now: scheduler picks it up within ~1s")
    @PostMapping("/{id}/run-now")
    public SuccessResponse<Void> runNow(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        connectorJobManageService.runNow(id, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Delete a job (USER/AGENT only; SYSTEM jobs are managed by the connector)")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        connectorJobManageService.delete(id, userId);
        return SuccessResponse.empty();
    }
}
