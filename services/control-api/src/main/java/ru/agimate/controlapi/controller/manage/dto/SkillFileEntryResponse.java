package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.storage.SkillStorage;

@Schema(description = "File entry in a skill directory")
public record SkillFileEntryResponse(
        @Schema(description = "Relative path within the skill directory")
        String path,

        @Schema(description = "File name")
        String name,

        @Schema(description = "File size in bytes")
        long size,

        @Schema(description = "Whether this is a directory")
        boolean directory
) {
    public static SkillFileEntryResponse from(SkillStorage.FileEntry entry) {
        return new SkillFileEntryResponse(entry.path(), entry.name(), entry.size(), entry.directory());
    }
}
