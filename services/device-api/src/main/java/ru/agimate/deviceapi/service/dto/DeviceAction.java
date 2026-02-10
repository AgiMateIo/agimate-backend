package ru.agimate.deviceapi.service.dto;

import java.util.List;

public record DeviceAction(
        String name,
        List<String> params
) {
}
