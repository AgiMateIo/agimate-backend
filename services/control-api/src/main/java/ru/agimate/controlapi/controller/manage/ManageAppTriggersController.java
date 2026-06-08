package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.dto.AppTrigger;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAppTriggersController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device Triggers", description = "Manage device triggers")
public class ManageAppTriggersController {

    public static final String PATH = "/manage/app-triggers";

    private final AppService appService;

    @Operation(
            summary = "Get triggers by app",
            description = "Returns available triggers for a specific app"
    )
    @GetMapping("/{appId}")
    public SuccessResponse<List<AppTrigger>> getTriggersByApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId
    ) {
        UUID userId = UUID.fromString(principal.id());
        var triggers = appService.getTriggersByAppIdAndUser(appId, userId);
        return SuccessResponse.ok(triggers);
    }
}
