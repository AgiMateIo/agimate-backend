package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.agent.error.EmptyAnswerExhausted;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.error.MaxTurnsExceeded;
import ru.agimate.agentworker.agent.error.RunCancelled;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.model.ToolDef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleAgentTest {

    private static SimpleAgent agent(SimpleAgent.LlmCaller llm, SimpleAgent.ToolDispatcher dispatcher,
                                     SimpleAgent.RunObserver observer, int maxTurns) {
        return new SimpleAgent(llm, dispatcher, List.of(), maxTurns, observer);
    }

    private static SimpleAgent.LlmReply reply(AgentChatMessage message) {
        return SimpleAgent.LlmReply.of(message);
    }

    @Test
    @DisplayName("отмена: цикл встаёт на шве, не начав следующий ход")
    void cancelledAtTheSeam() {
        AtomicInteger calls = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> {
            calls.incrementAndGet();
            return reply(AgentChatMessage.assistant("не должно случиться", false, List.of()));
        };
        SimpleAgent.RunObserver cancelled = new SimpleAgent.RunObserver() {
            @Override
            public boolean cancelRequested() {
                return true;
            }
        };

        assertThrows(RunCancelled.class, () -> agent(llm, c -> List.of(), cancelled, 10)
                .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("drain: тул-ход текущего хода доводится и записывается, встаём только на следующем шве")
    void toolTurnDrainsBeforeStopping() {
        AtomicInteger turn = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant("вызываю", false,
                List.of(new AgentChatMessage.ToolCall("id" + turn.incrementAndGet(), "board.create_task", "{}"))));
        // Отмена «нажата», пока тул исполняется.
        SimpleAgent.ToolDispatcher dispatcher = c -> {
            cancelled.set(true);
            return List.of(new AgentChatMessage.ToolResult(c.get(0).id(), c.get(0).name(), "{}", false));
        };
        List<AgentChatMessage> projected = new ArrayList<>();
        SimpleAgent.RunObserver observer = new SimpleAgent.RunObserver() {
            @Override
            public void onMessages(List<AgentChatMessage> msgs, LlmMeta meta) {
                projected.addAll(msgs);
            }

            @Override
            public boolean cancelRequested() {
                return cancelled.get();
            }
        };
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        RunCancelled stop = assertThrows(RunCancelled.class,
                () -> agent(llm, dispatcher, observer, 10).run(conv));

        // Ход дошёл до конца: вызовы и результаты — обе записи, ни одного tool_use без ответа.
        assertEquals(2, projected.size());
        assertEquals(AgentChatMessage.Role.TOOL, projected.get(1).role());
        assertEquals(List.of("board.create_task"), stop.executedTools());
    }

    @Test
    @DisplayName("квитанция: провалившийся тул в отчёт не идёт, повторы схлопываются")
    void receiptListsOnlySuccessfulToolsOnce() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant(null, false,
                List.of(new AgentChatMessage.ToolCall("id" + turn.incrementAndGet(), "t", "{}"))));
        SimpleAgent.ToolDispatcher dispatcher = c -> List.of(
                new AgentChatMessage.ToolResult("a", "telegram.send_message", "{}", false),
                new AgentChatMessage.ToolResult("b", "sheets.add_rows", null, true),
                new AgentChatMessage.ToolResult("c", "telegram.send_message", "{}", false));
        SimpleAgent.RunObserver observer = new SimpleAgent.RunObserver() {
            @Override
            public boolean cancelRequested() {
                return turn.get() > 0;
            }
        };

        RunCancelled stop = assertThrows(RunCancelled.class,
                () -> agent(llm, dispatcher, observer, 10)
                        .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));

        assertEquals(List.of("telegram.send_message"), stop.executedTools());
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
        SimpleAgent.RunObserver observer = new SimpleAgent.RunObserver() {
            @Override
            public void onMessages(List<AgentChatMessage> msgs, LlmMeta meta) {
                newMsgs.addAll(msgs);
            }
        };
        SimpleAgent agent = agent(llm, dispatcher, observer, 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("final", agent.run(conv));
        // user + assistant(tool) + tool-result + assistant(final)
        assertEquals(4, conv.size());
        assertEquals(AgentChatMessage.Role.TOOL, conv.get(2).role());
        assertTrue(newMsgs.stream().anyMatch(m -> m.role() == AgentChatMessage.Role.TOOL));
    }

    @Test
    @DisplayName("meta вызова прокидывается в observer на assistant-ход; на tool-ход meta null")
    void metaReachesObserverForAssistantOnly() {
        LlmMeta meta = new LlmMeta("tool_calls", "gpt-5-mini", "wf-llm-1", null);
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? new SimpleAgent.LlmReply(AgentChatMessage.assistant(null, false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))), meta, null, null,
                        SimpleAgent.Completion.TOOL_CALLS)
                : new SimpleAgent.LlmReply(AgentChatMessage.assistant("final", false, List.of()),
                        new LlmMeta("stop", "gpt-5-mini", "wf-llm-2", null), null, null,
                        SimpleAgent.Completion.STOP);
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{}", false));

        List<LlmMeta> metas = new ArrayList<>();
        List<AgentChatMessage.Role> roles = new ArrayList<>();
        SimpleAgent.RunObserver observer = new SimpleAgent.RunObserver() {
            @Override
            public void onMessages(List<AgentChatMessage> msgs, LlmMeta m) {
                msgs.forEach(x -> {
                    roles.add(x.role());
                    metas.add(m);
                });
            }
        };
        agent(llm, dispatcher, observer, 10).run(new ArrayList<>(List.of(AgentChatMessage.user("hi"))));

        // assistant(tool) → meta вызова; tool-результаты → null; assistant(final) → своя meta.
        assertEquals(List.of(AgentChatMessage.Role.ASSISTANT, AgentChatMessage.Role.TOOL,
                AgentChatMessage.Role.ASSISTANT), roles);
        assertEquals("tool_calls", metas.get(0).finishReason());
        assertNull(metas.get(1));
        assertEquals("wf-llm-2", metas.get(2).callId());
    }

    @Test
    @DisplayName("usage вызова сурфейсится в observer.onUsage (happy path)")
    void usageSurfacedToObserver() {
        LlmUsage usage = new LlmUsage("wf-1", "prov", "gpt-5-mini", 100, 20, 0, 0);
        SimpleAgent.LlmCaller llm = (msgs, defs) -> new SimpleAgent.LlmReply(
                AgentChatMessage.assistant("done", false, List.of()), null, usage, null,
                SimpleAgent.Completion.STOP);
        List<LlmUsage> got = new ArrayList<>();
        SimpleAgent.RunObserver observer = new SimpleAgent.RunObserver() {
            @Override
            public void onUsage(LlmUsage u) {
                got.add(u);
            }
        };

        agent(llm, calls -> List.of(), observer, 10)
                .run(new ArrayList<>(List.of(AgentChatMessage.user("hi"))));

        assertEquals(List.of(usage), got);
    }

    @Test
    @DisplayName("incomplete (truncation): usage учтён ДО прерывания, затем LlmResponseIncomplete")
    void incompleteSurfacesUsageThenThrows() {
        LlmUsage usage = new LlmUsage("wf-1", "prov", "gpt-5-mini", 100, 20, 0, 0);
        SimpleAgent.LlmCaller llm = (msgs, defs) -> new SimpleAgent.LlmReply(
                AgentChatMessage.assistant("обрезано", false, List.of()), null, usage,
                LlmResponseIncomplete.Reason.LENGTH, SimpleAgent.Completion.UNKNOWN);
        List<LlmUsage> got = new ArrayList<>();
        SimpleAgent.RunObserver observer = new SimpleAgent.RunObserver() {
            @Override
            public void onUsage(LlmUsage u) {
                got.add(u);
            }
        };

        assertThrows(LlmResponseIncomplete.class, () -> agent(llm, calls -> List.of(), observer, 10)
                .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        assertEquals(List.of(usage), got);
    }

    @Test
    @DisplayName("onStart сурфейсит стартовый список (system+history+trigger) ДО первого вызова, один раз")
    void startPromptSurfacedBeforeLoop() {
        AtomicInteger calls = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> {
            calls.incrementAndGet();
            return reply(AgentChatMessage.assistant("done", false, List.of()));
        };
        List<List<AgentChatMessage>> snapshots = new ArrayList<>();
        SimpleAgent.RunObserver observer = new SimpleAgent.RunObserver() {
            @Override
            public void onStart(List<AgentChatMessage> messages) {
                snapshots.add(messages);
            }
        };
        SimpleAgent agent = agent(llm, c -> List.of(), observer, 10);

        List<AgentChatMessage> start = List.of(
                AgentChatMessage.system("sys"),
                AgentChatMessage.user("prev"),
                AgentChatMessage.user("hi"));
        agent.run(new ArrayList<>(start));

        // Ровно один снимок, снятый до какого-либо LLM-вызова, равный исходному списку.
        assertEquals(1, snapshots.size());
        assertEquals(start, snapshots.get(0));
        assertEquals(1, calls.get());
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
    @DisplayName("мягкая посадка: wrap-up-нотис за WRAP_UP_TURNS до капа, последний ход без тулов")
    void softLandingWrapUp() {
        // «Перфекционист»: пока тулы доступны — вызывает; на безтуловом ходе вынужден ответить.
        List<List<ToolDef>> defsPerTurn = new ArrayList<>();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> {
            defsPerTurn.add(defs);
            return defs.isEmpty()
                    ? reply(AgentChatMessage.assistant("вот что успел", false, List.of()))
                    : reply(AgentChatMessage.assistant(null, false,
                            List.of(new AgentChatMessage.ToolCall("id", "t", "{}"))));
        };
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id", "t", "{}", false));
        SimpleAgent agent = new SimpleAgent(llm, dispatcher,
                List.of(new ToolDef("t", "tool", "{}")), 4, null);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("вот что успел", agent.run(conv));
        // Ход 4 (кап) — без тулов, предыдущие — с тулами.
        assertEquals(4, defsPerTurn.size());
        assertTrue(defsPerTurn.get(2).size() > 0);
        assertTrue(defsPerTurn.get(3).isEmpty());
        // Нотис инжектится перед ходом maxTurns - WRAP_UP_TURNS + 1 и не дублируется.
        long notices = conv.stream()
                .filter(m -> SimpleAgent.WRAP_UP_NOTICE.equals(m.text()))
                .count();
        assertEquals(1, notices);
    }

    @Test
    @DisplayName("мягкая посадка не активируется при крошечном maxTurns (<= WRAP_UP_TURNS)")
    void softLandingSkippedForTinyCap() {
        List<List<ToolDef>> defsPerTurn = new ArrayList<>();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> {
            defsPerTurn.add(defs);
            return reply(AgentChatMessage.assistant(null, false,
                    List.of(new AgentChatMessage.ToolCall("id", "t", "{}"))));
        };
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id", "t", "{}", false));
        List<ToolDef> defs = List.of(new ToolDef("t", "tool", "{}"));
        SimpleAgent agent = new SimpleAgent(llm, dispatcher, defs, 2, null);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertThrows(MaxTurnsExceeded.class, () -> agent.run(conv));
        // Оба хода с тулами, нотис не инжектился.
        assertTrue(defsPerTurn.stream().allMatch(d -> !d.isEmpty()));
        assertTrue(conv.stream().noneMatch(m -> SimpleAgent.WRAP_UP_NOTICE.equals(m.text())));
    }

    @Test
    @DisplayName("guard: пустой ход не финал — переспрос, пустой ход выброшен из диалога")
    void emptyReplyRetried() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant("   ", false, List.of()))
                : reply(AgentChatMessage.assistant("готово", false, List.of()));
        SimpleAgent agent = agent(llm, calls -> List.of(), null, 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("готово", agent.run(conv));
        // user + нудж + assistant(финал): пустого assistant-хода в диалоге не остаётся.
        assertEquals(3, conv.size());
        assertEquals(SimpleAgent.EMPTY_ANSWER_NUDGE, conv.get(1).text());
        assertTrue(conv.stream().noneMatch(m -> m.role() == AgentChatMessage.Role.ASSISTANT
                && (m.text() == null || m.text().isBlank())));
    }

    @Test
    @DisplayName("guard: пустой ход после переспроса — EmptyAnswerExhausted, а не пустой финал")
    void emptyReplyAbortsAfterRetries() {
        SimpleAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant(null, false, List.of()));
        SimpleAgent agent = agent(llm, calls -> List.of(), null, 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertThrows(EmptyAnswerExhausted.class, () -> agent.run(conv));
        long nudges = conv.stream()
                .filter(m -> SimpleAgent.EMPTY_ANSWER_NUDGE.equals(m.text()))
                .count();
        assertEquals(SimpleAgent.MAX_EMPTY_RETRIES, nudges);
    }

    @Test
    @DisplayName("guard: пустой текст рядом с tool call'ами финалом не считается — обычный ход с тулами")
    void emptyTextWithToolCallsIsNotAffected() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant(null, false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))))
                : reply(AgentChatMessage.assistant("final", false, List.of()));
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{}", false));
        SimpleAgent agent = agent(llm, dispatcher, null, 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("final", agent.run(conv));
        assertTrue(conv.stream().noneMatch(m -> SimpleAgent.EMPTY_ANSWER_NUDGE.equals(m.text())));
    }

    @Test
    @DisplayName("пустой ход выброшен из диалога — и в журнал не уходит")
    void emptyTurnNotProjected() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant("   ", false, List.of()))
                : reply(AgentChatMessage.assistant("готово", false, List.of()));
        List<AgentChatMessage> projected = new ArrayList<>();
        SimpleAgent.RunObserver observer = new SimpleAgent.RunObserver() {
            @Override
            public void onMessages(List<AgentChatMessage> msgs, LlmMeta m) {
                projected.addAll(msgs);
            }
        };

        assertEquals("готово", agent(llm, calls -> List.of(), observer, 10)
                .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        assertEquals(List.of("готово"), projected.stream().map(AgentChatMessage::text).toList());
    }

    @Test
    @DisplayName("finish_reason STOP обрывает цикл, TOOL_CALLS — продолжает")
    void completionDrivesTheLoop() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? new SimpleAgent.LlmReply(AgentChatMessage.assistant("сейчас гляну", false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))), null, null, null,
                        SimpleAgent.Completion.TOOL_CALLS)
                : new SimpleAgent.LlmReply(AgentChatMessage.assistant("готово", false, List.of()),
                        null, null, null, SimpleAgent.Completion.STOP);
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{}", false));
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("готово", agent(llm, dispatcher, null, 10).run(conv));
        assertEquals(AgentChatMessage.Role.TOOL, conv.get(2).role());
    }

    @Test
    @DisplayName("TOOL_CALLS без распарсенных вызовов финалом не считается — переспрос")
    void toolCallsWithoutCallsIsNotFinal() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? new SimpleAgent.LlmReply(AgentChatMessage.assistant("сейчас вызову", false, List.of()),
                        null, null, null, SimpleAgent.Completion.TOOL_CALLS)
                : new SimpleAgent.LlmReply(AgentChatMessage.assistant("готово", false, List.of()),
                        null, null, null, SimpleAgent.Completion.STOP);

        assertEquals("готово", agent(llm, calls -> List.of(), null, 10)
                .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        assertEquals(2, turn.get());   // потерянный вызов не принят за ответ: модель переспрошена
    }

    @Test
    @DisplayName("TOOL_CALLS без вызовов и без текста → пустой ход выброшен, а не переслан модели")
    void lostToolCallWithEmptyTextIsDropped() {
        AtomicInteger turn = new AtomicInteger();
        List<List<AgentChatMessage>> sent = new ArrayList<>();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> {
            sent.add(List.copyOf(msgs));
            return turn.getAndIncrement() == 0
                    ? new SimpleAgent.LlmReply(AgentChatMessage.assistant("  ", false, List.of()),
                            null, null, null, SimpleAgent.Completion.TOOL_CALLS)
                    : new SimpleAgent.LlmReply(AgentChatMessage.assistant("готово", false, List.of()),
                            null, null, null, SimpleAgent.Completion.STOP);
        };

        assertEquals("готово", agent(llm, calls -> List.of(), null, 10)
                .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        // Во втором вызове пустого assistant-хода в контексте нет — строгие шлюзы такое отклоняют.
        assertTrue(sent.get(1).stream().noneMatch(m -> m.role() == AgentChatMessage.Role.ASSISTANT));
        assertEquals(SimpleAgent.EMPTY_ANSWER_NUDGE, sent.get(1).get(sent.get(1).size() - 1).text());
    }

    @Test
    @DisplayName("UNKNOWN (провайдер не сказал или сказал своё) → решает форма сообщения")
    void unknownFallsBackToMessageShape() {
        AtomicInteger turn = new AtomicInteger();
        SimpleAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant(null, false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))))
                : reply(AgentChatMessage.assistant("final", false, List.of()));
        SimpleAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{}", false));
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        // reply(...) не несёт completion → UNKNOWN: ход с вызовами продолжает цикл, без вызовов — финал.
        assertEquals("final", agent(llm, dispatcher, null, 10).run(conv));
        assertEquals(AgentChatMessage.Role.TOOL, conv.get(2).role());
    }
}
