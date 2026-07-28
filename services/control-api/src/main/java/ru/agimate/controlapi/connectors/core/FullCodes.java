package ru.agimate.controlapi.connectors.core;

import lombok.experimental.UtilityClass;

import java.net.URI;

/**
 * Assembly of a connector instance's client-facing handle: {@code full_code = connector_code + "_" +
 * slug}. {@code slug} is a human-readable discriminator derived from the canonical identifier (a URL
 * → the host's main label, anything else → a slug). For example {@code (mcp,
 * https://mcp.context7.com/mcp)} → {@code mcp_context7}.
 */
@UtilityClass
public class FullCodes {

    public static String fullCode(String connectorCode, String identifier) {
        return connectorCode + "_" + slug(connectorCode, identifier);
    }

    public static String slug(String connectorCode, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return connectorCode;
        }
        String value = identifier;
        if (identifier.startsWith("http://") || identifier.startsWith("https://")) {
            value = mainHostLabel(identifier);
        }
        String slug = value.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isBlank() ? connectorCode : slug;
    }

    /** The main label of a URL's host: {@code mcp.context7.com/mcp} → {@code context7}. */
    private static String mainHostLabel(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isBlank()) {
                return url;
            }
            String[] labels = host.split("\\.");
            int end = labels.length - 1; // отбрасываем TLD
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
