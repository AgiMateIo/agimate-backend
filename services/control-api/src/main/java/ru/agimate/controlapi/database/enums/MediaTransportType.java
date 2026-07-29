package ru.agimate.controlapi.database.enums;

/**
 * How a provider is asked to generate an image. A property of the provider, not of the model: the
 * same model id is reached over different dialects depending on who serves it (see
 * {@code docs/decisions/media-transport.md}).
 */
public enum MediaTransportType {

    /**
     * {@code POST /chat/completions} with {@code modalities: ["image","text"]}, the picture in
     * {@code message.images[]} as a data URI — the OpenRouter convention.
     */
    CHAT_MODALITIES,

    /**
     * {@code POST /media} with {@code async: false} and a model-native {@code input} — the Polza
     * dialect. The result comes back as a link and is downloaded separately.
     */
    MEDIA_ENDPOINT
}
