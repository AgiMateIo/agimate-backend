package ru.agimate.controlapi.realtime;

import lombok.experimental.UtilityClass;

import java.util.UUID;

/**
 * The only place a Centrifugo channel name is built — by the publisher and by the token endpoints
 * alike, so a subscription always names the channel events go to. The namespaces must match
 * {@code ops/templates/centrifugo.config.yaml}.
 */
@UtilityClass
public class RealtimeChannels {

    /** The user's own channel: every notification the applications show. */
    public static String user(UUID userId) {
        return "user:" + userId;
    }

    /** Commands for an external agent: triggers and tool results. */
    public static String agent(UUID agentId) {
        return "agent:" + agentId;
    }

    /** Tool calls for a connected application. */
    public static String app(UUID appId) {
        return "app:" + appId;
    }

    /**
     * One conversation of the webchat.
     *
     * @deprecated the conversation's messages go to {@link #user}; this channel stays until the web
     *             and Android clients have moved over (docs/decisions/realtime-notifications.md)
     */
    @Deprecated
    public static String webchat(UUID sessionId) {
        return "webchat:" + sessionId;
    }
}
