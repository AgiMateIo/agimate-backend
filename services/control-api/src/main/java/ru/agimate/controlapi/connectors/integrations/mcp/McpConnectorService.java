package ru.agimate.controlapi.connectors.integrations.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.IntegrationConnectorHandler;
import ru.agimate.controlapi.connectors.core.JobProvider;
import ru.agimate.controlapi.connectors.core.ToolProvider;
import ru.agimate.controlapi.connectors.core.ViewProvider;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.CredentialField;
import ru.agimate.controlapi.connectors.core.dto.CredentialField.Type;
import ru.agimate.controlapi.connectors.core.dto.IntegrationValidationResult;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;
import ru.agimate.controlapi.connectors.core.dto.ViewPage;
import ru.agimate.controlapi.connectors.core.jobs.JobSchedule;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpAuthDiscovery;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpOAuthService;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.McpUnauthorizedException;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthCredentials;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthSetup;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A universal connector to a remote MCP server (Streamable HTTP). Unlike ordinary connectors its
 * tools are dynamic and per instance: each instance (a {@code connections} row = URL plus auth in
 * {@code secrets}) reports its own set through {@code tools/list}. So we implement
 * {@link ToolProvider} directly (without {@code BaseConnectorHandler} and {@code @Tool} methods):
 * <ul>
 *   <li>{@link #getTools()} — there are no static tools (empty); the per-instance list lives in
 *       {@code connection_tools} (populated by {@link McpToolDiscoveryListener} on integration
 *       create/modify) and is listed by {@code ToolDefinitionService}, like every DYNAMIC connector's;</li>
 *   <li>{@link #executeTool} — proxying into {@code tools/call}.</li>
 * </ul>
 * Its one background job is {@code oauth_refresh}: for servers that authorise over OAuth the access
 * token has to be renewed before it dies, and the job is also the only writer of a grant — which is
 * what makes locking around the refresh unnecessary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpConnectorService implements IntegrationConnectorHandler, ToolProvider, JobProvider, ViewProvider {

    public static final String CONNECTOR_CODE = McpUtils.CONNECTOR_CODE;

    /** Name of the token-refresh job; dispatched by {@link #executeJob}. */
    public static final String JOB_OAUTH_REFRESH = "oauth_refresh";

    /** Shorter than the refresh horizon, so a token never dies between two ticks. */
    private static final long REFRESH_INTERVAL_SECONDS = 300;

    private final McpClient mcpClient;
    private final McpAuthDiscovery authDiscovery;
    private final McpOAuthService oauthService;

    /** Where to send the user to (re-)authorise: a page, because a browser is what opens it. */
    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "MCP Server";
    }

    @Override
    public String connectorDescription() {
        return "A connection to an external MCP server: its tools become the agent's own. "
                + "Each connection has its own tool set, read from the server when it is added.";
    }

    @Override
    public ru.agimate.controlapi.database.model.ConnectorTraits traits() {
        // Tool descriptions of an MCP server are unbounded (a measured 3.9 KB each on one), so the
        // catalogue is listed by summary and disclosed on demand.
        return ru.agimate.controlapi.database.model.ConnectorTraits.dynamicIntegration().lazy();
    }

    @Override
    public Map<String, CredentialField> getCredentialFields() {
        Map<String, CredentialField> fields = new LinkedHashMap<>();
        fields.put(McpUtils.FIELD_URL, CredentialField.required("Server URL (Streamable HTTP)", Type.URL));
        fields.put(McpUtils.FIELD_AUTH_TOKEN, CredentialField.optional("Bearer token", Type.SECRET));
        fields.put(McpUtils.FIELD_HEADERS, CredentialField.optional("Extra headers as JSON", Type.JSON));
        return fields;
    }

    /** Two connections to one MCP server are two accounts on it — a product feature, not a mistake. */
    @Override
    public boolean allowsMultipleInstances() {
        return true;
    }

    /** The same normalisation as {@link #validateCredentials}, so a declared URL meets {@code sub_code} as written there. */
    @Override
    public Optional<String> identifierOf(Map<String, String> credentials) {
        try {
            return Optional.of(McpUtils.toServerConfig(credentials).url());
        } catch (ConnectorException e) {
            return Optional.empty();
        }
    }

    /**
     * Validation = the {@code initialize} handshake: it confirms the server is reachable and the auth
     * works. Tools are not persisted here — the instance's id is not assigned yet; they are synced by
     * {@link McpToolDiscoveryListener} after the commit. {@code identifier} = the server's URL (the
     * instance's canonical key).
     *
     * <p>A 401 is a third outcome rather than a failure: the server is alive and told us where to
     * authorise. What discovery found out travels back in {@code derivedCredentials} and lands in the
     * connection's secret — so neither the token exchange nor the refresh job has to walk the
     * metadata again.
     */
    @Override
    public IntegrationValidationResult validateCredentials(Map<String, String> credentials) {
        McpClient.ServerConfig config = McpUtils.toServerConfig(credentials);
        try {
            McpClient.ServerInfo info = mcpClient.probe(config);
            String label = info.name() != null && !info.name().isBlank() ? info.name() : host(config.url());
            return IntegrationValidationResult.success(config.url(), "MCP: " + label);
        } catch (McpUnauthorizedException e) {
            return authorizationOutcome(config.url(), e);
        } catch (ConnectorException e) {
            return IntegrationValidationResult.failure(McpUtils.FIELD_URL, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to validate MCP server {}", config.url(), e);
            return IntegrationValidationResult.failure(McpUtils.FIELD_URL, "Failed to reach MCP server");
        }
    }

    /**
     * A server that answered 401 either tells us where its authorisation server is — and then the
     * connection is created unauthorised — or it does not, and then OAuth is simply unavailable for
     * it. The latter is not a broken server: with a static token it keeps working, which is what the
     * message says.
     */
    private IntegrationValidationResult authorizationOutcome(String url, McpUnauthorizedException failure) {
        Optional<OAuthSetup> setup;
        try {
            setup = authDiscovery.discover(url, failure.challenge().orElse(null), McpClient.protocolVersion());
        } catch (ConnectorException e) {
            return IntegrationValidationResult.failure(McpUtils.FIELD_URL, e.getMessage());
        }
        return setup
                .map(value -> IntegrationValidationResult.authorizationRequired(
                        url, "MCP: " + host(url), OAuthCredentials.of(value)))
                .orElseGet(() -> IntegrationValidationResult.failure(McpUtils.FIELD_URL,
                        "The server requires authorization but does not expose an authorization server; "
                                + "provide a static token instead"));
    }

    /** MCP has no static tools — the set is always per instance, in {@code connection_tools}. */
    @Override
    public Map<String, ConnectorToolSpec> getTools() {
        return Map.of();
    }

    /**
     * A 401 here does not try to refresh anything: that would drag mutual exclusion into the middle of
     * somebody else's call to win a rare case. The connection is marked instead — the agent gets a
     * link, and the refresh job repairs it on its next tick if the grant is still alive.
     */
    @Override
    public Map<String, Object> executeTool(ConnectorEnv env, String toolName, Map<String, Object> args) {
        McpClient.ServerConfig config = McpUtils.toServerConfig(env.credentials());
        try {
            return mcpClient.callTool(config, toolName, args);
        } catch (McpUnauthorizedException e) {
            throw reauthorizationNeeded(env, e);
        }
    }

    /** {@code resources/read} of a {@code ui://} uri; authorisation failures as in {@link #executeTool}. */
    @Override
    public ViewPage readView(ConnectorEnv env, String uri) {
        McpClient.Resource resource;
        try {
            resource = mcpClient.readResource(McpUtils.toServerConfig(env.credentials()), uri);
        } catch (McpUnauthorizedException e) {
            throw reauthorizationNeeded(env, e);
        }
        if (!isViewMimeType(resource.mimeType())) {
            throw new ConnectorException("The server returned " + resource.mimeType() + " instead of an MCP App page");
        }
        JsonNode ui = resource.meta() == null ? null : resource.meta().get("ui");
        return new ViewPage(
                resource.mimeType(),
                resource.text(),
                objectOrNull(ui == null ? null : ui.get("permissions")),
                ui != null && ui.path("prefersBorder").isBoolean() ? ui.get("prefersBorder").asBoolean() : null);
    }

    /**
     * {@link #executeTool} records the server's whole {@code CallToolResult}, so a view gets its
     * {@code structuredContent} back instead of the text flattening the agent sees.
     */
    @Override
    public ViewCallResult toViewResult(String output) {
        JsonNode node = output == null ? null : JsonUtils.toJsonNodeOrNull(output);
        if (node == null || !node.isObject() || !node.path("content").isArray()) {
            return ViewCallResult.text(output == null ? "" : output);
        }
        Map<String, Object> result = JsonUtils.MAPPER.convertValue(node, JsonUtils.MAP_TYPE_REFERENCE);
        return new ViewCallResult(
                new ArrayList<>((List<?>) result.get("content")),
                result.get("structuredContent"),
                Boolean.TRUE.equals(result.get("isError")));
    }

    /** {@code text/html} with the parameter {@code profile=mcp-app}, whatever the spacing, case or quoting. */
    static boolean isViewMimeType(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        String[] parts = mimeType.split(";");
        if (!parts[0].trim().equalsIgnoreCase("text/html")) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            String[] parameter = parts[i].split("=", 2);
            if (parameter.length == 2 && parameter[0].trim().equalsIgnoreCase("profile")
                    && unquote(parameter[1].trim()).toLowerCase(Locale.ROOT).equals("mcp-app")) {
                return true;
            }
        }
        return false;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static Map<String, Object> objectOrNull(JsonNode node) {
        return node != null && node.isObject() ? JsonUtils.MAPPER.convertValue(node, JsonUtils.MAP_TYPE_REFERENCE) : null;
    }

    private ConnectorException reauthorizationNeeded(ConnectorEnv env, McpUnauthorizedException failure) {
        UUID connectionId = connectionId(env);
        if (failure.insufficientScope()) {
            // The token is alive, it is simply too narrow. The scopes the server asked for are recorded
            // so the next authorisation requests them together with what is already granted; resuming
            // the interrupted call afterwards is a separate question and is not done here.
            if (connectionId != null) {
                failure.requiredScope().ifPresent(scope -> oauthService.widenScope(connectionId, scope));
            }
            return new ConnectorException("The server requires additional permissions"
                    + failure.requiredScope().map(scope -> " (" + scope + ")").orElse("")
                    + ". Re-connect the integration to grant them: " + reauthorizationLink(connectionId));
        }
        if (connectionId != null) {
            oauthService.markExpired(connectionId);
        }
        return new ConnectorException("Authorization for this MCP server has expired. "
                + "Re-connect the integration: " + reauthorizationLink(connectionId));
    }

    private String reauthorizationLink(UUID connectionId) {
        return connectionId == null ? frontendBaseUrl + "/connections" : frontendBaseUrl + "/connections/" + connectionId;
    }

    private static UUID connectionId(ConnectorEnv env) {
        if (env == null || env.connectionId() == null) {
            return null;
        }
        try {
            return UUID.fromString(env.connectionId());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The connector's one job by name; whether an instance gets a row is decided in {@link #getJobs(ConnectorEnv)}. */
    @Override
    public Map<String, JobSpec> getJobs() {
        return Map.of(JOB_OAUTH_REFRESH, new JobSpec(
                JOB_OAUTH_REFRESH,
                ConnectorJobType.PERIODIC,
                JobSchedule.periodicConfig(REFRESH_INTERVAL_SECONDS),
                Map.of(),
                60));
    }

    /**
     * A row only for instances with a recorded expiry: a static token has nothing to renew, and a row
     * that can never do anything would still show up in the connection's job listing. The same column
     * is what {@link McpOAuthService#refreshIfNeeded} decides by, so declaration and execution agree.
     */
    @Override
    public Map<String, JobSpec> getJobs(ConnectorEnv env) {
        UUID connectionId = connectionId(env);
        return connectionId != null && oauthService.tracksExpiry(connectionId) ? getJobs() : Map.of();
    }

    @Override
    public Map<String, Object> executeJob(ConnectorEnv env, String name, Map<String, Object> args) {
        if (!JOB_OAUTH_REFRESH.equals(name)) {
            throw new ConnectorException("Unknown MCP job: " + name);
        }
        UUID connectionId = connectionId(env);
        if (connectionId == null) {
            throw new ConnectorException("MCP job requires a connection");
        }
        return Map.of("refreshed", oauthService.refreshIfNeeded(connectionId));
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
