package ru.agimate.controlapi.service.channel.handler;

import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.service.channel.handler.dto.*;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Channel handler: the multimodal «codec» between a connector and an agent.
 *
 * <p>Every implementation is a separate bean with a unique {@link #name()}, and that name is written
 * into {@code channels.channel_handler}. Behaviour is set by code, while a particular channel's data
 * lives in {@link ChannelConfig} (connectorCode + connectionId + settings).
 *
 * <p>A tool call returned by a handler from {@link #handleOutput} is executed by the caller through
 * the regular tool-calling subsystem, so the ABAC policies are respected. {@link #listOfTriggers} and
 * {@link #listOfTools} exist so that creating a channel can generate the corresponding
 * {@code AgentConnectionPolicy} rules ({@code PolicyKind.TRIGGER}/{@code TOOL}).
 *
 * <p>Inside the layer throw only {@code ConnectorException}.
 */
public interface ChannelHandler {

    /** System-wide unique name of the handler; written into {@code channels.channel_handler}. */
    String name();

    /**
     * JSON Schema (object) of the {@code config} fields — so the UI can render a form with
     * descriptions and the user fills the settings in correctly (the filtering parameters included).
     */
    default Map<String, Object> getConfigFields() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    /**
     * Incoming triggers a channel with this {@code config} handles. They are bound to the channel's
     * {@link ChannelConfig#connectorCode()}/{@link ChannelConfig#connectionId()} when the
     * {@code AgentConnectionPolicy} rules of kind {@code TRIGGER} are generated.
     */
    List<TriggerDefinition> listOfTriggers(ChannelConfig config);

    /**
     * Tools (connector+connectionId+name) the handler may call on outbound — for generating the
     * {@code AgentConnectionPolicy} rules of kind {@code TOOL}. The reply target may differ from the
     * channel's connector/connectionId.
     */
    List<ToolDefinition> listOfTools(ChannelConfig config);

    /** Validation of {@code config} when a channel is created or updated; throws {@code ConnectorException} on error. */
    void validateConfig(ChannelConfig config);

    /** Reduces a trigger to the unified {@link InboundMessage}; {@code empty} — the trigger was filtered out or skipped. */
    Optional<InboundMessage> handleInput(ChannelConfig config, Trigger trigger);

    /**
     * Whether the agent's intermediate output (progress) should be delivered into this channel.
     * {@code true} → the router fills the progress role in {@code Channels} with the same channel, and
     * the worker sends progress lines through {@link #handleOutput} (with {@code stream="progress"})
     * on a par with the final answer.
     */
    default boolean deliverProgress(ChannelConfig config) {
        return false;
    }

    /**
     * Whether the channel brings its own connector's tools into a DIALOGUE run's context, bypassing
     * the skill gate ({@code requiredConnectors}). {@code true} → {@code RunContextService} mixes in
     * the prompt channel's connector tools regardless of the agent's skills. The semantics is «the
     * channel brings tools»: the IDE connector hands over fs/terminal tools for as long as the
     * conversation comes from the IDE, with no manual skill setup.
     */
    default boolean contributesPromptTools() {
        return false;
    }

    /**
     * Whether the handler supports attachments in an outgoing answer ({@link OutboundMessage#parts()}).
     * {@code true} → {@code RunContextService} explains the attach convention
     * ({@code [[attach:agf_…]]}) to the agent, and {@link #handleOutput} is obliged to deliver the
     * parts. {@code false} → parts are silently not delivered (the markers are cut from the text
     * regardless).
     */
    default boolean supportsOutboundAttachments() {
        return false;
    }

    /**
     * Maps the model's answer onto the channel's actions. The handler either delivers it itself
     * (webchat/acp — a push into the live connection) and returns an empty list, or returns
     * {@link ToolCallRequest}s — which the caller executes (idempotency + ABAC + dispatch after the
     * log is committed). The idempotency key is {@link OutboundDispatch#messageId()} (with a
     * deterministic suffix for the additional requests); the answer's address comes from
     * {@link OutboundDispatch#replyContext()}. There are no tool side effects inside the handler —
     * that breaks the bean cycle with the inbound router and keeps the dispatch outside transactions.
     */
    List<ToolCallRequest> handleOutput(ChannelConfig config, OutboundMessage outbound,
                                       OutboundDispatch dispatch);
}
