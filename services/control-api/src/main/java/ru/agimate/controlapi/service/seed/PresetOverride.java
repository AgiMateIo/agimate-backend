package ru.agimate.controlapi.service.seed;

import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.model.PresetFieldOverride;
import ru.agimate.controlapi.database.model.PresetHeaderField;

import java.util.List;
import java.util.Map;

/**
 * Exceptions of {@code overrides.yaml} for one preset. The file is the vendor's data; an override is
 * a human correction that cannot live there yet.
 */
public record PresetOverride(
        List<ConnectorPresetAuth> authMethods,
        Map<String, PresetFieldOverride> fields,
        List<PresetHeaderField> headerFields
) {
}
