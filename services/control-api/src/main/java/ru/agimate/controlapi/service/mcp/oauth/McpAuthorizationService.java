package ru.agimate.controlapi.service.mcp.oauth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.McpOAuthGrant;
import ru.agimate.controlapi.database.enums.McpOAuthGrantStatus;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.McpOAuthGrantRepository;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The authorization endpoint of the MCP server, split between the consent page on the front and
 * {@code /manage/mcp-oauth} here (docs/decisions/mcp-server-oauth.md, section 5).
 *
 * <p>Two kinds of failure, and the difference is the whole point of the order of checks. Until the
 * client and its {@code redirect_uri} are verified nothing is redirected — that would be an open
 * redirector — and the page shows the error itself ({@link BadRequestStatusException}). After that,
 * errors go back to the client in the redirect, as RFC 6749 wants. Every redirect address is built
 * here rather than on the page, so {@code iss}, {@code state} and the encoding live in one place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class McpAuthorizationService {

    private static final int MAX_AGENTS_LISTED = 500;

    private final CimdClientResolver cimdClientResolver;
    private final McpOAuthEndpoints endpoints;
    private final McpTokenService tokenService;
    private final AgentRepository agentRepository;
    private final McpOAuthGrantRepository grantRepository;
    private final InboundRateLimiter rateLimiter;

    private sealed interface Validation {
        record Valid(OAuthClient client, String redirectUri) implements Validation {}
        record Invalid(String redirectUrl) implements Validation {}
    }

    public CheckOutcome check(UUID userId, AuthorizationRequest request) {
        return switch (validate(userId, request)) {
            case Validation.Invalid invalid -> new CheckOutcome.Redirect(invalid.redirectUrl());
            case Validation.Valid valid -> new CheckOutcome.Consent(consentView(userId, valid));
        };
    }

    /** @return where the browser goes next: the client's redirect with a code, or with an error */
    @Transactional
    public String approve(UUID userId, AuthorizationRequest request, UUID agentId) {
        Validation validation = validate(userId, request);
        if (validation instanceof Validation.Invalid invalid) {
            return invalid.redirectUrl();
        }
        Validation.Valid valid = (Validation.Valid) validation;
        Agent agent = agentRepository.findById(agentId)
                .filter(found -> found.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getType().hasExternalBrain()) {
            throw new BadRequestStatusException("An agent of type GENERIC runs on the platform and cannot be connected over MCP");
        }
        if (!agent.isEnabled()) {
            throw new BadRequestStatusException("The agent is disabled");
        }

        LocalDateTime now = LocalDateTime.now();
        McpOAuthGrant grant = grantRepository.save(McpOAuthGrant.builder()
                .userId(userId)
                .agentId(agent.getId())
                .status(McpOAuthGrantStatus.PENDING)
                .clientKind(valid.client().kind())
                .clientId(valid.client().clientId())
                .clientName(valid.client().name())
                .redirectUri(valid.redirectUri())
                .resource(endpoints.resource())
                .codeChallenge(request.codeChallenge())
                .build());
        String code = tokenService.issueCode(grant.getId(), now);
        log.info("MCP OAuth: consent for agent {} given to client {} (grant {})",
                agent.getId(), valid.client().clientId(), grant.getId());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("state", request.state());
        params.put("iss", endpoints.issuer());
        return redirect(valid.redirectUri(), params);
    }

    public String deny(UUID userId, AuthorizationRequest request) {
        return switch (validate(userId, request)) {
            case Validation.Invalid invalid -> invalid.redirectUrl();
            case Validation.Valid valid -> errorRedirect(valid.redirectUri(), request.state(),
                    OAuthErrors.ACCESS_DENIED, "The user denied access");
        };
    }

    /**
     * Each call may fetch a document from an address the request names, and a failed fetch is not
     * cached — so the limit is on the caller, before anything goes out.
     */
    private Validation validate(UUID userId, AuthorizationRequest request) {
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.MCP_CONSENT, userId)) {
            throw new TooManyRequestsStatusException("Too many authorization requests");
        }
        OAuthClient client = resolveClient(request.clientId());
        String redirectUri = resolveRedirectUri(client, request.redirectUri());

        if (!"code".equals(request.responseType())) {
            return invalid(redirectUri, request, OAuthErrors.UNSUPPORTED_RESPONSE_TYPE, "Only response_type=code is supported");
        }
        if (!PkceS256.wellFormed(request.codeChallenge())) {
            return invalid(redirectUri, request, OAuthErrors.INVALID_REQUEST, "code_challenge is required");
        }
        // An absent method means plain (RFC 7636), so absence is refused too.
        if (!PkceS256.METHOD.equals(request.codeChallengeMethod())) {
            return invalid(redirectUri, request, OAuthErrors.INVALID_REQUEST, "code_challenge_method must be S256");
        }
        if (request.resource() != null && !endpoints.isOwnResource(request.resource())) {
            return invalid(redirectUri, request, OAuthErrors.INVALID_TARGET, "Unknown resource");
        }
        return new Validation.Valid(client, redirectUri);
    }

    private OAuthClient resolveClient(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new BadRequestStatusException("client_id is required");
        }
        if (!CimdClientResolver.isDocumentUrl(clientId)) {
            throw new BadRequestStatusException("Unknown client");
        }
        try {
            return cimdClientResolver.resolve(clientId);
        } catch (OAuthClientRejectedException e) {
            throw new BadRequestStatusException(e.getMessage());
        }
    }

    /** An absent redirect_uri is accepted only when the client registered exactly one. */
    private static String resolveRedirectUri(OAuthClient client, String requested) {
        if (requested == null || requested.isBlank()) {
            if (client.redirectUris().size() == 1) {
                return client.redirectUris().getFirst();
            }
            throw new BadRequestStatusException("redirect_uri is required");
        }
        if (RedirectUris.parse(requested).isEmpty()
                || client.redirectUris().stream().noneMatch(registered -> RedirectUris.matches(registered, requested))) {
            throw new BadRequestStatusException("redirect_uri is not registered for this client");
        }
        return requested;
    }

    private ConsentView consentView(UUID userId, Validation.Valid valid) {
        URI redirect = RedirectUris.parse(valid.redirectUri()).orElseThrow();
        boolean web = "https".equalsIgnoreCase(redirect.getScheme()) || "http".equalsIgnoreCase(redirect.getScheme());
        List<ConsentView.AgentChoice> agents = agentRepository
                .findByUserId(userId, PageRequest.of(0, MAX_AGENTS_LISTED, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(agent -> new ConsentView.AgentChoice(agent.getId(), agent.getName(), agent.getDescription(),
                        agent.getType(), agent.isEnabled(), agent.getType().hasExternalBrain()))
                .collect(Collectors.toList());
        OAuthClient client = valid.client();
        return new ConsentView(client.clientId(), client.name(), client.host(), client.kind(),
                web ? redirect.getHost() : null, RedirectUris.isLocalApplication(redirect), agents);
    }

    private Validation invalid(String redirectUri, AuthorizationRequest request, String error, String description) {
        return new Validation.Invalid(errorRedirect(redirectUri, request.state(), error, description));
    }

    private String errorRedirect(String redirectUri, String state, String error, String description) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("error", error);
        params.put("error_description", description);
        params.put("state", state);
        params.put("iss", endpoints.issuer());
        return redirect(redirectUri, params);
    }

    /** Appends to whatever query the registered address already has; null values are left out. */
    static String redirect(String redirectUri, Map<String, String> params) {
        StringBuilder url = new StringBuilder(redirectUri);
        char separator = redirectUri.contains("?") ? '&' : '?';
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (param.getValue() == null) {
                continue;
            }
            url.append(separator)
                    .append(param.getKey())
                    .append('=')
                    .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8).replace("+", "%20"));
            separator = '&';
        }
        return url.toString();
    }
}
