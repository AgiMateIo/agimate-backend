package ru.agimate.userapi.service.push.universal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.service.push.PushMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuStorePushTransport")
class RuStorePushTransportTest {

    private static final String TOKEN = "cV8kQz1p-device-token";

    private static PushProperties properties(String projectId, String serviceKey) {
        PushProperties properties = new PushProperties();
        properties.getRustore().setProjectId(projectId);
        properties.getRustore().setServiceKey(serviceKey);
        return properties;
    }

    /** У этого канала креды — две константы из консоли, и в тело они едут как есть. */
    @Test
    @DisplayName("креды из конфигурации едут в теле под именем rustore")
    @SuppressWarnings("unchecked")
    void credentialsAndTokensInTheBody() {
        RuStorePushTransport transport = new RuStorePushTransport(properties("project-1", "key-1"));

        Map<String, Object> body = transport.body(
                TOKEN, new PushMessage(Map.of("type", "webchat_message"), null), transport.authToken());

        Map<String, Object> rustore = (Map<String, Object>) ((Map<String, Object>) body.get("providers")).get("rustore");
        assertEquals("project-1", rustore.get("project_id"));
        assertEquals("key-1", rustore.get("auth_token"));
        assertEquals(List.of(TOKEN), ((Map<String, Object>) body.get("tokens")).get("rustore"));
        assertEquals(PushProvider.RUSTORE, transport.provider());
    }

    @Test
    @DisplayName("пустые креды — отправка выключена, старт проходит")
    void emptyCredentialsDisableSending() {
        RuStorePushTransport transport = new RuStorePushTransport(properties("", ""));

        assertDoesNotThrow(transport::checkConfiguration);
        assertFalse(transport.isConfigured());
    }

    /** Заполненная половина — опечатка, и всплыла бы она уведомлением, которое не пришло. */
    @Test
    @DisplayName("креды заполнены наполовину — отказ старта")
    void halfConfiguredFailsFast() {
        RuStorePushTransport withoutKey = new RuStorePushTransport(properties("project-1", ""));
        RuStorePushTransport withoutProject = new RuStorePushTransport(properties("", "key-1"));

        assertThrows(IllegalStateException.class, withoutKey::checkConfiguration);
        assertThrows(IllegalStateException.class, withoutProject::checkConfiguration);
    }

    @Test
    @DisplayName("обе половины на месте — транспорт готов")
    void configured() {
        RuStorePushTransport transport = new RuStorePushTransport(properties("project-1", "key-1"));

        assertDoesNotThrow(transport::checkConfiguration);
        assertTrue(transport.isConfigured());
    }
}
