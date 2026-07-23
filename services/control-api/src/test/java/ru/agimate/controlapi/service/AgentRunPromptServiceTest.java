package ru.agimate.controlapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentRunPromptService")
class AgentRunPromptServiceTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String PROMPT_JSON = "[{\"role\":\"SYSTEM\",\"text\":\"sys\"},"
            + "{\"role\":\"USER\",\"text\":\"hi\"}]";

    @Mock
    private AgentRunRepository agentRunRepository;

    @InjectMocks
    private AgentRunPromptService service;

    private AgentRun run(UUID ownerAgentId, JsonNode existingPrompt) {
        AgentRun run = mock(AgentRun.class);
        Agent agent = mock(Agent.class);
        when(run.getAgent()).thenReturn(agent);
        when(agent.getId()).thenReturn(ownerAgentId);
        // getPrompt читается только после проверки владельца — на mismatch-пути не вызывается.
        org.mockito.Mockito.lenient().when(run.getPrompt()).thenReturn(existingPrompt);
        return run;
    }

    @Test
    @DisplayName("снимка ещё нет → пишет распарсенное дерево, stored=true")
    void firstWriteStores() {
        AgentRun run = run(AGENT_ID, null);
        when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        AgentRunPromptService.SaveResult result = service.save(AGENT_ID, RUN_ID, PROMPT_JSON);

        assertTrue(result.stored());
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(run).setPrompt(captor.capture());
        assertTrue(captor.getValue().isArray());
        assertEquals("sys", captor.getValue().get(0).get("text").asText());
    }

    @Test
    @DisplayName("снимок уже есть → first-write-wins, не перезатирает, stored=false")
    void secondWriteIgnored() {
        JsonNode existing = mock(JsonNode.class);
        AgentRun run = run(AGENT_ID, existing);
        when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        AgentRunPromptService.SaveResult result = service.save(AGENT_ID, RUN_ID, PROMPT_JSON);

        assertFalse(result.stored());
        verify(run, never()).setPrompt(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("ран не найден → NotFoundStatusException")
    void runNotFound() {
        when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        assertThrows(NotFoundStatusException.class, () -> service.save(AGENT_ID, RUN_ID, PROMPT_JSON));
    }

    @Test
    @DisplayName("ран принадлежит другому агенту → BadRequestStatusException")
    void agentMismatch() {
        AgentRun run = run(UUID.randomUUID(), null);
        when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        assertThrows(BadRequestStatusException.class, () -> service.save(AGENT_ID, RUN_ID, PROMPT_JSON));
        verify(run, never()).setPrompt(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("пустой prompt_json → BadRequestStatusException, ран не читается")
    void blankPromptRejected() {
        assertThrows(BadRequestStatusException.class, () -> service.save(AGENT_ID, RUN_ID, "  "));
    }

    @Test
    @DisplayName("невалидный JSON → BadRequestStatusException")
    void invalidJsonRejected() {
        AgentRun run = run(AGENT_ID, null);
        when(agentRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));

        assertThrows(BadRequestStatusException.class, () -> service.save(AGENT_ID, RUN_ID, "{not json"));
        verify(run, never()).setPrompt(org.mockito.ArgumentMatchers.any());
    }
}
