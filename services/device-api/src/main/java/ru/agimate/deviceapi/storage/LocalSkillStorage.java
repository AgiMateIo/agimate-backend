package ru.agimate.deviceapi.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.InternalServerErrorStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Component
public class LocalSkillStorage implements SkillStorage {

    private final Path rootDir;

    public LocalSkillStorage(@Value("${app.skill.dir}") String skillDir) {
        this.rootDir = Paths.get(skillDir).toAbsolutePath().normalize();
    }

    @Override
    public void saveFile(String basePath, String relativePath, InputStream content, long size) {
        Path filePath = resolve(basePath, relativePath);
        try {
            Files.createDirectories(filePath.getParent());
            Files.copy(content, filePath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Saved file: {}", filePath);
        } catch (IOException e) {
            log.error("Failed to save file: {}", filePath, e);
            throw new InternalServerErrorStatusException("Failed to save file", e);
        }
    }

    @Override
    public InputStream readFile(String basePath, String relativePath) {
        Path filePath = resolve(basePath, relativePath);
        try {
            return Files.newInputStream(filePath);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            log.error("Failed to read file: {}", filePath, e);
            throw new InternalServerErrorStatusException("Failed to read file", e);
        }
    }

    @Override
    public void deleteFile(String basePath, String relativePath) {
        Path filePath = resolve(basePath, relativePath);
        try {
            Files.deleteIfExists(filePath);
            log.debug("Deleted file: {}", filePath);
        } catch (IOException e) {
            log.error("Failed to delete file: {}", filePath, e);
            throw new InternalServerErrorStatusException("Failed to delete file", e);
        }
    }

    @Override
    public void deleteAll(String basePath) {
        Path dirPath = rootDir.resolve(basePath).normalize();
        if (!dirPath.startsWith(rootDir) || !Files.exists(dirPath)) {
            return;
        }
        try {
            Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.debug("Deleted all files in: {}", dirPath);
        } catch (IOException e) {
            log.error("Failed to delete directory: {}", dirPath, e);
            throw new InternalServerErrorStatusException("Failed to delete skill files", e);
        }
    }

    @Override
    public List<FileEntry> listFiles(String basePath) {
        Path dirPath = rootDir.resolve(basePath).normalize();
        if (!dirPath.startsWith(rootDir) || !Files.exists(dirPath)) {
            return List.of();
        }

        List<FileEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dirPath)) {
            stream.filter(p -> !p.equals(dirPath))
                    .forEach(p -> {
                        String relative = dirPath.relativize(p).toString();
                        boolean isDir = Files.isDirectory(p);
                        long size = 0;
                        if (!isDir) {
                            try {
                                size = Files.size(p);
                            } catch (IOException ignored) {
                            }
                        }
                        entries.add(new FileEntry(relative, p.getFileName().toString(), size, isDir));
                    });
        } catch (IOException e) {
            log.error("Failed to list files in: {}", dirPath, e);
            throw new InternalServerErrorStatusException("Failed to list skill files", e);
        }
        return entries;
    }

    @Override
    public boolean exists(String basePath, String relativePath) {
        Path filePath = resolve(basePath, relativePath);
        return Files.exists(filePath);
    }

    @Override
    public int countFiles(String basePath) {
        Path dirPath = rootDir.resolve(basePath).normalize();
        if (!dirPath.startsWith(rootDir) || !Files.exists(dirPath)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dirPath)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            log.error("Failed to count files in: {}", dirPath, e);
            throw new InternalServerErrorStatusException("Failed to count skill files", e);
        }
    }

    @Override
    public void copyAll(String sourceBasePath, String targetBasePath) {
        Path sourceDir = rootDir.resolve(sourceBasePath).normalize();
        Path targetDir = rootDir.resolve(targetBasePath).normalize();

        if (!sourceDir.startsWith(rootDir) || !targetDir.startsWith(rootDir)) {
            throw new IllegalArgumentException("Invalid storage path");
        }

        if (!Files.exists(sourceDir)) {
            return;
        }

        try {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path target = targetDir.resolve(sourceDir.relativize(dir));
                    Files.createDirectories(target);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path target = targetDir.resolve(sourceDir.relativize(file));
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.debug("Copied all files from {} to {}", sourceDir, targetDir);
        } catch (IOException e) {
            log.error("Failed to copy files from {} to {}", sourceDir, targetDir, e);
            throw new InternalServerErrorStatusException("Failed to clone skill files", e);
        }
    }

    private Path resolve(String basePath, String relativePath) {
        Path resolved = rootDir.resolve(basePath).resolve(relativePath).normalize();
        if (!resolved.startsWith(rootDir)) {
            throw new IllegalArgumentException("Invalid file path");
        }
        return resolved;
    }
}
