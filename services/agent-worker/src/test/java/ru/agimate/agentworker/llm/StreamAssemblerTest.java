package ru.agimate.agentworker.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import ru.agimate.agentworker.agent.TestTemplates;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("сборка хода из потока: то же сообщение, что и из целого ответа")
class StreamAssemblerTest {

    private final LlmMessageMapper mapper = new LlmMessageMapper(TestTemplates.of("ru"));

    private static ChatResponse chunk(AssistantMessage delta, String finishReason) {
        ChatGenerationMetadata metadata = finishReason == null
                ? ChatGenerationMetadata.NULL
                : ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(delta, metadata)));
    }

    private static AssistantMessage text(String value) {
        return new AssistantMessage(value);
    }

    private static AssistantMessage thought(String value) {
        return AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", value))
                .build();
    }

    @Test
    @DisplayName("текстовые дельты склеиваются, finish_reason берётся последний непустой")
    void concatenatesText() {
        StreamAssembler assembler = new StreamAssembler(mapper);

        assembler.accept(chunk(text("при"), null));
        assembler.accept(chunk(text("вет"), null));
        assembler.accept(chunk(text(""), "stop"));

        AgentChatMessage turn = assembler.message("run-1-0");
        assertEquals("привет", turn.text());
        assertEquals("stop", assembler.finishReason());
        assertNull(turn.reasoning());
    }

    /**
     * Чанки построены так, как их отдаёт Spring AI: {@code reasoning_content} в каждом — накопленный
     * итог, а не дельта (это закреплено на живом стабе в {@code LlmCallTest}). Тест на дельтах
     * прошёл бы и при склейке — и пропустил бы квадратичное размножение.
     */
    @Test
    @DisplayName("рассуждение берётся последним накопленным, не склеивается из чанков")
    void takesTheLastReasoning() {
        StreamAssembler assembler = new StreamAssembler(mapper);

        assembler.accept(chunk(thought("сна"), null));
        assembler.accept(chunk(thought("сна\n\nчала"), null));
        assembler.accept(chunk(AssistantMessage.builder()
                .content("ответ")
                .properties(Map.of("reasoningContent", "сна\n\nчала"))
                .build(), "stop"));

        AgentChatMessage turn = assembler.message("run-1-0");
        assertEquals("сна\n\nчала", turn.reasoning());
        assertTrue(turn.thinking());
        assertEquals("ответ", turn.text());
    }

    @Test
    @DisplayName("id тул-вызова чеканится так же, как на нестриминговом пути — на нём висит идемпотентность")
    void mintsTheSameToolCallIds() {
        StreamAssembler assembler = new StreamAssembler(mapper);
        AssistantMessage withCalls = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call_0", "function", "time_now", "{}"),
                        new AssistantMessage.ToolCall("call_1", "function", "memory_read", "{\"k\":1}")))
                .build();

        assembler.accept(chunk(withCalls, "tool_calls"));

        AgentChatMessage turn = assembler.message("run-1-0");
        assertEquals(2, turn.toolCalls().size());
        assertEquals(LlmMessageMapper.mintToolCallId("run-1-0", 0), turn.toolCalls().get(0).id());
        assertEquals(LlmMessageMapper.mintToolCallId("run-1-0", 1), turn.toolCalls().get(1).id());
        assertEquals("time_now", turn.toolCalls().get(0).name());
        assertEquals("{\"k\":1}", turn.toolCalls().get(1).argumentsJson());
    }

    @Test
    @DisplayName("хвостовой чанк со счётчиками побеждает пустые, но первый ненулевой не затирается нулём")
    void takesTheLastNonEmptyUsage() {
        StreamAssembler assembler = new StreamAssembler(mapper);

        assembler.accept(new ChatResponse(List.of(new Generation(text("a"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(0, 0)).build()));
        assembler.accept(new ChatResponse(List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build()));
        assembler.accept(new ChatResponse(List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(0, 0)).build()));

        assertEquals(100, assembler.usage().getPromptTokens());
        assertEquals(20, assembler.usage().getCompletionTokens());
    }

    @Test
    @DisplayName("«началось» — граница повтора: до первой дельты повторять нечего, после уже оплачено")
    void marksWhenTheAnswerStarted() {
        StreamAssembler assembler = new StreamAssembler(mapper);
        assertFalse(assembler.started());

        assembler.accept(new ChatResponse(List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(1, 0)).build()));
        assertFalse(assembler.started(), "метаданные — ещё не ответ");

        assembler.accept(chunk(text("п"), null));
        assertTrue(assembler.started());
        assertEquals(2, assembler.chunks());
        assertTrue(assembler.summary().startsWith("2 chunks (first after "), assembler.summary());
        assertTrue(assembler.summary().endsWith("1 chars text, 0 chars reasoning, 0 tool calls"),
                assembler.summary());
    }

    @Test
    @DisplayName("«принёс что-то» — то, что сбрасывает бюджет: пустая дельта и повтор рассуждения не считаются")
    void tellsACarryingChunkFromAnEmptyOne() {
        StreamAssembler assembler = new StreamAssembler(mapper);

        assertFalse(assembler.accept(chunk(text(""), null)), "role-прелюдия");
        assertTrue(assembler.accept(chunk(thought("сна"), null)), "рассуждение");
        assertFalse(assembler.accept(chunk(thought("сна"), null)), "тот же накопленный итог");
        assertTrue(assembler.accept(chunk(thought("снача"), null)), "итог подрос");
        assertTrue(assembler.accept(chunk(text("п"), null)), "текст");
        assertTrue(assembler.accept(chunk(text(""), "stop")), "finish_reason");
        assertTrue(assembler.accept(new ChatResponse(List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build())), "usage");
        assertFalse(assembler.accept(new ChatResponse(List.of())), "чанк без generation");

        assertTrue(assembler.summary().startsWith("8 chunks (3 empty, first after "), assembler.summary());
    }

    @Test
    @DisplayName("сводка пустого потока: «none» вместо времени до первого чанка")
    void summarisesAnEmptyStream() {
        assertEquals("0 chunks (none), 0 chars text, 0 chars reasoning, 0 tool calls",
                new StreamAssembler(mapper).summary());
    }
}
