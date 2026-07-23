package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.agent.error.ImitationLoopExhausted;
import ru.agimate.agentworker.agent.error.MaxTurnsExceeded;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleAgentTest {

    private static SimpleAgent agent(SimpleAgent.LlmCaller llm, SimpleAgent.ToolDispatcher dispatcher,
                                     SimpleAgent.TurnSink onNewMessages, int maxTurns) {
        return new SimpleAgent(llm, dispatcher, List.of(), maxTurns, onNewMessages);
    }

    private static SimpleAgent.LlmReply reply(AgentChatMessage message) {
        return SimpleAgent.LlmReply.of(message);
    }

    @Test
    @DisplayName("returns the final text when the model emits no tool calls")
    void finalAnswer() {
        SimpleAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant("done", false, List.of()));
        SimpleAgent agent = agent(llm, calls -> List.of(), null, 10);
        assertEquals("done", agent.run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
    }

    @Test
    @DisplayName("dispatches tool calls, appends results, then finishes on the next turn")
    void toolThenAnswer() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant(null, false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))))
                : reply(AgentChatMessage.assistant("final", false, List.of()));
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{\"ok\":true}", false));

        List<AgentChatMessage> newMsgs = new ArrayList<>();
        SimpleAgent agent = agent(llm, dispatcher, (msgs, meta) -> newMsgs.addAll(msgs), 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("final", agent.run(conv));
        // user + assistant(tool) + tool-result + assistant(final)
        assertEquals(4, conv.size());
        assertEquals(AgentChatMessage.Role.TOOL, conv.get(2).role());
        assertTrue(newMsgs.stream().anyMatch(m -> m.role() == AgentChatMessage.Role.TOOL));
    }

    @Test
    @DisplayName("meta вызова прокидывается в sink на assistant-ход; на tool-ход meta null")
    void metaReachesSinkForAssistantOnly() {
        LlmMeta meta = new LlmMeta("tool_calls", "gpt-5-mini", "wf-llm-1");
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? new SimpleAgent.LlmReply(AgentChatMessage.assistant(null, false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))), meta)
                : new SimpleAgent.LlmReply(AgentChatMessage.assistant("final", false, List.of()),
                        new LlmMeta("stop", "gpt-5-mini", "wf-llm-2"));
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{}", false));

        List<LlmMeta> metas = new ArrayList<>();
        List<AgentChatMessage.Role> roles = new ArrayList<>();
        SimpleAgent.TurnSink sink = (msgs, m) -> msgs.forEach(x -> {
            roles.add(x.role());
            metas.add(m);
        });
        agent(llm, dispatcher, sink, 10).run(new ArrayList<>(List.of(AgentChatMessage.user("hi"))));

        // assistant(tool) → meta вызова; tool-результаты → null; assistant(final) → своя meta.
        assertEquals(List.of(AgentChatMessage.Role.ASSISTANT, AgentChatMessage.Role.TOOL,
                AgentChatMessage.Role.ASSISTANT), roles);
        assertEquals("tool_calls", metas.get(0).finishReason());
        assertNull(metas.get(1));
        assertEquals("wf-llm-2", metas.get(2).callId());
    }

    @Test
    @DisplayName("throws MaxTurnsExceeded when the loop never produces a final reply")
    void maxTurns() {
        SimpleAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant(null, false,
                List.of(new AgentChatMessage.ToolCall("id", "t", "{}"))));
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id", "t", "{}", false));
        SimpleAgent agent = agent(llm, dispatcher, null, 3);
        assertThrows(MaxTurnsExceeded.class, () -> agent.run(new ArrayList<>()));
    }

    @Test
    @DisplayName("guard: имитация вызова текстом «🔧 …» не принимается как финал — корректирующий ход")
    void textToolCallImitationCorrected() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant("Сейчас проверю.\n\n🔧 desktop.tool.apps.list", false, List.of()))
                : reply(AgentChatMessage.assistant("готово", false, List.of()));
        SimpleAgent agent = agent(llm, calls -> List.of(), null, 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("готово", agent.run(conv));
        // user + assistant(имитация) + корректирующий user + assistant(финал)
        assertEquals(4, conv.size());
        assertEquals(SimpleAgent.IMITATION_CORRECTION, conv.get(2).text());
    }

    @Test
    @DisplayName("guard: имитация «[вызван инструмент …]» тоже перехватывается")
    void bracketImitationCorrected() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant("[вызван инструмент time.now]", false, List.of()))
                : reply(AgentChatMessage.assistant("ok", false, List.of()));
        SimpleAgent agent = agent(llm, calls -> List.of(), null, 10);
        assertEquals("ok", agent.run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
    }

    @Test
    @DisplayName("guard: после исчерпания коррекций имитация не принимается — ImitationLoopExhausted")
    void imitationAbortsAfterMaxCorrections() {
        String imitation = "🔧 t";
        SimpleAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant(imitation, false, List.of()));
        SimpleAgent agent = agent(llm, calls -> List.of(), null, 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertThrows(ImitationLoopExhausted.class, () -> agent.run(conv));
        // Ровно MAX_IMITATION_CORRECTIONS корректирующих ходов, затем abort (без сырой имитации в финале).
        long corrections = conv.stream()
                .filter(m -> SimpleAgent.IMITATION_CORRECTION.equals(m.text()))
                .count();
        assertEquals(SimpleAgent.MAX_IMITATION_CORRECTIONS, corrections);
    }

    @Test
    @DisplayName("guard: упоминание 🔧 в середине строки финала не триггерит коррекцию")
    void inlineEmojiNotCorrected() {
        SimpleAgent.LlmCaller llm = (msgs, defs) ->
                reply(AgentChatMessage.assistant("я использовал 🔧 для задачи", false, List.of()));
        SimpleAgent agent = agent(llm, calls -> List.of(), null, 10);
        assertEquals("я использовал 🔧 для задачи",
                agent.run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
    }
}
