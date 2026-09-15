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
import ru.agimate.controlapi.service.runcontext.RunBlock;
import ru.agimate.controlapi.service.runcontext.TriggerBlocks;

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
    private final TriggerBlocks triggerBlocks;

    /**
     * One claimed inbound in the shape the context layer already speaks: a message of the prompt
     * channel — the canonical text (the same {@code handleInput} extraction as at dispatch) plus
     * attachment references — or an event, as the blocks its own run would have started with.
     *
     * @param text   for an event, the compact JSON a worker that predates {@code blocks} still reads;
     *               a current worker renders the blocks instead
     * @param blocks empty for a channel message; for an event, the guidance and main block of
     *               {@link TriggerBlocks}, so the model gets the same trust marking either way
     */
    public record SteeringInbound(UUID runId, String text, List<InboundPart> parts, List<RunBlock> blocks) {}

    /**
     * Claim everything currently steerable into {@code mainRunId} and return the messages, oldest
     * first. Idempotent for the same main: a replayed seam re-fetches what is still claimed and
     * unconfirmed. Works for trigger runs too — a connection's events share its session, and a
     * detached result or a subagent's report shares the conversation's — and what gets absorbed is
     * then the event itself.
     */
    @Transactional
    public List<SteeringInbound> claim(UUID agentId, UUID mainRunId) {
        AgentRun main = ownedRun(agentId, mainRunId);
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
     * The claimed run's inbound as the model should receive it: the prompt channel's extraction when
     * the run has one and it yields anything; otherwise the event as its own run would have rendered
     * it — the trigger's guidance and its main block, untrusted unless declared a prompt.
     */
    private SteeringInbound inboundOf(AgentRun run) {
        Trigger trigger = Trigger.fromLog(run.getTriggerLog());
        Channels channels = ChannelsCodec.fromMap(run.getChannels());
        if (channels == null || channels.prompt() == null) {
            return new SteeringInbound(run.getId(), trigger.compactJson(), List.of(), triggerBlocks.of(trigger));
        }
        InboundMessage message = inboundTextResolver.resolve(channels.prompt().channelId(), trigger).orElse(null);
        if (message == null || (isBlank(message.text()) && message.parts().isEmpty())) {
            return new SteeringInbound(run.getId(), trigger.compactJson(), List.of(),
                    List.of(TriggerBlocks.eventBlock(trigger)));
        }
        return new SteeringInbound(run.getId(), message.text(), parts(message), List.of());
    }

    private static List<InboundPart> parts(InboundMessage message) {
        return message.parts().stream()
                .map(p -> new InboundPart(p.storageRef(), p.version(), p.type(), p.mime(), p.size(), partName(p)))
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
