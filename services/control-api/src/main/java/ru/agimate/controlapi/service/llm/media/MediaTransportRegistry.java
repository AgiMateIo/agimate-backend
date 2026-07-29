package ru.agimate.controlapi.service.llm.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.MediaTransportType;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one place where «which dialect does this provider speak» is answered. The order is
 * deliberate — the provider's own field first, the default second, and nothing is ever guessed at
 * call time (see {@code docs/decisions/media-transport.md}): a wrong guess costs the user money,
 * because a media call that fails may still have been billed.
 */
@Component
@Slf4j
public class MediaTransportRegistry {

    /**
     * What a provider gets when its field is empty. The convention we started from, and the one both
     * OpenRouter-compatible gateways speak — a provider of another dialect has to say so explicitly.
     */
    static final MediaTransportType DEFAULT_TRANSPORT = MediaTransportType.CHAT_MODALITIES;

    private final Map<MediaTransportType, MediaTransport> transports;

    public MediaTransportRegistry(List<MediaTransport> transports) {
        this.transports = transports.stream()
                .collect(Collectors.toMap(MediaTransport::type, Function.identity()));
    }

    public MediaTransport forProvider(LlmProvider provider) {
        MediaTransportType type = provider.getMediaTransport() == null
                ? DEFAULT_TRANSPORT : provider.getMediaTransport();
        MediaTransport transport = transports.get(type);
        if (transport == null) {
            throw new MediaInferenceException("media transport " + type + " is configured on provider '"
                    + provider.getName() + "' but not implemented in this build");
        }
        return transport;
    }
}
