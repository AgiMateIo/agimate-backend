package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limits on inbound traffic from external sources (connected apps and webhooks), per connection. A
 * value {@code <= 0} disables the limit for that scope.
 */
@Component
@ConfigurationProperties(prefix = "inbound-rate-limit")
@Getter
@Setter
public class InboundRateLimitProperties {
    private boolean enabled = true;
    /** Triggers ({@code /app/trigger/new}, {@code /webhook/*}) per minute per connection. */
    private int triggersPerMinute = 120;
    /** Tool results ({@code /app/tools/result}) per minute per connection. */
    private int toolResultsPerMinute = 120;
    /** File uploads ({@code /app/files}) per minute per connection. */
    private int fileUploadsPerMinute = 30;
    /** MCP tool calls ({@code /mcp}) per minute per agent — there the key, not a connection, is the entry point. */
    private int mcpCallsPerMinute = 120;
    /**
     * MCP task polls ({@code tasks/get} and friends) per minute per agent. Sized from the live-task
     * cap at the suggested poll interval (10 tasks × 12 polls/min), doubled — an honest client must
     * hit its own interval before this limit.
     */
    private int mcpTaskPollsPerMinute = 240;
}
