package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.CreateSkillRequest;
import ru.agimate.deviceapi.controller.manage.dto.SkillDetailResponse;
import ru.agimate.deviceapi.controller.manage.dto.SkillResponse;
import ru.agimate.deviceapi.controller.manage.dto.UpdateSkillRequest;
import ru.agimate.deviceapi.service.SkillService;

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

    @Operation(summary = "List own skills with optional search and connector filter")
    @GetMapping("/")
    public SuccessResponse<Page<SkillResponse>> getMySkills(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(skillService.getMySkills(userPubId, search, connectorCode, page, size));
    }

    @Operation(summary = "List public skills (non-featured) with optional search and connector filter")
    @GetMapping("/public/")
    public SuccessResponse<Page<SkillResponse>> getPublicSkills(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return SuccessResponse.ok(skillService.getPublicSkills(search, connectorCode, page, size));
    }

    @Operation(summary = "List featured skills with optional search and connector filter")
    @GetMapping("/featured/")
    public SuccessResponse<Page<SkillResponse>> getFeaturedSkills(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return SuccessResponse.ok(skillService.getFeaturedSkills(search, connectorCode, page, size));
    }

    @Operation(summary = "Get skill details with SKILL.md content")
    @GetMapping("/{pubId}")
    public SuccessResponse<SkillDetailResponse> getSkillDetail(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID pubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(skillService.getSkillDetail(pubId, userPubId));
    }

    @Operation(summary = "Create skill from JSON with SKILL.md content")
    @PostMapping(value = "/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SuccessResponse<SkillResponse> createSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateSkillRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(skillService.create(userPubId, request));
    }

    @Operation(summary = "Create skill by uploading SKILL.md file")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SuccessResponse<SkillResponse> createSkillFromFile(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean isPublic
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            return SuccessResponse.ok(skillService.create(userPubId, new CreateSkillRequest(content, isPublic)));
        } catch (IOException e) {
            throw new BadRequestStatusException("Failed to read uploaded file");
        }
    }

    @Operation(summary = "Update skill")
    @PutMapping("/{pubId}")
    public SuccessResponse<SkillResponse> updateSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID pubId,
            @Valid @RequestBody UpdateSkillRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(skillService.update(pubId, userPubId, request));
    }

    @Operation(summary = "Delete skill (soft delete)")
    @DeleteMapping("/{pubId}")
    public SuccessResponse<Void> deleteSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID pubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        skillService.delete(pubId, userPubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Clone a public skill to own collection")
    @PostMapping("/{pubId}/clone")
    public SuccessResponse<SkillResponse> cloneSkill(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID pubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(skillService.clone(pubId, userPubId));
    }
}
