package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentPresetResponse;
import ru.agimate.controlapi.service.AgentPresetService;

import java.util.List;

@RestController
@RequestMapping(ManageAgentPresetController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent presets", description = "Role presets for the agent creation wizard")
public class ManageAgentPresetController {

    public static final String PATH = "/manage/agent-presets";

    private final AgentPresetService agentPresetService;

    @Operation(summary = "List enabled agent role presets")
    @GetMapping("/")
    public SuccessResponse<List<AgentPresetResponse>> getPresets() {
        return SuccessResponse.ok(agentPresetService.list());
    }
}
