package ru.agimate.controlapi.connectors.internal.agents;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.service.channel.handler.AgentsChannelHandler;
import ru.agimate.controlapi.service.runcontext.RunCatalog;
import ru.agimate.controlapi.service.subagent.SubagentService;
import ru.agimate.controlapi.service.subagent.TeammateService;
import ru.agimate.controlapi.service.team.AgentRequestEventPublisher;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerAudience;
import ru.agimate.controlapi.service.trigger.TriggerContext;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The agents tool: an agent hands a request to another agent of its team. The request travels the
 * way a subagent's does — a trigger of the callee's agents channel into a thread that works for this
 * conversation — so the reply comes back as a report later, never within this call. Who may be
 * asked is decided before anything is routed ({@link TeammateService}), so a refusal is immediate
 * and says who can be asked instead.
 */
@Component
@RequiredArgsConstructor
public class AgentsToolService {

    static final int TITLE_MAX_LENGTH = 80;

    private final SubagentService subagentService;
    private final TeammateService teammateService;
    private final AgentRepository agentRepository;
    private final TriggerRouterService triggerRouterService;
    private final AgentRequestEventPublisher eventPublisher;

    @Tool(name = "ask_agent",
            description = "Hand a request to another agent of your team and get its answer as a separate "
                    + "message later. Use it when a teammate is the expert for a question — its "
                    + "instructions, skills, memory and tools differ from yours; for work that only needs "
                    + "your own tools, ask_subagent is cheaper. The agent starts with no knowledge of "
                    + "this conversation: put everything it needs into the request. The call returns at "
                    + "once with a threadId; the report arrives after your turn ends, so finish your "
                    + "turn instead of waiting. Pass threadId to add to a request you made to that agent "
                    + "before. For long work with a visible state, put a task on the board instead.",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public Map<String, Object> askAgent(
            @ToolParam("Id of the agent to ask — one of your teammates listed in the prompt") String agentId,
            @ToolParam("Short name of the request, e.g. \"Contract review\"") String title,
            @ToolParam("What to do and what to answer, in what form") String instructions,
            @ToolParam(value = "Everything the agent needs to know from this conversation: facts, "
                    + "constraints, the language and tone of the answer", required = false) String context,
            @ToolParam(value = "Id of a thread you opened with this agent earlier, to add this request "
                    + "to it instead of starting a new one", required = false) String threadId) {
        ConnectorEnv env = ConnectorEnvHolder.current();
        if (env.agentId() == null || env.userId() == null || env.runId() == null) {
            throw new ConnectorException("ask_agent is only available inside an agent's run");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new ConnectorException("instructions are required");
        }
        UUID calleeId = parseId(agentId, "agentId");
        if (calleeId == null) {
            throw new ConnectorException("agentId is required");
        }
        UUID existing = parseId(threadId, "threadId");
        String name = title == null || title.isBlank() ? firstLine(instructions) : title.strip();
        Agent asker = agentRepository.findById(env.agentId())
                .orElseThrow(() -> new ConnectorException("ask_agent: the calling agent is gone"));
        UUID connectionId = UUID.fromString(env.connectionId());

        // The request as it will travel: the author is ours, not the model's, and the callee's
        // params_filter is judged over this before anything is created. A new thread has no id yet,
        // so a rule on threadId cannot judge a new request here — the router judges it once more over
        // the full data, after the thread exists.
        Map<String, Object> data = new LinkedHashMap<>();
        if (existing != null) {
            data.put("threadId", existing.toString());
        }
        data.put("mode", existing != null ? "append" : "new");
        data.put("title", name);
        data.put("instructions", instructions);
        if (context != null && !context.isBlank()) {
            data.put("context", context);
        }
        data.put("fromAgentId", asker.getId().toString());
        data.put("fromAgentName", asker.getName());
        Agent callee = teammateService.resolve(asker, connectionId, calleeId, data);

        SubagentService.Target target = subagentService.openFor(
                new SubagentService.Callee(callee, RunCatalog.AGENTS, AgentsChannelHandler.NAME,
                        "Agents: " + callee.getName()),
                env.agentId(), env.runId(), env.connectionId(), name, existing);
        data.put("threadId", target.childSessionId().toString());

        // Routed outside the session's transaction: the DBOS enqueue must not share it.
        triggerRouterService.routeTrigger(env.userId(), Trigger.createDirected(
                RunCatalog.AGENTS,
                env.connectionId(),
                SubagentService.REQUEST_TRIGGER,
                data,
                new TriggerContext(
                        new TriggerAudience(null, List.of(callee.getId())),
                        Channels.ofPrompt(new ChannelInfo(target.channelId(), target.childSessionId(), null)),
                        env.runId())));

        boolean started = target.mode() == SubagentService.Mode.NEW;
        eventPublisher.publish(target.childSessionId(),
                started ? AgentRequestEventPublisher.STARTED : AgentRequestEventPublisher.APPENDED);

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("threadId", target.childSessionId().toString());
        receipt.put("agentName", callee.getName());
        receipt.put("status", started ? "started" : "appended");
        receipt.put("note", callee.getName() + " is working on it. The report arrives as a separate message "
                + "after your turn ends. Do not wait or poll; finish your turn.");
        return receipt;
    }

    private static UUID parseId(String value, String param) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException(param + " is not a valid id: " + value);
        }
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() <= TITLE_MAX_LENGTH ? line : line.substring(0, TITLE_MAX_LENGTH);
    }
}
