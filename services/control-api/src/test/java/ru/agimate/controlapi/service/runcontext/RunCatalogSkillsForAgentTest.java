package ru.agimate.controlapi.service.runcontext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.service.AgentSkillService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunCatalog.skillsForAgent — тот же гейт, что у рана")
class RunCatalogSkillsForAgentTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID KEPT = UUID.randomUUID();
    private static final UUID WITHHELD = UUID.randomUUID();

    @Mock
    private AgentRepository agentRepository;
    @Mock
    private AgentSkillRepository agentSkillRepository;
    @Mock
    private AgentSkillService agentSkillService;

    @InjectMocks
    private RunCatalog catalog;

    @Test
    @DisplayName("навык, удержанный гейтом, в листинг не попадает")
    void withheldSkillIsNotListed() {
        when(agentRepository.findById(AGENT_ID))
                .thenReturn(Optional.of(Agent.builder().id(AGENT_ID).build()));
        when(agentSkillService.gate(AGENT_ID))
                .thenReturn(new AgentSkillService.SkillGate(Map.of(KEPT, Set.of()), List.of()));
        when(agentSkillRepository.findByAgentId(AGENT_ID)).thenReturn(List.of(
                AgentSkill.builder().skillId(KEPT).build(),
                AgentSkill.builder().skillId(WITHHELD).build()));
        when(agentSkillService.resolveSkills(any())).thenReturn(Map.of(
                KEPT, new AgentSkillWithConnectorsResponse(KEPT, "kept", "d", List.of(), Disclosure.LAZY),
                WITHHELD, new AgentSkillWithConnectorsResponse(WITHHELD, "withheld", "d", List.of(), Disclosure.LAZY)));

        List<AgentSkillWithConnectorsResponse> result = catalog.skillsForAgent(AGENT_ID);

        assertEquals(List.of("kept"), result.stream()
                .map(AgentSkillWithConnectorsResponse::skillName)
                .toList());
    }
}
