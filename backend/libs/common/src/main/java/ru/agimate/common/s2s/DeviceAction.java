package ru.agimate.common.s2s;

import java.util.Map;

public record DeviceAction(
        String name,
        String description,
        Map<String, String> params
) {
}
