package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetForm;
import ru.agimate.controlapi.database.entities.ConnectorPreset;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.database.model.PresetFieldOverride;
import ru.agimate.controlapi.database.model.PresetHeaderField;
import ru.agimate.controlapi.database.model.PresetHeaderGroup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PresetFormAssembler — форма из декларации коннектора и оверрайдов пресета")
class PresetFormAssemblerTest {

    @Mock
    private ConnectorRegistry connectorRegistry;
    @Mock
    private IntegrationConnectorHandler handler;
    @InjectMocks
    private PresetFormAssembler assembler;

    @Test
    @DisplayName("url скрыт, auth_token и headers остаются, подпись берётся из оверрайда")
    void hidesUrlAndAppliesOverride() {
        when(connectorRegistry.findIntegrationHandler("mcp")).thenReturn(Optional.of(handler));
        when(handler.getCredentialFields()).thenReturn(credentialFields());

        ConnectorPreset preset = basePreset()
                .authMethods(List.of(ConnectorPresetAuth.TOKEN))
                .fields(Map.of("auth_token", new PresetFieldOverride("API token", "API-токен", true)))
                .build();

        ConnectorPresetForm form = assembler.assemble(preset, ContentLanguage.RU);

        assertFalse(form.credentialFields().containsKey("url"));
        assertTrue(form.credentialFields().containsKey("headers"));
        assertEquals("API-токен", form.credentialFields().get("auth_token").label());
        assertTrue(form.credentialFields().get("auth_token").required());
        assertEquals(Map.of("url", "https://example.com/mcp"), form.params());
    }

    @Test
    @DisplayName("при headerFields поле headers и auth_token скрываются")
    void hidesHeadersAndAuthTokenWhenHeaderFieldsExist() {
        when(connectorRegistry.findIntegrationHandler("mcp")).thenReturn(Optional.of(handler));
        when(handler.getCredentialFields()).thenReturn(credentialFields());

        ConnectorPreset preset = basePreset()
                .authMethods(List.of(ConnectorPresetAuth.TOKEN))
                .headerFields(List.of(new PresetHeaderField("X-API-Token", "API token", "API-токен",
                        true, true, "", null)))
                .build();

        ConnectorPresetForm form = assembler.assemble(preset, ContentLanguage.EN);

        assertTrue(form.credentialFields().isEmpty());
        assertEquals(1, form.headerFields().size());
        assertEquals("X-API-Token", form.headerFields().get(0).name());
        assertTrue(form.headerFields().get(0).secret());
    }

    @Test
    @DisplayName("OAuth-only пресет не показывает auth_token")
    void hidesAuthTokenForOauthOnly() {
        when(connectorRegistry.findIntegrationHandler("mcp")).thenReturn(Optional.of(handler));
        when(handler.getCredentialFields()).thenReturn(credentialFields());

        ConnectorPreset preset = basePreset()
                .authMethods(List.of(ConnectorPresetAuth.OAUTH))
                .build();

        ConnectorPresetForm form = assembler.assemble(preset, ContentLanguage.EN);

        assertFalse(form.credentialFields().containsKey("auth_token"));
        assertTrue(form.credentialFields().containsKey("headers"));
    }

    @Test
    @DisplayName("headerGroups и authMethods переносятся как есть")
    void carriesGroupsAndAuthMethods() {
        when(connectorRegistry.findIntegrationHandler("mcp")).thenReturn(Optional.of(handler));
        when(handler.getCredentialFields()).thenReturn(credentialFields());

        ConnectorPreset preset = basePreset()
                .authMethods(List.of(ConnectorPresetAuth.TOKEN))
                .headerFields(List.of(
                        new PresetHeaderField("x-org-id", "Organization ID", "Организация", false, false, "", null),
                        new PresetHeaderField("x-cloud-org-id", "Cloud organization ID", "Облачная организация",
                                false, false, "", null)))
                .headerGroups(List.of(new PresetHeaderGroup("org",
                        List.of("x-org-id", "x-cloud-org-id"), true)))
                .build();

        ConnectorPresetForm form = assembler.assemble(preset, ContentLanguage.EN);

        assertEquals(List.of(ConnectorPresetAuth.TOKEN), form.authMethods());
        assertEquals(1, form.headerGroups().size());
        assertEquals(List.of("x-org-id", "x-cloud-org-id"), form.headerGroups().get(0).oneOf());
        assertTrue(form.headerGroups().get(0).required());
    }

    @Test
    @DisplayName("неизвестный коннектор не роняет форму: поля пустые, params остаются")
    void missingHandlerYieldsParamsOnly() {
        when(connectorRegistry.findIntegrationHandler("mcp")).thenReturn(Optional.empty());

        ConnectorPreset preset = basePreset()
                .authMethods(List.of(ConnectorPresetAuth.TOKEN))
                .build();

        ConnectorPresetForm form = assembler.assemble(preset, ContentLanguage.EN);

        assertNotNull(form);
        assertTrue(form.credentialFields().isEmpty());
        assertEquals(Map.of("url", "https://example.com/mcp"), form.params());
    }

    private static Map<String, CredentialField> credentialFields() {
        Map<String, CredentialField> fields = new LinkedHashMap<>();
        fields.put("url", CredentialField.required("Server URL", CredentialField.Type.URL));
        fields.put("auth_token", CredentialField.optional("Bearer token", CredentialField.Type.SECRET));
        fields.put("headers", CredentialField.optional("Extra headers as JSON", CredentialField.Type.SECRET));
        return fields;
    }

    private static ConnectorPreset.ConnectorPresetBuilder basePreset() {
        return ConnectorPreset.builder()
                .code("example")
                .connectorCode("mcp")
                .name("Example")
                .category(ru.agimate.controlapi.database.enums.ContentCategory.DEVELOPMENT)
                .availability(ConnectorPresetAvailability.AVAILABLE)
                .params(Map.of("url", "https://example.com/mcp"))
                .identity("https://example.com/mcp");
    }
}
