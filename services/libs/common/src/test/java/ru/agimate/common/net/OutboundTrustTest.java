package ru.agimate.common.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OutboundTrust — набор якорей исходящего TLS")
class OutboundTrustTest {

    private static final String BUNDLED_ROOT = "certs/russian-trusted-root.pem";

    private InputStream bundledRoot() {
        InputStream pem = getClass().getClassLoader().getResourceAsStream(BUNDLED_ROOT);
        assertTrue(pem != null, BUNDLED_ROOT + " должен лежать в ресурсах модуля");
        return pem;
    }

    @Nested
    @DisplayName("слияние с платформенным набором")
    class Merging {

        @Test
        @DisplayName("добавленный якорь не вытесняет платформенные, а прибавляется к ним")
        void addsToPlatformAnchors() throws Exception {
            int platform = OutboundTrust.systemDefault().anchorCount();
            try (InputStream pem = bundledRoot()) {
                OutboundTrust trust = OutboundTrust.with(List.of(pem));

                assertEquals(platform + 1, trust.anchorCount());
                assertEquals(1, trust.addedAnchors().size());
            }
        }

        @Test
        @DisplayName("публичные центры остаются на месте — их видно в итоговом наборе")
        void keepsPublicAuthorities() throws Exception {
            Set<String> platform = subjects(OutboundTrust.systemDefault().manager().getAcceptedIssuers());
            try (InputStream pem = bundledRoot()) {
                Set<String> merged = subjects(OutboundTrust.with(List.of(pem)).manager().getAcceptedIssuers());

                assertTrue(merged.containsAll(platform));
            }
        }

        @Test
        @DisplayName("менеджер остаётся штатным X509ExtendedTrustManager — своей проверки доверия нет")
        void keepsTheJdkManager() throws Exception {
            try (InputStream pem = bundledRoot()) {
                assertInstanceOf(X509ExtendedTrustManager.class, OutboundTrust.with(List.of(pem)).manager());
            }
        }

        @Test
        @DisplayName("без добавленных якорей поведение платформы не меняется")
        void emptyIsSystemDefault() {
            OutboundTrust trust = OutboundTrust.with(List.of());

            assertTrue(trust.addedAnchors().isEmpty());
            assertEquals(OutboundTrust.systemDefault().anchorCount(), trust.anchorCount());
        }

        private Set<String> subjects(X509Certificate[] anchors) {
            return java.util.Arrays.stream(anchors)
                    .map(anchor -> anchor.getSubjectX500Principal().getName())
                    .collect(Collectors.toSet());
        }
    }

    @Nested
    @DisplayName("негодный PEM")
    class BadInput {

        @Test
        @DisplayName("мусор вместо сертификата — отказ, а не молчаливый пропуск")
        void rejectsGarbage() {
            InputStream garbage = new ByteArrayInputStream("not a certificate".getBytes(StandardCharsets.UTF_8));

            assertThrows(IllegalArgumentException.class, () -> OutboundTrust.with(List.of(garbage)));
        }

        @Test
        @DisplayName("пустой ресурс — отказ: настроенный файл обязан что-то добавлять")
        void rejectsEmptyBundle() {
            InputStream empty = new ByteArrayInputStream(new byte[0]);

            assertThrows(IllegalArgumentException.class, () -> OutboundTrust.with(List.of(empty)));
        }
    }
}
