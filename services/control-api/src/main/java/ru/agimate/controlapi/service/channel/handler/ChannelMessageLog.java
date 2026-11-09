package ru.agimate.controlapi.service.channel.handler;

import org.springframework.data.domain.Page;
import ru.agimate.controlapi.controller.manage.dto.session.SessionMessageResponse;

import java.util.UUID;

/**
 * A channel handler's own message log: the plugin stores the conversation itself, so the platform
 * does not write its generic projection ({@code channel_session_messages}) for that channel.
 * Webchat is the only implementation today — {@code webchat_messages} is its message store; a
 * channel without this capability (Telegram, ACP, any future messenger) is served by the generic
 * projection. Resolved through {@code channels.channel_handler}, not the connector code — see
 * {@link ChannelMessageLogResolver}.
 */
public interface ChannelMessageLog {

    /**
     * The session's history, newest first (the frontend reverses it when rendering). Ownership is
     * checked by the caller — the plugin only reads its own store.
     */
    Page<SessionMessageResponse> listMessages(UUID userId, UUID sessionId, int page, int size);
}
