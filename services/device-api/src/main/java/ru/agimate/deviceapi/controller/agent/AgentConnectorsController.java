package ru.agimate.deviceapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.controller.manage.dto.DeviceTriggersResponse;
import ru.agimate.deviceapi.service.dto.ConnectedDevice;
import ru.agimate.deviceapi.service.dto.DeviceTool;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.deviceapi.service.ConnectorApiService;

import java.util.List;

@RestController
@RequestMapping(AgentConnectorsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connectors API", description = "Connector operations via API Key")
public class AgentConnectorsController {

    public static final String PATH = AgentController.PATH + "/connectors";

    private final ConnectorApiService connectorApiService;

    @Operation(
            summary = "Get connected connectors",
            description = "Returns all connected connectors for the authenticated user"
    )
    @GetMapping("/")
    public SuccessResponse<List<ConnectedDevice>> getConnectors() {
        var userPubId = SecurityUtils.getApiKeyUserPubId();
        var devices = connectorApiService.getConnectors(userPubId);
        return SuccessResponse.ok(devices);
    }

    @Operation(
            summary = "Get all connector triggers",
            description = "Returns available triggers for all user's connectors"
    )
    @GetMapping("/triggers/")
    public SuccessResponse<List<DeviceTriggersResponse>> getAllTriggers() {
        var userPubId = SecurityUtils.getApiKeyUserPubId();
        var triggers = connectorApiService.getAllConnectorTriggers(userPubId);
        return SuccessResponse.ok(triggers);
    }

    @Operation(
            summary = "Get connector triggers",
            description = "Returns available triggers for a specific connector"
    )
    @GetMapping("/triggers/{connectorId}")
    public SuccessResponse<List<DeviceTrigger>> getTriggers(@PathVariable String connectorId) {
        var triggers = connectorApiService.getTriggers(connectorId);
        return SuccessResponse.ok(triggers);
    }


    @GetMapping("/tools/")
    public SuccessResponse<List<DeviceTriggersResponse>> getAllTools() {
        // TODO: implement as for triggers
        return SuccessResponse.ok(null);
    }

    @Operation(
            summary = "Get connector tools",
            description = "Returns available tools for a specific connector"
    )
    @GetMapping("/tools/{connectorId}")
    public SuccessResponse<List<DeviceTool>> getTools(@PathVariable String connectorId) {
        var tools = connectorApiService.getTools(connectorId);
        return SuccessResponse.ok(tools);
    }
}
