package ru.agimate.deviceapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.storage.SkillStorage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    public void saveSkillMd(UUID skillPubId, String content) {
        String basePath = resolveBasePath(skillPubId);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        skillStorage.saveFile(basePath, SKILL_MD, new ByteArrayInputStream(bytes), bytes.length);
    }

    public String readSkillMd(UUID skillPubId) {
        String basePath = resolveBasePath(skillPubId);
        try (InputStream is = skillStorage.readFile(basePath, SKILL_MD)) {
            if (is == null) {
                throw new NotFoundStatusException("SKILL.md not found");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new NotFoundStatusException("Failed to read SKILL.md");
        }
    }

    public void uploadFile(UUID skillPubId, String relativePath, MultipartFile file) {
        validateRelativePath(relativePath);

        if (file.getSize() > maxFileSize) {
            throw new BadRequestStatusException("File size exceeds maximum of " + (maxFileSize / 1024 / 1024) + "MB");
        }

        String basePath = resolveBasePath(skillPubId);

        if (!SKILL_MD.equals(relativePath) && !skillStorage.exists(basePath, relativePath)) {
            int currentCount = skillStorage.countFiles(basePath);
            if (currentCount >= maxFilesPerSkill) {
                throw new BadRequestStatusException("Maximum number of files per skill reached (" + maxFilesPerSkill + ")");
            }
        }

        try {
            skillStorage.saveFile(basePath, relativePath, file.getInputStream(), file.getSize());
            log.info("Uploaded file '{}' for skill pubId={}", relativePath, skillPubId);
        } catch (IOException e) {
            throw new BadRequestStatusException("Failed to read uploaded file");
        }
    }

    public InputStream readFile(UUID skillPubId, String relativePath) {
        validateRelativePath(relativePath);
        String basePath = resolveBasePath(skillPubId);
        InputStream is = skillStorage.readFile(basePath, relativePath);
        if (is == null) {
            throw new NotFoundStatusException("File not found: " + relativePath);
        }
        return is;
    }

    public void deleteFile(UUID skillPubId, String relativePath) {
        validateRelativePath(relativePath);

        if (SKILL_MD.equals(relativePath)) {
            throw new BadRequestStatusException("Cannot delete SKILL.md");
        }

        String basePath = resolveBasePath(skillPubId);
        if (!skillStorage.exists(basePath, relativePath)) {
            throw new NotFoundStatusException("File not found: " + relativePath);
        }

        skillStorage.deleteFile(basePath, relativePath);
        log.info("Deleted file '{}' from skill pubId={}", relativePath, skillPubId);
    }

    public List<SkillStorage.FileEntry> listFiles(UUID skillPubId) {
        String basePath = resolveBasePath(skillPubId);
        return skillStorage.listFiles(basePath);
    }

    public InputStream getOrCreateZip(UUID skillPubId, LocalDateTime updatedAt) {
        String zipParent = resolveZipParentPath(skillPubId);
        String zipFileName = skillPubId + ".zip";

        long cacheModified = skillStorage.lastModified(zipParent, zipFileName);
        long entityModified = updatedAt.toInstant(ZoneOffset.UTC).toEpochMilli();

        if (cacheModified >= entityModified) {
            log.debug("Serving cached ZIP for skill pubId={}", skillPubId);
            return skillStorage.readFile(zipParent, zipFileName);
        }

        log.debug("Generating ZIP for skill pubId={}", skillPubId);
        String basePath = resolveBasePath(skillPubId);
        List<SkillStorage.FileEntry> files = skillStorage.listFiles(basePath);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (SkillStorage.FileEntry entry : files) {
                if (entry.directory()) {
                    continue;
                }
                try (InputStream is = skillStorage.readFile(basePath, entry.path())) {
                    if (is == null) {
                        continue;
                    }
                    zos.putNextEntry(new ZipEntry(entry.path()));
                    is.transferTo(zos);
                    zos.closeEntry();
                }
            }
        } catch (IOException e) {
            log.error("Failed to create ZIP for skill pubId={}", skillPubId, e);
            throw new BadRequestStatusException("Failed to create skill archive");
        }

        byte[] zipBytes = baos.toByteArray();
        skillStorage.saveFile(zipParent, zipFileName, new ByteArrayInputStream(zipBytes), zipBytes.length);

        return new ByteArrayInputStream(zipBytes);
    }

    public void deleteAll(UUID skillPubId) {
        String basePath = resolveBasePath(skillPubId);
        skillStorage.deleteAll(basePath);

        String zipParent = resolveZipParentPath(skillPubId);
        String zipFileName = skillPubId + ".zip";
        skillStorage.deleteFile(zipParent, zipFileName);

        log.info("Deleted all files for skill pubId={}", skillPubId);
    }

    public void copyAll(UUID sourcePubId, UUID targetPubId) {
        String sourcePath = resolveBasePath(sourcePubId);
        String targetPath = resolveBasePath(targetPubId);
        skillStorage.copyAll(sourcePath, targetPath);
    }

    String resolveBasePath(UUID skillPubId) {
        String pubIdStr = skillPubId.toString();
        String p1 = pubIdStr.substring(0, 2);
        String p2 = pubIdStr.substring(2, 4);
        return p1 + "/" + p2 + "/" + pubIdStr;
    }

    private String resolveZipParentPath(UUID skillPubId) {
        String pubIdStr = skillPubId.toString();
        String p1 = pubIdStr.substring(0, 2);
        String p2 = pubIdStr.substring(2, 4);
        return p1 + "/" + p2;
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
