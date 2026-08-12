package ru.agimate.controlapi.controller.app;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.controller.app.dto.ToolResultRequest;
import ru.agimate.controlapi.security.AppPrincipal;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.dto.ToolResult;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.service.tool.ToolCallLogService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(AppToolsController.PATH)
public class AppToolsController {

    public static final String PATH = AppRegistrationController.PATH + "/tools";

    private final AppService appService;
    private final AgentDeliveryService agentDeliveryService;
    private final ToolCallLogService toolCallLogService;
    private final InboundRateLimiter rateLimiter;

    @PostMapping("/result")
    public SuccessResponse<String> submitToolResult(
            @RequestBody @Valid
            ToolResultRequest toolResultRequest,
            @AuthenticationPrincipal AppPrincipal principal
    ) {
        // Before touching the database: the key (appId == connectionId) is already authenticated in the principal.
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.TOOL_RESULT, principal.appId())) {
            throw new TooManyRequestsStatusException("Tool result rate limit exceeded");
        }

        // The output may contain user content — only sizes go into the log, never the payload.
        log.info("Tool result received - id={}, app={}, hasError={}, outputChars={}",
                toolResultRequest.id(), principal.appId(), toolResultRequest.error() != null,
                toolResultRequest.output() != null ? toolResultRequest.output().length() : 0);

        var app = appService.getApp(principal);

        var toolCallLog = toolCallLogService.recordOutputFromApp(app, toolResultRequest);

        // The app sent the result under the log's PK; we deliver it to the agent under its external_id — the
        // agent (and the worker's gRPC polling) correlate calls in their own identifier space.
        var agentResult = new ToolResult(
                toolCallLog.getExternalId(),
                toolCallLog.getConnectorCode(),
                toolCallLog.getOutput(),
                toolCallLog.getError());
        agentDeliveryService.deliverToolResult(toolCallLog, agentResult);

        return SuccessResponse.empty();
    }

}
