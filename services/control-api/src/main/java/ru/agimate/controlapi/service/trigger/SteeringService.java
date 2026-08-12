package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.channel.InboundTextResolver;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.runcontext.InboundPart;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Steering: a running run absorbs the inbound messages of the younger ENQUEUED runs of its session
 * instead of leaving them to wait out the partition. The claimed run is never touched in DBOS — it
 * stays queued, reaches the front only after its main is terminal (partition concurrency 1), and
 * stands aside at its own seq 0 ack when the absorption is confirmed
 * ({@code MessageLogPersistence}). Every failure on this path therefore degrades toward the run
 * executing normally — a delay or a duplicate answer, never a lost message.
 *
 * <p>Two facts with two writers on purpose: {@link #claim} stamps {@code main_run_id} when the
 * main takes the message, {@link #markSteered} stamps {@code steered_at} when the model has
 * actually seen it (the worker confirms after the LLM call returns). A claim whose response never
 * reached the worker stays unconfirmed and the claimed run runs itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SteeringService {

    private final AgentRunRepository agentRunRepository;
    private final InboundTextResolver inboundTextResolver;

    /**
     * One claimed inbound in the shape the context layer already speaks: the canonical text (the
     * same {@code handleInput} extraction as at dispatch, or a compact JSON of the event) plus
     * attachment references.
     */
    public record SteeringInbound(UUID runId, String text, List<InboundPart> parts) {}

    /**
     * Claim everything currently steerable into {@code mainRunId} and return the messages, oldest
     * first. Idempotent for the same main: a replayed seam re-fetches what is still claimed and
     * unconfirmed. Empty for a run without a session — there is nothing to steer within.
     */
    @Transactional
    public List<SteeringInbound> claim(UUID agentId, UUID mainRunId) {
        AgentRun main = ownedRun(agentId, mainRunId);
        if (main.getSessionId() == null) {
            return List.of();
        }
        List<AgentRun> claimable = agentRunRepository.findSteerable(
                main.getSessionId(), agentId, mainRunId, main.getCreatedAt());
        List<SteeringInbound> result = new ArrayList<>(claimable.size());
        for (AgentRun run : claimable) {
            run.setMainRunId(mainRunId); // managed row — dirty checking flushes it at commit
            result.add(inboundOf(run));
        }
        if (!result.isEmpty()) {
            log.info("run {} claimed {} steerable run(s) of session {}",
                    mainRunId, result.size(), main.getSessionId());
        }
        return result;
    }

    /** Absorption confirmed by the worker; returns how many rows were actually stamped. */
    @Transactional
    public int markSteered(UUID agentId, UUID mainRunId, List<UUID> steeredRunIds) {
        ownedRun(agentId, mainRunId);
        if (steeredRunIds.isEmpty()) {
            return 0;
        }
        int stamped = agentRunRepository.markSteered(steeredRunIds, mainRunId, LocalDateTime.now());
        log.info("run {} confirmed {} steered run(s)", mainRunId, stamped);
        return stamped;
    }

    private AgentRun ownedRun(UUID agentId, UUID runId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new NotFoundStatusException("Run not found: " + runId));
        if (!run.getAgent().getId().equals(agentId)) {
            throw new BadRequestStatusException("Run " + runId + " does not belong to agent " + agentId);
        }
        return run;
    }

    /**
     * The claimed run's inbound as the model should receive it. Mirrors the canonical inbound of
     * the message log: the prompt channel's extraction when it yields anything, otherwise the
     * compact JSON of the event (which also covers trigger runs that share the session).
     */
    private SteeringInbound inboundOf(AgentRun run) {
        Trigger trigger = Trigger.fromLog(run.getTriggerLog());
        Channels channels = ChannelsCodec.fromMap(run.getChannels());
        InboundMessage message = channels != null && channels.prompt() != null
                ? inboundTextResolver.resolve(channels.prompt().channelId(), trigger).orElse(null)
                : null;
        if (message == null || (isBlank(message.text()) && message.parts().isEmpty())) {
            return new SteeringInbound(run.getId(), trigger.compactJson(), List.of());
        }
        return new SteeringInbound(run.getId(), message.text(), parts(message));
    }

    private static List<InboundPart> parts(InboundMessage message) {
        return message.parts().stream()
                .map(p -> new InboundPart(p.storageRef(), p.type(), p.mime(), p.size(), partName(p)))
                .toList();
    }

    private static String partName(Part part) {
        Object name = part.meta() != null ? part.meta().get("name") : null;
        return name != null ? name.toString() : "";
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
