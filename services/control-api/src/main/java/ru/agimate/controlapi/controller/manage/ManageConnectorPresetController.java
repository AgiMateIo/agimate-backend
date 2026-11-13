package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetResponse;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.service.ConnectorPresetQueryService;

import java.util.List;

@RestController
@RequestMapping(ManageConnectorPresetController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connector presets", description = "Connector catalogue with the connection-form prefill")
public class ManageConnectorPresetController {

    public static final String PATH = "/manage/connector-presets";

    private final ConnectorPresetQueryService connectorPresetQueryService;

    @Operation(summary = "Connector presets: card, form prefill and connection identity")
    @GetMapping("/")
    public SuccessResponse<List<ConnectorPresetResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(required = false) ContentCategory category,
            @RequestParam(required = false) String jurisdiction) {
        return SuccessResponse.ok(connectorPresetQueryService.list(search, connectorCode, category, jurisdiction));
    }

    @Operation(summary = "Connector preset by code")
    @GetMapping("/{code}")
    public SuccessResponse<ConnectorPresetResponse> get(@PathVariable String code) {
        return SuccessResponse.ok(connectorPresetQueryService.get(code));
    }
}
