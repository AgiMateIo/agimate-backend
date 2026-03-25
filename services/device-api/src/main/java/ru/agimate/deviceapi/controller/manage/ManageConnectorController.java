package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.controller.manage.dto.ConnectorResponse;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;

import java.util.List;

@RestController
@RequestMapping(ManageConnectorController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connectors", description = "Connector catalog")
public class ManageConnectorController {

    public static final String PATH = "/manage/connectors";

    private final ConnectorRepository connectorRepository;

    @Operation(summary = "List all available connectors")
    @GetMapping("/")
    public SuccessResponse<List<ConnectorResponse>> getAll() {
        List<ConnectorResponse> connectors = connectorRepository.findAll()
                .stream()
                .map(ConnectorResponse::from)
                .toList();
        return SuccessResponse.ok(connectors);
    }
}
