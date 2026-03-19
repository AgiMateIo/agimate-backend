package ru.agimate.deviceapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.agimate.deviceapi.database.entities.Skill;
import ru.agimate.deviceapi.security.AgentPrincipal;
import ru.agimate.deviceapi.service.SkillFileService;
import ru.agimate.deviceapi.service.SkillService;

import java.util.UUID;

@RestController
@RequestMapping(AgentSkillController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Skills", description = "Agent access to skills via API Key")
public class AgentSkillController {

    public static final String PATH = "/agent/skills";

    private final SkillService skillService;
    private final SkillFileService skillFileService;

    @Operation(summary = "Download all skill files as a ZIP archive")
    @GetMapping("/{skillPubId}.zip")
    public ResponseEntity<StreamingResponseBody> downloadSkillZip(
            @AuthenticationPrincipal AgentPrincipal principal,
            @PathVariable UUID skillPubId
    ) {
        Skill skill = skillService.findAccessibleSkill(skillPubId, principal.userPubId());

        StreamingResponseBody body = out ->
                skillFileService.writeZip(skill.getName(), skill.getUserPubId(), out);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + skill.getName() + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(body);
    }
}
