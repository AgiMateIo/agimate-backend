package ru.agimate.mobileapi.controller.dto.request;

public record AddConnectionRequest(
        String name,
        String connectionKey
) {
}
