package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.agimate.common.rest.PageResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.AgentSummaryResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateSkillRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillDetailResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillListScope;
import ru.agimate.controlapi.controller.manage.dto.SkillResponse;
import ru.agimate.controlapi.controller.manage.dto.UpdateSkillConnectorsRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateSkillRequest;
import ru.agimate.controlapi.service.SkillService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping(ManageSkillController.PATH)
@RequiredArgsConstructor
@Tag(name = "Skills", description = "Manage skills")
public class ManageSkillController {

    public static final String PATH = "/manage/skills";

    private final SkillService skillService;

    @Operation(summary = "List skills with optional search and connector filter. "
            + "scope=MINE (default) — own skills of any visibility; scope=PUBLIC — all public skills")
    @GetMapping("/")
    public SuccessResponse<PageResponse<SkillResponse>> getSkills(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(defaultValue = "MINE") SkillListScope scope,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(PageResponse.from(
                skillService.getSkills(userId, scope, search, connectorCode, page, size)));
    }

    @Operation(summary = "Get skill details with SKILL.md body")
    @GetMapping("/{id}")
    public SuccessResponse<SkillDetailResponse> getSkillDetail(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(skillService.getSkillDetail(id, userId));
    }

    @Operation(summary = "List user's agents that use this skill, with optional name/prompt search")
    @GetMapping("/{id}/agents/")
    public SuccessResponse<PageResponse<AgentSummaryResponse>> getSkillAgents(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(PageResponse.from(skillService.getSkillAgents(id, userId, search, page, size)));
    }

    @Operation(summary = "Create skill from JSON with SKILL.md content")
    @PostMapping(value = "/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuccessResponse<SkillResponse> createSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateSkillRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(skillService.create(userId, request));
    }

    @Operation(summary = "Create a system (platform) skill from SKILL.md. ADMIN only; "
            + "owner is the platform, always public")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping(value = "/system", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuccessResponse<SkillResponse> createSystemSkill(
            @Valid @RequestBody CreateSkillRequest request
    ) {
        return SuccessResponse.ok(skillService.createSystem(request));
    }

    @Operation(summary = "Create skill by uploading SKILL.md file")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SuccessResponse<SkillResponse> createSkillFromFile(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean isPublic
    ) {
        UUID userId = UUID.fromString(principal.id());
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            return SuccessResponse.ok(skillService.create(userId, new CreateSkillRequest(content, isPublic)));
        } catch (IOException e) {
            throw new BadRequestStatusException("Failed to read uploaded file");
        }
    }

    @Operation(summary = "Update skill")
    @PutMapping("/{id}")
    public SuccessResponse<SkillResponse> updateSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSkillRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(skillService.update(id, userId, principal.isAdmin(), request));
    }

    @Operation(summary = "Replace the skill's required connector codes (does not touch the body; "
            + "bound agents are not auto-resynced)")
    @PutMapping("/{id}/connectors")
    public SuccessResponse<SkillResponse> updateSkillConnectors(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSkillConnectorsRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(skillService.updateConnectors(id, userId, principal.isAdmin(), request));
    }

    @Operation(summary = "Delete skill (soft delete)")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> deleteSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        skillService.delete(id, userId, principal.isAdmin());
        return SuccessResponse.empty();
    }
}
