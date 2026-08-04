package ru.agimate.controlapi.storage;

import lombok.Builder;

import java.time.Duration;
import java.util.UUID;

/**
 * Everything about a file except its bytes — the argument of {@link FileStorageService#store}. A
 * record rather than a parameter list: {@code origin}, {@code name} and {@code mime} are three
 * adjacent strings, and a positional mix-up between them would only surface as a corrupted row.
 *
 * @param userId   the owner and the access boundary
 * @param agentId  the agent that produced the file — provenance only; {@code null} where the
 *                 producer is genuinely unknown (ingest of an inbound message, an upload from a
 *                 device or the webchat UI). Never an access check: see the migration's comment
 * @param origin   provenance of the producer: {@code connector_code:connection} / tool / channel
 * @param name     the file name as the producer knew it; {@code null} when there is none (path
 *                 parts, control characters and quotes are stripped — the value reaches a
 *                 {@code Content-Disposition} header and a multipart part name)
 * @param mime     MIME of the contents
 * @param ttl      {@code null} — {@code app.files.default-ttl}
 */
@Builder
public record NewFile(
        UUID userId,
        UUID agentId,
        String origin,
        String name,
        String mime,
        long sizeBytes,
        Duration ttl
) {

    /** Beyond this a name is not a name but a payload; the column is TEXT, the limit is for consumers. */
    private static final int MAX_NAME_LENGTH = 255;

    public NewFile {
        name = sanitizeName(name);
    }

    private static String sanitizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String base = raw.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (base.isBlank() || base.equals(".") || base.equals("..")) {
            return null;
        }
        return base.length() > MAX_NAME_LENGTH ? base.substring(0, MAX_NAME_LENGTH) : base;
    }
}
