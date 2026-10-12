package ru.agimate.controlapi.service.mcp.oauth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CimdClientResolver: загрузка и кэш документа клиента")
class CimdClientResolverFetchTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile String cacheControl;
    private volatile String body;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/client.json", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (cacheControl != null) {
                exchange.getResponseHeaders().set("Cache-Control", cacheControl);
            }
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        body = document(clientId(), "Local Client");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String clientId() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/client.json";
    }

    private static String document(String clientId, String name) {
        return """
                {"client_id": "%s", "client_name": "%s", "redirect_uris": ["http://127.0.0.1/callback"],
                 "token_endpoint_auth_method": "none"}""".formatted(clientId, name);
    }

    /** A local stand: private targets and plain http allowed, as with app.net.allow-private-targets. */
    private CimdClientResolver localStand() {
        return new CimdClientResolver(new PublicOnlyHttp(true));
    }

    private String rejection(CimdClientResolver resolver, String clientId) {
        return assertThrows(OAuthClientRejectedException.class, () -> resolver.resolve(clientId)).getMessage();
    }

    @Test
    @DisplayName("документ разобран и закэширован: без Cache-Control второй раз за ним не ходят")
    void fetchedAndCached() {
        CimdClientResolver resolver = localStand();

        OAuthClient client = resolver.resolve(clientId());
        resolver.resolve(clientId());

        assertEquals("Local Client", client.name());
        assertEquals(List.of("http://127.0.0.1/callback"), client.redirectUris());
        assertEquals(1, hits.get());
    }

    @Test
    @DisplayName("no-store — документ скачивается каждый раз")
    void noStoreIsNotCached() {
        cacheControl = "no-store";
        CimdClientResolver resolver = localStand();

        resolver.resolve(clientId());
        resolver.resolve(clientId());

        assertEquals(2, hits.get());
    }

    @Test
    @DisplayName("ответ не 2xx — отказ с кодом, и отказ не кэшируется")
    void non2xxIsRejected() {
        status = 404;
        CimdClientResolver resolver = localStand();

        assertEquals("Client metadata document answered 404", rejection(resolver, clientId()));
        status = 200;
        assertEquals("Local Client", resolver.resolve(clientId()).name());
    }

    @Test
    @DisplayName("документ больше 10 КБ — отказ")
    void oversizedIsRejected() {
        body = document(clientId(), "x".repeat(CimdClientResolver.MAX_DOCUMENT_BYTES));

        assertEquals("Client metadata document is too large", rejection(localStand(), clientId()));
    }

    @Test
    @DisplayName("хост недоступен — отказ без подробностей соединения")
    void unreachableIsRejected() {
        String closed = "http://127.0.0.1:1/client.json";

        assertEquals("Client metadata document is unreachable", rejection(localStand(), closed));
    }

    @Test
    @DisplayName("на проде без allow-private-targets: http отвергается, https на приватный адрес — тоже, до запроса")
    void privateTargetsRefusedOnProd() {
        CimdClientResolver prod = new CimdClientResolver(new PublicOnlyHttp(false));

        assertEquals("client_id must be an https URL", rejection(prod, clientId()));
        assertEquals("client_id points to an address that is not allowed",
                rejection(prod, "https://127.0.0.1:" + server.getAddress().getPort() + "/client.json"));
        assertEquals(0, hits.get());
    }
}
