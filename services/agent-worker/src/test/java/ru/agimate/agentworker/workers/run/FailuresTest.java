package ru.agimate.agentworker.workers.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("текст отказа: message — модели, detail — в лог и в ошибку рана")
class FailuresTest {

    @Test
    @DisplayName("причина из cause-цепочки дописывается: «Request failed» сам по себе не диагноз")
    void unwrapsTheCause() {
        Throwable wrapped = new RuntimeException("Request failed",
                new SocketTimeoutException("timeout"));

        assertEquals("Request failed", Failures.message(wrapped));
        assertEquals("Request failed: SocketTimeoutException: timeout", Failures.detail(wrapped));
    }

    @Test
    @DisplayName("обёртка, скопировавшая сообщение причины, не дублируется")
    void skipsARepeatedMessage() {
        Throwable copied = new IllegalStateException("boom", new IOException("boom"));

        assertEquals("boom", Failures.detail(copied));
    }

    @Test
    @DisplayName("обёртка вида new X(cause) — сообщение равно cause.toString() — не печатается дважды")
    void dropsAToStringWrapper() {
        // Reactor's blockLast wraps a checked TimeoutException this way.
        Throwable wrapped = new RuntimeException(new TimeoutException("no first chunk within 90 s"));

        assertEquals("no first chunk within 90 s", Failures.detail(wrapped));
        assertEquals("Request failed: SocketTimeoutException: timeout", Failures.detail(
                new RuntimeException(new RuntimeException("Request failed", new SocketTimeoutException("timeout")))));
    }

    @Test
    @DisplayName("причина без сообщения представлена именем класса")
    void namesAMessagelessCause() {
        Throwable wrapped = new RuntimeException("Request failed", new IOException());

        assertEquals("Request failed: IOException", Failures.detail(wrapped));
    }

    @Test
    @DisplayName("своё сообщение пустое → имя класса, как и у message")
    void fallsBackToTheClassName() {
        assertEquals("IOException", Failures.detail(new IOException()));
        assertEquals("IOException", Failures.message(new IOException()));
    }

    @Test
    @DisplayName("цепочка ограничена: длинная обёртка не разворачивается в простыню")
    void capsTheChain() {
        Throwable deep = new IOException("l5");
        for (int i = 4; i >= 0; i--) {
            deep = new RuntimeException("l" + i, deep);
        }

        String detail = Failures.detail(deep);

        assertTrue(detail.startsWith("l0: RuntimeException: l1"), detail);
        assertTrue(detail.contains("l3"), detail);
        assertFalse(detail.contains("l5"), () -> "цепочка не обрезана: " + detail);
    }

    @Test
    @DisplayName("самоссылающаяся причина не зацикливает разбор")
    void survivesACyclicCause() {
        SelfCaused cyclic = new SelfCaused();

        assertEquals("self", Failures.detail(cyclic));
    }

    /** Исключение, чья причина — оно само: у драйверов и прокси такое встречается. */
    private static final class SelfCaused extends RuntimeException {
        SelfCaused() {
            super("self");
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
