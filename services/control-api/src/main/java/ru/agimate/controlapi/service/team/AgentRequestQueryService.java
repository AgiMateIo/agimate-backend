package ru.agimate.controlapi.service.team;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestDetailResponse;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestExchangeItem;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestOrigin;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestParty;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestReport;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestResponse;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestStatus;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.database.projections.AgentRequestExchangeProjection;
import ru.agimate.controlapi.database.projections.AgentRequestProjection;
import ru.agimate.controlapi.database.projections.AgentRequestRunProjection;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.service.AgentRunQueryService;
import ru.agimate.controlapi.service.AgenticTeamService;
import ru.agimate.controlapi.service.runcontext.RunCatalog;
import ru.agimate.controlapi.service.subagent.SubagentService;
import ru.agimate.controlapi.service.trigger.RunActivityService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Requests between the agents of a team, as the user reads them: who asked whom, what came back,
 * what is still being worked on. The unit is the callee's thread — a session of the {@code agents}
 * connector — and everything else is folded from its runs, so there is no state to keep in step
 * with the runs themselves.
 *
 * <p>Threads of {@code subagents} are not requests between agents and never appear here: a subagent
 * is the same agent working on its own, and its place is beside the conversation.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentRequestQueryService {

    /** As much of an answer as a listing row shows. */
    static final int PREVIEW_LENGTH = 240;

    private static final int MAX_PAGE_SIZE = 100;

    private final AgentSessionRepository agentSessionRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentRepository agentRepository;
    private final AgentRunQueryService agentRunQueryService;
    private final AgenticTeamService agenticTeamService;

    /** A request with the addressing an event needs: the team it belongs to and its owner. */
    public record TeamRequest(UUID teamId, UUID userId, AgentRequestResponse request) {}

    /** Every filter is optional; the team is the ownership gate and is checked before anything is read. */
    public Page<AgentRequestResponse> list(UUID userId, UUID teamId, UUID agentId, UUID fromAgentId,
                                           UUID toAgentId, LocalDateTime since, int page, int size) {
        agenticTeamService.getById(teamId, userId);
        Page<AgentRequestProjection> threads = agentSessionRepository.findRequests(
                RunCatalog.AGENTS, userId, teamId, null, agentId, fromAgentId, toAgentId, since,
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)));
        return threads.map(enricher(threads.getContent()));
    }

    /** One request with its whole exchange — the same header the listing shows, plus what was said. */
    public AgentRequestDetailResponse get(UUID userId, UUID teamId, UUID threadId) {
        agenticTeamService.getById(teamId, userId);
        AgentRequestProjection thread = one(userId, teamId, threadId)
                .orElseThrow(() -> new NotFoundStatusException("Request not found"));
        List<AgentRequestExchangeProjection> runs = agentRunRepository.findExchangeRuns(threadId);
        return new AgentRequestDetailResponse(
                response(thread, runs, live(List.of(threadId)), names(List.of(thread))),
                exchange(runs));
    }

    /**
     * The payload of a live event. No team is asked for — the row reports the one its callee is in,
     * and an event about an agent that has left every team is not published at all.
     */
    public Optional<TeamRequest> forEvent(UUID threadId) {
        AgentSession thread = agentSessionRepository.findById(threadId).orElse(null);
        if (thread == null) {
            return Optional.empty();
        }
        return one(thread.getUserId(), null, threadId)
                .filter(row -> row.getTeamId() != null)
                .map(row -> new TeamRequest(row.getTeamId(), row.getUserId(),
                        enricher(List.of(row)).apply(row)));
    }

    private Optional<AgentRequestProjection> one(UUID userId, UUID teamId, UUID threadId) {
        return agentSessionRepository.findRequests(RunCatalog.AGENTS, userId, teamId, threadId,
                        null, null, null, null, PageRequest.of(0, 1))
                .stream().findFirst();
    }

    /** One page, one read of the runs, one of the liveness, one of the names. */
    private Function<AgentRequestProjection, AgentRequestResponse> enricher(List<AgentRequestProjection> threads) {
        if (threads.isEmpty()) {
            return thread -> null;
        }
        List<UUID> ids = threads.stream().map(AgentRequestProjection::getId).toList();
        Map<UUID, List<AgentRequestRunProjection>> runs = agentRunRepository.findRequestRuns(ids).stream()
                .collect(Collectors.groupingBy(AgentRequestRunProjection::getSessionId));
        Set<UUID> live = live(ids);
        Map<UUID, String> names = names(threads);
        return thread -> response(thread, runs.getOrDefault(thread.getId(), List.of()),
                live, names);
    }

    private Set<UUID> live(Collection<UUID> sessionIds) {
        return agentRunQueryService.liveSessionIds(sessionIds);
    }

    private Map<UUID, String> names(Collection<AgentRequestProjection> threads) {
        Set<UUID> ids = new HashSet<>();
        for (AgentRequestProjection thread : threads) {
            ids.add(thread.getToAgentId());
            ids.add(thread.getFromAgentId());
        }
        return agentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Agent::getId, Agent::getName));
    }

    private AgentRequestResponse response(AgentRequestProjection thread,
                                          List<? extends AgentRequestRunProjection> runs,
                                          Set<UUID> live, Map<UUID, String> names) {
        AgentRequestRunProjection last = null;
        AgentRequestRunProjection answered = null;
        int requests = 0;
        for (AgentRequestRunProjection run : runs) {
            if (SubagentService.REQUEST_TRIGGER.equals(run.getName())) {
                requests++;
            }
            // An absorbed run said nothing and answers nothing: the run that took its message does.
            if (run.getSteeredAt() != null) {
                continue;
            }
            last = run;
            if (run.getResult() != null || run.getError() != null) {
                answered = run;
            }
        }
        return new AgentRequestResponse(
                thread.getId(),
                thread.getTitle(),
                new AgentRequestParty(thread.getFromAgentId(), names.get(thread.getFromAgentId())),
                new AgentRequestParty(thread.getToAgentId(), names.get(thread.getToAgentId())),
                new AgentRequestOrigin(thread.getOriginSessionId(), thread.getOriginTitle(),
                        thread.getOriginConnectorCode()),
                status(thread, last, live.contains(thread.getId())),
                requests,
                report(answered, PREVIEW_LENGTH),
                thread.getCreatedAt(),
                thread.getLastActivityAt(),
                thread.getClosedAt());
    }

    /**
     * A stopped run wins over a live one — a run cancelled mid-flight is still running for a moment,
     * and the user who stopped it is not shown work. Liveness itself is the listings' one opinion
     * ({@link AgentRunQueryService#liveSessionIds}), and it outranks a finished last run: an answer
     * given while a detached call is pending is interim, and the thread is still working.
     */
    private static AgentRequestStatus status(AgentRequestProjection thread, AgentRequestRunProjection last,
                                             boolean live) {
        if (last == null) {
            // The request is routed after the thread's transaction commits, so a thread of the last
            // few minutes without a run is starting; an older one never started at all.
            return thread.getCreatedAt().isAfter(staleBefore())
                    ? AgentRequestStatus.WORKING
                    : AgentRequestStatus.STALLED;
        }
        if (last.getCancelRequestedAt() != null || last.getStatus() == RunStatus.CANCELLED) {
            return AgentRequestStatus.CANCELLED;
        }
        if (live) {
            return AgentRequestStatus.WORKING;
        }
        return switch (last.getStatus()) {
            case DONE -> AgentRequestStatus.DONE;
            case FAILED -> AgentRequestStatus.FAILED;
            default -> AgentRequestStatus.STALLED;
        };
    }

    private static AgentRequestReport report(AgentRequestRunProjection run, int previewLength) {
        if (run == null) {
            return null;
        }
        boolean failed = run.getError() != null;
        return new AgentRequestReport(
                failed ? AgentRequestStatus.FAILED : AgentRequestStatus.DONE,
                cut(failed ? run.getError() : run.getResult(), previewLength),
                at(run),
                run.getReportedAt());
    }

    /** Requests and reports of one thread in the order they happened. */
    private static List<AgentRequestExchangeItem> exchange(List<AgentRequestExchangeProjection> runs) {
        List<AgentRequestExchangeItem> items = new ArrayList<>();
        for (AgentRequestExchangeProjection run : runs) {
            Map<String, Object> input = run.getInput() == null ? Map.of() : run.getInput();
            if (SubagentService.REQUEST_TRIGGER.equals(run.getName())) {
                items.add(AgentRequestExchangeItem.request(
                        run.getCreatedAt(), run.getId(),
                        text(input.get("mode")), text(input.get("title")),
                        text(input.get("instructions")), text(input.get("context")),
                        run.getSteeredAt() != null));
            }
            if (run.getResult() != null || run.getError() != null) {
                boolean failed = run.getError() != null;
                items.add(AgentRequestExchangeItem.report(at(run), run.getId(),
                        failed ? AgentRequestStatus.FAILED : AgentRequestStatus.DONE,
                        failed ? run.getError() : run.getResult(),
                        run.getReportedAt()));
            }
        }
        // A request added to a working thread is created while the earlier run is still answering,
        // so the two runs interleave — time order is the thread as it happened, run order is not.
        items.sort(Comparator.comparing(AgentRequestExchangeItem::at));
        return items;
    }

    /** When the run finished: its last sign of life, or — for a run that never gave one — its last write. */
    private static LocalDateTime at(AgentRequestRunProjection run) {
        return run.getLastActivityAt() != null ? run.getLastActivityAt() : run.getUpdatedAt();
    }

    private static LocalDateTime staleBefore() {
        return LocalDateTime.now().minus(RunActivityService.STALE_AFTER);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String cut(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        int cut = length;
        if (Character.isHighSurrogate(value.charAt(cut - 1))) {
            cut--;
        }
        return value.substring(0, cut) + "…";
    }
}
