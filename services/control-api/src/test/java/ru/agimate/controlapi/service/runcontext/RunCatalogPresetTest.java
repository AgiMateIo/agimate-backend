package ru.agimate.controlapi.service.runcontext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("RunCatalog — выбор пресета по маршруту")
class RunCatalogPresetTest {

    private static final ChannelInfo CHAT = new ChannelInfo(UUID.randomUUID(), UUID.randomUUID(), null);
    private static final TriggerSpec CARRIES_ON = new TriggerSpec("report", List.of(), null, true);

    private static Trigger trigger(String name) {
        return Trigger.createBasic("subagents", "conn", name, Map.of());
    }

    @Test
    @DisplayName("prompt-канал — диалог, что бы ни объявил триггер")
    void promptIsDialogue() {
        assertEquals(ContextSpec.DIALOGUE,
                RunCatalog.presetOf(Channels.ofPrompt(CHAT), trigger("report_received"), CARRIES_ON));
    }

    @Test
    @DisplayName("событие, продолжающее разговор, с сессией разговора — DIALOGUE_EVENT")
    void carriesOnWithConversation() {
        Channels answerOnly = new Channels(null, null, CHAT);
        assertEquals(ContextSpec.DIALOGUE_EVENT, RunCatalog.presetOf(answerOnly, trigger("report_received"), CARRIES_ON));
        Channels historyOnly = new Channels(null, null, new ChannelInfo(null, UUID.randomUUID(), null));
        assertEquals(ContextSpec.DIALOGUE_EVENT, RunCatalog.presetOf(historyOnly, trigger("report_received"), CARRIES_ON));
    }

    @Test
    @DisplayName("tool_completed продолжает разговор без объявления")
    void toolCompletedCarriesOn() {
        assertEquals(ContextSpec.DIALOGUE_EVENT,
                RunCatalog.presetOf(new Channels(null, null, CHAT), trigger("tool_completed"), null));
    }

    @Test
    @DisplayName("без разговора или без объявления — автономное событие")
    void otherwiseSystemTrigger() {
        assertEquals(ContextSpec.SYSTEM_TRIGGER, RunCatalog.presetOf(null, trigger("report_received"), CARRIES_ON));
        assertEquals(ContextSpec.SYSTEM_TRIGGER,
                RunCatalog.presetOf(new Channels(null, null, CHAT), trigger("due"), new TriggerSpec("due", List.of())));
    }
}
