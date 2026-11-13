package ru.agimate.controlapi.controller.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.catalog.dto.CatalogConnectorPresetResponse;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.service.ConnectorPresetQueryService;
import ru.agimate.controlapi.service.ContentTaxonomyService;
import ru.agimate.controlapi.service.PublicCatalogService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicCatalogController — пресеты коннекторов без формы")
class PublicCatalogControllerTest {

    @Mock
    private PublicCatalogService publicCatalogService;
    @Mock
    private ContentTaxonomyService contentTaxonomyService;
    @Mock
    private ConnectorPresetQueryService connectorPresetQueryService;
    @InjectMocks
    private PublicCatalogController controller;

    @Test
    @DisplayName("список отдаёт карточки без формы и публичный кэш")
    void listIsCachedAndHasNoForm() {
        CatalogConnectorPresetResponse preset = preset();
        when(connectorPresetQueryService.listPublic("bitrix", ContentCategory.WORK, "RU"))
                .thenReturn(List.of(preset));

        ResponseEntity<SuccessResponse<List<CatalogConnectorPresetResponse>>> response =
                controller.getConnectorPresets("bitrix", ContentCategory.WORK, "RU");

        assertEquals(List.of(preset), response.getBody().getResponse());
        assertTrue(response.getHeaders().getCacheControl().contains("max-age=300"));
    }

    @Test
    @DisplayName("карточка по коду делегируется сервису")
    void getDelegates() {
        CatalogConnectorPresetResponse preset = preset();
        when(connectorPresetQueryService.getPublic("bitrix24")).thenReturn(preset);

        assertEquals(preset, controller.getConnectorPreset("bitrix24").getBody().getResponse());
    }

    private static CatalogConnectorPresetResponse preset() {
        return new CatalogConnectorPresetResponse(
                "bitrix24",
                "mcp",
                new CatalogConnectorPresetResponse.CatalogConnectorPresetCard(
                        "Bitrix24", "CRM", "https://example.com/icon.png", ContentCategory.WORK,
                        "RU", ConnectorPresetAuth.TOKEN, "https://example.com/docs"),
                new CatalogConnectorPresetResponse.CatalogConnectorPresetConnection(
                        ConnectorPresetAvailability.AVAILABLE));
    }
}
