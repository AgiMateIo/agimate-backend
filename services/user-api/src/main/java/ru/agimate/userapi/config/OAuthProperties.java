package ru.agimate.userapi.config;

import jakarta.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

@ConfigurationProperties(prefix = "app.oauth")
@Getter
@Setter
public class OAuthProperties {

    private boolean cookieSecure;
    private String cookieDomain;
    private String frontendRedirectUrl;
    private List<String> allowedRedirectUrls = List.of();

    public record ResolvedDomain(String cookieDomain, boolean cookieSecure, String frontendRedirectUrl) {}

    public ResolvedDomain resolveFromRedirectUrl(@Nullable String redirectToUrl) {
        if (redirectToUrl != null && allowedRedirectUrls.contains(redirectToUrl)) {
            return new ResolvedDomain(extractBaseDomain(redirectToUrl), cookieSecure, redirectToUrl);
        }
        return defaults();
    }

    public ResolvedDomain resolveFromRequest(HttpServletRequest request) {
        String host = request.getServerName();
        for (String url : allowedRedirectUrls) {
            String domain = extractBaseDomain(url);
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return new ResolvedDomain(domain, cookieSecure, url);
            }
        }
        return defaults();
    }

    private ResolvedDomain defaults() {
        return new ResolvedDomain(cookieDomain, cookieSecure, frontendRedirectUrl);
    }

    private String extractBaseDomain(String url) {
        String host = URI.create(url).getHost();
        return host.startsWith("www.") ? host.substring(4) : host;
    }
}
