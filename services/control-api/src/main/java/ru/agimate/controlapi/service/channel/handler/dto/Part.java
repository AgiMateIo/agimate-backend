package ru.agimate.controlapi.service.channel.handler.dto;

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
 * @param mime       MIME type
 * @param size       size in bytes
 * @param meta       arbitrary metadata (file name, duration, transcription, ...)
 */
public record Part(
        String type,
        String storageRef,
        String mime,
        long size,
        Map<String, Object> meta
) {

    /** Attachment type from the MIME: {@code image|video|audio|file} — how to render it or feed it to the LLM. */
    public static String typeForMime(String mime) {
        return FileNames.kindForMime(mime);
    }
}
