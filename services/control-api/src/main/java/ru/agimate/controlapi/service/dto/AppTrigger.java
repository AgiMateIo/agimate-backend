package ru.agimate.controlapi.service.dto;

import java.util.List;

public record AppTrigger(
        String name,
        String description,
        List<String> params
) {
}
