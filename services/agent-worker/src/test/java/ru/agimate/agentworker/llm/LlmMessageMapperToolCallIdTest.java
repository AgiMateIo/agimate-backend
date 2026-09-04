package ru.agimate.agentworker.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import ru.agimate.agentworker.agent.TestTemplates;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool call ids are minted by us, not taken from the provider. The regression this locks: a
 * provider numbering its calls positionally ({@code call_0} restarting every response) burned the
 * backend's idempotency key — the second call under that id came back as «reused with a different
 * input» and the agent spent the rest of the run retrying into it.
 */
@DisplayName("LlmMessageMapper — свои tool_call_id вместо провайдерских")
class LlmMessageMapperToolCallIdTest {

    private final LlmMessageMapper mapper = new LlmMessageMapper(TestTemplates.of("ru"));

    private static ChatResponse response(AssistantMessage.ToolCall... toolCalls) {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCalls))
                .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static AssistantMessage.ToolCall providerCall(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    private List<String> ids(ChatResponse response, String callId) {
        return mapper.fromResponse(response, callId).toolCalls().stream()
                .map(AgentChatMessage.ToolCall::id)
                .toList();
    }

    @Test
    @DisplayName("провайдер переиспользует call_0 между ходами — наши id всё равно разные")
    void repeatedProviderIdStillMintsDistinctIds() {
        // Two turns of one run: the same provider id, different arguments — the shape that broke.
        List<String> first = ids(response(providerCall("call_0", "board__create_task", "{\"title\":\"a\"}")),
                "wf-llm-1");
        List<String> second = ids(response(providerCall("call_0", "board__create_task", "{\"title\":\"b\"}")),
                "wf-llm-2");

        assertNotEquals(first.get(0), second.get(0));
        assertNotEquals("call_0", first.get(0));
    }

    @Test
    @DisplayName("несколько вызовов в одном ответе получают разные id")
    void callsWithinOneResponseGetDistinctIds() {
        List<String> minted = ids(response(
                providerCall("call_0", "board__get_tasks", "{}"),
                providerCall("call_1", "time__current_datetime", "{}"),
                providerCall("call_2", "board__get_tasks", "{}")), "wf-llm-1");

        assertEquals(3, Set.copyOf(minted).size());
    }

    @Test
    @DisplayName("replay того же LLM-вызова чеканит те же id — иначе тул выполнится дважды")
    void replayOfTheSameCallMintsTheSameIds() {
        ChatResponse response = response(
                providerCall("call_0", "board__get_tasks", "{}"),
                providerCall("call_1", "time__current_datetime", "{}"));

        assertEquals(ids(response, "wf-llm-1"), ids(response, "wf-llm-1"));
    }

    @Test
    @DisplayName("формат — ровно 9 символов a-zA-Z0-9: пересечение требований провайдеров")
    void mintedIdFitsTheStrictestProvider() {
        // Mistral: «must be a-z, A-Z, 0-9, with a length of 9»; OpenAI caps the id at 40 characters.
        List<String> minted = ids(response(
                providerCall("call_0", "board__get_tasks", "{}"),
                providerCall("call_1", "time__current_datetime", "{}")), "wf-llm-1");

        for (String id : minted) {
            assertTrue(id.matches("[a-zA-Z0-9]{9}"), () -> "unusable tool call id: " + id);
        }
    }

    @Test
    @DisplayName("без callId (вне DBOS) id всё ещё уникальны, а не пустые")
    void mintsWithoutACallId() {
        Set<String> minted = ids(response(
                providerCall("call_0", "board__get_tasks", "{}"),
                providerCall("call_1", "time__current_datetime", "{}")), "  ")
                .stream().collect(Collectors.toSet());

        assertEquals(2, minted.size());
        minted.forEach(id -> assertTrue(id.matches("[a-zA-Z0-9]{9}")));
    }
}
