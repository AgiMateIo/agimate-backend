package ru.agimate.controlapi.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.service.AgentKeyAuthService;
import ru.agimate.controlapi.service.mcp.oauth.McpAccess;
import ru.agimate.controlapi.service.mcp.oauth.McpTokenService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AgentAuthFilter: какие поверхности открывает ключ агента")
class AgentAuthFilterTest {

    private static final String KEY = "agntkeykeykeykeykeykeykeykeykeykeykeykeykeykeykeykeykeykeykeykey";

    private final AgentKeyAuthService keyAuthService = mock(AgentKeyAuthService.class);
    private final McpTokenService mcpTokenService = mock(McpTokenService.class);
    private final AgentAuthFilter filter = new AgentAuthFilter(keyAuthService, mcpTokenService);

    private static final String ACCESS_TOKEN = "agmatokentokentokentokentokentokentokentokentokentokentokentoken";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Authentication authenticate(AgentType type) throws Exception {
        when(keyAuthService.validateKey(KEY)).thenReturn(Optional.of(Agent.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("agent")
                .type(type)
                .build()));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", KEY);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        return SecurityContextHolder.getContext().getAuthentication();
    }

    private List<String> rolesOf(AgentType type) throws Exception {
        Authentication authentication = authenticate(type);
        assertNotNull(authentication, "ключ валиден — аутентификация должна быть");
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }

    private static final List<String> EXTERNAL_ROLES =
            List.of("ROLE_AGENT", "ROLE_AGENT_CLIENT", "ROLE_MCP_AGENT");

    @Test
    @DisplayName("CENTRIFUGO — мозг снаружи: все поверхности внешнего агента")
    void centrifugo() throws Exception {
        assertEquals(EXTERNAL_ROLES, rolesOf(AgentType.CENTRIFUGO));
    }

    @Test
    @DisplayName("WEBHOOK — тип отличается только доставкой, поверхности те же")
    void webhook() throws Exception {
        assertEquals(EXTERNAL_ROLES, rolesOf(AgentType.WEBHOOK));
    }

    @Test
    @DisplayName("MCP — тоже внешний: /mcp плюс то, что он и так не умеет позвать")
    void mcp() throws Exception {
        assertEquals(EXTERNAL_ROLES, rolesOf(AgentType.MCP));
    }

    @Test
    @DisplayName("GENERIC — мозг в нашем воркере: только ACP, HTTP-поверхность мозга закрыта")
    void generic() throws Exception {
        assertEquals(List.of("ROLE_AGENT_CLIENT"), rolesOf(AgentType.GENERIC));
    }

    @Test
    @DisplayName("Authorization: bearer в любом регистре — схема регистронезависима")
    void bearerSchemeIsCaseInsensitive() throws Exception {
        when(keyAuthService.validateKey(KEY)).thenReturn(Optional.of(Agent.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID()).name("agent")
                .type(AgentType.MCP).build()));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "bearer " + KEY);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("невалидный ключ → контекст пуст")
    void invalidKey() throws Exception {
        when(keyAuthService.validateKey(anyString())).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", "nope");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("невалидный ключ помечает запрос — /mcp добавит error=invalid_token")
    void invalidKeyMarksRequest() throws Exception {
        when(keyAuthService.validateKey(anyString())).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", "nope");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNotNull(request.getAttribute(AgentAuthFilter.INVALID_CREDENTIAL_ATTRIBUTE));
    }

    private Authentication authenticateOAuth(AgentType type, MockHttpServletRequest request) throws Exception {
        Agent agent = Agent.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).name("agent").type(type).build();
        when(mcpTokenService.authenticate(ACCESS_TOKEN)).thenReturn(Optional.of(new McpAccess(agent, UUID.randomUUID())));
        request.addHeader("Authorization", "Bearer " + ACCESS_TOKEN);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("OAuth-токен открывает только /mcp, хотя ключ того же агента открывает всё")
    void oauthTokenOpensOnlyMcp() throws Exception {
        Authentication authentication = authenticateOAuth(AgentType.MCP, new MockHttpServletRequest());

        assertNotNull(authentication);
        assertEquals(List.of("ROLE_MCP_AGENT"),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
    }

    @Test
    @DisplayName("OAuth-токен агента, ставшего GENERIC после согласия, — без ролей: /mcp ответит 403")
    void oauthTokenOfGenericAgentHasNoRoles() throws Exception {
        Authentication authentication = authenticateOAuth(AgentType.GENERIC, new MockHttpServletRequest());

        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().isEmpty());
    }

    @Test
    @DisplayName("OAuth-токен в Authorization побеждает протухший X-Api-Key из настроек клиента")
    void oauthTokenWinsOverApiKeyHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", KEY);

        assertNotNull(authenticateOAuth(AgentType.MCP, request));
        verify(keyAuthService, never()).validateKey(anyString());
    }

    @Test
    @DisplayName("отвергнутый OAuth-токен — контекст пуст, запрос помечен")
    void rejectedOAuthToken() throws Exception {
        when(mcpTokenService.authenticate(ACCESS_TOKEN)).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + ACCESS_TOKEN);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(request.getAttribute(AgentAuthFilter.INVALID_CREDENTIAL_ATTRIBUTE));
        verify(keyAuthService, never()).validateKey(anyString());
    }

    @Test
    @DisplayName("без заголовка ключ не ищется — контекст пуст")
    void noKey() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
