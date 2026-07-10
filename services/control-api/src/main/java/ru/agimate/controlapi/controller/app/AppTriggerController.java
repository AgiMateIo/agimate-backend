package ru.agimate.controlapi.controller.app;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.app.dto.TriggerRequest;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

@Slf4j
@RestController
@RequestMapping(AppTriggerController.PATH)
@RequiredArgsConstructor
public class AppTriggerController {

    public static final String PATH = AppRegistrationController.PATH + "/trigger";

    private final AppService appService;
    private final TriggerRouterService triggerRouterService;

    @Operation(
            summary = "Submit trigger from device",
            description = "Receives trigger event from device and publishes it for processing by webhook subscribers"
    )
    @PostMapping("/new")
    public SuccessResponse<String> submitTrigger(
            @RequestBody @Valid
            TriggerRequest triggerRequest,
            Authentication authentication
    ) {
        log.info("Trigger received - {}", triggerRequest.toString());

        var app = appService.getApp(authentication);
        triggerRouterService.routeAppTrigger(app, triggerRequest);

        return SuccessResponse.ok(triggerRequest.id());
    }

}
