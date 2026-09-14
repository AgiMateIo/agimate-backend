package ru.agimate.controlapi.connectors.internal.subagents;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.service.subagent.SubagentService;
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
 * The subagents tool: the agent hands a request to a copy of itself. The request travels the way a
 * person's message does — a trigger of the subagents channel into the child's session — so the reply
 * comes back as a report later, never within this call.
 */
@Component
@RequiredArgsConstructor
public class SubagentsToolService {

    static final int TITLE_MAX_LENGTH = 80;

    private final SubagentService subagentService;
    private final TriggerRouterService triggerRouterService;

    @Tool(name = "ask_subagent",
            description = "Hand a self-contained request to a subagent — a copy of you that starts with no "
                    + "knowledge of this conversation, works in its own session and reports back. Use it "
                    + "for work that would flood your context (research across many sources, reading "
                    + "long material) or for independent pieces that can run in parallel; do a single "
                    + "lookup or a single tool call yourself. The call returns at once with a subagentId; "
                    + "the report arrives as a separate message after your turn ends, so finish your turn "
                    + "instead of waiting. Pass subagentId to add to the request of a subagent you asked "
                    + "before.",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public Map<String, Object> askSubagent(
            @ToolParam("Short name of the request, e.g. \"Bank B tariffs\"") String title,
            @ToolParam("What to do and what to return, in what form") String instructions,
            @ToolParam(value = "Everything the subagent needs to know from this conversation: facts, "
                    + "constraints, the language and tone of the report", required = false) String context,
            @ToolParam(value = "Id of a subagent of this conversation to add this request to, "
                    + "instead of starting a new one", required = false) String subagentId) {
        ConnectorEnv env = ConnectorEnvHolder.current();
        if (env.agentId() == null || env.userId() == null || env.runId() == null) {
            throw new ConnectorException("ask_subagent is only available inside an agent's run");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new ConnectorException("instructions are required");
        }
        String name = title == null || title.isBlank() ? firstLine(instructions) : title.strip();
        UUID existing = parseSubagentId(subagentId);

        SubagentService.Target target = subagentService.open(
                env.agentId(), env.runId(), env.connectionId(), name, existing);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subagentId", target.childSessionId().toString());
        data.put("title", name);
        data.put("mode", target.mode().name().toLowerCase());
        data.put("instructions", instructions);
        if (context != null && !context.isBlank()) {
            data.put("context", context);
        }
        // Routed outside the session's transaction: the DBOS enqueue must not share it.
        triggerRouterService.routeTrigger(env.userId(), Trigger.createDirected(
                SubagentService.CONNECTOR_CODE,
                env.connectionId(),
                SubagentService.REQUEST_TRIGGER,
                data,
                new TriggerContext(
                        new TriggerAudience(null, List.of(env.agentId())),
                        Channels.ofPrompt(new ChannelInfo(target.channelId(), target.childSessionId(), null)),
                        env.runId())));

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("subagentId", target.childSessionId().toString());
        receipt.put("status", target.mode() == SubagentService.Mode.NEW ? "started" : "appended");
        receipt.put("note", "The subagent is working. Its report arrives as a separate message after "
                + "your turn ends. Do not wait or poll; finish your turn.");
        return receipt;
    }

    private static UUID parseSubagentId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("subagentId is not a valid id: " + value);
        }
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() <= TITLE_MAX_LENGTH ? line : line.substring(0, TITLE_MAX_LENGTH);
    }
}
