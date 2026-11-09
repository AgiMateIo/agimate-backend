package ru.agimate.controlapi.service.channel.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.PageRequests;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.controller.manage.dto.session.SessionMessageResponse;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.WebchatMessage;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundDispatch;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.channel.handler.dto.ToolDefinition;
import ru.agimate.controlapi.service.channel.handler.dto.TriggerDefinition;
import ru.agimate.controlapi.service.channel.handler.MediaStubs;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.webchat.WebchatAttachment;
import ru.agimate.controlapi.service.webchat.WebchatMessagePublisher;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The code handler for webchat channels: inbound arrives as ready text from {@code /manage/webchat}
 * (the trigger {@code message_received}), and outbound is delivered without tools — a UI history row
 * ({@code webchat_messages}) plus a Centrifugo event through {@link WebchatMessagePublisher}. The
 * only handler with {@code deliverProgress=true}: the agent's intermediate output is streamed into
 * the chat. It is also the only channel that owns its message log ({@link ChannelMessageLog}):
 * {@code webchat_messages} is the conversation's store, so the platform does not write the generic
 * projection {@code channel_session_messages} for it.
 */
@Component
@RequiredArgsConstructor
public class WebchatChannelHandler implements ChannelHandler, ChannelMessageLog {

    public static final String NAME = "webchat";
    /** Code of the webchat connector — the single source of truth (for channel connectors it equals {@link #NAME}). */
    public static final String CONNECTOR_CODE = NAME;
    /** Trigger for an incoming message from the web chat — the single source of truth for the connector and the orchestrator. */
    public static final String TRIGGER_MESSAGE_RECEIVED = "message_received";

    private static final String STREAM_ANSWER = "answer";

    private final ChannelRepository channelRepository;
    private final WebchatMessagePublisher webchatMessagePublisher;
    private final WebchatMessageRepository webchatMessageRepository;
    private final SignedFileUrlService signedFileUrlService;

    @Override
    public String name() {
        return NAME;
    }

    /** The UI log is this channel's message store — the platform's generic projection is not written for it. */
    @Override
    public Page<SessionMessageResponse> listMessages(UUID userId, UUID sessionId, int page, int size) {
        // created_at comes from CURRENT_TIMESTAMP, which is the same for every row a run writes in one
        // transaction — the id (uuidv7) is what keeps a page from repeating or skipping one of them.
        PageRequest pageRequest = PageRequests.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return webchatMessageRepository.findBySessionId(sessionId, pageRequest)
                .map(message -> SessionMessageResponse.from(message, attachments(message)));
    }

    /** Stored parts plus a fresh signed link to the contents (expired links are never stored). */
    private List<WebchatAttachment> attachments(WebchatMessage message) {
        return WebchatAttachment.fromStored(message.getParts(), message.getUserId(), signedFileUrlService::issue);
    }

    @Override
    public List<TriggerDefinition> listOfTriggers(ChannelConfig config) {
        return List.of(new TriggerDefinition(TRIGGER_MESSAGE_RECEIVED));
    }

    @Override
    public List<ToolDefinition> listOfTools(ChannelConfig config) {
        return List.of();
    }

    @Override
    public void validateConfig(ChannelConfig config) {
        if (!CONNECTOR_CODE.equals(config.connectorCode())) {
            throw new ConnectorException("webchat channel handler requires connectorCode='webchat'");
        }
    }

    @Override
    public Optional<InboundMessage> handleInput(ChannelConfig config, Trigger trigger) {
        Map<String, Object> data = trigger.data() != null ? trigger.data() : Map.of();
        List<Part> parts = parts(data.get("parts"));
        Object textRaw = data.get("text");
        String userText = textRaw != null ? textRaw.toString() : "";
        if (userText.isBlank() && parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new InboundMessage(MediaStubs.withStubs(userText, parts), parts));
    }

    /** Attachments from the trigger's data ({@code [{type,fileId,mime,size,name}]}) — already validated when sent. */
    @SuppressWarnings("unchecked")
    private static List<Part> parts(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Part> parts = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) map;
            Object fileId = m.get("fileId");
            if (fileId == null || fileId.toString().isBlank()) {
                continue;
            }
            String mime = m.get("mime") != null ? m.get("mime").toString() : null;
            String type = m.get("type") != null ? m.get("type").toString() : Part.typeForMime(mime);
            long size = m.get("size") instanceof Number n ? n.longValue() : 0L;
            Map<String, Object> meta = m.get("name") != null ? Map.of("name", m.get("name")) : Map.of();
            parts.add(new Part(type, fileId.toString(), Part.storedVersion(m), mime, size, meta));
        }
        return parts;
    }

    @Override
    public boolean deliverProgress(ChannelConfig config) {
        return true;
    }

    /** The answer's attachments are delivered as parts of the webchat message (the frontend renders images). */
    @Override
    public boolean supportsOutboundAttachments() {
        return true;
    }

    @Override
    public List<ToolCallRequest> handleOutput(ChannelConfig config, OutboundMessage outbound,
                                                  OutboundDispatch dispatch) {
        Channel channel = channelRepository.findByIdAndDeletedAtIsNull(dispatch.channelId())
                .orElseThrow(() -> new ConnectorException("webchat channel not found: " + dispatch.channelId()));
        String stream = dispatch.stream() != null ? dispatch.stream() : STREAM_ANSWER;
        webchatMessagePublisher.record(
                channel.getUserId(), config.agentId(), channel.getId(), dispatch.sessionId(),
                WebchatMessageDirection.AGENT, stream, dispatch.messageId(), outbound.text(),
                outbound.parts());
        return List.of();
    }
}
