package ru.agimate.controlapi.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.controller.manage.dto.session.SessionResponse;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.realtime.RealtimeEvent.AgentDelivery;
import ru.agimate.controlapi.realtime.RealtimeEvent.AgentRequestChanged;
import ru.agimate.controlapi.realtime.RealtimeEvent.AppToolCall;
import ru.agimate.controlapi.realtime.RealtimeEvent.BoardTaskChanged;
import ru.agimate.controlapi.realtime.RealtimeEvent.SessionChanged;
import ru.agimate.controlapi.realtime.RealtimeEvent.WebchatMessageRecorded;
import ru.agimate.controlapi.realtime.dto.CentrifugoMessage;
import ru.agimate.controlapi.realtime.dto.WebchatActivityPayload;
import ru.agimate.controlapi.realtime.dto.WebchatMessagePayload;
import ru.agimate.controlapi.service.channel.handler.WebchatChannelHandler;
import ru.agimate.controlapi.service.session.SessionRows;
import ru.agimate.controlapi.service.team.AgentRequestQueryService;
import ru.agimate.controlapi.service.webchat.ContactRows;
import ru.agimate.controlapi.service.webchat.WebchatAttachment;
import ru.agimate.controlapi.service.webchat.WebchatMessagePublisher;
import ru.agimate.controlapi.service.webchat.WebchatPreviews;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The whole channel contract on one screen: which channel, type, tags and payload every
 * {@link RealtimeEvent} becomes (docs/contracts/centrifugo-channels.md). Called after the commit, so
 * a row read here is the committed one. The interface is sealed — an event without a branch does not
 * compile.
 */
@Component
@RequiredArgsConstructor
public class RealtimeMessages {

    public static final String SESSION_CREATED = "session.created";
    public static final String SESSION_UPDATED = "session.updated";
    public static final String WEBCHAT_AGENT_UPDATED = "webchat.agent.updated";
    public static final String WEBCHAT_MESSAGE = "webchat.message";
    public static final String TOOL_CALL = "toolCall";
    /** @deprecated the type in {@code webchat:{sessionId}}, which goes with that channel */
    @Deprecated
    public static final String WEBCHAT_MESSAGE_LEGACY = "webchat_message";
    /** @deprecated superseded by {@link #WEBCHAT_AGENT_UPDATED} */
    @Deprecated
    public static final String WEBCHAT_ACTIVITY = "webchat_activity";

    private final AgentSessionRepository agentSessionRepository;
    private final SessionRows sessionRows;
    private final ContactRows contactRows;
    private final AgentRequestQueryService agentRequestQueryService;
    private final SignedFileUrlService signedFileUrlService;

    /** One publication: {@code data} goes to the channel as is. */
    public record Message(String channel, Object data, Map<String, String> tags) {

        static Message of(String channel, String type, Object payload, Map<String, String> tags) {
            return new Message(channel, new CentrifugoMessage<>(type, payload), tags);
        }
    }

    public List<Message> render(RealtimeEvent event) {
        return switch (event) {
            case SessionChanged e -> session(e);
            case WebchatMessageRecorded e -> webchatMessage(e);
            case BoardTaskChanged e -> List.of(Message.of(RealtimeChannels.user(e.userId()), e.type(), e.task(),
                    Map.of("entity", "board.task", "boardId", e.boardId().toString())));
            case AgentRequestChanged e -> agentRequestQueryService.forEvent(e.threadId())
                    .map(request -> List.of(Message.of(RealtimeChannels.user(request.userId()), e.type(),
                            request.request(),
                            Map.of("entity", "agent.request", "teamId", request.teamId().toString()))))
                    .orElse(List.of());
            // The agent's own envelope, no wrapper: an external agent parses AgentMessage directly.
            case AgentDelivery e -> List.of(new Message(RealtimeChannels.agent(e.agentId()), e.message(), Map.of()));
            case AppToolCall e -> List.of(Message.of(RealtimeChannels.app(e.appId()), TOOL_CALL, e.payload(), Map.of()));
        };
    }

    /** The session row, and for a webchat conversation its agent's contact row — the unread sum across chats. */
    private List<Message> session(SessionChanged e) {
        AgentSession session = agentSessionRepository.findById(e.sessionId()).orElse(null);
        if (session == null) {
            return List.of();
        }
        String channel = RealtimeChannels.user(session.getUserId());
        String agentId = session.getAgentId().toString();
        SessionResponse row = sessionRows.of(session);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.of(channel, e.created() ? SESSION_CREATED : SESSION_UPDATED, row,
                Map.of("entity", "session", "agentId", agentId)));
        if (WebchatChannelHandler.CONNECTOR_CODE.equals(session.getConnectorCode())) {
            contactRows.find(session.getAgentId()).ifPresent(contact -> messages.add(
                    Message.of(channel, WEBCHAT_AGENT_UPDATED, contact,
                            Map.of("entity", "webchat.agent", "agentId", agentId))));
        }
        return messages;
    }

    /** Links to attachments are signed here, at sending: a stored one would have expired. */
    @SuppressWarnings("deprecation")
    private List<Message> webchatMessage(WebchatMessageRecorded e) {
        WebchatMessagePayload payload = new WebchatMessagePayload(e.sessionId(), e.channelId(), e.agentId(),
                e.messageId(), e.direction(), e.stream(), e.text(),
                WebchatAttachment.fromStored(e.parts(), e.userId(), signedFileUrlService::issue),
                e.createdAt());
        String user = RealtimeChannels.user(e.userId());
        List<Message> messages = new ArrayList<>();
        messages.add(Message.of(user, WEBCHAT_MESSAGE, payload, Map.of("entity", "webchat.message",
                "agentId", e.agentId().toString(), "sessionId", e.sessionId().toString())));
        // Until the web and Android clients move over: the conversation's own channel and the badge.
        messages.add(Message.of(RealtimeChannels.webchat(e.sessionId()), WEBCHAT_MESSAGE_LEGACY, payload, Map.of()));
        if ("AGENT".equals(e.direction()) && !WebchatMessagePublisher.STREAM_PROGRESS.equals(e.stream())) {
            messages.add(Message.of(user, WEBCHAT_ACTIVITY,
                    new WebchatActivityPayload(e.agentId(), e.sessionId(), e.messageId(), e.stream(),
                            WebchatPreviews.shorten(e.text()), e.createdAt()),
                    Map.of("entity", "webchat.message", "agentId", e.agentId().toString())));
        }
        return messages;
    }
}
