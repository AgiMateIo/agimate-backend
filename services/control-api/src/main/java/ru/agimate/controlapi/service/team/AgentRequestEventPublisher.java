package ru.agimate.controlapi.service.team;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;

import java.util.Map;
import java.util.UUID;

/**
 * Live changes of the team's requests, on the user's own channel — the same shape the board's task
 * events have, so one subscription carries both. The payload is the listing row, built fresh at the
 * moment of the event: a client may redraw a row from it without asking for the page again.
 *
 * <p>A failure to publish never fails what caused it: the request was made, the report was
 * delivered, and a lost push only means a stale screen until the next read.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRequestEventPublisher {

    public static final String STARTED = "agent.request.started";
    public static final String APPENDED = "agent.request.appended";
    public static final String REPORTED = "agent.request.reported";
    public static final String CANCELLED = "agent.request.cancelled";

    static final String ENTITY = "agent.request";

    private final AgentRequestQueryService agentRequestQueryService;
    private final CentrifugoService centrifugoService;

    public void publish(UUID threadId, String eventType) {
        try {
            agentRequestQueryService.forEvent(threadId).ifPresent(request -> centrifugoService.publishMessage(
                    "user:" + request.userId(),
                    eventType,
                    request.request(),
                    Map.of("entity", ENTITY, "teamId", request.teamId().toString())));
        } catch (Exception e) {
            log.warn("Failed to publish '{}' for request thread {}: {}", eventType, threadId, e.getMessage());
        }
    }
}
