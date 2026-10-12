package ru.agimate.controlapi.service.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PublicOnlyHttp: дедлайн на весь запрос")
class PublicOnlyHttpDeadlineTest {

    private ServerSocket server;
    private Thread serving;

    /** Headers at once, then a body of 10 000 bytes at a byte per 100 ms — every read within any read timeout. */
    @BeforeEach
    void startDribblingServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        serving = Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread.ofVirtual().start(() -> dribble(socket));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    private static void dribble(Socket socket) {
        try (socket) {
            socket.getInputStream().read(new byte[4096]);
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 10000\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            for (int i = 0; i < 10_000; i++) {
                out.write(' ');
                out.flush();
                Thread.sleep(100);
            }
        } catch (IOException | InterruptedException ignored) {
            // The client hung up — the point of the test.
        }
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        serving.interrupt();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/client.json";
    }

    @Test
    @DisplayName("медленное тело обрывается на дедлайне, а не тянется, пока сервер шлёт по байту")
    void slowBodyIsCutAtTheDeadline() {
        RestClient client = new PublicOnlyHttp(true).restClient(Duration.ofSeconds(5), Duration.ofSeconds(1)).build();

        long started = System.nanoTime();
        assertThrows(Exception.class, () -> client.get().uri(url()).retrieve().body(byte[].class));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertTrue(elapsed.compareTo(Duration.ofSeconds(3)) < 0, "took " + elapsed);
    }
}
