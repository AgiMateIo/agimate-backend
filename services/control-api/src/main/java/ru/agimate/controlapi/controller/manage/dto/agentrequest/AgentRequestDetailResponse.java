package ru.agimate.controlapi.controller.manage.dto.agentrequest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** One request thread: the row of the listing, plus everything said in it. */
@Schema(description = "A request with its full exchange")
public record AgentRequestDetailResponse(
        @Schema(description = "The same fields the listing carries")
        AgentRequestResponse request,

        @Schema(description = "Requests and reports of the thread, oldest first")
        List<AgentRequestExchangeItem> exchange
) {
}
