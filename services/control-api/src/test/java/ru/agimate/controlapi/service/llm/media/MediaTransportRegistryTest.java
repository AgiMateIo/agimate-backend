package ru.agimate.controlapi.service.llm.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.MediaTransportType;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MediaTransportRegistry — выбор диалекта по провайдеру")
class MediaTransportRegistryTest {

    private final MediaTransport chat = stub(MediaTransportType.CHAT_MODALITIES);
    private final MediaTransport media = stub(MediaTransportType.MEDIA_ENDPOINT);

    private static MediaTransport stub(MediaTransportType type) {
        return new MediaTransport() {
            @Override
            public MediaTransportType type() {
                return type;
            }

            @Override
            public GeneratedImage generate(GenerationRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static LlmProvider provider(MediaTransportType transport) {
        return LlmProvider.builder()
                .id(UUID.randomUUID())
                .name("p")
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .mediaTransport(transport)
                .build();
    }

    @Test
    @DisplayName("поле пустое — дефолт CHAT_MODALITIES (провайдеры до этой колонки не меняют поведение)")
    void defaultsToChatModalities() {
        MediaTransportRegistry registry = new MediaTransportRegistry(List.of(chat, media));

        assertEquals(MediaTransportType.CHAT_MODALITIES,
                registry.forProvider(provider(null)).type());
    }

    @Test
    @DisplayName("поле задано — берётся оно, тип провайдера ни при чём")
    void explicitFieldWins() {
        MediaTransportRegistry registry = new MediaTransportRegistry(List.of(chat, media));

        assertEquals(MediaTransportType.MEDIA_ENDPOINT,
                registry.forProvider(provider(MediaTransportType.MEDIA_ENDPOINT)).type());
    }

    @Test
    @DisplayName("настроен транспорт, которого нет в сборке — внятный отказ, не NPE")
    void unknownTransportFailsLoudly() {
        MediaTransportRegistry registry = new MediaTransportRegistry(List.of(chat));

        MediaInferenceException e = assertThrows(MediaInferenceException.class,
                () -> registry.forProvider(provider(MediaTransportType.MEDIA_ENDPOINT)));

        assertTrue(e.getMessage().contains("MEDIA_ENDPOINT"), e.getMessage());
    }
}
