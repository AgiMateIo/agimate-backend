package ru.agimate.controlapi.controller.manage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetCard;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetConnection;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetForm;
import ru.agimate.controlapi.controller.manage.dto.ConnectorPresetResponse;
import ru.agimate.controlapi.database.enums.ConnectorPresetAuth;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.service.ConnectorPresetQueryService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManageConnectorPresetController — делегирование каталогу")
class ManageConnectorPresetControllerTest {

    @Mock
    private ConnectorPresetQueryService queryService;
    @InjectMocks
    private ManageConnectorPresetController controller;

    @Test
    @DisplayName("список передаёт фильтры и возвращает ответ сервиса")
    void listDelegatesFilters() {
        ConnectorPresetResponse preset = preset();
        when(queryService.list("bitrix", "mcp", ContentCategory.WORK, "RU")).thenReturn(List.of(preset));

        SuccessResponse<List<ConnectorPresetResponse>> response =
                controller.list("bitrix", "mcp", ContentCategory.WORK, "RU");

        assertEquals(List.of(preset), response.getResponse());
    }

    @Test
    @DisplayName("карточка по коду делегируется сервису")
    void getDelegatesCode() {
        ConnectorPresetResponse preset = preset();
        when(queryService.get("bitrix24")).thenReturn(preset);

        assertEquals(preset, controller.get("bitrix24").getResponse());
        verify(queryService).get("bitrix24");
    }

    private static ConnectorPresetResponse preset() {
        return new ConnectorPresetResponse(
                "bitrix24",
                "mcp",
                new ConnectorPresetCard("Bitrix24", "CRM", "https://example.com/icon.png",
                        ContentCategory.WORK, "RU", ConnectorPresetAuth.TOKEN, "https://example.com/docs"),
                new ConnectorPresetForm(Map.of("url", "https://example.com/mcp"), Map.of(), List.of(), List.of(),
                        List.of(ConnectorPresetAuth.TOKEN)),
                new ConnectorPresetConnection("https://example.com/mcp", ConnectorPresetAvailability.AVAILABLE));
    }
}
