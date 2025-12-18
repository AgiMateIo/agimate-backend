package ru.agimate.mobileapi.controller.dto.request;

public record TriggerRequest(
        String type,
        String name,
        String params
) {
}
