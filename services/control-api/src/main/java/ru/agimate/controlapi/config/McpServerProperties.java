package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** The MCP server as its clients see it from outside (docs/decisions/mcp-server-oauth.md). */
@Component
@ConfigurationProperties(prefix = "app.mcp-server")
@Getter
@Setter
public class McpServerProperties {

    /**
     * Public base address of control-api, context path included ({@code https://api.agimate.io/control}).
     * The OAuth issuer, the resource ({@code <base>/mcp}) and every metadata address are derived from
     * it — never from the request's {@code Host}: behind a proxy the two differ, and these values are
     * compared byte for byte by clients.
     */
    private String publicUrl = "http://localhost:8080/control";
}
