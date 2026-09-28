package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.channel.handler.AcpChannelHandler;
import ru.agimate.controlapi.service.channel.handler.WebchatChannelHandler;

import java.util.Set;

/**
 * Whether a tool call acts on the owner's request — the gate for an agent changing its own settings.
 * An agent would rewrite them on the say-so of any text it reads, so only runs whose prompt the owner
 * wrote qualify: webchat (the user's session) and ACP (the agent key, which only the owner hands out).
 * A call with no run comes over the agent key too (the MCP server, {@code /agent/tools/call}). An
 * injection inside an owner's run (a mail read in a webchat run) is beyond this gate.
 */
@Component
@RequiredArgsConstructor
public class OwnerRequestGuard {

    private static final Set<String> OWNER_CHANNELS =
            Set.of(WebchatChannelHandler.CONNECTOR_CODE, AcpChannelHandler.CONNECTOR_CODE);

    private final AgentRunRepository agentRunRepository;

    public boolean isOwnerRequest(ConnectorEnv env) {
        if (env.runId() == null) {
            return true;
        }
        return agentRunRepository.findTriggerConnectorCode(env.runId())
                .map(OWNER_CHANNELS::contains)
                .orElse(false);
    }

    public void require(ConnectorEnv env) {
        if (!isOwnerRequest(env)) {
            throw new ConnectorException("Settings can only be changed at the owner's request: "
                    + "ask the user to do it in the web chat or in the agent's settings panel");
        }
    }
}
