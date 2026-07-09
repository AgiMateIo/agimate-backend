package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import ru.agimate.agentworker.agent.SimpleAgent;
import ru.agimate.agentworker.agent.context.RequestBuilder;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.dto.AgentMessage;
import ru.agimate.agentworker.workers.ControlSignal;
import ru.agimate.agentworker.workers.Queues;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The run's steering inbox: drains the DBOS control topic non-blockingly at each turn boundary,
 * folding steer messages in as user turns and flagging a graceful interrupt. Used as the
 * {@link SimpleAgent.Checkpointer} when the session policy is steer/interrupt.
 */
class ControlMailbox {

    private final DBOS dbos;

    ControlMailbox(DBOS dbos) {
        this.dbos = dbos;
    }

    /**
     * Drain every pending control message: fold each steer in as a user turn and request a graceful
     * stop on the first interrupt.
     */
    SimpleAgent.CheckpointResult drain() {
        List<AgentChatMessage> injected = new ArrayList<>();
        boolean cancel = false;
        while (true) {
            Optional<String> raw = dbos.recv(Queues.CONTROL_TOPIC, Duration.ZERO);
            if (raw.isEmpty()) {
                break;
            }
            ControlSignal signal = ControlSignal.fromJson(raw.get());
            if (signal.isInterrupt()) {
                cancel = true;
                continue;
            }
            String text = steerText(signal.message());
            if (text != null && !text.isBlank()) {
                injected.add(AgentChatMessage.user(text));
            }
        }
        return new SimpleAgent.CheckpointResult(injected, cancel);
    }

    /** Extract the user text of a steered message: the channel's inbound text, else the trigger wrap. */
    private static String steerText(AgentMessage message) {
        if (message == null) {
            return null;
        }
        if (message.promptChannel() != null && message.inbound() != null
                && message.inbound().text() != null && !message.inbound().text().isEmpty()) {
            return message.inbound().text();
        }
        if (message.payload() != null) {
            return RequestBuilder.buildUntrustedTriggerRequest(message.payload());
        }
        return null;
    }
}
