package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ConnectorSettingsService")
class ConnectorSettingsServiceTest {

    record Sample(String timezone, Integer threshold) {}

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();

    private final AgentConnectionRepository repository = mock(AgentConnectionRepository.class);
    private final ConnectorSettingsService service = new ConnectorSettingsService(repository);

    private AgentConnection bind(Map<String, Object> settings) {
        AgentConnection binding = AgentConnection.builder().settings(new HashMap<>(settings)).build();
        when(repository.findActiveBinding(AGENT_ID, CONNECTION_ID)).thenReturn(Optional.of(binding));
        return binding;
    }

    @Test
    @DisplayName("нет агента или привязки — настройки по умолчанию")
    void defaults() {
        assertEquals(new Sample(null, null), service.get(null, CONNECTION_ID.toString(), Sample.class));
        assertEquals(new Sample(null, null), service.get(AGENT_ID, CONNECTION_ID.toString(), Sample.class));
        assertEquals(new Sample(null, null), service.get(AGENT_ID, "not-a-uuid", Sample.class));
    }

    @Test
    @DisplayName("чтение мягкое: неизвестный ключ не ломает, недостающий — null")
    void lenientRead() {
        bind(Map.of("timezone", "Europe/Moscow", "removedLongAgo", true));

        Sample sample = service.get(AGENT_ID, CONNECTION_ID.toString(), Sample.class);

        assertEquals("Europe/Moscow", sample.timezone());
        assertNull(sample.threshold());
    }

    @Test
    @DisplayName("запись заменяет настройки целиком и не хранит null")
    void saveDropsNulls() {
        AgentConnection binding = bind(Map.of("timezone", "Europe/Moscow", "threshold", 5));

        service.save(AGENT_ID, CONNECTION_ID.toString(), new Sample(null, 7));

        assertEquals(Map.of("threshold", 7), binding.getSettings());
    }

    @Test
    @DisplayName("запись без привязки — ошибка для агента")
    void saveUnbound() {
        assertThrows(ConnectorException.class,
                () -> service.save(AGENT_ID, CONNECTION_ID.toString(), new Sample("UTC", null)));
    }
}
