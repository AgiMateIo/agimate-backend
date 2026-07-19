package org.springframework.ai.openai;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline-проверка: как agent-worker строит {@link Media} для inbound-изображения (см.
 * {@code LlmMessageMapper}), реально ли Spring AI 2.0.0 сериализует его в {@code image_url}
 * запроса OpenAI. Тест в пакете {@code org.springframework.ai.openai} ради доступа к
 * package-private {@code OpenAiChatModel.createRequest}. Сети не требует.
 */
@DisplayName("Spring AI OpenAI — сериализация inbound-изображения в image_url")
class OpenAiMediaSerializationTest {

    private OpenAiChatOptions options() {
        return OpenAiChatOptions.builder().apiKey("test-key").model("gpt-4o").build();
    }

    private OpenAiChatModel model() {
        return OpenAiChatModel.builder().options(options()).build();
    }

    @Test
    @DisplayName("Media из ByteArrayResource (как в LlmMessageMapper) → image_url в запросе")
    void byteArrayResourceMediaSerialized() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
        MimeType mime = MimeType.valueOf("image/jpeg");
        UserMessage user = UserMessage.builder()
                .text("что на фото?")
                .media(List.of(Media.builder().mimeType(mime).data(new ByteArrayResource(jpeg)).build()))
                .build();

        ChatCompletionCreateParams params = model().createRequest(new Prompt(List.of(user), options()), false);

        String body = params.toString();
        assertTrue(body.contains("image_url") || body.contains("imageUrl"),
                () -> "нет image_url-части в запросе:\n" + body);
        assertTrue(body.contains("data:image/jpeg;base64,"),
                () -> "нет base64 data-URI изображения в запросе:\n" + body);
    }

    @Test
    @DisplayName("контроль: Media из byte[] напрямую → image_url в запросе")
    void rawBytesMediaSerialized() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
        UserMessage user = UserMessage.builder()
                .text("что на фото?")
                .media(List.of(Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType("image/jpeg")).data((Object) jpeg).build()))
                .build();

        ChatCompletionCreateParams params = model().createRequest(new Prompt(List.of(user), options()), false);

        assertTrue(params.toString().contains("data:image/jpeg;base64,"),
                () -> "нет base64 data-URI изображения:\n" + params);
    }
}
