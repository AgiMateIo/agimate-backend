package ru.agimate.controlapi.service.mcp.oauth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.config.McpServerProperties;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.McpOAuthGrant;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.McpOAuthClientKind;
import ru.agimate.controlapi.database.enums.McpOAuthGrantStatus;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthGrantRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("McpAuthorizationService: проверка запроса, согласие и отказ")
class McpAuthorizationServiceTest {

    private static final String CLIENT_ID = "https://claude.ai/oauth/mcp-client.json";
    private static final String WEB_REDIRECT = "https://claude.ai/api/mcp/auth_callback";
    private static final String LOOPBACK_REDIRECT = "http://localhost/callback";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String ISSUER = "https://api.agimate.io/control";

    private final CimdClientResolver resolver = mock(CimdClientResolver.class);
    private final McpTokenService tokenService = mock(McpTokenService.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final McpOAuthGrantRepository grantRepository = mock(McpOAuthGrantRepository.class);
    private McpAuthorizationService service;

    private final UUID userId = UUID.randomUUID();
    private Agent agent;

    @BeforeEach
    void setUp() {
        McpServerProperties properties = new McpServerProperties();
        properties.setPublicUrl(ISSUER);
        service = new McpAuthorizationService(resolver, new McpOAuthEndpoints(properties, "https://agimate.io"),
                tokenService, agentRepository, grantRepository);

        when(resolver.resolve(CLIENT_ID)).thenReturn(new OAuthClient(CLIENT_ID, McpOAuthClientKind.CIMD, "Claude",
                "claude.ai", List.of(WEB_REDIRECT, LOOPBACK_REDIRECT)));
        agent = Agent.builder().id(UUID.randomUUID()).userId(userId).name("ops").type(AgentType.MCP).build();
        when(agentRepository.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(agentRepository.findByUserId(eq(userId), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(agent)));
        when(grantRepository.save(any())).thenAnswer(invocation -> {
            McpOAuthGrant grant = invocation.getArgument(0);
            grant.setId(UUID.randomUUID());
            return grant;
        });
        when(tokenService.issueCode(any(), any())).thenReturn("agmc-code");
    }

    private static AuthorizationRequest request(String redirectUri) {
        return new AuthorizationRequest("code", CLIENT_ID, redirectUri, CHALLENGE, "S256", "xyz", null, null);
    }

    @Test
    @DisplayName("верный запрос — экран согласия с клиентом и агентами")
    void validShowsConsent() {
        CheckOutcome outcome = service.check(userId, request(WEB_REDIRECT));

        ConsentView view = assertInstanceOf(CheckOutcome.Consent.class, outcome).view();
        assertEquals("Claude", view.clientName());
        assertEquals("claude.ai", view.clientHost());
        assertEquals("claude.ai", view.redirectHost());
        assertFalse(view.localApplication());
        assertEquals(1, view.agents().size());
        assertTrue(view.agents().getFirst().available());
    }

    @Test
    @DisplayName("GENERIC в списке есть, но недоступен")
    void genericListedUnavailable() {
        agent.setType(AgentType.GENERIC);
        ConsentView view = ((CheckOutcome.Consent) service.check(userId, request(WEB_REDIRECT))).view();
        assertFalse(view.agents().getFirst().available());
    }

    @Test
    @DisplayName("loopback совпадает с любым портом и помечается как приложение на этом компьютере")
    void loopbackAnyPort() {
        ConsentView view = ((CheckOutcome.Consent) service.check(userId, request("http://localhost:53682/callback"))).view();
        assertTrue(view.localApplication());
    }

    @Test
    @DisplayName("незарегистрированный redirect_uri — 400 на странице, без редиректа")
    void unregisteredRedirectIsFatal() {
        assertThrows(BadRequestStatusException.class, () -> service.check(userId, request("https://evil.example/cb")));
    }

    @Test
    @DisplayName("непроверяемый клиент — 400 на странице, без редиректа")
    void rejectedClientIsFatal() {
        when(resolver.resolve(CLIENT_ID)).thenThrow(new OAuthClientRejectedException("unreachable"));
        assertThrows(BadRequestStatusException.class, () -> service.check(userId, request(WEB_REDIRECT)));
    }

    @Test
    @DisplayName("client_id не URL документа — 400 (DCR — фаза 2)")
    void opaqueClientIdIsFatal() {
        AuthorizationRequest opaque = new AuthorizationRequest("code", "abc123", WEB_REDIRECT, CHALLENGE, "S256", null, null, null);
        assertThrows(BadRequestStatusException.class, () -> service.check(userId, opaque));
    }

    @Test
    @DisplayName("redirect_uri не прислан при двух зарегистрированных — 400")
    void missingRedirectWithSeveralRegistered() {
        assertThrows(BadRequestStatusException.class, () -> service.check(userId, request(null)));
    }

    @Test
    @DisplayName("без code_challenge_method — ошибка уходит клиенту редиректом (отсутствие значит plain)")
    void missingMethodRedirects() {
        AuthorizationRequest plain = new AuthorizationRequest("code", CLIENT_ID, WEB_REDIRECT, CHALLENGE, null, "xyz", null, null);

        String url = assertInstanceOf(CheckOutcome.Redirect.class, service.check(userId, plain)).redirectUrl();
        assertTrue(url.startsWith(WEB_REDIRECT + "?error=invalid_request"));
        assertTrue(url.contains("state=xyz"));
        assertTrue(url.contains("iss=" + java.net.URLEncoder.encode(ISSUER, java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("response_type не code — unsupported_response_type редиректом")
    void wrongResponseType() {
        AuthorizationRequest token = new AuthorizationRequest("token", CLIENT_ID, WEB_REDIRECT, CHALLENGE, "S256", null, null, null);
        String url = ((CheckOutcome.Redirect) service.check(userId, token)).redirectUrl();
        assertTrue(url.contains("error=unsupported_response_type"));
    }

    @Test
    @DisplayName("чужой resource — invalid_target редиректом")
    void foreignResource() {
        AuthorizationRequest foreign = new AuthorizationRequest("code", CLIENT_ID, WEB_REDIRECT, CHALLENGE, "S256", null,
                "https://other.example/mcp", null);
        String url = ((CheckOutcome.Redirect) service.check(userId, foreign)).redirectUrl();
        assertTrue(url.contains("error=invalid_target"));
    }

    @Test
    @DisplayName("согласие создаёт PENDING-грант с точным redirect_uri и возвращает код, state и iss")
    void approve() {
        String requested = "http://localhost:53682/callback";
        String url = service.approve(userId, request(requested), agent.getId());

        assertEquals(requested + "?code=agmc-code&state=xyz&iss=https%3A%2F%2Fapi.agimate.io%2Fcontrol", url);
        verify(grantRepository).save(argThat(grant -> grant.getStatus() == McpOAuthGrantStatus.PENDING
                && grant.getRedirectUri().equals(requested)
                && grant.getAgentId().equals(agent.getId())
                && grant.getResource().equals(ISSUER + "/mcp")));
    }

    @Test
    @DisplayName("чужой агент — 404")
    void foreignAgent() {
        agent.setUserId(UUID.randomUUID());
        assertThrows(NotFoundStatusException.class, () -> service.approve(userId, request(WEB_REDIRECT), agent.getId()));
    }

    @Test
    @DisplayName("агент GENERIC — 400, грант не создаётся")
    void genericAgentRefused() {
        agent.setType(AgentType.GENERIC);
        assertThrows(BadRequestStatusException.class, () -> service.approve(userId, request(WEB_REDIRECT), agent.getId()));
        verify(grantRepository, never()).save(any());
    }

    @Test
    @DisplayName("отказ — access_denied редиректом, адрес собирает бэкенд")
    void deny() {
        String url = service.deny(userId, request(WEB_REDIRECT));
        assertTrue(url.startsWith(WEB_REDIRECT + "?error=access_denied"));
        assertTrue(url.contains("state=xyz"));
    }

    @Test
    @DisplayName("state с пробелами и амперсандом экранируется, существующий query сохраняется")
    void redirectEncoding() {
        String url = McpAuthorizationService.redirect("https://app.example/cb?x=1",
                new java.util.LinkedHashMap<>(java.util.Map.of("state", "a b&c")));
        assertEquals("https://app.example/cb?x=1&state=a%20b%26c", url);
    }
}
