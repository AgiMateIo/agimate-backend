package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.agent.error.AgentInterrupted;
import ru.agimate.agentworker.agent.error.MaxTurnsExceeded;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleAgentTest {

    private static SimpleAgent agent(SimpleAgent.LlmCaller llm, SimpleAgent.ToolDispatcher dispatcher,
                                     java.util.function.Consumer<List<AgentChatMessage>> onNewMessages,
                                     SimpleAgent.Checkpointer cp, int maxTurns) {
        return new SimpleAgent(llm, dispatcher, List.of(), maxTurns, onNewMessages, cp, null);
    }

    @Test
    @DisplayName("returns the final text when the model emits no tool calls")
    void finalAnswer() {
        SimpleAgent.LlmCaller llm = (msgs, defs) -> AgentChatMessage.assistant("done", false, List.of());
        SimpleAgent agent = agent(llm, calls -> List.of(), null, null, 10);
        assertEquals("done", agent.run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
    }

    @Test
    @DisplayName("dispatches tool calls, appends results, then finishes on the next turn")
    void toolThenAnswer() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? AgentChatMessage.assistant(null, false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}")))
                : AgentChatMessage.assistant("final", false, List.of());
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{\"ok\":true}", false));

        List<AgentChatMessage> newMsgs = new ArrayList<>();
        SimpleAgent agent = agent(llm, dispatcher, newMsgs::addAll, null, 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("final", agent.run(conv));
        // user + assistant(tool) + tool-result + assistant(final)
        assertEquals(4, conv.size());
        assertEquals(AgentChatMessage.Role.TOOL, conv.get(2).role());
        assertTrue(newMsgs.stream().anyMatch(m -> m.role() == AgentChatMessage.Role.TOOL));
    }

    @Test
    @DisplayName("a checkpoint interrupt aborts the loop with AgentInterrupted")
    void interrupt() {
        SimpleAgent.LlmCaller llm = (msgs, defs) -> AgentChatMessage.assistant("done", false, List.of());
        SimpleAgent.Checkpointer cp = (msgs, phase) -> new SimpleAgent.CheckpointResult(List.of(), true);
        SimpleAgent agent = agent(llm, calls -> List.of(), null, cp, 10);
        assertThrows(AgentInterrupted.class, () -> agent.run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
    }

    @Test
    @DisplayName("a checkpoint steer injection keeps the loop going and delivers the interim answer")
    void steer() {
        AtomicInteger llmTurns = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> AgentChatMessage.assistant(
                "answer-" + llmTurns.incrementAndGet(), false, List.of());
        // Inject one steer on the first checkpoint, then none — so the loop runs exactly twice.
        AtomicInteger checks = new AtomicInteger();
        SimpleAgent.Checkpointer cp = (msgs, phase) -> checks.getAndIncrement() == 0
                ? new SimpleAgent.CheckpointResult(List.of(AgentChatMessage.user("follow up")), false)
                : SimpleAgent.CheckpointResult.NONE;
        List<String> interim = new ArrayList<>();
        SimpleAgent agent = new SimpleAgent(llm, calls -> List.of(), List.of(), 10, null, cp, interim::add);

        assertEquals("answer-2", agent.run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        // The answer completed before the steer folded in is delivered, not silently dropped.
        assertEquals(List.of("answer-1"), interim);
    }

    @Test
    @DisplayName("throws MaxTurnsExceeded when the loop never produces a final reply")
    void maxTurns() {
        SimpleAgent.LlmCaller llm = (msgs, defs) -> AgentChatMessage.assistant(null, false,
                List.of(new AgentChatMessage.ToolCall("id", "t", "{}")));
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id", "t", "{}", false));
        SimpleAgent agent = agent(llm, dispatcher, null, null, 3);
        assertThrows(MaxTurnsExceeded.class, () -> agent.run(new ArrayList<>()));
    }
}
