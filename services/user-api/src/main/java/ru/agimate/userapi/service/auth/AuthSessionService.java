package ru.agimate.userapi.service.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.common.security.jwt.JwtProperties;
import ru.agimate.common.security.jwt.JwtService;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.AuthSession;
import ru.agimate.userapi.database.entities.SessionRevokeReason;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.repositories.AuthSessionRepository;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.push.PushSubscriptionService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The registry of live sign-ins, and the only place that decides whether a refresh token is still
 * good for anything. Holding it in the database rather than in memory is what makes a logout an
 * actual revocation across replicas and restarts, which in turn is what allows a native session to
 * last for months. See docs/decisions/native-auth.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthSessionService {

    /**
     * How long the id a rotation replaced still buys the pair that replaced it. A refresh whose
     * response never arrived — a tunnel, a switched network, a killed process — is the common case
     * on a phone, and answering it with a logout would be a bug the user experiences and we never
     * see. Long enough for a retry, short enough that a stolen token cannot wait around for it.
     */
    static final int RETRY_WINDOW_SECONDS = 60;

    private static final int DEVICE_LABEL_MAX_LENGTH = 200;

    private final AuthSessionRepository sessionRepository;
    private final UserService userService;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final PushSubscriptionService pushSubscriptionService;

    @Transactional
    public IssuedTokens open(UserEntity user, AuthClient client, String deviceLabel) {
        LocalDateTime now = LocalDateTime.now();
        String jti = UUID.randomUUID().toString();

        AuthSession session = new AuthSession();
        session.setUserId(user.getId());
        session.setClient(client);
        session.setDeviceLabel(truncateLabel(deviceLabel));
        session.setCurrentJti(jti);
        session.setLastSeenAt(now);
        session.setExpiresAt(now.plusSeconds(refreshLifetime(client)));

        AuthSession saved = sessionRepository.save(session);
        log.debug("opened {} session {} for user {}", client, saved.getId(), user.getId());

        return mint(user, saved.getId(), client, jti);
    }

    /**
     * The whole refresh decision: which generation the id belongs to, whether that is a rotation, a
     * retry or a theft, and the pair that comes out of it.
     *
     * @param expectedClient the client the token arrived as — a refresh token issued to a browser
     *                       must not be redeemable for tokens in a response body, and the other way
     *                       round
     */
    @Transactional(noRollbackFor = ForbiddenStatusException.class)
    public IssuedTokens refresh(String jti, AuthClient expectedClient) {
        LocalDateTime now = LocalDateTime.now();
        AuthSession session = requireUsableSession(jti, expectedClient, now);
        UserEntity user = requireUser(session.getUserId());

        LocalDateTime expiresAt = now.plusSeconds(refreshLifetime(session.getClient()));

        if (jti.equals(session.getCurrentJti())) {
            String newJti = UUID.randomUUID().toString();
            requireWon(sessionRepository.rotate(session.getId(), jti, newJti, now, expiresAt));
            return mint(user, session.getId(), session.getClient(), newJti);
        }

        // The id was the one rotation replaced, and it is inside the retry window: this is a client
        // asking again for an answer it never received. It is served the generation it should have
        // got, and the session is not pushed forward — a second lost response must be survivable too.
        requireWon(sessionRepository.touch(session.getId(), session.getCurrentJti(), now, expiresAt));
        log.debug("re-served the current pair of session {} to a retried refresh", session.getId());
        return mint(user, session.getId(), session.getClient(), session.getCurrentJti());
    }

    @Transactional(noRollbackFor = ForbiddenStatusException.class)
    public void closeByJti(String jti, AuthClient expectedClient) {
        LocalDateTime now = LocalDateTime.now();
        AuthSession session = requireUsableSession(jti, expectedClient, now);
        revokeSession(session.getId(), SessionRevokeReason.LOGOUT, now);
    }

    public List<AuthSession> listActive(UUID userId) {
        return sessionRepository.findActive(userId, LocalDateTime.now());
    }

    /** Dropping a device from one's own list; someone else's session is simply not there to drop. */
    @Transactional
    public void revokeOwn(UUID userId, UUID sessionId) {
        AuthSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new NotFoundStatusException("Session not found"));
        revokeSession(session.getId(), SessionRevokeReason.REVOKED, LocalDateTime.now());
    }

    @Transactional
    public void revoke(UUID sessionId, SessionRevokeReason reason) {
        revokeSession(sessionId, reason, LocalDateTime.now());
    }

    /**
     * The single way a session ends, whatever ended it — and the only place the device stops being
     * notified. The subscription is deleted rather than left to the foreign key: revoking stamps
     * {@code revoked_at} and keeps the row, so the cascade would not fire until the sweep deletes
     * the expired session, and a lost phone would go on receiving previews until then.
     *
     * <p>Sessions swept by {@link AuthCleanupTask} need nothing here: deleting the row is exactly
     * what the cascade is for.
     */
    private void revokeSession(UUID sessionId, SessionRevokeReason reason, LocalDateTime now) {
        sessionRepository.revoke(sessionId, reason, now);
        pushSubscriptionService.dropByAuthSession(sessionId);
    }

    /**
     * Everything that can disqualify a refresh id before its generation is even considered. A
     * session unknown here is a session that cannot be revoked, so an unknown id is refused rather
     * than adopted — including the tokens that predate this registry, whose holders sign in again
     * once.
     *
     * <p>Refusal is thrown as {@link ForbiddenStatusException}, which the callers exclude from
     * rollback: detecting a replay both refuses the request and revokes the session, and the two
     * must not undo each other.
     */
    private AuthSession requireUsableSession(String jti, AuthClient expectedClient, LocalDateTime now) {
        AuthSession session = sessionRepository.findByJti(jti)
                .orElseThrow(() -> new ForbiddenStatusException("Unknown or expired refresh token"));

        if (session.getClient() != expectedClient) {
            log.warn("refresh token of a {} session presented as {} — session {}",
                    session.getClient(), expectedClient, session.getId());
            throw new ForbiddenStatusException("Refresh token does not belong to this client");
        }
        if (session.getRevokedAt() != null) {
            throw new ForbiddenStatusException("Session revoked");
        }
        if (session.getExpiresAt().isBefore(now)) {
            throw new ForbiddenStatusException("Session expired");
        }
        if (!jti.equals(session.getCurrentJti())) {
            requireWithinRetryWindow(session, now);
        }
        return session;
    }

    /**
     * Past the window a superseded id is not a retry — the token exists in two places, and the only
     * safe reading is that one of them is not its owner. RFC 9700 answers that by ending the
     * session, so that a copy cannot quietly outlive the theft.
     */
    private void requireWithinRetryWindow(AuthSession session, LocalDateTime now) {
        LocalDateTime deadline = session.getRotatedAt() == null
                ? null
                : session.getRotatedAt().plusSeconds(RETRY_WINDOW_SECONDS);

        if (deadline == null || deadline.isBefore(now)) {
            log.warn("replayed refresh token on session {} — revoking", session.getId());
            revokeSession(session.getId(), SessionRevokeReason.REPLAY, now);
            throw new ForbiddenStatusException("Refresh token was already rotated — session revoked");
        }
    }

    /**
     * A conditional write that matched nothing means a parallel refresh got there first. The client
     * is told to retry rather than handed a second pair: only one of them can be the current
     * generation, and the loser's pair would be dead the moment it was stored.
     */
    private void requireWon(int updated) {
        if (updated == 0) {
            throw new ConflictStatusException("Concurrent refresh — retry with the current token");
        }
    }

    private UserEntity requireUser(UUID userId) {
        return userService.findById(userId)
                .orElseThrow(() -> new ForbiddenStatusException("User no longer exists"));
    }

    private IssuedTokens mint(UserEntity user, UUID sessionId, AuthClient client, String jti) {
        AgimateUserPrincipal principal = AgimateUserPrincipal.fromUser(
                user.getId().toString(), user.getRole(), sessionId);
        int accessLifetime = accessLifetime(client);

        return new IssuedTokens(
                sessionId,
                jwtService.generateAccessToken(principal, accessLifetime),
                accessLifetime,
                jwtService.generateRefreshToken(principal, jti, refreshLifetime(client)),
                jti);
    }

    private int accessLifetime(AuthClient client) {
        return client == AuthClient.NATIVE
                ? jwtProperties.getNativeAccessExpiration()
                : jwtProperties.getAccessExpiration();
    }

    private int refreshLifetime(AuthClient client) {
        return client == AuthClient.NATIVE
                ? jwtProperties.getNativeRefreshExpiration()
                : jwtProperties.getRefreshExpiration();
    }

    /** The label is shown back to its owner and trusted for nothing, so a long one is cut, not refused. */
    private String truncateLabel(String deviceLabel) {
        if (deviceLabel == null || deviceLabel.isBlank()) {
            return null;
        }
        return deviceLabel.length() <= DEVICE_LABEL_MAX_LENGTH
                ? deviceLabel
                : deviceLabel.substring(0, DEVICE_LABEL_MAX_LENGTH);
    }
}
