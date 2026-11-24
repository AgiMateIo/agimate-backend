package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The installation's OAuth client settings. {@code client-id} is the address of our CIMD document and
 * is required only on the CIMD path; {@code redirect-uri} is required on both. {@code dcr-enabled}
 * lets an operator forbid new registrations while keeping already issued DCR grants refreshable.
 */
@Component
@ConfigurationProperties(prefix = "app.connectors.mcp.oauth")
@Getter
@Setter
public class McpOAuthProperties {

    /** HTTPS address of the client ID metadata document; empty disables CIMD, not OAuth itself. */
    private String clientId = "";

    /** Where the authorisation server returns the browser; the same address is registered for DCR. */
    private String redirectUri = "";

    /** Whether this installation may register new DCR clients. */
    private boolean dcrEnabled = true;

    /**
     * New registrations one user may trigger in an hour. A reconnect normally reuses the grant's
     * client; only a wider scope or a dead client causes a new one. {@code <= 0} disables the limit.
     */
    private int registrationsPerUserPerHour = 10;

    /**
     * New registrations all users may trigger at one authorisation server in an hour. The server's
     * own rate limit is a backstop, not a plan: its block would hit the whole installation by IP.
     * {@code <= 0} disables the limit.
     */
    private int registrationsPerServerPerHour = 100;
}
