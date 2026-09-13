package ru.agimate.controlapi.connectors.internal.skillloader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.service.runcontext.RunCatalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("load_skill")
class SkillLoaderToolServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final RunCatalog.Catalog CATALOG =
            new RunCatalog.Catalog(null, null, null, null, List.of(), List.of(), List.of(), List.of(), true, false);

    @Mock private RunCatalog runCatalog;

    private static final ConnectorEnv IN_RUN = new ConnectorEnv("conn-0", UUID.randomUUID(), AGENT_ID, RUN_ID, null, null, Map.of(), null);

    private SkillLoaderConnectorService connector;

    @BeforeEach
    void setUp() {
        connector = new SkillLoaderConnectorService(new SkillLoaderToolService(runCatalog));
        when(runCatalog.forRun(AGENT_ID, RUN_ID)).thenReturn(CATALOG);
    }

    private Map<String, Object> loadSkill(List<String> names) {
        return connector.executeTool(IN_RUN, "load_skill", Map.of("names", names));
    }

    @Test
    @DisplayName("bodies by name, misses listed apart")
    void bodiesAndMisses() {
        when(runCatalog.skillBody(CATALOG, "platform")).thenReturn(Optional.of("# platform body"));
        when(runCatalog.skillBody(CATALOG, "nope")).thenReturn(Optional.empty());

        Map<String, Object> result = loadSkill(List.of("platform", "nope"));

        assertEquals(List.of(Map.of("name", "platform", "body", "# platform body")), result.get("skills"));
        assertEquals(List.of("nope"), result.get("unknown"));
    }

    @Test
    @DisplayName("nothing known: an error naming the skills the agent has")
    void allUnknown() {
        when(runCatalog.skillBody(CATALOG, "nope")).thenReturn(Optional.empty());
        when(runCatalog.skillNames(CATALOG)).thenReturn(List.of("platform", "sheets"));

        ConnectorException e = assertThrows(ConnectorException.class, () -> loadSkill(List.of("nope")));

        assertTrue(e.getMessage().contains("platform, sheets"));
    }
}
