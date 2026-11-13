package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetForm;
import ru.agimate.controlapi.controller.manage.dto.CredentialFieldResponse;
import ru.agimate.controlapi.controller.manage.dto.HeaderFieldResponse;
import ru.agimate.controlapi.controller.manage.dto.HeaderGroupResponse;
import ru.agimate.controlapi.database.entities.ConnectorPreset;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.database.model.PresetFieldOverride;
import ru.agimate.controlapi.database.model.PresetHeaderField;
import ru.agimate.controlapi.database.model.PresetHeaderGroup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the form prefill: the connector's declaration is the source of the fields, the preset only
 * hides what the form carries itself ({@code url}), replaces it with dedicated header inputs
 * ({@code headers}) or says that the token is not a bearer token ({@code auth_token}).
 *
 * <p>Assembled on read rather than stored: a change to the connector's field declaration must reach
 * every preset without a re-seed, and the preset stores only its overrides. The hidden/replaced field
 * codes ({@code url}, {@code headers}, {@code auth_token}) are MCP's; another connector type would
 * need its own rule.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresetFormAssembler {

    private final ConnectorRegistry connectorRegistry;

    public ConnectorPresetForm assemble(ConnectorPreset preset, ContentLanguage language) {
        Map<String, CredentialField> declared = connectorRegistry
                .findIntegrationHandler(preset.getConnectorCode())
                .map(handler -> handler.getCredentialFields())
                .orElse(null);
        if (declared == null) {
            log.warn("No integration handler for connector '{}'; preset '{}' has no credential fields",
                    preset.getConnectorCode(), preset.getCode());
        }

        Map<String, CredentialFieldResponse> credentialFields = new LinkedHashMap<>();
        if (declared != null) {
            declared.forEach((code, field) -> {
                if ("url".equals(code)) {
                    return;
                }
                if ("headers".equals(code) && hasHeaderFields(preset)) {
                    return;
                }
                if ("auth_token".equals(code) && !hasAuthTokenField(preset)) {
                    return;
                }
                PresetFieldOverride override = preset.getFields() == null ? null : preset.getFields().get(code);
                credentialFields.put(code, new CredentialFieldResponse(
                        label(override, field, language),
                        field.type(),
                        override != null && override.required() != null ? override.required() : field.required()));
            });
        }

        return new ConnectorPresetForm(
                preset.getParams(),
                credentialFields,
                headerFields(preset.getHeaderFields(), language),
                headerGroups(preset.getHeaderGroups()),
                preset.getAuthMethods() != null ? List.copyOf(preset.getAuthMethods())
                        : List.<ConnectorPresetAuth>of());
    }

    private static boolean hasHeaderFields(ConnectorPreset preset) {
        return preset.getHeaderFields() != null && !preset.getHeaderFields().isEmpty();
    }

    /** The seed decides whether the token is a bearer token; a custom secret header means it is not. */
    private static boolean hasAuthTokenField(ConnectorPreset preset) {
        return preset.getFields() != null && preset.getFields().containsKey("auth_token");
    }

    private static String label(PresetFieldOverride override, CredentialField field, ContentLanguage language) {
        if (override == null) {
            return field.label();
        }
        if (language == ContentLanguage.RU && override.labelRu() != null) {
            return override.labelRu();
        }
        return override.label() != null ? override.label() : field.label();
    }

    private static List<HeaderFieldResponse> headerFields(List<PresetHeaderField> fields,
                                                          ContentLanguage language) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        return fields.stream()
                .map(field -> new HeaderFieldResponse(field.name(), headerLabel(field, language),
                        field.secret(), field.required(), field.valuePrefix(), field.keyPrefix()))
                .toList();
    }

    private static String headerLabel(PresetHeaderField field, ContentLanguage language) {
        if (language == ContentLanguage.RU && field.labelRu() != null) {
            return field.labelRu();
        }
        return field.label() != null ? field.label() : field.name();
    }

    private static List<HeaderGroupResponse> headerGroups(List<PresetHeaderGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        return groups.stream()
                .map(group -> new HeaderGroupResponse(group.id(), group.oneOf(), group.required()))
                .toList();
    }
}
