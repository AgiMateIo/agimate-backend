package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.model.ConnectorTraits;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConnectorBootstrap — upsert каталога и валидация trust-полей ContextDirectives")
class ConnectorBootstrapTest {

    interface InternalTriggerHandler extends InternalConnectorHandler, TriggerProvider {
    }

    interface IntegrationTriggerHandler extends IntegrationConnectorHandler, TriggerProvider {
    }

    private final ConnectorRepository connectorRepository = mock(ConnectorRepository.class);
    private final ConnectorJobService jobService = mock(ConnectorJobService.class);

    private ConnectorBootstrap bootstrap(ConnectorHandler handler) {
        when(connectorRepository.findById(anyString())).thenReturn(Optional.empty());
        lenient().when(connectorRepository.existsById(anyString())).thenReturn(true);
        return new ConnectorBootstrap(connectorRepository, new ConnectorRegistry(List.of(handler)), jobService);
    }

    private static TriggerSpec promptSpec(String promptParam) {
        return new TriggerSpec("desc", List.of("prompt"), ContextDirectives.builder()
                .presentation(ContextDirectives.Presentation.PROMPT)
                .promptParam(promptParam)
                .build());
    }

    @Test
    @DisplayName("internal-коннектор с PROMPT и promptParam проходит")
    void internalPromptAllowed() {
        InternalTriggerHandler handler = mock(InternalTriggerHandler.class);
        when(handler.connectorCode()).thenReturn("time");
        when(handler.connectorName()).thenReturn("Time");
        when(handler.traits()).thenReturn(ConnectorTraits.internal());
        when(handler.getTriggers()).thenReturn(Map.of("due", promptSpec("prompt")));

        assertDoesNotThrow(() -> bootstrap(handler).bootstrap());
    }

    @Test
    @DisplayName("description коннектора берётся из хендлера, статические строки — из бутстрапа")
    void descriptionPersisted() {
        InternalTriggerHandler handler = mock(InternalTriggerHandler.class);
        when(handler.connectorCode()).thenReturn("time");
        when(handler.connectorName()).thenReturn("Time");
        when(handler.connectorDescription()).thenReturn("Текущее время и отложенные задачи");
        when(handler.traits()).thenReturn(ConnectorTraits.internal());
        when(handler.getTriggers()).thenReturn(Map.of());

        bootstrap(handler).bootstrap();

        ArgumentCaptor<Connector> saved = ArgumentCaptor.forClass(Connector.class);
        verify(connectorRepository, atLeastOnce()).save(saved.capture());
        Map<String, String> descriptions = saved.getAllValues().stream()
                .collect(Collectors.toMap(Connector::getCode, Connector::getDescription));

        assertEquals("Текущее время и отложенные задачи", descriptions.get("time"));
        assertNotNull(descriptions.get("app"));
        assertNotNull(descriptions.get("claude-code"));
    }

    @Test
    @DisplayName("integration-коннектор с PROMPT роняет старт")
    void integrationPromptRejected() {
        IntegrationTriggerHandler handler = mock(IntegrationTriggerHandler.class);
        when(handler.connectorCode()).thenReturn("telegram");
        when(handler.connectorName()).thenReturn("Telegram");
        when(handler.traits()).thenReturn(ConnectorTraits.dynamicIntegration());
        when(handler.getCredentialFields()).thenReturn(Map.of("token", "Bot token"));
        when(handler.getTriggers()).thenReturn(Map.of("message_received", promptSpec("text")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> bootstrap(handler).bootstrap());

        assertTrue(e.getMessage().contains("internal connectors only"));
    }

    @Test
    @DisplayName("PROMPT без promptParam — ошибка декларации")
    void promptWithoutParamRejected() {
        InternalTriggerHandler handler = mock(InternalTriggerHandler.class);
        when(handler.connectorCode()).thenReturn("time");
        when(handler.connectorName()).thenReturn("Time");
        when(handler.traits()).thenReturn(ConnectorTraits.internal());
        when(handler.getTriggers()).thenReturn(Map.of("due", promptSpec(" ")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> bootstrap(handler).bootstrap());

        assertTrue(e.getMessage().contains("requires promptParam"));
    }

    @Test
    @DisplayName("scope-поля у интеграции валидны (guard только на trust)")
    void integrationScopeFieldsAllowed() {
        IntegrationTriggerHandler handler = mock(IntegrationTriggerHandler.class);
        when(handler.connectorCode()).thenReturn("telegram");
        when(handler.connectorName()).thenReturn("Telegram");
        when(handler.traits()).thenReturn(ConnectorTraits.dynamicIntegration());
        when(handler.getCredentialFields()).thenReturn(Map.of("token", "Bot token"));
        when(handler.getTriggers()).thenReturn(Map.of("message_received", new TriggerSpec(
                "desc", List.of("text"),
                ContextDirectives.builder().historyLimit(10).build())));

        assertDoesNotThrow(() -> bootstrap(handler).bootstrap());
    }
}
