package ru.agimate.controlapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.service.AgentSkillService;
import ru.agimate.controlapi.service.SkillFileService;
import ru.agimate.controlapi.service.SkillService;

import java.io.InputStream;
import java.util.UUID;

@RestController
@RequestMapping(AgentSkillController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Skills", description = "Agent access to skills via API Key")
public class AgentSkillController {

    public static final String PATH = "/agent/skills";

    private final SkillService skillService;
    private final SkillFileService skillFileService;
    private final AgentSkillService agentSkillService;

    @Operation(summary = "List skills assigned to this agent")
    @GetMapping("/")
    public SuccessResponse<Page<AgentSkillWithConnectorsResponse>> getSkills(
            @AuthenticationPrincipal AgentPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return SuccessResponse.ok(agentSkillService.getAgentSkillsWithConnectors(principal.agentId(), principal.userId(), page, size));
    }

    @Operation(summary = "Download all skill files as a ZIP archive")
    @GetMapping("/{skillId}.zip")
    public ResponseEntity<InputStreamResource> downloadSkillZip(
            @AuthenticationPrincipal AgentPrincipal principal,
            @PathVariable UUID skillId
    ) {
        Skill skill = agentSkillService.findAssignedSkill(principal.agentId(), skillId, principal.userId());
        UUID fileOwnerId = skillService.resolveFileOwnerId(skill);

        InputStream zipStream = skillFileService.getOrCreateZip(fileOwnerId, skill.getUpdatedAt());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + skill.getName() + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(new InputStreamResource(zipStream));
    }
}
