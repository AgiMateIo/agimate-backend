package ru.agimate.userapi.config;

import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
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

    /**
     * Where an installed application may be sent back to — App Links, Universal Links, or a custom
     * scheme. Deliberately a second list rather than more entries in the first: the web branch
     * derives a cookie domain from its addresses, and there is no such thing for {@code agimate://}.
     */
    private List<String> nativeRedirectUrls = List.of();

    public record ResolvedDomain(String cookieDomain, boolean cookieSecure, String frontendRedirectUrl) {}

    /**
     * Fails the startup rather than the login: an address in the wrong list produces a cookie
     * scoped to a domain that does not exist, and the only symptom is that signing in stops working
     * on one of the two clients.
     */
    @PostConstruct
    void validateRedirectLists() {
        for (String url : allowedRedirectUrls) {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalStateException(
                        "app.oauth.allowed-redirect-urls is the web list and takes http(s) addresses only; "
                                + url + " belongs in app.oauth.native-redirect-urls");
            }
            // Every login reads this list to find the installation it arrived at, and the cookie
            // domain comes out of the host — an address without one fails the login, not the boot.
            if (uri.getHost() == null) {
                throw new IllegalStateException(
                        "app.oauth.allowed-redirect-urls: " + url + " has no host to take a cookie domain from");
            }
        }
        for (String url : nativeRedirectUrls) {
            if (allowedRedirectUrls.contains(url)) {
                throw new IllegalStateException(
                        "app.oauth redirect " + url + " is listed as both web and native");
            }
        }
    }

    /**
     * Where the browser branch of a login ends up. Three sources in order: {@code redirect_to} when
     * it is on the list, then the installation the callback itself arrived at, and only then the
     * fixed default — that one holds a single installation's address, so every other installation
     * used to send its people to somebody else's frontend.
     *
     * @param redirectToUrl what the client asked for, or null; honoured only on an exact match
     */
    public ResolvedDomain resolveFromRedirectUrl(@Nullable String redirectToUrl, HttpServletRequest request) {
        if (redirectToUrl != null && allowedRedirectUrls.contains(redirectToUrl)) {
            return new ResolvedDomain(extractBaseDomain(redirectToUrl), cookieSecure, redirectToUrl);
        }
        return resolveFromRequest(request);
    }

    /** Exact string match, like the web list: a prefix rule is how an open redirect gets in. */
    public boolean isNativeRedirect(@Nullable String redirectToUrl) {
        return redirectToUrl != null && nativeRedirectUrls.contains(redirectToUrl);
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
