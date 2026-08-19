package ru.agimate.userapi.security.jwt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.agimate.common.security.UserRole;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.common.security.jwt.JwtProperties;
import ru.agimate.common.security.jwt.JwtService;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.service.UserService;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JwtDbAuthenticationFilter")
class JwtDbAuthenticationFilterTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private JwtService jwtService;
    private JwtDbAuthenticationFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new JwtService(properties());

        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setRole(UserRole.USER);

        UserService userService = mock(UserService.class);
        when(userService.findById(USER_ID)).thenReturn(Optional.of(user));

        filter = new JwtDbAuthenticationFilter(jwtService, userService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Роль фильтр берёт из базы, а вот про вход знает только токен: потеряв здесь claim, регистрация
     * подписки на пуши остаётся без ключа, по которому её сносит отзыв сессии.
     */
    @Test
    @DisplayName("сессия входа из токена доезжает до principal")
    void carriesAuthSessionFromTheToken() throws Exception {
        UUID authSessionId = UUID.randomUUID();

        assertEquals(authSessionId, authenticate(token(authSessionId)).authSessionId());
    }

    /** Токен, выпущенный до появления claim'а, — это вход без ключа, а не отказ в аутентификации. */
    @Test
    @DisplayName("токен без claim'а аутентифицирует, но сессии входа не даёт")
    void tokenWithoutClaimStillAuthenticates() throws Exception {
        AgimateUserPrincipal principal = authenticate(token(null));

        assertEquals(USER_ID.toString(), principal.id());
        assertNull(principal.authSessionId());
    }

    private AgimateUserPrincipal authenticate(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        return (AgimateUserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private String token(UUID authSessionId) {
        return jwtService.generateAccessToken(
                AgimateUserPrincipal.fromUser(USER_ID.toString(), UserRole.USER, authSessionId));
    }

    private static JwtProperties properties() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();

        JwtProperties properties = new JwtProperties();
        properties.setPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        properties.setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        properties.setAccessExpiration(900);
        properties.setRefreshExpiration(3600);
        return properties;
    }
}
