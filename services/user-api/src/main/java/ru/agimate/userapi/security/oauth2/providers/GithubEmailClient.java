package ru.agimate.userapi.security.oauth2.providers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Reads the addresses of the signed-in GitHub account. {@code /user} only carries the public email,
 * which most accounts hide, so the usable address lives behind a second call.
 */
@Component
public class GithubEmailClient {

    private static final String EMAILS_URI = "https://api.github.com/user/emails";
    // The call sits inside the login request, so a slow GitHub must fail rather than hold the thread.
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ParameterizedTypeReference<List<GithubEmail>> EMAIL_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public GithubEmailClient() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                // GitHub answers 403 to requests without a User-Agent.
                .defaultHeader(HttpHeaders.USER_AGENT, "agimate-user-api")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .build();
    }

    /** The address GitHub itself considers confirmed; empty when the account has none. */
    public Optional<String> primaryVerifiedEmail(String accessToken) {
        List<GithubEmail> emails = restClient.get()
                .uri(EMAILS_URI)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(EMAIL_LIST);

        if (emails == null) {
            return Optional.empty();
        }
        return emails.stream()
                .filter(email -> email.primary() && email.verified())
                .map(GithubEmail::email)
                .findFirst();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GithubEmail(String email, boolean primary, boolean verified) {
    }
}
