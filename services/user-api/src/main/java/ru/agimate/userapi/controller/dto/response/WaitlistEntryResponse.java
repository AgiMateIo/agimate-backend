package ru.agimate.userapi.controller.dto.response;

import ru.agimate.userapi.database.entities.WaitlistEntry;

public record WaitlistEntryResponse(String registrationCode) {

    public static WaitlistEntryResponse from(WaitlistEntry entry) {
        return new WaitlistEntryResponse(entry.getPubId().toString());
    }
}
