package ru.agimate.userapi.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.util.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        // Extract error details from Yandex OAuth callback
        String error = request.getParameter("error");
        String errorDescription = request.getParameter("error_description");

        // Log the full error details
        log.error("OAuth2 authentication failed. Error: {}, Description: {}, Exception: {}",
                error, errorDescription, exception.getMessage());

        // Return JSON error response directly (no redirect to avoid view resolution issues)
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String errorMessage = String.format("OAuth2 authentication failed. Error: %s, Description: %s",
                error != null ? error : "authentication_failed",
                errorDescription != null ? errorDescription : exception.getMessage());

        response.getWriter().write(JsonUtils.writeValueAsString(new ErrorResponse(errorMessage)));
        response.getWriter().flush();
    }
}