package ru.agimate.controlapi.connectors.integrations.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("McpClient — SSRF-guard")
class McpClientTest {

    private final McpClient guarded = new McpClient(false);

    private McpClient.ServerConfig cfg(String url) {
        return new McpClient.ServerConfig(url, null, Map.of());
    }

    private String probeError(McpClient client, String url) {
        return assertThrows(ConnectorException.class, () -> client.probe(cfg(url))).getMessage();
    }

    @Test
    @DisplayName("loopback-адрес блокируется до сетевого вызова")
    void blocksLoopback() {
        assertTrue(probeError(guarded, "http://127.0.0.1:8080/mcp").contains("non-public"));
    }

    @Test
    @DisplayName("cloud metadata 169.254.169.254 (link-local) блокируется")
    void blocksMetadataEndpoint() {
        assertTrue(probeError(guarded, "http://169.254.169.254/latest/meta-data/").contains("non-public"));
    }

    @Test
    @DisplayName("приватный диапазон 10.0.0.0/8 блокируется")
    void blocksSiteLocal() {
        assertTrue(probeError(guarded, "https://10.0.0.5/mcp").contains("non-public"));
    }

    @Test
    @DisplayName("не-http(s) схема отклоняется")
    void rejectsNonHttpScheme() {
        assertTrue(probeError(guarded, "ftp://example.com/mcp").contains("http or https"));
    }

    @Test
    @DisplayName("с allow-private-targets=true guard пропускает loopback (падает уже на соединении)")
    void allowFlagBypassesGuard() {
        McpClient permissive = new McpClient(true);
        // Порт 1 закрыт → быстрый отказ соединения; важно, что это НЕ ошибка SSRF-guard'а.
        assertFalse(probeError(permissive, "http://127.0.0.1:1/mcp").contains("non-public"));
    }
}
