package ru.agimate.controlapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.controller.manage.dto.CreateSkillRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillResponse;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillService.create — валидация connector_codes")
class SkillServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private SkillRepository skillRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private ConnectorRegistry connectorRegistry;

    @InjectMocks
    private SkillService service;

    private void knownConnectors(String... codes) {
        List<ConnectorHandler> handlers = java.util.Arrays.stream(codes).map(code -> {
            ConnectorHandler handler = mock(ConnectorHandler.class);
            when(handler.connectorCode()).thenReturn(code);
            return handler;
        }).toList();
        when(connectorRegistry.getHandlers()).thenReturn(List.copyOf(handlers));
    }

    private String skillMd(String connectorsYaml) {
        return """
                ---
                name: Test Skill
                description: d
                connectors:
                %s
                ---
                Body.
                """.formatted(connectorsYaml);
    }

    @Test
    @DisplayName("неизвестный код коннектора → 400 с перечислением")
    void rejectsUnknownConnectorCode() {
        knownConnectors("board");
        CreateSkillRequest request = new CreateSkillRequest(skillMd("  - board\n  - bogus"), false);

        BadRequestStatusException ex = assertThrows(BadRequestStatusException.class,
                () -> service.create(USER_ID, request));
        assertTrue(ex.getMessage().contains("bogus"), ex.getMessage());
    }

    @Test
    @DisplayName("все коды известны → скилл создаётся")
    void acceptsKnownConnectorCodes() {
        knownConnectors("board");
        when(skillRepository.existsByUserIdAndNameNotDeleted(USER_ID, "Test Skill")).thenReturn(false);
        when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> {
            Skill s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        CreateSkillRequest request = new CreateSkillRequest(skillMd("  - board"), false);

        SkillResponse response = service.create(USER_ID, request);

        assertEquals("Test Skill", response.name());
        assertEquals(List.of("board"), response.connectorCodes());
    }
}
