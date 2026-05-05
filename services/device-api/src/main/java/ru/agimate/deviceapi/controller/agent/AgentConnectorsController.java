package ru.agimate.deviceapi.controller.agent;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(AgentConnectorsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Connectors API", description = "Connector operations via API Key")
public class AgentConnectorsController {

    public static final String PATH = AgentController.PATH + "/connectors";

}
