package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.catalog.dto.CatalogConnectorPresetResponse;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetForm;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetResponse;
import ru.agimate.controlapi.database.entities.ConnectorPreset;
import ru.agimate.controlapi.database.entities.ConnectorPresetText;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.database.repositories.ConnectorPresetRepository;
import ru.agimate.controlapi.database.repositories.ConnectorPresetTextRepository;
import ru.agimate.controlapi.service.seed.ContentLanguageResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectorPresetQueryService — карточка, форма и публичный ответ")
class ConnectorPresetQueryServiceTest {

    @Mock
    private ConnectorPresetRepository presetRepository;
    @Mock
    private ConnectorPresetTextRepository textRepository;
    @Mock
    private ContentLanguageResolver languageResolver;
    @Mock
    private PresetFormAssembler formAssembler;
    @InjectMocks
    private ConnectorPresetQueryService service;

    @Test
    @DisplayName("manage-список локализует карточку, собирает форму и отдаёт identity")
    void listLocalizesAndAssemblesForm() {
        UUID id = UUID.randomUUID();
        ConnectorPreset preset = preset(id, List.of(ConnectorPresetAuth.OAUTH, ConnectorPresetAuth.TOKEN));
        ConnectorPresetText text = ConnectorPresetText.builder()
                .presetId(id)
                .language(ContentLanguage.RU)
                .name("Битрикс24")
                .description("CRM и задачи")
                .build();
        ConnectorPresetForm form = new ConnectorPresetForm(Map.of("url", "https://example.com/mcp"),
                Map.of(), List.of(), List.of(), List.of(ConnectorPresetAuth.OAUTH, ConnectorPresetAuth.TOKEN));
        when(languageResolver.requestLanguage()).thenReturn(ContentLanguage.RU);
        when(presetRepository.search("", null, null, null)).thenReturn(List.of(preset));
        when(textRepository.findAllByPresetIdInAndLanguage(List.of(id), ContentLanguage.RU))
                .thenReturn(List.of(text));
        when(formAssembler.assemble(preset, ContentLanguage.RU)).thenReturn(form);

        List<ConnectorPresetResponse> response = service.list(null, null, null, null);

        assertEquals(1, response.size());
        ConnectorPresetResponse item = response.get(0);
        assertEquals("bitrix24", item.code());
        assertEquals("mcp", item.connectorCode());
        assertEquals("Битрикс24", item.card().name());
        assertEquals("CRM и задачи", item.card().description());
        assertEquals(ConnectorPresetAuth.OAUTH, item.card().auth());
        assertEquals("RU", item.card().jurisdiction());
        assertEquals("https://example.com/mcp", item.connection().identity());
        assertEquals(ConnectorPresetAvailability.AVAILABLE, item.connection().availability());
        assertNotNull(item.form());
    }

    @Test
    @DisplayName("неизвестный код — 404")
    void missingCodeIsNotFound() {
        when(languageResolver.requestLanguage()).thenReturn(ContentLanguage.EN);
        when(presetRepository.findByCodeAndSourcePresentTrueAndEnabledTrue("missing"))
                .thenReturn(Optional.empty());

        assertThrows(NotFoundStatusException.class, () -> service.get("missing"));
    }

    @Test
    @DisplayName("публичный список не собирает форму и отдаёт только доступность")
    void publicListSkipsForm() {
        UUID id = UUID.randomUUID();
        ConnectorPreset preset = preset(id, List.of(ConnectorPresetAuth.OAUTH, ConnectorPresetAuth.TOKEN));
        when(languageResolver.requestLanguage()).thenReturn(ContentLanguage.EN);
        when(presetRepository.search("", null, ContentCategory.WORK.name(), "RU"))
                .thenReturn(List.of(preset));
        when(textRepository.findAllByPresetIdInAndLanguage(List.of(id), ContentLanguage.EN))
                .thenReturn(List.of());

        List<CatalogConnectorPresetResponse> response = service.listPublic("", ContentCategory.WORK, "RU");

        assertEquals(1, response.size());
        assertEquals("Bitrix24", response.get(0).card().name());
        assertEquals(ConnectorPresetAvailability.AVAILABLE, response.get(0).connection().availability());
        verify(formAssembler, never()).assemble(any(), any());
    }

    @Test
    @DisplayName("поиск экранирует % _ и \\")
    void escapesLikeWildcards() {
        when(languageResolver.requestLanguage()).thenReturn(ContentLanguage.EN);
        when(presetRepository.search(eq("100\\%\\_off\\\\"), eq(null), eq(null), eq(null)))
                .thenReturn(List.of());

        service.list("100%_off\\", null, null, null);

        verify(presetRepository).search("100\\%\\_off\\\\", null, null, null);
    }

    @Test
    @DisplayName("пустой auth_methods даёт бейдж UNKNOWN")
    void emptyAuthMethodsIsUnknown() {
        UUID id = UUID.randomUUID();
        ConnectorPreset preset = preset(id, List.of());
        when(languageResolver.requestLanguage()).thenReturn(ContentLanguage.EN);
        when(presetRepository.search("", null, null, null)).thenReturn(List.of(preset));
        when(textRepository.findAllByPresetIdInAndLanguage(List.of(id), ContentLanguage.EN))
                .thenReturn(List.of());

        List<ConnectorPresetResponse> response = service.list(null, null, null, null);

        assertEquals(ConnectorPresetAuth.UNKNOWN, response.get(0).card().auth());
    }

    private static ConnectorPreset preset(UUID id, List<ConnectorPresetAuth> authMethods) {
        return ConnectorPreset.builder()
                .id(id)
                .code("bitrix24")
                .connectorCode("mcp")
                .name("Bitrix24")
                .description("CRM")
                .iconUrl("https://example.com/icon.png")
                .category(ContentCategory.WORK)
                .jurisdiction("RU")
                .authMethods(authMethods)
                .availability(ConnectorPresetAvailability.AVAILABLE)
                .docsUrl("https://example.com/docs")
                .params(Map.of("url", "https://example.com/mcp"))
                .identity("https://example.com/mcp")
                .build();
    }
}
