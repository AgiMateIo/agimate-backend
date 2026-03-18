package ru.agimate.deviceapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.storage.SkillStorage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class SkillFileService {

    private static final String SKILL_MD = "SKILL.md";

    private final SkillStorage skillStorage;
    private final long maxFileSize;
    private final int maxFilesPerSkill;

    public SkillFileService(
            SkillStorage skillStorage,
            @Value("${app.skill.max-file-size:10485760}") long maxFileSize,
            @Value("${app.skill.max-files-per-skill:10}") int maxFilesPerSkill
    ) {
        this.skillStorage = skillStorage;
        this.maxFileSize = maxFileSize;
        this.maxFilesPerSkill = maxFilesPerSkill;
    }

    public void saveSkillMd(String skillName, UUID userPubId, String content) {
        String basePath = resolveBasePath(skillName, userPubId);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        skillStorage.saveFile(basePath, SKILL_MD, new ByteArrayInputStream(bytes), bytes.length);
    }

    public String readSkillMd(String skillName, UUID userPubId) {
        String basePath = resolveBasePath(skillName, userPubId);
        try (InputStream is = skillStorage.readFile(basePath, SKILL_MD)) {
            if (is == null) {
                throw new NotFoundStatusException("SKILL.md not found");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new NotFoundStatusException("Failed to read SKILL.md");
        }
    }

    public void uploadFile(String skillName, UUID userPubId, String relativePath, MultipartFile file) {
        validateRelativePath(relativePath);

        if (file.getSize() > maxFileSize) {
            throw new BadRequestStatusException("File size exceeds maximum of " + (maxFileSize / 1024 / 1024) + "MB");
        }

        String basePath = resolveBasePath(skillName, userPubId);

        if (!SKILL_MD.equals(relativePath) && !skillStorage.exists(basePath, relativePath)) {
            int currentCount = skillStorage.countFiles(basePath);
            if (currentCount >= maxFilesPerSkill) {
                throw new BadRequestStatusException("Maximum number of files per skill reached (" + maxFilesPerSkill + ")");
            }
        }

        try {
            skillStorage.saveFile(basePath, relativePath, file.getInputStream(), file.getSize());
            log.info("Uploaded file '{}' for skill '{}' user={}", relativePath, skillName, userPubId);
        } catch (IOException e) {
            throw new BadRequestStatusException("Failed to read uploaded file");
        }
    }

    public InputStream readFile(String skillName, UUID userPubId, String relativePath) {
        validateRelativePath(relativePath);
        String basePath = resolveBasePath(skillName, userPubId);
        InputStream is = skillStorage.readFile(basePath, relativePath);
        if (is == null) {
            throw new NotFoundStatusException("File not found: " + relativePath);
        }
        return is;
    }

    public void deleteFile(String skillName, UUID userPubId, String relativePath) {
        validateRelativePath(relativePath);

        if (SKILL_MD.equals(relativePath)) {
            throw new BadRequestStatusException("Cannot delete SKILL.md");
        }

        String basePath = resolveBasePath(skillName, userPubId);
        if (!skillStorage.exists(basePath, relativePath)) {
            throw new NotFoundStatusException("File not found: " + relativePath);
        }

        skillStorage.deleteFile(basePath, relativePath);
        log.info("Deleted file '{}' from skill '{}' user={}", relativePath, skillName, userPubId);
    }

    public List<SkillStorage.FileEntry> listFiles(String skillName, UUID userPubId) {
        String basePath = resolveBasePath(skillName, userPubId);
        return skillStorage.listFiles(basePath);
    }

    public void deleteAll(String skillName, UUID userPubId) {
        String basePath = resolveBasePath(skillName, userPubId);
        skillStorage.deleteAll(basePath);
        log.info("Deleted all files for skill '{}' user={}", skillName, userPubId);
    }

    public void copyAll(String sourceSkillName, UUID sourceUserPubId,
                        String targetSkillName, UUID targetUserPubId) {
        String sourcePath = resolveBasePath(sourceSkillName, sourceUserPubId);
        String targetPath = resolveBasePath(targetSkillName, targetUserPubId);
        skillStorage.copyAll(sourcePath, targetPath);
    }

    String resolveBasePath(String skillName, UUID userPubId) {
        String nn = skillName.substring(0, Math.min(2, skillName.length()));
        String userPubIdStr = userPubId.toString();
        String uu = userPubIdStr.substring(0, 2);
        String uu2 = userPubIdStr.substring(2, 4);
        return nn + "/" + skillName + "/" + uu + "/" + uu2 + "/" + userPubIdStr;
    }

    private void validateRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BadRequestStatusException("File path is required");
        }
        if (relativePath.contains("..") || relativePath.startsWith("/")) {
            throw new BadRequestStatusException("Invalid file path");
        }
    }
}
