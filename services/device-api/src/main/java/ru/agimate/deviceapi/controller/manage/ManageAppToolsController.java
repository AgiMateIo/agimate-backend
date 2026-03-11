package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.service.AppApiService;

import ru.agimate.deviceapi.service.dto.AppTool;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAppToolsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device Tools", description = "Manage device tools")
public class ManageAppToolsController {

    public static final String PATH = "/manage/app-tools";

    private final AppApiService appApiService;

    @Operation(
            summary = "Get tools by app",
            description = "Returns available tools for a specific app"
    )
    @GetMapping("/{appPubId}")
    public SuccessResponse<List<AppTool>> getToolsByApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var tools = appApiService.getToolsByAppPubIdAndUser(appPubId, userPubId);
        return SuccessResponse.ok(tools);
    }
}
