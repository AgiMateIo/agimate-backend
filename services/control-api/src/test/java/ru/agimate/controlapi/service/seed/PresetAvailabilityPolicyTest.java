package ru.agimate.controlapi.service.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.enums.ConnectorPresetAvailability;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PresetAvailabilityPolicy — один код доступности карточки")
class PresetAvailabilityPolicyTest {

    @Nested
    @DisplayName("терминальные состояния")
    class TerminalStates {

        @Test
        @DisplayName("down — DOWN даже при подтверждённом транспорте")
        void downWins() {
            assertEquals(ConnectorPresetAvailability.DOWN,
                    evaluate("down", "streamable-http", "MEASURED", false, true, true));
        }

        @Test
        @DisplayName("SSE — транспорт не поддерживается")
        void sseIsUnsupported() {
            assertEquals(ConnectorPresetAvailability.TRANSPORT_UNSUPPORTED,
                    evaluate("up", "sse", "MEASURED", false, true, true));
        }

        @Test
        @DisplayName("неизвестный транспорт — тоже не поддерживается")
        void unknownTransportIsUnsupported() {
            assertEquals(ConnectorPresetAvailability.TRANSPORT_UNSUPPORTED,
                    evaluate("up", null, "UNKNOWN", false, true, true));
        }

        @Test
        @DisplayName("нет поддерживаемого способа входа — AUTH_UNSUPPORTED")
        void noAuthIsUnsupported() {
            assertEquals(ConnectorPresetAvailability.AUTH_UNSUPPORTED,
                    evaluate("auth_required", "streamable-http", "INFERRED", false, null, false));
        }
    }

    @Nested
    @DisplayName("транспорт")
    class Transport {

        @Test
        @DisplayName("auth_required принимает неподтверждённый транспорт, если URL не /sse")
        void authRequiredAllowsInferredTransport() {
            assertEquals(ConnectorPresetAvailability.AVAILABLE,
                    evaluate("auth_required", "streamable-http", "INFERRED", false, null, true));
        }

        @Test
        @DisplayName("auth_required с исходным /sse блокируется")
        void authRequiredRejectsLegacySse() {
            assertEquals(ConnectorPresetAvailability.TRANSPORT_UNSUPPORTED,
                    evaluate("auth_required", "streamable-http", "INFERRED", true, null, true));
        }

        @Test
        @DisplayName("up принимает MEASURED и DECLARED")
        void upAcceptsMeasuredAndDeclared() {
            assertEquals(ConnectorPresetAvailability.AVAILABLE,
                    evaluate("up", "streamable-http", "MEASURED", false, true, true));
            assertEquals(ConnectorPresetAvailability.AVAILABLE,
                    evaluate("up", "streamable-http", "DECLARED", false, true, true));
        }

        @Test
        @DisplayName("up с UNKNOWN не обещаем")
        void upRejectsUnknownSource() {
            assertEquals(ConnectorPresetAvailability.TRANSPORT_UNSUPPORTED,
                    evaluate("up", "streamable-http", "UNKNOWN", false, true, true));
        }
    }

    @Nested
    @DisplayName("протокол")
    class Protocol {

        @Test
        @DisplayName("up без подтверждённого handshake — PROTOCOL_UNSUPPORTED")
        void upWithoutHandshakeIsUnsupported() {
            assertEquals(ConnectorPresetAvailability.PROTOCOL_UNSUPPORTED,
                    evaluate("up", "streamable-http", "MEASURED", false, false, true));
            assertEquals(ConnectorPresetAvailability.PROTOCOL_UNSUPPORTED,
                    evaluate("up", "streamable-http", "MEASURED", false, null, true));
        }

        @Test
        @DisplayName("auth_required не требует handshake: 401 его скрывает")
        void authRequiredSkipsHandshake() {
            assertEquals(ConnectorPresetAvailability.AVAILABLE,
                    evaluate("auth_required", "streamable-http", "INFERRED", false, false, true));
        }
    }

    @Nested
    @DisplayName("приоритет")
    class Priority {

        @Test
        @DisplayName("DOWN важнее транспорта и авторизации")
        void downBeforeTransportAndAuth() {
            assertEquals(ConnectorPresetAvailability.DOWN,
                    evaluate("down", "sse", "UNKNOWN", true, false, false));
        }

        @Test
        @DisplayName("транспорт важнее протокола")
        void transportBeforeProtocol() {
            assertEquals(ConnectorPresetAvailability.TRANSPORT_UNSUPPORTED,
                    evaluate("up", "sse", "MEASURED", false, false, true));
        }
    }

    private static ConnectorPresetAvailability evaluate(String status, String transport,
                                                        String transportSource, boolean legacySse,
                                                        Boolean handshake, boolean hasAuth) {
        return PresetAvailabilityPolicy.evaluate(status, transport, transportSource, legacySse,
                handshake, hasAuth);
    }
}
