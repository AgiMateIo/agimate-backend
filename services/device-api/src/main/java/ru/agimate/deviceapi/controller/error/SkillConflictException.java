package ru.agimate.deviceapi.controller.error;

import lombok.Getter;
import ru.agimate.common.rest.error.ConflictStatusException;

import java.util.UUID;

@Getter
public class SkillConflictException extends ConflictStatusException {

    private final UUID existingSkillId;

    public SkillConflictException(String message, UUID existingSkillId) {
        super(message);
        this.existingSkillId = existingSkillId;
    }
}
