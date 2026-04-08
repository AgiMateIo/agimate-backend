package ru.agimate.deviceapi.controller.agent;

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
import ru.agimate.deviceapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.deviceapi.database.entities.Skill;
import ru.agimate.deviceapi.security.AgentPrincipal;
import ru.agimate.deviceapi.service.AgentSkillService;
import ru.agimate.deviceapi.service.SkillFileService;
import ru.agimate.deviceapi.service.SkillService;

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
        return SuccessResponse.ok(agentSkillService.getAgentSkillsWithConnectors(principal.agentPubId(), principal.userPubId(), page, size));
    }

    @Operation(summary = "Download all skill files as a ZIP archive")
    @GetMapping("/{skillPubId}.zip")
    public ResponseEntity<InputStreamResource> downloadSkillZip(
            @AuthenticationPrincipal AgentPrincipal principal,
            @PathVariable UUID skillPubId
    ) {
        Skill skill = agentSkillService.findAssignedSkill(principal.agentPubId(), skillPubId, principal.userPubId());
        UUID fileOwnerPubId = skillService.resolveFileOwnerPubId(skill);

        InputStream zipStream = skillFileService.getOrCreateZip(fileOwnerPubId, skill.getUpdatedAt());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + skill.getName() + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(new InputStreamResource(zipStream));
    }
}
