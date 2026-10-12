package ru.agimate.controlapi.service.mcp.oauth;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.database.enums.McpOAuthTokenKind;

import java.time.Duration;
import java.util.Optional;

/**
 * Token kinds of the MCP server's authorization server: their AGM prefixes and lifetimes. The
 * prefixes are distinct from anything starting with {@code mcp}, which many products use — a leaked
 * token has to be recognisable as ours (docs/contracts/api-keys.md).
 */
@UtilityClass
public class McpOAuthTokens {

    public static final String CODE_PREFIX = "agmc";
    public static final String ACCESS_PREFIX = "agma";
    public static final String REFRESH_PREFIX = "agmr";

    public static final Duration CODE_TTL = Duration.ofSeconds(60);
    public static final Duration ACCESS_TTL = Duration.ofHours(1);
    /** Sliding: every rotation issues a refresh token that lives this long again. */
    public static final Duration REFRESH_TTL = Duration.ofDays(30);

    /**
     * How long a rotated refresh token still counts as a retry of a lost response rather than a
     * replay of a stolen one (docs/decisions/native-auth.md, п. 6).
     */
    public static final Duration RETRY_WINDOW = Duration.ofSeconds(60);

    public static String prefixOf(McpOAuthTokenKind kind) {
        return switch (kind) {
            case CODE -> CODE_PREFIX;
            case ACCESS -> ACCESS_PREFIX;
            case REFRESH -> REFRESH_PREFIX;
        };
    }

    public static Duration ttlOf(McpOAuthTokenKind kind) {
        return switch (kind) {
            case CODE -> CODE_TTL;
            case ACCESS -> ACCESS_TTL;
            case REFRESH -> REFRESH_TTL;
        };
    }

    /** The kind a presented token claims by its prefix; empty for anything that is not ours. */
    public static Optional<McpOAuthTokenKind> kindOf(String token) {
        if (token == null || token.length() < 4) {
            return Optional.empty();
        }
        return switch (token.substring(0, 4)) {
            case CODE_PREFIX -> Optional.of(McpOAuthTokenKind.CODE);
            case ACCESS_PREFIX -> Optional.of(McpOAuthTokenKind.ACCESS);
            case REFRESH_PREFIX -> Optional.of(McpOAuthTokenKind.REFRESH);
            default -> Optional.empty();
        };
    }

    public static boolean isAccessToken(String token) {
        return kindOf(token).filter(kind -> kind == McpOAuthTokenKind.ACCESS).isPresent();
    }
}
