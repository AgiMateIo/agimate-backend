package ru.agimate.controlapi.connectors.internal.webchat;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.service.channel.handler.WebchatChannelHandler;

import java.util.List;
import java.util.Map;

/**
 * Facade of the webchat connector: the user's chat with an agent from our own frontend.
 *
 * <p>One connection per user (a mode row, materialised by {@code ConnectionBindingService} on the
 * first chat); the connector derives no data owner — every interaction arrives with an explicit
 * address (sessionId → channel → agent). Agents attach to it through {@code agent_connections}
 * bindings, and channels are per agent. Incoming messages are sent by the frontend through
 * {@code /manage/webchat/...} (the trigger {@code message_received} with an explicit
 * sessionId/audience), and replies are delivered by {@code WebchatChannelHandler} (webchat_messages
 * plus Centrifugo). There are no tools and no jobs — of the capability interfaces it implements only
 * {@link TriggerProvider}.
 */
@Component
public class WebchatConnectorService implements InternalConnectorHandler, TriggerProvider {

    @Override
    public String connectorCode() {
        return WebchatChannelHandler.CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Webchat";
    }

    @Override
    public String connectorDescription() {
        return "Chat with the agent in the web interface — the default channel for talking to the user.";
    }

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        return Map.of(WebchatChannelHandler.TRIGGER_MESSAGE_RECEIVED, new TriggerSpec(
                "Message from the user typed in the web chat",
                List.of("sessionId", "messageId", "text")));
    }
}
