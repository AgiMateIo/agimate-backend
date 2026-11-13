package ru.agimate.controlapi.service.seed;

import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.model.PresetFieldOverride;
import ru.agimate.controlapi.database.model.PresetHeaderField;
import ru.agimate.controlapi.database.model.PresetHeaderGroup;

import java.util.List;
import java.util.Map;

/**
 * One parsed seed file, already reduced to what the database stores. {@code nameRu}/{@code descriptionRu}
 * are nullable: a missing translation is not an empty string.
 */
public record ConnectorPresetSeed(
        String code,
        String connectorCode,
        String name,
        String description,
        String nameRu,
        String descriptionRu,
        String iconUrl,
        ContentCategory category,
        String jurisdiction,
        String docsUrl,
        List<ConnectorPresetAuth> authMethods,
        ConnectorPresetAvailability availability,
        Map<String, String> params,
        String identity,
        Map<String, PresetFieldOverride> fields,
        List<PresetHeaderField> headerFields,
        List<PresetHeaderGroup> headerGroups
) {
}
