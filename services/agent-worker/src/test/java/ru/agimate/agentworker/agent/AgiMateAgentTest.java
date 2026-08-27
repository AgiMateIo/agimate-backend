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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgiMateAgentTest {

    private static AgiMateAgent agent(AgiMateAgent.LlmCaller llm, AgiMateAgent.ToolDispatcher dispatcher,
                                     AgiMateAgent.RunObserver observer, int maxTurns) {
        return new AgiMateAgent(llm, dispatcher, List.of(), maxTurns, observer);
    }

    private static AgiMateAgent.LlmReply reply(AgentChatMessage message) {
        return AgiMateAgent.LlmReply.of(message);
    }

    @Test
    @DisplayName("отмена: цикл встаёт на шве, не начав следующий ход")
    void cancelledAtTheSeam() {
        AtomicInteger calls = new AtomicInteger();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> {
            calls.incrementAndGet();
            return reply(AgentChatMessage.assistant("не должно случиться", false, List.of()));
        };
        AgiMateAgent.RunObserver cancelled = new AgiMateAgent.RunObserver() {
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
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant("вызываю", false,
                List.of(new AgentChatMessage.ToolCall("id" + turn.incrementAndGet(), "board.create_task", "{}"))));
        // Отмена «нажата», пока тул исполняется.
        AgiMateAgent.ToolDispatcher dispatcher = c -> {
            cancelled.set(true);
            return List.of(new AgentChatMessage.ToolResult(c.get(0).id(), c.get(0).name(), "{}", false));
        };
        List<AgentChatMessage> projected = new ArrayList<>();
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
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
    @DisplayName("отмена до диспатча: тулы не вызываются вовсе, но пара tool_use/tool_result закрыта")
    void cancelledBeforeDispatchSkipsTheCalls() {
        AtomicBoolean dispatched = new AtomicBoolean();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant("сейчас отправлю", false,
                List.of(new AgentChatMessage.ToolCall("c1", "telegram.send_message", "{}"))));
        AgiMateAgent.ToolDispatcher dispatcher = c -> {
            dispatched.set(true);
            return List.of();
        };
        // На первом шве отмены ещё нет, к моменту диспатча — уже да.
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
            private int seen;

            @Override
            public boolean cancelRequested() {
                return seen++ > 0;
            }
        };
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        RunCancelled stop = assertThrows(RunCancelled.class,
                () -> agent(llm, dispatcher, observer, 10).run(conv));

        assertFalse(dispatched.get());
        assertTrue(stop.executedTools().isEmpty());
        AgentChatMessage toolMsg = conv.get(conv.size() - 1);
        assertEquals(AgentChatMessage.Role.TOOL, toolMsg.role());
        assertEquals("c1", toolMsg.toolResults().get(0).id());
        assertTrue(toolMsg.toolResults().get(0).failed());
    }

    @Test
    @DisplayName("квитанция: провалившийся тул в отчёт не идёт, повторы схлопываются")
    void receiptListsOnlySuccessfulToolsOnce() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant(null, false,
                List.of(new AgentChatMessage.ToolCall("c1", "t", "{}"))));
        AgiMateAgent.ToolDispatcher dispatcher = c -> {
            cancelled.set(true);
            return List.of(
                    new AgentChatMessage.ToolResult("a", "telegram.send_message", "{}", false),
                    new AgentChatMessage.ToolResult("b", "sheets.add_rows", null, true),
                    new AgentChatMessage.ToolResult("c", "telegram.send_message", "{}", false));
        };
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
            @Override
            public boolean cancelRequested() {
                return cancelled.get();
            }
        };

        RunCancelled stop = assertThrows(RunCancelled.class,
                () -> agent(llm, dispatcher, observer, 10)
                        .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));

        assertEquals(List.of("telegram.send_message"), stop.executedTools());
    }

    @Test
    @DisplayName("квитанция не тянет тулы из истории прошлых ранов")
    void receiptIgnoresHistory() {
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant("ответ", false, List.of()));
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
            @Override
            public boolean cancelRequested() {
                return true;
            }
        };
        // Хвост прошлого рана приезжает воркеру в том же списке сообщений.
        List<AgentChatMessage> conv = new ArrayList<>(List.of(
                AgentChatMessage.user("раньше"),
                AgentChatMessage.toolResults(List.of(
                        new AgentChatMessage.ToolResult("old", "board.create_task", "{}", false))),
                AgentChatMessage.user("hi")));

        RunCancelled stop = assertThrows(RunCancelled.class,
                () -> agent(llm, c -> List.of(), observer, 10).run(conv));

        assertTrue(stop.executedTools().isEmpty());
    }

    @Test
    @DisplayName("returns the final text when the model emits no tool calls")
    void finalAnswer() {
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant("done", false, List.of()));
        AgiMateAgent agent = agent(llm, calls -> List.of(), null, 10);
        assertEquals("done", agent.run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
    }

    @Test
    @DisplayName("dispatches tool calls, appends results, then finishes on the next turn")
    void toolThenAnswer() {
        AtomicInteger turn = new AtomicInteger();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant(null, false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))))
                : reply(AgentChatMessage.assistant("final", false, List.of()));
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{\"ok\":true}", false));

        List<AgentChatMessage> newMsgs = new ArrayList<>();
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
            @Override
            public void onMessages(List<AgentChatMessage> msgs, LlmMeta meta) {
                newMsgs.addAll(msgs);
            }
        };
        AgiMateAgent agent = agent(llm, dispatcher, observer, 10);
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
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? new AgiMateAgent.LlmReply(AgentChatMessage.assistant(null, false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))), meta, null, null,
                        AgiMateAgent.Completion.TOOL_CALLS)
                : new AgiMateAgent.LlmReply(AgentChatMessage.assistant("final", false, List.of()),
                        new LlmMeta("stop", "gpt-5-mini", "wf-llm-2", null), null, null,
                        AgiMateAgent.Completion.STOP);
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{}", false));

        List<LlmMeta> metas = new ArrayList<>();
        List<AgentChatMessage.Role> roles = new ArrayList<>();
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
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
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> new AgiMateAgent.LlmReply(
                AgentChatMessage.assistant("done", false, List.of()), null, usage, null,
                AgiMateAgent.Completion.STOP);
        List<LlmUsage> got = new ArrayList<>();
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
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
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> new AgiMateAgent.LlmReply(
                AgentChatMessage.assistant("обрезано", false, List.of()), null, usage,
                LlmResponseIncomplete.Reason.LENGTH, AgiMateAgent.Completion.UNKNOWN);
        List<LlmUsage> got = new ArrayList<>();
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
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
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> {
            calls.incrementAndGet();
            return reply(AgentChatMessage.assistant("done", false, List.of()));
        };
        List<List<AgentChatMessage>> snapshots = new ArrayList<>();
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
            @Override
            public void onStart(List<AgentChatMessage> messages) {
                snapshots.add(messages);
            }
        };
        AgiMateAgent agent = agent(llm, c -> List.of(), observer, 10);

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
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant(null, false,
                List.of(new AgentChatMessage.ToolCall("id", "t", "{}"))));
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id", "t", "{}", false));
        AgiMateAgent agent = agent(llm, dispatcher, null, 3);
        assertThrows(MaxTurnsExceeded.class, () -> agent.run(new ArrayList<>()));
    }

    @Test
    @DisplayName("мягкая посадка: wrap-up-нотис за WRAP_UP_TURNS до капа, последний ход без тулов")
    void softLandingWrapUp() {
        // «Перфекционист»: пока тулы доступны — вызывает; на безтуловом ходе вынужден ответить.
        List<List<ToolDef>> defsPerTurn = new ArrayList<>();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> {
            defsPerTurn.add(defs);
            return defs.isEmpty()
                    ? reply(AgentChatMessage.assistant("вот что успел", false, List.of()))
                    : reply(AgentChatMessage.assistant(null, false,
                            List.of(new AgentChatMessage.ToolCall("id", "t", "{}"))));
        };
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id", "t", "{}", false));
        AgiMateAgent agent = new AgiMateAgent(llm, dispatcher,
                List.of(new ToolDef("t", "tool", "{}")), 4, null);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("вот что успел", agent.run(conv));
        // Ход 4 (кап) — без тулов, предыдущие — с тулами.
        assertEquals(4, defsPerTurn.size());
        assertTrue(defsPerTurn.get(2).size() > 0);
        assertTrue(defsPerTurn.get(3).isEmpty());
        // Нотис инжектится перед ходом maxTurns - WRAP_UP_TURNS + 1 и не дублируется.
        long notices = conv.stream()
                .filter(m -> AgiMateAgent.WRAP_UP_NOTICE.equals(m.text()))
                .count();
        assertEquals(1, notices);
    }

    @Test
    @DisplayName("мягкая посадка не активируется при крошечном maxTurns (<= WRAP_UP_TURNS)")
    void softLandingSkippedForTinyCap() {
        List<List<ToolDef>> defsPerTurn = new ArrayList<>();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> {
            defsPerTurn.add(defs);
            return reply(AgentChatMessage.assistant(null, false,
                    List.of(new AgentChatMessage.ToolCall("id", "t", "{}"))));
        };
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id", "t", "{}", false));
        List<ToolDef> defs = List.of(new ToolDef("t", "tool", "{}"));
        AgiMateAgent agent = new AgiMateAgent(llm, dispatcher, defs, 2, null);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertThrows(MaxTurnsExceeded.class, () -> agent.run(conv));
        // Оба хода с тулами, нотис не инжектился.
        assertTrue(defsPerTurn.stream().allMatch(d -> !d.isEmpty()));
        assertTrue(conv.stream().noneMatch(m -> AgiMateAgent.WRAP_UP_NOTICE.equals(m.text())));
    }

    @Test
    @DisplayName("guard: пустой ход не финал — повторяется тот же самый запрос")
    void emptyReplyRetried() {
        AtomicInteger turn = new AtomicInteger();
        List<List<AgentChatMessage>> sent = new ArrayList<>();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> {
            sent.add(List.copyOf(msgs));
            return turn.getAndIncrement() == 0
                    ? reply(AgentChatMessage.assistant("   ", false, List.of()))
                    : reply(AgentChatMessage.assistant("готово", false, List.of()));
        };
        AgiMateAgent agent = agent(llm, calls -> List.of(), null, 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("готово", agent.run(conv));
        // Переспрос — это тот же запрос, а не другой: ничего не дописано взамен пустого хода.
        assertEquals(sent.get(0), sent.get(1));
        // user + assistant(финал): пустого assistant-хода в диалоге не остаётся.
        assertEquals(2, conv.size());
        assertTrue(conv.stream().noneMatch(m -> m.role() == AgentChatMessage.Role.ASSISTANT
                && (m.text() == null || m.text().isBlank())));
    }

    @Test
    @DisplayName("guard: пустой ход после переспроса — EmptyAnswerExhausted, а не пустой финал")
    void emptyReplyAbortsAfterRetries() {
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> reply(AgentChatMessage.assistant(null, false, List.of()));
        AgiMateAgent agent = agent(llm, calls -> List.of(), null, 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertThrows(EmptyAnswerExhausted.class, () -> agent.run(conv));
        // Взамен пустых ходов ничего не дописано: пользовательское сообщение как было одно, так и
        // осталось. (Последний пустой ход остаётся в списке — ран прерван, и список никто не читает.)
        assertEquals(List.of("hi"), conv.stream()
                .filter(m -> m.role() == AgentChatMessage.Role.USER)
                .map(AgentChatMessage::text).toList());
    }

    @Test
    @DisplayName("guard: пустой текст рядом с tool call'ами финалом не считается — обычный ход с тулами")
    void emptyTextWithToolCallsIsNotAffected() {
        AtomicInteger turn = new AtomicInteger();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant(null, false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))))
                : reply(AgentChatMessage.assistant("final", false, List.of()));
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{}", false));
        AgiMateAgent agent = agent(llm, dispatcher, null, 10);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("final", agent.run(conv));
        // Guard не сработал: ход с вызовами остался в диалоге вместе с результатом тула.
        assertTrue(conv.stream().anyMatch(AgentChatMessage::hasToolCalls));
        assertTrue(conv.stream().anyMatch(m -> m.role() == AgentChatMessage.Role.TOOL));
    }

    @Test
    @DisplayName("пустой ход выброшен из диалога — и в журнал не уходит")
    void emptyTurnNotProjected() {
        AtomicInteger turn = new AtomicInteger();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant("   ", false, List.of()))
                : reply(AgentChatMessage.assistant("готово", false, List.of()));
        List<AgentChatMessage> projected = new ArrayList<>();
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
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
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? new AgiMateAgent.LlmReply(AgentChatMessage.assistant("сейчас гляну", false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))), null, null, null,
                        AgiMateAgent.Completion.TOOL_CALLS)
                : new AgiMateAgent.LlmReply(AgentChatMessage.assistant("готово", false, List.of()),
                        null, null, null, AgiMateAgent.Completion.STOP);
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{}", false));
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("готово", agent(llm, dispatcher, null, 10).run(conv));
        assertEquals(AgentChatMessage.Role.TOOL, conv.get(2).role());
    }

    @Test
    @DisplayName("TOOL_CALLS без распарсенных вызовов финалом не считается — переспрос")
    void toolCallsWithoutCallsIsNotFinal() {
        AtomicInteger turn = new AtomicInteger();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? new AgiMateAgent.LlmReply(AgentChatMessage.assistant("сейчас вызову", false, List.of()),
                        null, null, null, AgiMateAgent.Completion.TOOL_CALLS)
                : new AgiMateAgent.LlmReply(AgentChatMessage.assistant("готово", false, List.of()),
                        null, null, null, AgiMateAgent.Completion.STOP);

        assertEquals("готово", agent(llm, calls -> List.of(), null, 10)
                .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        assertEquals(2, turn.get());   // потерянный вызов не принят за ответ: модель переспрошена
    }

    @Test
    @DisplayName("TOOL_CALLS без вызовов и без текста → пустой ход выброшен, а не переслан модели")
    void lostToolCallWithEmptyTextIsDropped() {
        AtomicInteger turn = new AtomicInteger();
        List<List<AgentChatMessage>> sent = new ArrayList<>();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> {
            sent.add(List.copyOf(msgs));
            return turn.getAndIncrement() == 0
                    ? new AgiMateAgent.LlmReply(AgentChatMessage.assistant("  ", false, List.of()),
                            null, null, null, AgiMateAgent.Completion.TOOL_CALLS)
                    : new AgiMateAgent.LlmReply(AgentChatMessage.assistant("готово", false, List.of()),
                            null, null, null, AgiMateAgent.Completion.STOP);
        };

        assertEquals("готово", agent(llm, calls -> List.of(), null, 10)
                .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        // Во втором вызове пустого assistant-хода в контексте нет — строгие шлюзы такое отклоняют,
        // и взамен него тоже ничего нет: второй запрос равен первому.
        assertTrue(sent.get(1).stream().noneMatch(m -> m.role() == AgentChatMessage.Role.ASSISTANT));
        assertEquals(sent.get(0), sent.get(1));
    }

    @Test
    @DisplayName("UNKNOWN (провайдер не сказал или сказал своё) → решает форма сообщения")
    void unknownFallsBackToMessageShape() {
        AtomicInteger turn = new AtomicInteger();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> turn.getAndIncrement() == 0
                ? reply(AgentChatMessage.assistant(null, false,
                        List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))))
                : reply(AgentChatMessage.assistant("final", false, List.of()));
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{}", false));
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        // reply(...) не несёт completion → UNKNOWN: ход с вызовами продолжает цикл, без вызовов — финал.
        assertEquals("final", agent(llm, dispatcher, null, 10).run(conv));
        assertEquals(AgentChatMessage.Role.TOOL, conv.get(2).role());
    }

    // ===== Стиринг =====

    @Test
    @DisplayName("стиринг: сообщение с шва дописывается в диалог и уходит в следующий вызов модели")
    void steeringAbsorbedAtSeam() {
        List<List<AgentChatMessage>> sent = new ArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> {
            sent.add(List.copyOf(msgs));
            return turn.getAndIncrement() == 0
                    ? reply(AgentChatMessage.assistant(null, false,
                            List.of(new AgentChatMessage.ToolCall("id1", "t", "{}"))))
                    : reply(AgentChatMessage.assistant("учёл", false, List.of()));
        };
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id1", "t", "{}", false));
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
            private int polls;

            @Override
            public List<AgentChatMessage> pollSteering() {
                return ++polls == 2 ? List.of(AgentChatMessage.user("новое сообщение")) : List.of();
            }
        };

        assertEquals("учёл", agent(llm, dispatcher, observer, 10)
                .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        // Второй вызов модели видит поглощённое сообщение последним — после результатов тулов.
        List<AgentChatMessage> second = sent.get(1);
        assertEquals("новое сообщение", second.get(second.size() - 1).text());
        assertEquals(AgentChatMessage.Role.USER, second.get(second.size() - 1).role());
    }

    @Test
    @DisplayName("стиринг: сброс бюджета продлевает ран за исходный кап, wrap-up снимается и перевзводится")
    void steeringResetsTheTurnBudget() {
        List<List<AgentChatMessage>> sent = new ArrayList<>();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> {
            sent.add(List.copyOf(msgs));
            return defs.isEmpty()
                    ? reply(AgentChatMessage.assistant("вот что успел", false, List.of()))
                    : reply(AgentChatMessage.assistant(null, false,
                            List.of(new AgentChatMessage.ToolCall("id", "t", "{}"))));
        };
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id", "t", "{}", false));
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
            private int polls;

            @Override
            public List<AgentChatMessage> pollSteering() {
                // Шов 4 — wrap-up уже инжектирован (на ходе 3 при maxTurns=4).
                return ++polls == 4 ? List.of(AgentChatMessage.user("ещё задача")) : List.of();
            }
        };
        AgiMateAgent agent = new AgiMateAgent(llm, dispatcher,
                List.of(new ToolDef("t", "tool", "{}")), 4, observer);
        List<AgentChatMessage> conv = new ArrayList<>(List.of(AgentChatMessage.user("hi")));

        assertEquals("вот что успел", agent.run(conv));
        // 3 хода с тулами + после сброса ещё 3 + безтуловый финал: исходный кап в 4 хода пройден.
        assertEquals(7, sent.size());
        // Вызов сразу после поглощения: старого wrap-up-нотиса в контексте нет, сообщение — есть.
        List<AgentChatMessage> afterAbsorb = sent.get(3);
        assertTrue(afterAbsorb.stream().noneMatch(m -> AgiMateAgent.WRAP_UP_NOTICE.equals(m.text())));
        assertEquals("ещё задача", afterAbsorb.get(afterAbsorb.size() - 1).text());
        // Перевзведённый нотис инжектирован заново — в диалоге он ровно один.
        assertEquals(1, conv.stream()
                .filter(m -> AgiMateAgent.WRAP_UP_NOTICE.equals(m.text()))
                .count());
    }

    @Test
    @DisplayName("стиринг: потолок сбросов — дальше шов не опрашивается и ран завершается")
    void steeringResetsAreCapped() {
        AtomicInteger llmCalls = new AtomicInteger();
        AgiMateAgent.LlmCaller llm = (msgs, defs) -> {
            llmCalls.incrementAndGet();
            return reply(AgentChatMessage.assistant(null, false,
                    List.of(new AgentChatMessage.ToolCall("id", "t", "{}"))));
        };
        AgiMateAgent.ToolDispatcher dispatcher = calls -> List.of(
                new AgentChatMessage.ToolResult("id", "t", "{}", false));
        AtomicInteger polls = new AtomicInteger();
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
            @Override
            public List<AgentChatMessage> pollSteering() {
                polls.incrementAndGet();
                return List.of(AgentChatMessage.user("ещё"));
            }
        };
        // maxTurns=2 → мягкой посадки нет; каждый опрос приносит сообщение и сбрасывает бюджет.
        AgiMateAgent agent = agent(llm, dispatcher, observer, 2);

        assertThrows(MaxTurnsExceeded.class,
                () -> agent.run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        // Ровно MAX_STEERING_RESETS поглощений; после потолка шов молчит и кап добивает ран.
        assertEquals(AgiMateAgent.MAX_STEERING_RESETS, polls.get());
        // Каждый сброс оставляет бюджету один ход (turn=1 → 2), после потолка — последний ход капа.
        assertEquals(AgiMateAgent.MAX_STEERING_RESETS + 1, llmCalls.get());
    }

    @Test
    @DisplayName("стиринг: отмена первее — стоп не поглощает новую работу")
    void cancellationWinsOverSteering() {
        AtomicBoolean polled = new AtomicBoolean();
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
            @Override
            public boolean cancelRequested() {
                return true;
            }

            @Override
            public List<AgentChatMessage> pollSteering() {
                polled.set(true);
                return List.of(AgentChatMessage.user("не должно поглотиться"));
            }
        };

        assertThrows(RunCancelled.class,
                () -> agent((msgs, defs) -> reply(AgentChatMessage.assistant("нет", false, List.of())),
                        c -> List.of(), observer, 10)
                        .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        assertFalse(polled.get());
    }

    @Test
    @DisplayName("стиринг: поглощение на первом шве попадает в снимок промпта (onStart после опроса)")
    void firstSeamAbsorptionLandsInTheSnapshot() {
        List<AgentChatMessage> snapshot = new ArrayList<>();
        AgiMateAgent.RunObserver observer = new AgiMateAgent.RunObserver() {
            private int polls;

            @Override
            public void onStart(List<AgentChatMessage> messages) {
                snapshot.addAll(messages);
            }

            @Override
            public List<AgentChatMessage> pollSteering() {
                return ++polls == 1 ? List.of(AgentChatMessage.user("ждало в очереди")) : List.of();
            }
        };
        AgiMateAgent.LlmCaller llm = (msgs, defs) ->
                reply(AgentChatMessage.assistant("готово", false, List.of()));

        assertEquals("готово", agent(llm, c -> List.of(), observer, 10)
                .run(new ArrayList<>(List.of(AgentChatMessage.user("hi")))));
        assertEquals("ждало в очереди", snapshot.get(snapshot.size() - 1).text());
    }
}
