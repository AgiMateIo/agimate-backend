package org.springframework.ai.openai;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline-проверка: extra_body из {@code LlmCredentials} (см. {@code ModelFactory} — прокидывается
 * в {@code OpenAiChatOptions.extraBody}) реально попадает в тело chat/completions-запроса.
 * Здесь живёт контракт с OpenRouter-совместимыми расширениями ({@code provider}-роутинг и т.п.) —
 * стандартной OpenAI-схеме эти поля неизвестны, доезжают только через extraBody. Тест в пакете
 * {@code org.springframework.ai.openai} ради package-private {@code createRequest}. Сети не требует.
 */
@DisplayName("Spring AI OpenAI — сериализация extra_body в тело запроса")
class OpenAiExtraBodySerializationTest {

    @Test
    @DisplayName("extraBody (OpenRouter provider-роутинг) попадает в ChatCompletionCreateParams")
    void extraBodySerializedIntoRequest() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey("test-key")
                .model("moonshotai/kimi-k2.5")
                .extraBody(Map.of("provider", Map.of(
                        "only", List.of("moonshotai"),
                        "require_parameters", true)))
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder().options(options).build();
        UserMessage user = UserMessage.builder().text("привет").build();

        ChatCompletionCreateParams params = model.createRequest(new Prompt(List.of(user), options), false);

        String body = params.toString();
        assertTrue(body.contains("provider"), () -> "нет provider-блока в запросе:\n" + body);
        assertTrue(body.contains("moonshotai"), () -> "нет значения only в запросе:\n" + body);
        assertTrue(body.contains("require_parameters"),
                () -> "нет require_parameters в запросе:\n" + body);
    }
}
