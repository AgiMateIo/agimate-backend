package ru.agimate.controlapi.service.mcp.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.common.security.keys.AppKeyUtils;
import ru.agimate.common.security.keys.GeneratedAppKey;
import ru.agimate.controlapi.config.McpServerProperties;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.McpOAuthGrant;
import ru.agimate.controlapi.database.entities.McpOAuthToken;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.McpOAuthClientKind;
import ru.agimate.controlapi.database.enums.McpOAuthGrantStatus;
import ru.agimate.controlapi.database.enums.McpOAuthTokenKind;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthGrantRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("McpTokenService: коды и токены сервера авторизации /mcp")
class McpTokenServiceTest {

    private static final String CLIENT_ID = "https://claude.ai/oauth/mcp-client.json";
    private static final String REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback";
    private static final String VERIFIER = "dBjftJeZ4CVP-mJ92K9KO-Gb1q2hR8n8KdFlWmV0w2MaQwzx";

    private final McpOAuthGrantRepository grantRepository = mock(McpOAuthGrantRepository.class);
    private final McpOAuthTokenRepository tokenRepository = mock(McpOAuthTokenRepository.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private McpTokenService service;

    private McpOAuthGrant grant;
    private Agent agent;

    @BeforeEach
    void setUp() {
        McpServerProperties properties = new McpServerProperties();
        properties.setPublicUrl("https://api.agimate.io/control");
        McpOAuthEndpoints endpoints = new McpOAuthEndpoints(properties, "https://agimate.io");
        service = new McpTokenService(grantRepository, tokenRepository, agentRepository, endpoints);

        agent = Agent.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).name("agent").type(AgentType.MCP).build();
        grant = McpOAuthGrant.builder()
                .id(UUID.randomUUID())
                .userId(agent.getUserId())
                .agentId(agent.getId())
                .status(McpOAuthGrantStatus.PENDING)
                .clientKind(McpOAuthClientKind.CIMD)
                .clientId(CLIENT_ID)
                .clientName("Claude")
                .redirectUri(REDIRECT_URI)
                .resource(endpoints.resource())
                .codeChallenge(challenge(VERIFIER))
                .build();
        when(grantRepository.findById(grant.getId())).thenReturn(Optional.of(grant));
        when(agentRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(tokenRepository.supersede(any(), any())).thenReturn(1);
    }

    private static String challenge(String verifier) throws RuntimeException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A stored token of the grant and the string the client holds for it. */
    private String stored(McpOAuthTokenKind kind, LocalDateTime expiresAt, LocalDateTime supersededAt) {
        GeneratedAppKey key = AppKeyUtils.generate(McpOAuthTokens.prefixOf(kind));
        McpOAuthToken token = McpOAuthToken.builder()
                .id(UUID.randomUUID())
                .grantId(grant.getId())
                .kind(kind)
                .keyId(key.keyId())
                .keyHash(key.secretHash())
                .expiresAt(expiresAt)
                .supersededAt(supersededAt)
                .build();
        when(tokenRepository.findByKeyId(key.keyId())).thenReturn(Optional.of(token));
        return key.fullKey();
    }

    private static String errorOf(TokenResult result) {
        return assertInstanceOf(TokenResult.Refused.class, result).error();
    }

    @Nested
    @DisplayName("обмен кода")
    class ExchangeCode {

        private String code;

        @BeforeEach
        void code() {
            code = stored(McpOAuthTokenKind.CODE, LocalDateTime.now().plusSeconds(60), null);
        }

        @Test
        @DisplayName("верный обмен активирует грант и выдаёт access и refresh с нашими префиксами")
        void success() {
            TokenResult result = service.exchangeCode(code, VERIFIER, CLIENT_ID, REDIRECT_URI, null);

            TokenResult.Issued issued = assertInstanceOf(TokenResult.Issued.class, result);
            assertTrue(issued.accessToken().startsWith(McpOAuthTokens.ACCESS_PREFIX));
            assertTrue(issued.refreshToken().startsWith(McpOAuthTokens.REFRESH_PREFIX));
            assertEquals(3600, issued.expiresIn());
            verify(grantRepository).activate(eq(grant.getId()), any());
        }

        @Test
        @DisplayName("redirect_uri и resource не обязательны в обмене (OAuth 2.1)")
        void redirectAndResourceOptional() {
            assertInstanceOf(TokenResult.Issued.class, service.exchangeCode(code, VERIFIER, CLIENT_ID, null, null));
        }

        @Test
        @DisplayName("неизвестный код — invalid_grant")
        void unknownCode() {
            String foreign = AppKeyUtils.generate(McpOAuthTokens.CODE_PREFIX).fullKey();
            assertEquals(OAuthErrors.INVALID_GRANT, errorOf(service.exchangeCode(foreign, VERIFIER, CLIENT_ID, null, null)));
        }

        @Test
        @DisplayName("повторный обмен отзывает грант целиком")
        void replayRevokesGrant() {
            when(tokenRepository.supersede(any(), any())).thenReturn(0);

            assertEquals(OAuthErrors.INVALID_GRANT, errorOf(service.exchangeCode(code, VERIFIER, CLIENT_ID, null, null)));
            verify(grantRepository).revoke(eq(grant.getId()), any());
            verify(tokenRepository).deleteByGrantId(grant.getId());
        }

        @Test
        @DisplayName("протухший код — invalid_grant, токены не выдаются")
        void expired() {
            String expired = stored(McpOAuthTokenKind.CODE, LocalDateTime.now().minusSeconds(1), null);
            assertEquals(OAuthErrors.INVALID_GRANT, errorOf(service.exchangeCode(expired, VERIFIER, CLIENT_ID, null, null)));
            verify(grantRepository, never()).activate(any(), any());
        }

        @Test
        @DisplayName("чужой client_id — invalid_grant")
        void foreignClient() {
            assertEquals(OAuthErrors.INVALID_GRANT,
                    errorOf(service.exchangeCode(code, VERIFIER, "https://evil.example/client.json", null, null)));
        }

        @Test
        @DisplayName("присланный redirect_uri обязан совпасть")
        void redirectMismatch() {
            assertEquals(OAuthErrors.INVALID_GRANT,
                    errorOf(service.exchangeCode(code, VERIFIER, CLIENT_ID, "https://evil.example/cb", null)));
        }

        @Test
        @DisplayName("чужой resource — invalid_target")
        void foreignResource() {
            assertEquals(OAuthErrors.INVALID_TARGET,
                    errorOf(service.exchangeCode(code, VERIFIER, CLIENT_ID, null, "https://other.example/mcp")));
        }

        @Test
        @DisplayName("resource с завершающим слэшем — тот же ресурс")
        void resourceTrailingSlash() {
            assertInstanceOf(TokenResult.Issued.class,
                    service.exchangeCode(code, VERIFIER, CLIENT_ID, null, "https://API.agimate.io/control/mcp/"));
        }

        @Test
        @DisplayName("неверный code_verifier — invalid_grant")
        void wrongVerifier() {
            assertEquals(OAuthErrors.INVALID_GRANT,
                    errorOf(service.exchangeCode(code, VERIFIER.replace('d', 'e'), CLIENT_ID, null, null)));
        }

        @Test
        @DisplayName("агент выключен после согласия — invalid_grant")
        void disabledAgent() {
            agent.setEnabled(false);
            assertEquals(OAuthErrors.INVALID_GRANT, errorOf(service.exchangeCode(code, VERIFIER, CLIENT_ID, null, null)));
        }

        @Test
        @DisplayName("агент стал GENERIC после согласия — invalid_grant")
        void genericAgent() {
            agent.setType(AgentType.GENERIC);
            assertEquals(OAuthErrors.INVALID_GRANT, errorOf(service.exchangeCode(code, VERIFIER, CLIENT_ID, null, null)));
        }

        @Test
        @DisplayName("access-токен не обменивается как код")
        void wrongKind() {
            String access = stored(McpOAuthTokenKind.ACCESS, LocalDateTime.now().plusHours(1), null);
            assertEquals(OAuthErrors.INVALID_GRANT, errorOf(service.exchangeCode(access, VERIFIER, CLIENT_ID, null, null)));
        }
    }

    @Nested
    @DisplayName("refresh")
    class Refresh {

        @BeforeEach
        void active() {
            grant.setStatus(McpOAuthGrantStatus.ACTIVE);
        }

        @Test
        @DisplayName("текущий refresh ротируется и даёт новую пару")
        void rotates() {
            String refresh = stored(McpOAuthTokenKind.REFRESH, LocalDateTime.now().plusDays(30), null);

            assertInstanceOf(TokenResult.Issued.class, service.refresh(refresh, CLIENT_ID));
            verify(tokenRepository).supersede(any(), any());
        }

        @Test
        @DisplayName("предыдущий refresh в окне ретрая — новая пара, грант жив")
        void retryWithinWindow() {
            String refresh = stored(McpOAuthTokenKind.REFRESH, LocalDateTime.now().plusDays(30),
                    LocalDateTime.now().minusSeconds(10));

            assertInstanceOf(TokenResult.Issued.class, service.refresh(refresh, CLIENT_ID));
            verify(grantRepository, never()).revoke(any(), any());
        }

        @Test
        @DisplayName("проигравший гонку двух refresh — тоже ретрай, а не атака")
        void lostRace() {
            String refresh = stored(McpOAuthTokenKind.REFRESH, LocalDateTime.now().plusDays(30), null);
            when(tokenRepository.supersede(any(), any())).thenReturn(0);

            assertInstanceOf(TokenResult.Issued.class, service.refresh(refresh, CLIENT_ID));
            verify(grantRepository, never()).revoke(any(), any());
        }

        @Test
        @DisplayName("предыдущий refresh за окном — повтор украденного, грант отозван")
        void replayAfterWindow() {
            String refresh = stored(McpOAuthTokenKind.REFRESH, LocalDateTime.now().plusDays(30),
                    LocalDateTime.now().minusMinutes(5));

            assertEquals(OAuthErrors.INVALID_GRANT, errorOf(service.refresh(refresh, CLIENT_ID)));
            verify(grantRepository).revoke(eq(grant.getId()), any());
        }

        @Test
        @DisplayName("протухший refresh — invalid_grant")
        void expired() {
            String refresh = stored(McpOAuthTokenKind.REFRESH, LocalDateTime.now().minusSeconds(1), null);
            assertEquals(OAuthErrors.INVALID_GRANT, errorOf(service.refresh(refresh, CLIENT_ID)));
        }

        @Test
        @DisplayName("отозванный грант — invalid_grant")
        void revokedGrant() {
            grant.setStatus(McpOAuthGrantStatus.REVOKED);
            String refresh = stored(McpOAuthTokenKind.REFRESH, LocalDateTime.now().plusDays(30), null);
            assertEquals(OAuthErrors.INVALID_GRANT, errorOf(service.refresh(refresh, CLIENT_ID)));
        }

        @Test
        @DisplayName("client_id не обязателен, но присланный обязан совпасть")
        void clientId() {
            String refresh = stored(McpOAuthTokenKind.REFRESH, LocalDateTime.now().plusDays(30), null);
            assertEquals(OAuthErrors.INVALID_GRANT, errorOf(service.refresh(refresh, "https://evil.example/c.json")));
            assertInstanceOf(TokenResult.Issued.class, service.refresh(refresh, null));
        }
    }

    @Nested
    @DisplayName("access-токен на /mcp")
    class Authenticate {

        @BeforeEach
        void active() {
            grant.setStatus(McpOAuthGrantStatus.ACTIVE);
        }

        @Test
        @DisplayName("живой токен — агент гранта; last_used_at пишется не чаще раза в минуту")
        void valid() {
            String access = stored(McpOAuthTokenKind.ACCESS, LocalDateTime.now().plusHours(1), null);

            Optional<McpAccess> result = service.authenticate(access);
            assertEquals(agent.getId(), result.orElseThrow().agent().getId());
            verify(grantRepository).touch(eq(grant.getId()), any(), any());

            grant.setLastUsedAt(LocalDateTime.now());
            service.authenticate(access);
            verify(grantRepository, times(1)).touch(any(), any(), any());
        }

        @Test
        @DisplayName("протухший токен не проходит")
        void expired() {
            String access = stored(McpOAuthTokenKind.ACCESS, LocalDateTime.now().minusSeconds(1), null);
            assertTrue(service.authenticate(access).isEmpty());
        }

        @Test
        @DisplayName("ротированный access живёт до своего срока: отзыв — это статус гранта")
        void supersededStillValid() {
            String access = stored(McpOAuthTokenKind.ACCESS, LocalDateTime.now().plusMinutes(30),
                    LocalDateTime.now().minusMinutes(10));
            assertTrue(service.authenticate(access).isPresent());
        }

        @Test
        @DisplayName("отозванный грант — токен мёртв сразу")
        void revokedGrant() {
            grant.setStatus(McpOAuthGrantStatus.REVOKED);
            String access = stored(McpOAuthTokenKind.ACCESS, LocalDateTime.now().plusHours(1), null);
            assertTrue(service.authenticate(access).isEmpty());
        }

        @Test
        @DisplayName("выключенный или удалённый агент — токен не проходит")
        void agentGone() {
            String access = stored(McpOAuthTokenKind.ACCESS, LocalDateTime.now().plusHours(1), null);
            agent.setEnabled(false);
            assertTrue(service.authenticate(access).isEmpty());

            agent.setEnabled(true);
            when(agentRepository.findById(agent.getId())).thenReturn(Optional.empty());
            assertTrue(service.authenticate(access).isEmpty());
        }

        @Test
        @DisplayName("refresh-токен на /mcp не проходит")
        void refreshIsNotAccess() {
            String refresh = stored(McpOAuthTokenKind.REFRESH, LocalDateTime.now().plusDays(30), null);
            assertTrue(service.authenticate(refresh).isEmpty());
        }

        @Test
        @DisplayName("подделанный секрет при верном key_id не проходит")
        void forgedSecret() {
            String access = stored(McpOAuthTokenKind.ACCESS, LocalDateTime.now().plusHours(1), null);
            String forged = AppKeyUtils.generate(McpOAuthTokens.ACCESS_PREFIX).fullKey();
            String spliced = access.substring(0, 16) + forged.substring(16);
            assertTrue(service.authenticate(spliced).isEmpty());
        }
    }

    @Test
    @DisplayName("revoke любым токеном гранта отзывает грант; чужой client_id — молча ничего")
    void revoke() {
        grant.setStatus(McpOAuthGrantStatus.ACTIVE);
        String refresh = stored(McpOAuthTokenKind.REFRESH, LocalDateTime.now().plusDays(30), null);

        service.revoke(refresh, "https://evil.example/c.json");
        verify(grantRepository, never()).revoke(any(), any());

        service.revoke(refresh, CLIENT_ID);
        verify(grantRepository).revoke(eq(grant.getId()), any());
    }
}
