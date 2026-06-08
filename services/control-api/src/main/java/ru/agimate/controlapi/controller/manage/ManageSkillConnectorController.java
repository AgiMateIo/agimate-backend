package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.ReplaceSkillConnectorsRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillConnectorRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillConnectorResponse;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.service.SkillConnectorService;
import ru.agimate.controlapi.service.SkillService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/manage/skills/{id}/connectors")
@RequiredArgsConstructor
@Tag(name = "Skill Connectors", description = "Manage skill-connector bindings")
public class ManageSkillConnectorController {

    private final SkillService skillService;
    private final SkillConnectorService skillConnectorService;

    @Operation(summary = "List all connector bindings for a skill")
    @GetMapping("/")
    public SuccessResponse<List<SkillConnectorResponse>> getAll(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        Skill skill = skillService.findOwnedSkill(id, userId);
        return SuccessResponse.ok(skillConnectorService.getAll(skill));
    }

    @Operation(summary = "Replace all connector bindings for a skill")
    @PutMapping("/")
    public SuccessResponse<List<SkillConnectorResponse>> replaceAll(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody ReplaceSkillConnectorsRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        Skill skill = skillService.findOwnedSkill(id, userId);
        skillService.requireNotFeaturedClone(skill);
        return SuccessResponse.ok(skillConnectorService.replaceAll(skill, request));
    }

    @Operation(summary = "Add a single connector binding to a skill")
    @PostMapping("/")
    public SuccessResponse<SkillConnectorResponse> addOne(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody SkillConnectorRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        Skill skill = skillService.findOwnedSkill(id, userId);
        skillService.requireNotFeaturedClone(skill);
        return SuccessResponse.ok(skillConnectorService.addOne(skill, request));
    }

    @Operation(summary = "Delete a connector binding from a skill")
    @DeleteMapping("/{connectorId}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @PathVariable UUID connectorId
    ) {
        UUID userId = UUID.fromString(principal.id());
        Skill skill = skillService.findOwnedSkill(id, userId);
        skillService.requireNotFeaturedClone(skill);
        skillConnectorService.delete(skill, connectorId);
        return SuccessResponse.empty();
    }
}
