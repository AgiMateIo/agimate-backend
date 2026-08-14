package ru.agimate.agentworker.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code thinking} flag of an assistant turn — the value that drives the 💭 progress marker.
 * Only the flag lives on the message; the reasoning text itself travels on {@code LlmMeta} and is
 * persisted as {@code agent_run_turns.thinking_text}. It is read out of Spring AI's assistant
 * metadata by a key whose constant is private upstream, so this locks our half of that contract.
 */
@DisplayName("LlmMessageMapper — флаг thinking из reasoning-метаданных")
class LlmMessageMapperThinkingTest {

    private final LlmMessageMapper mapper = new LlmMessageMapper();

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
    @DisplayName("непустой reasoningContent → thinking = true, текст и тулы не затронуты")
    void reasoningContentSetsFlag() {
        AgentChatMessage msg = mapper.fromResponse(response(metadata("reasoningContent", "сначала подумаю")));

        assertTrue(msg.thinking());
        assertEquals("готово", msg.text());
    }

    @Test
    @DisplayName("пустой reasoningContent (модель не рассуждала) → thinking = false")
    void blankReasoningContentLeavesFlagOff() {
        assertFalse(mapper.fromResponse(response(metadata("reasoningContent", ""))).thinking());
        assertFalse(mapper.fromResponse(response(metadata("reasoningContent", "   "))).thinking());
    }

    @Test
    @DisplayName("не-строка под ключом не считается рассуждением")
    void nonStringReasoningIgnored() {
        assertFalse(mapper.fromResponse(response(metadata("reasoningContent", 42))).thinking());
    }

    @Test
    @DisplayName("ключа нет вовсе → фолбэк на любой reasoning-ключ (переименование в Spring AI)")
    void fallsBackToAnyReasoningKey() {
        assertTrue(mapper.fromResponse(response(metadata("reasoning_content", "подумал"))).thinking());
        assertTrue(mapper.fromResponse(response(metadata("reasoning", "подумал"))).thinking());
    }

    @Test
    @DisplayName("метаданных о рассуждении нет — thinking = false, а не исключение")
    void noReasoningMetadataAtAll() {
        assertFalse(mapper.fromResponse(response(metadata(null, null))).thinking());
    }
}
