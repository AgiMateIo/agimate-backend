package ru.agimate.userapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Push notifications to the user's devices (docs/decisions/push-notifications.md). There is no
 * {@code enabled} flag on purpose: sending is on when there are credentials to send with, the same
 * rule that governs the OAuth providers of this service. A flag would add the state «switched on
 * with nothing to send with», which is precisely the one that takes hours to diagnose.
 */
@Component
@ConfigurationProperties(prefix = "app.push")
@Getter
@Setter
public class PushProperties {

    /**
     * How long the transport keeps trying, when the caller does not say. An answer that arrives in
     * the evening is of no use to anyone, and the transport's own default is four weeks.
     */
    private Duration ttl = Duration.ofHours(1);

    private RuStore rustore = new RuStore();

    private Fcm fcm = new Fcm();

    /** Credentials from the RuStore console; both empty — nothing is sent. */
    @Getter
    @Setter
    public static class RuStore {

        /**
         * The project the mobile application is built against. A stand sharing production's project
         * notifies live devices, so the two are always different values.
         */
        private String projectId = "";

        private String serviceKey = "";

        public boolean isConfigured() {
            return !projectId.isBlank() && !serviceKey.isBlank();
        }

        /** Exactly one of the two filled in is a typo, not a choice — see {@code RuStorePushTransport}. */
        public boolean isHalfConfigured() {
            return projectId.isBlank() != serviceKey.isBlank();
        }
    }

    /**
     * The Firebase channel, named after the sending API rather than after the provider: the token is
     * stored as {@code FIREBASE} because that is what the SDK on the device calls it, while
     * everything on this side — the credentials, their scope, the name in the request — says
     * {@code fcm}. See {@code FirebasePushTransport}.
     */
    @Getter
    @Setter
    public static class Fcm {

        /**
         * Base64 of the service account JSON of the Firebase project — the whole configuration of
         * the channel, project id included. Empty — nothing is sent; unreadable — the boot fails.
         */
        private String credentials = "";

        public boolean isConfigured() {
            return !credentials.isBlank();
        }
    }
}
