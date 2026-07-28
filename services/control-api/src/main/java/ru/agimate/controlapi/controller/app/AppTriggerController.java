package ru.agimate.controlapi.controller.app;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.controller.app.dto.TriggerRequest;
import ru.agimate.controlapi.security.AppPrincipal;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

@Slf4j
@RestController
@RequestMapping(AppTriggerController.PATH)
@RequiredArgsConstructor
public class AppTriggerController {

    public static final String PATH = AppRegistrationController.PATH + "/trigger";

    private final AppService appService;
    private final TriggerRouterService triggerRouterService;
    private final InboundRateLimiter rateLimiter;

    @Operation(
            summary = "Submit trigger from device",
            description = "Receives trigger event from device and publishes it for processing by webhook subscribers"
    )
    @PostMapping("/new")
    public SuccessResponse<String> submitTrigger(
            @RequestBody @Valid
            TriggerRequest triggerRequest,
            @AuthenticationPrincipal AppPrincipal principal
    ) {
        // Before touching the database: the key (appId == connectionId) is already authenticated in the principal.
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.TRIGGER, principal.appId())) {
            throw new TooManyRequestsStatusException("Trigger rate limit exceeded");
        }

        // data may contain user content — only metadata goes into the log, never the payload.
        log.info("Trigger received - name={}, id={}, app={}, dataFields={}",
                triggerRequest.name(), triggerRequest.id(), principal.appId(),
                triggerRequest.data().size());

        var app = appService.getApp(principal);
        appService.requireDeclaredTrigger(app, triggerRequest.name());
        triggerRouterService.routeAppTrigger(app, triggerRequest);

        return SuccessResponse.ok(triggerRequest.id());
    }

}
