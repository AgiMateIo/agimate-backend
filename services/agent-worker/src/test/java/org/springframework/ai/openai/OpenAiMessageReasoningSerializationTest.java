package org.springframework.ai.openai;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import ru.agimate.agentworker.agent.TestTemplates;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.llm.LlmMessageMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline-проверка полного пути рассуждения обратно к провайдеру: {@code AgentChatMessage.reasoning}
 * → метаданные {@link AssistantMessage} ({@code LlmMessageMapper}) → поле {@code reasoning_content}
 * в теле запроса. Держать её нужно потому, что средний стык — ключ метаданных — константа, приватная
 * в Spring AI: наша копия ни во что не упирается на компиляции, и переименование наверху молча
 * перестало бы класть поле в запрос. Ровно этого молчания стоит 400 от DeepSeek: в режиме
 * размышления с параметром {@code tools} он требует рассуждение прошлых ходов обратно.
 *
 * <p>Тест в пакете {@code org.springframework.ai.openai} ради доступа к package-private
 * {@code OpenAiChatModel.createRequest}. Сети не требует.
 */
@DisplayName("Spring AI OpenAI — рассуждение ассистента уезжает обратно как reasoning_content")
class OpenAiMessageReasoningSerializationTest {

    private static final String REASONING = "сначала посмотрю погоду, потом отвечу";

    private final LlmMessageMapper mapper = new LlmMessageMapper(TestTemplates.of("ru"));

    private OpenAiChatOptions options() {
        return OpenAiChatOptions.builder().apiKey("test-key").model("deepseek-v4-pro").build();
    }

    private String requestBody(List<AgentChatMessage> conversation) {
        List<Message> messages = mapper.toSpringMessages(conversation);
        return OpenAiChatModel.builder().options(options()).build()
                .createRequest(new Prompt(messages, options()), false)
                .toString();
    }

    /** Ход с вызовом тула — ровно та форма, на которой падал прогон. */
    @Test
    @DisplayName("ассистент с рассуждением и вызовом тула → reasoning_content в теле запроса")
    void reasoningOnAToolCallingTurnReachesTheWire() {
        String body = requestBody(List.of(
                AgentChatMessage.user("какая погода в Берлине?"),
                AgentChatMessage.assistant("сейчас гляну", REASONING,
                        List.of(new AgentChatMessage.ToolCall("abc123xyz", "wx__get_weather", "{\"city\":\"Berlin\"}")))));

        assertTrue(body.contains("reasoning_content"), () -> "нет поля reasoning_content:\n" + body);
        assertTrue(body.contains(REASONING), () -> "текст рассуждения не доехал:\n" + body);
    }

    /** Правило DeepSeek покрывает и ходы без вызовов — простой ответ везёт рассуждение так же. */
    @Test
    @DisplayName("ассистент с рассуждением без вызовов → reasoning_content всё равно уезжает")
    void reasoningOnAPlainAnswerReachesTheWire() {
        String body = requestBody(List.of(
                AgentChatMessage.user("привет"),
                AgentChatMessage.assistant("здравствуйте", REASONING, List.of())));

        assertTrue(body.contains(REASONING), () -> "текст рассуждения не доехал:\n" + body);
    }

    @Test
    @DisplayName("модель не рассуждала → поля в запросе нет (пустая строка провайдеру не отправляется)")
    void noReasoningNoField() {
        String body = requestBody(List.of(
                AgentChatMessage.user("привет"),
                AgentChatMessage.assistant("здравствуйте", null, List.of())));

        assertFalse(body.contains("reasoning_content"), () -> "поле появилось без рассуждения:\n" + body);
    }
}
