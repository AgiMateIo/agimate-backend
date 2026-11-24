package ru.agimate.controlapi.connectors.integrations.mcp.oauth;

import java.util.List;

/**
 * What discovery decided about the client identity, frozen into the connection's secret with the
 * rest of the setup. {@code dcrSupported} itself is an input to that decision and is not kept: after
 * the choice the source is the only thing the flow needs.
 *
 * @param registrationEndpoint       the endpoint DCR would register at; null when CIMD was chosen
 * @param tokenEndpointAuthMethods   as advertised by the authorisation server; empty means «not
 *                                   declared», and RFC 8414's default is then
 *                                   {@code client_secret_basic}
 */
public record ClientIdentification(
        OAuthClientSource source,
        String registrationEndpoint,
        List<String> tokenEndpointAuthMethods
) {

    public ClientIdentification {
        tokenEndpointAuthMethods = tokenEndpointAuthMethods == null
                ? List.of()
                : List.copyOf(tokenEndpointAuthMethods);
    }
}
