package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.storage.FileLink;
import ru.agimate.controlapi.storage.FileNames;

import java.util.Map;

/**
 * An attachment of a multimodal message (image, audio, file).
 *
 * <p>Phase 1 (text) does not use the parts field; the model was introduced up front so the
 * {@link InboundMessage}/{@link OutboundMessage} contract would not have to change when media
 * arrives.
 *
 * @param type       attachment type (e.g. {@code "image"}, {@code "audio"}, {@code "file"})
 * @param storageRef reference to the contents in object storage
 * @param version    the version of the file the message carries — what was sent, even after the
 *                   file is rewritten
 * @param mime       MIME type
 * @param size       size in bytes
 * @param meta       arbitrary metadata (file name, duration, transcription, ...)
 */
public record Part(
        String type,
        String storageRef,
        int version,
        String mime,
        long size,
        Map<String, Object> meta
) {

    /** Attachment type from the MIME: {@code image|video|audio|file} — how to render it or feed it to the LLM. */
    public static String typeForMime(String mime) {
        return FileNames.kindForMime(mime);
    }

    /** The version of a part stored as a map; a part written before files had versions is version 1. */
    public static int storedVersion(Map<String, Object> stored) {
        return stored.get("version") instanceof Number n ? n.intValue() : FileLink.FIRST_VERSION;
    }
}
