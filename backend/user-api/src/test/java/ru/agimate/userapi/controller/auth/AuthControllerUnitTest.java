package ru.agimate.userapi.controller.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import ru.agimate.userapi.controller.AuthController;
import ru.agimate.userapi.controller.dto.response.auth.AuthResponse;
import ru.agimate.userapi.security.jwt.JwtUtils;
import ru.agimate.userapi.security.UserPrincipal;
import ru.agimate.userapi.security.jwt.RefreshTokenService;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerUnitTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthController authController;

    @Test
    void refreshToken_ShouldReturnNewTokens_WhenUserIsAuthenticated() {
        // Given
        Long userId = 1L;
        UUID userPubId = UUID.randomUUID();
        String userEmail = "test@example.com";

        UserPrincipal userPrincipal = new UserPrincipal(
            userId,
            userPubId,
            userEmail,
            "Test",
            "User",
            "Test User",
            Collections.singletonList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))
        );

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(userPrincipal);

        String newAccessToken = "new_access_token";
        String newRefreshToken = "new_refresh_token";

        when(jwtUtils.generateToken(userPrincipal)).thenReturn(newAccessToken);
        when(refreshTokenService.createRefreshToken(userPrincipal)).thenReturn(newRefreshToken);

        // When
        ResponseEntity<ru.agimate.common.rest.SuccessResponse<AuthResponse>> response = 
            authController.refreshToken(authentication);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody().getResponse());
        assertEquals(newAccessToken, response.getBody().getResponse().getAccessToken());
        assertEquals(newRefreshToken, response.getBody().getResponse().getRefreshToken());

        verify(jwtUtils).generateToken(userPrincipal);
        verify(refreshTokenService).createRefreshToken(userPrincipal);
    }
}