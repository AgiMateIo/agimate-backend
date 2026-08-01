package ru.agimate.controlapi.connectors.core;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.util.Slugs;

import java.net.URI;

/**
 * Assembly of a connector instance's client-facing handle: {@code full_code = connector_code + "_" +
 * slug}. Two sources of the slug, and which one applies is a property of the connector.
 *
 * <p>For a single-instance connector it is derived from the canonical identifier (a URL → the host's
 * main label): {@code (mcp, https://mcp.context7.com/mcp)} → {@code mcp_context7}. For a
 * multi-instance one the identifier no longer tells instances apart — two Notion accounts share a
 * URL — so the discriminator is the name the user gave the connection: {@code mcp_notion_work}. It is
 * computed once, at creation: renaming a connection must not silently rename the agent's tools.
 */
@UtilityClass
public class FullCodes {

    private static final int MAX_SLUG_LENGTH = 48;

    public static String fullCode(String connectorCode, String identifier) {
        return withSlug(connectorCode, slug(connectorCode, identifier));
    }

    /** {@code connector_code + "_" + slug} — for callers that resolved the slug themselves. */
    public static String withSlug(String connectorCode, String slug) {
        return connectorCode + "_" + slug;
    }

    public static String slug(String connectorCode, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return connectorCode;
        }
        String value = identifier;
        if (identifier.startsWith("http://") || identifier.startsWith("https://")) {
            value = mainHostLabel(identifier);
        }
        String slug = Slugs.slug(value, MAX_SLUG_LENGTH);
        return slug.isBlank() ? connectorCode : slug;
    }

    /**
     * Slug of a connection's display name, with the identifier's slug as the fallback: a user may
     * leave the name empty, and «Notion работа» must not collapse to nothing either.
     */
    public static String nameSlug(String connectorCode, String identifier, String name) {
        String slug = Slugs.slug(name, MAX_SLUG_LENGTH);
        return slug.isBlank() ? slug(connectorCode, identifier) : slug;
    }

    /** The main label of a URL's host: {@code mcp.context7.com/mcp} → {@code context7}. */
    private static String mainHostLabel(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isBlank()) {
                return url;
            }
            String[] labels = host.split("\\.");
            int end = labels.length - 1; // drop the TLD
            int start = 0;
            while (start < end && (labels[start].equals("www") || labels[start].equals("mcp")
                    || labels[start].equals("api") || labels[start].equals("app"))) {
                start++;
            }
            int idx = Math.max(start, end - 1);
            return idx < labels.length ? labels[idx] : host;
        } catch (Exception e) {
            return url;
        }
    }
}
