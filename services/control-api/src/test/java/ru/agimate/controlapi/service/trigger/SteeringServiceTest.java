package ru.agimate.controlapi.service.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.TriggerLog;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.channel.InboundTextResolver;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SteeringService")
class SteeringServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID MAIN_RUN_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID PROMPT_CHANNEL = UUID.randomUUID();

    @Mock private AgentRunRepository agentRunRepository;
    @Mock private InboundTextResolver inboundTextResolver;

    private SteeringService service() {
        return new SteeringService(agentRunRepository, inboundTextResolver);
    }

    private AgentRun mainRun(UUID sessionId) {
        AgentRun main = AgentRun.builder()
                .agent(Agent.builder().id(AGENT_ID).build())
                .sessionId(sessionId)
                .build();
        main.setId(MAIN_RUN_ID);
        when(agentRunRepository.findById(MAIN_RUN_ID)).thenReturn(Optional.of(main));
        return main;
    }

    private static AgentRun queuedRun(Channels channels) {
        AgentRun run = AgentRun.builder()
                .agent(Agent.builder().id(AGENT_ID).build())
                .triggerLog(TriggerLog.builder()
                        .connectorCode("webchat")
                        .connectionId(UUID.randomUUID().toString())
                        .externalId("evt-2")
                        .name("message_received")
                        .input(Map.of("text", "второе сообщение"))
                        .build())
                .destination("GENERIC")
                .sessionId(SESSION_ID)
                .channels(ChannelsCodec.toMap(channels))
                .build();
        run.setId(UUID.randomUUID());
        return run;
    }

    private static Channels promptChannels() {
        return Channels.ofPrompt(new ChannelInfo(PROMPT_CHANNEL, SESSION_ID, null));
    }

    @Test
    @DisplayName("захват: младшие ENQUEUED получают main_run_id, текст — той же экстракцией, что при диспетчере")
    void claimStampsAndExtracts() {
        mainRun(SESSION_ID);
        AgentRun queued = queuedRun(promptChannels());
        when(agentRunRepository.findSteerable(eq(SESSION_ID), eq(AGENT_ID), eq(MAIN_RUN_ID), any()))
                .thenReturn(List.of(queued));
        when(inboundTextResolver.resolve(eq(PROMPT_CHANNEL), any()))
                .thenReturn(Optional.of(InboundMessage.text("второе сообщение")));

        List<SteeringService.SteeringInbound> claimed = service().claim(AGENT_ID, MAIN_RUN_ID);

        assertEquals(1, claimed.size());
        assertEquals(queued.getId(), claimed.get(0).runId());
        assertEquals("второе сообщение", claimed.get(0).text());
        assertEquals(MAIN_RUN_ID, queued.getMainRunId());
    }

    @Test
    @DisplayName("вложения переезжают ссылками (InboundPart), имя — из meta")
    void claimCarriesParts() {
        mainRun(SESSION_ID);
        AgentRun queued = queuedRun(promptChannels());
        when(agentRunRepository.findSteerable(eq(SESSION_ID), eq(AGENT_ID), eq(MAIN_RUN_ID), any()))
                .thenReturn(List.of(queued));
        when(inboundTextResolver.resolve(eq(PROMPT_CHANNEL), any()))
                .thenReturn(Optional.of(new InboundMessage("глянь фото",
                        List.of(new Part("image", "agf_123", "image/png", 42, Map.of("name", "cat.png"))))));

        List<SteeringService.SteeringInbound> claimed = service().claim(AGENT_ID, MAIN_RUN_ID);

        assertEquals(1, claimed.get(0).parts().size());
        assertEquals("agf_123", claimed.get(0).parts().get(0).fileId());
        assertEquals("image", claimed.get(0).parts().get(0).type());
        assertEquals("cat.png", claimed.get(0).parts().get(0).name());
    }

    @Test
    @DisplayName("канал не извлёк текста → компактный JSON события, как у каноники INBOUND")
    void claimFallsBackToCompactEvent() {
        mainRun(SESSION_ID);
        AgentRun queued = queuedRun(null); // no channels snapshot — e.g. a trigger run in the session
        when(agentRunRepository.findSteerable(eq(SESSION_ID), eq(AGENT_ID), eq(MAIN_RUN_ID), any()))
                .thenReturn(List.of(queued));

        List<SteeringService.SteeringInbound> claimed = service().claim(AGENT_ID, MAIN_RUN_ID);

        assertTrue(claimed.get(0).text().contains("\"connectorCode\":\"webchat\""));
        assertTrue(claimed.get(0).text().contains("второе сообщение"));
        assertTrue(claimed.get(0).parts().isEmpty());
    }

    @Test
    @DisplayName("триггерный ран стирится наравне с канальным — по сессии коннекшена")
    void claimWorksForConnectionSession() {
        mainRun(SESSION_ID);
        AgentRun queued = queuedRun(null); // событие коннектора: снапшота каналов нет вовсе
        when(agentRunRepository.findSteerable(eq(SESSION_ID), eq(AGENT_ID), eq(MAIN_RUN_ID), any()))
                .thenReturn(List.of(queued));

        List<SteeringService.SteeringInbound> claimed = service().claim(AGENT_ID, MAIN_RUN_ID);

        assertEquals(1, claimed.size());
        assertEquals(MAIN_RUN_ID, queued.getMainRunId());
    }

    @Test
    @DisplayName("чужой агент → BadRequest, несуществующий main → NotFound")
    void ownershipGate() {
        mainRun(SESSION_ID);

        assertThrows(BadRequestStatusException.class,
                () -> service().claim(UUID.randomUUID(), MAIN_RUN_ID));
        assertThrows(NotFoundStatusException.class,
                () -> service().claim(AGENT_ID, UUID.randomUUID()));
    }

    @Test
    @DisplayName("подтверждение: штампуются только свои захваты; пустой список — без похода в базу")
    void markSteered() {
        mainRun(SESSION_ID);
        List<UUID> ids = List.of(UUID.randomUUID());
        when(agentRunRepository.markSteered(eq(ids), eq(MAIN_RUN_ID), any())).thenReturn(1);

        assertEquals(1, service().markSteered(AGENT_ID, MAIN_RUN_ID, ids));

        assertEquals(0, service().markSteered(AGENT_ID, MAIN_RUN_ID, List.of()));
        verify(agentRunRepository, never()).markSteered(eq(List.of()), any(), any());
    }
}
