package ru.agimate.userapi.service.push.universal;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.database.entities.PushProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * The Firebase channel of the universal sending service ({@link UniversalPushTransport}), the second
 * way to a device and the only one for a phone without RuStore installed — there the RuStore channel
 * is not «worse», it is absent, because its messages travel through the RuStore application itself.
 *
 * <p>Where RuStore has a service key that never changes, Firebase has none at all: the
 * {@code auth_token} of this channel is an OAuth2 access token good for about an hour, minted from
 * the service account of the Firebase project. Hence the one piece of live state in the push layer,
 * and hence {@link #authToken()} asking for it on every send: a token taken once at startup makes a
 * channel that works for an hour after every deploy and then goes quiet without saying so.
 *
 * <p>The project id is read from that same service account JSON rather than configured beside it —
 * two spellings of one truth drift apart, and this one drifts into notifying somebody else's devices.
 */
@Slf4j
@Component
public class FirebasePushTransport extends UniversalPushTransport {

    private static final String MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    /** Null — this installation has no Firebase credentials and the channel is off. */
    private final GoogleCredentials credentials;
    private final String projectId;

    public FirebasePushTransport(PushProperties pushProperties) {
        super(pushProperties);
        PushProperties.Fcm fcm = pushProperties.getFcm();
        if (!fcm.isConfigured()) {
            log.info("FCM push is not configured — subscriptions are stored, notifications are not sent");
            this.credentials = null;
            this.projectId = "";
            return;
        }

        ServiceAccountCredentials account = readServiceAccount(fcm.getCredentials());
        if (account.getProjectId() == null || account.getProjectId().isBlank()) {
            throw new IllegalStateException("app.push.fcm.credentials: the service account JSON has no project_id");
        }
        this.projectId = account.getProjectId();
        this.credentials = account.createScoped(MESSAGING_SCOPE);
    }

    /** Ready-made credentials, for the tests: minting a real access token is a trip to Google. */
    FirebasePushTransport(PushProperties pushProperties, GoogleCredentials credentials, String projectId) {
        super(pushProperties);
        this.credentials = credentials;
        this.projectId = projectId;
    }

    @Override
    public PushProvider provider() {
        return PushProvider.FIREBASE;
    }

    @Override
    public boolean isConfigured() {
        return credentials != null;
    }

    /** The vendor's own disagreement: the SDK on the device calls this channel {@code firebase}. */
    @Override
    protected String wireName() {
        return "fcm";
    }

    @Override
    protected String projectId() {
        return projectId;
    }

    @Override
    protected String authToken() {
        try {
            credentials.refreshIfExpired();
        } catch (IOException e) {
            // Logged here and not left to the caller: upstream only sees «the send failed», and the
            // failure this channel has that the other does not is precisely this one. The reason
            // comes from Google's token endpoint — a revoked key, a clock out of step — and carries
            // nothing of ours.
            log.warn("FCM access token could not be refreshed: {}", e.getMessage());
            throw new IllegalStateException("FCM access token could not be refreshed", e);
        }
        return credentials.getAccessToken().getTokenValue();
    }

    /**
     * Unreadable credentials fail the boot: an empty value is how «this installation does not send»
     * is spelled, so anything else that cannot be read is a typo in the deployment, and it would
     * otherwise surface as notifications that never arrive.
     */
    private static ServiceAccountCredentials readServiceAccount(String encoded) {
        try {
            byte[] json = Base64.getMimeDecoder().decode(encoded);
            return ServiceAccountCredentials.fromStream(new ByteArrayInputStream(json));
        } catch (IllegalArgumentException | IOException e) {
            throw new IllegalStateException(
                    "app.push.fcm.credentials must be base64 of the Firebase service account JSON", e);
        }
    }
}
