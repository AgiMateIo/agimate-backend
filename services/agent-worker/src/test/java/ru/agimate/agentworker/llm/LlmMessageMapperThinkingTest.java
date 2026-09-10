package ru.agimate.agentworker.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import ru.agimate.agentworker.agent.TestTemplates;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Чтение рассуждения из ответа провайдера. Текст живёт на самом сообщении: он и питает 💭-маркер
 * ({@code thinking()} выводится из него), и уезжает обратно провайдеру следующим запросом —
 * {@code OpenAiMessageReasoningSerializationTest} проверяет вторую половину пути. Ключ метаданных,
 * из-под которого он читается, — приватная константа Spring AI, так что здесь заперта наша половина
 * этого договора.
 */
@DisplayName("LlmMessageMapper — рассуждение из reasoning-метаданных ответа")
class LlmMessageMapperThinkingTest {

    private static final String CALL_ID = "wf-llm-1";

    private final LlmMessageMapper mapper = new LlmMessageMapper(TestTemplates.of("ru"));

    /** As {@code OpenAiChatModel} builds it: the reasoning key is always present, empty when absent on the wire. */
    private static ChatResponse response(Map<String, Object> metadata) {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("готово")
                .properties(metadata)
                .toolCalls(List.of())
                .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static Map<String, Object> metadata(String key, Object value) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("finishReason", "stop");
        if (key != null) {
            metadata.put(key, value);
        }
        return metadata;
    }

    @Test
    @DisplayName("непустой reasoningContent → текст рассуждения на сообщении, thinking = true")
    void reasoningContentKeptOnTheMessage() {
        AgentChatMessage msg = mapper.fromResponse(response(metadata("reasoningContent", "сначала подумаю")), CALL_ID);

        // Не флаг: этот текст DeepSeek требует обратно в следующем запросе с tools.
        assertEquals("сначала подумаю", msg.reasoning());
        assertTrue(msg.thinking());
        assertEquals("готово", msg.text());
    }

    @Test
    @DisplayName("пустой reasoningContent (модель не рассуждала) → thinking = false")
    void blankReasoningContentLeavesFlagOff() {
        assertFalse(mapper.fromResponse(response(metadata("reasoningContent", "")), CALL_ID).thinking());
        assertFalse(mapper.fromResponse(response(metadata("reasoningContent", "   ")), CALL_ID).thinking());
    }

    @Test
    @DisplayName("не-строка под ключом не считается рассуждением")
    void nonStringReasoningIgnored() {
        assertFalse(mapper.fromResponse(response(metadata("reasoningContent", 42)), CALL_ID).thinking());
    }

    @Test
    @DisplayName("ключа нет вовсе → фолбэк на любой reasoning-ключ (переименование в Spring AI)")
    void fallsBackToAnyReasoningKey() {
        assertTrue(mapper.fromResponse(response(metadata("reasoning_content", "подумал")), CALL_ID).thinking());
        assertTrue(mapper.fromResponse(response(metadata("reasoning", "подумал")), CALL_ID).thinking());
    }

    @Test
    @DisplayName("метаданных о рассуждении нет — thinking = false, а не исключение")
    void noReasoningMetadataAtAll() {
        assertFalse(mapper.fromResponse(response(metadata(null, null)), CALL_ID).thinking());
    }
}
