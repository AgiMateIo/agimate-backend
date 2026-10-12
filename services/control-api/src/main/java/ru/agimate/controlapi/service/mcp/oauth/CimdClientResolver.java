package ru.agimate.controlapi.service.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.agimate.common.net.PublicTargets;
import ru.agimate.common.net.TargetNotAllowedException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.enums.McpOAuthClientKind;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client ID Metadata Documents: the {@code client_id} is the HTTPS address of a JSON document the
 * client publishes, and this server fetches it (docs/decisions/mcp-server-oauth.md, section 4).
 *
 * <p>The fetch is an outbound request to an address someone else chose, so it goes through
 * {@link PublicOnlyHttp}: no redirects, no private addresses, a small body. The cache is a cache,
 * not state — per instance, bounded by the document's own {@code Cache-Control} and by a day.
 */
@Slf4j
@Component
public class CimdClientResolver {

    static final int MAX_DOCUMENT_BYTES = 10 * 1024;
    private static final int MAX_NAME_LENGTH = 100;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /** The whole fetch: the address is the client's choice, and the consent page is waiting on it. */
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);
    private static final Duration MAX_TTL = Duration.ofDays(1);
    private static final Pattern MAX_AGE = Pattern.compile("max-age\\s*=\\s*(\\d+)");

    private record Fetched(OAuthClient client, Duration ttl) {}

    private final RestClient restClient;
    private final PublicTargets targets;
    private final Cache<String, Fetched> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfter(new Expiry<String, Fetched>() {
                @Override
                public long expireAfterCreate(String key, Fetched value, long currentTime) {
                    return value.ttl().toNanos();
                }

                @Override
                public long expireAfterUpdate(String key, Fetched value, long currentTime, long currentDuration) {
                    return value.ttl().toNanos();
                }

                @Override
                public long expireAfterRead(String key, Fetched value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();

    public CimdClientResolver(PublicOnlyHttp http) {
        this.targets = http.targets();
        this.restClient = http.restClient(READ_TIMEOUT, DEADLINE).build();
    }

    /** Whether this {@code client_id} is a metadata document address rather than a registered id. */
    public static boolean isDocumentUrl(String clientId) {
        return clientId != null && (clientId.startsWith("https://") || clientId.startsWith("http://"));
    }

    public OAuthClient resolve(String clientId) {
        Fetched cached = cache.getIfPresent(clientId);
        if (cached != null) {
            return cached.client();
        }
        URI uri = requireDocumentUrl(clientId);
        Fetched fetched = fetch(clientId, uri);
        if (!fetched.ttl().isZero()) {
            cache.put(clientId, fetched);
        }
        return fetched.client();
    }

    /**
     * https (http only where private targets are allowed, i.e. a local stand), a path, no fragment,
     * no credentials, no dot segments — the shape the CIMD draft requires of a {@code client_id}.
     */
    private URI requireDocumentUrl(String clientId) {
        URI uri;
        try {
            uri = new URI(clientId);
        } catch (Exception e) {
            throw new OAuthClientRejectedException("client_id is not a valid URL");
        }
        boolean https = "https".equals(uri.getScheme());
        if (!https && !("http".equals(uri.getScheme()) && targets.allowsPrivate())) {
            throw new OAuthClientRejectedException("client_id must be an https URL");
        }
        String path = uri.getRawPath();
        if (uri.getHost() == null || path == null || path.isEmpty() || "/".equals(path)
                || uri.getRawFragment() != null || uri.getRawUserInfo() != null
                || path.contains("/./") || path.contains("/../") || path.endsWith("/.") || path.endsWith("/..")) {
            throw new OAuthClientRejectedException("client_id is not a valid client metadata document URL");
        }
        try {
            return targets.requireAllowed(clientId, https);
        } catch (TargetNotAllowedException e) {
            throw new OAuthClientRejectedException("client_id points to an address that is not allowed");
        }
    }

    private Fetched fetch(String clientId, URI uri) {
        record Response(byte[] body, String cacheControl) {}
        Response response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, http) -> {
                        if (!http.getStatusCode().is2xxSuccessful()) {
                            throw new OAuthClientRejectedException(
                                    "Client metadata document answered " + http.getStatusCode().value());
                        }
                        try (InputStream in = http.getBody()) {
                            // Bytes, not a String: without a charset RestClient would read ISO-8859-1.
                            byte[] body = in.readNBytes(MAX_DOCUMENT_BYTES + 1);
                            return new Response(body, http.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
                        }
                    });
        } catch (OAuthClientRejectedException e) {
            throw e;
        } catch (Exception e) {
            log.debug("CIMD fetch failed for {}: {}", clientId, e.getMessage());
            throw new OAuthClientRejectedException("Client metadata document is unreachable");
        }
        if (response.body().length > MAX_DOCUMENT_BYTES) {
            throw new OAuthClientRejectedException("Client metadata document is too large");
        }
        JsonNode document = JsonUtils.toJsonNodeOrNull(new String(response.body(), StandardCharsets.UTF_8));
        return new Fetched(parse(clientId, uri, document, targets.allowsPrivate()), ttlOf(response.cacheControl()));
    }

    /** @param allowInsecureHttp a local stand: plain http redirect targets beyond loopback are kept */
    static OAuthClient parse(String clientId, URI uri, JsonNode document, boolean allowInsecureHttp) {
        if (document == null || !document.isObject()) {
            throw new OAuthClientRejectedException("Client metadata document is not a JSON object");
        }
        // Byte for byte: the document must name itself by the very address it was fetched from.
        if (!clientId.equals(document.path("client_id").asText(null))) {
            throw new OAuthClientRejectedException("client_id in the metadata document does not match its address");
        }
        String authMethod = document.path("token_endpoint_auth_method").asText("none");
        if (!"none".equals(authMethod)) {
            throw new OAuthClientRejectedException("Only public clients are supported (token_endpoint_auth_method: none)");
        }
        JsonNode grantTypes = document.path("grant_types");
        if (grantTypes.isArray() && !contains(grantTypes, "authorization_code")) {
            throw new OAuthClientRejectedException("The client does not use the authorization_code grant");
        }
        List<String> redirectUris = new ArrayList<>();
        for (JsonNode item : document.path("redirect_uris")) {
            // An address that breaks the rules is dropped, not repaired: a request naming it then finds
            // nothing registered and is refused on the page instead of redirected.
            if (item.isTextual() && RedirectUris.parse(item.asText())
                    .filter(redirect -> RedirectUris.isAllowed(redirect, allowInsecureHttp)).isPresent()) {
                redirectUris.add(item.asText());
            }
        }
        if (redirectUris.isEmpty()) {
            throw new OAuthClientRejectedException("Client metadata document has no usable redirect_uris");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String name = document.path("client_name").asText("").strip();
        if (name.isEmpty()) {
            name = host;
        } else if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        return new OAuthClient(clientId, McpOAuthClientKind.CIMD, name, host, List.copyOf(redirectUris));
    }

    /** The document's own {@code Cache-Control}, capped at a day; zero means do not cache. */
    static Duration ttlOf(String cacheControl) {
        if (cacheControl == null) {
            return DEFAULT_TTL;
        }
        String value = cacheControl.toLowerCase(Locale.ROOT);
        if (value.contains("no-store") || value.contains("no-cache")) {
            return Duration.ZERO;
        }
        Matcher maxAge = MAX_AGE.matcher(value);
        if (maxAge.find()) {
            try {
                Duration ttl = Duration.ofSeconds(Long.parseLong(maxAge.group(1)));
                return ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
            } catch (NumberFormatException e) {
                return MAX_TTL;
            }
        }
        return DEFAULT_TTL;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }
}
