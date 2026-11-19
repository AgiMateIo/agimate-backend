package ru.agimate.controlapi.service.http;

import org.apache.hc.core5.http.io.SocketConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PublicOnlyHttp: настройки исходящих сокетов")
class PublicOnlyHttpTest {

    @Test
    @DisplayName("keepalive включён, но молчание измеряется минутами, а не дефолтными 5/5/3 httpcore5")
    void keepAliveToleratesSlowAnswers() {
        SocketConfig config = PublicOnlyHttp.SOCKET_CONFIG;

        assertTrue(config.isSoKeepAlive(), "детектор мёртвого пира остаётся включённым");
        assertEquals(300, config.getTcpKeepIdle(),
                "дефолт httpcore5 — 5 с: любая долгая генерация обрывается раньше ответа");
        assertEquals(60, config.getTcpKeepInterval());
        assertEquals(3, config.getTcpKeepCount());
    }
}
