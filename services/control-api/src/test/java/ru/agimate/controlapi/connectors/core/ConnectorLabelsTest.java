package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.service.seed.ConnectorTexts;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Строка каталога хранит тексты на языке инсталляции, поэтому перевод на другой язык не может откатываться
 * к ней: на RU-инсталляции английский ответ получил бы русский текст.
 */
@DisplayName("ConnectorLabels — подписи каталога на языке запроса")
class ConnectorLabelsTest {

    private final ConnectorLabels labels;

    ConnectorLabelsTest() {
        InternalConnectorHandler time = mock(InternalConnectorHandler.class);
        when(time.connectorCode()).thenReturn("time");
        when(time.connectorName()).thenReturn("Time");
        when(time.connectorDescription()).thenReturn("Current time and scheduled tasks");
        labels = new ConnectorLabels(new ConnectorRegistry(List.of(time)), new ConnectorTexts());
    }

    /** Строка в том виде, в каком её пишет бутстрап RU-инсталляции. */
    private static Connector storedInRussian(String code) {
        return Connector.builder().code(code).name("Русское имя").description("Русское описание").build();
    }

    @Test
    @DisplayName("хендлер на английском — текст из кода, а не из строки")
    void handlerEnglishFromCode() {
        Connector time = storedInRussian("time");

        assertEquals("Time", labels.name(time, ContentLanguage.EN));
        assertEquals("Current time and scheduled tasks", labels.description(time, ContentLanguage.EN));
    }

    @Test
    @DisplayName("статическая строка без хендлера на английском — текст из StaticConnector")
    void staticEnglishFromCode() {
        assertEquals("App", labels.name(storedInRussian("app"), ContentLanguage.EN));
    }

    @Test
    @DisplayName("на русском — перевод из бандла")
    void russianFromBundle() {
        assertEquals("Приложение", labels.name(storedInRussian("app"), ContentLanguage.RU));
    }

    @Test
    @DisplayName("код без хендлера и без статической строки — текст строки")
    void unknownCodeFromRow() {
        assertEquals("Русское имя", labels.name(storedInRussian("gone"), ContentLanguage.EN));
    }
}
