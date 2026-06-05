package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.SkillFileEntryResponse;
import ru.agimate.deviceapi.database.entities.Skill;
import ru.agimate.deviceapi.service.SkillFileService;
import ru.agimate.deviceapi.service.SkillService;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageSkillFileController.PATH)
@RequiredArgsConstructor
@Tag(name = "Skill Files", description = "Manage skill files")
public class ManageSkillFileController {

    public static final String PATH = "/manage/skill-files";

    private final SkillService skillService;
    private final SkillFileService skillFileService;

    @Operation(summary = "Download all skill files as a ZIP archive")
    @GetMapping("/{id}.zip")
    public ResponseEntity<InputStreamResource> downloadZip(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        Skill skill = skillService.findAccessibleSkill(id, userId);
        UUID fileOwnerId = skillService.resolveFileOwnerId(skill);

        InputStream zipStream = skillFileService.getOrCreateZip(fileOwnerId, skill.getUpdatedAt());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + skill.getName() + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(new InputStreamResource(zipStream));
    }

    @Operation(summary = "List all files in a skill directory")
    @GetMapping("/{id}/")
    public SuccessResponse<List<SkillFileEntryResponse>> listFiles(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        Skill skill = skillService.findAccessibleSkill(id, userId);
        UUID fileOwnerId = skillService.resolveFileOwnerId(skill);
        List<SkillFileEntryResponse> entries = skillFileService
                .listFiles(fileOwnerId)
                .stream()
                .map(SkillFileEntryResponse::from)
                .toList();
        return SuccessResponse.ok(entries);
    }

    @Operation(summary = "Upload a file to a skill directory")
    @PostMapping(value = "/{id}/", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SuccessResponse<Void> uploadFile(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "path", defaultValue = "") String path
    ) {
        UUID userId = UUID.fromString(principal.id());
        Skill skill = skillService.findOwnedSkill(id, userId);
        skillService.requireNotFeaturedClone(skill);

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BadRequestStatusException("File name is required");
        }
        String safeFilename = Paths.get(originalFilename).getFileName().toString();
        String relativePath = path.isEmpty() ? safeFilename : path + "/" + safeFilename;

        skillFileService.uploadFile(skill.getId(), relativePath, file);
        skillService.touchUpdatedAt(id, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Download a file from a skill directory")
    @GetMapping("/{id}/**")
    public ResponseEntity<InputStreamResource> downloadFile(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        Skill skill = skillService.findAccessibleSkill(id, userId);
        UUID fileOwnerId = skillService.resolveFileOwnerId(skill);
        String relativePath = extractRelativePath(request, id);

        InputStream is = skillFileService.readFile(fileOwnerId, relativePath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + getFileName(relativePath) + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(is));
    }

    @Operation(summary = "Delete a file from a skill directory")
    @DeleteMapping("/{id}/**")
    public SuccessResponse<Void> deleteFile(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        Skill skill = skillService.findOwnedSkill(id, userId);
        skillService.requireNotFeaturedClone(skill);
        String relativePath = extractRelativePath(request, id);

        skillFileService.deleteFile(skill.getId(), relativePath);
        skillService.touchUpdatedAt(id, userId);
        return SuccessResponse.empty();
    }

    private String extractRelativePath(HttpServletRequest request, UUID id) {
        String fullPath = request.getRequestURI();
        String prefix = PATH + "/" + id + "/";
        int idx = fullPath.indexOf(prefix);
        if (idx < 0) {
            return "";
        }
        String encoded = fullPath.substring(idx + prefix.length());
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }

    private String getFileName(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash >= 0 ? relativePath.substring(lastSlash + 1) : relativePath;
    }
}
