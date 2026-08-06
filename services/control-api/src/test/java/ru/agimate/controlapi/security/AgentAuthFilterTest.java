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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AgentAuthFilter: какие поверхности открывает ключ агента")
class AgentAuthFilterTest {

    private static final String KEY = "agntkeykeykeykeykeykeykeykeykeykeykeykeykeykeykeykeykeykeykeykey";

    private final AgentKeyAuthService keyAuthService = mock(AgentKeyAuthService.class);
    private final AgentAuthFilter filter = new AgentAuthFilter(keyAuthService);

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
    @DisplayName("невалидный ключ → контекст пуст")
    void invalidKey() throws Exception {
        when(keyAuthService.validateKey(anyString())).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Api-Key", "nope");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("без заголовка ключ не ищется — контекст пуст")
    void noKey() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
