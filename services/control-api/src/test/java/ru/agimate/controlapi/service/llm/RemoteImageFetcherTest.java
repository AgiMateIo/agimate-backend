package ru.agimate.controlapi.service.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RemoteImageFetcher — ссылка из ответа провайдера как недоверенный ввод")
class RemoteImageFetcherTest {

    @Test
    @DisplayName("http и не-URL отвергаются: скачиваем только по https")
    void requiresHttps() {
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublicHttps(URI.create("http://cdn.example.com/a.png")));
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublicHttps(URI.create("file:///etc/passwd")));
    }

    @Test
    @DisplayName("loopback и приватные адреса отвергаются — иначе это SSRF в нашу же сеть")
    void rejectsPrivateAddresses() {
        MediaInferenceException e = assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublicHttps(URI.create("https://127.0.0.1/a.png")));
        assertTrue(e.getMessage().contains("private address"), e.getMessage());

        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublicHttps(URI.create("https://10.0.0.5/a.png")));
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublicHttps(URI.create("https://192.168.1.1/a.png")));
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublicHttps(URI.create("https://169.254.169.254/latest/meta-data")));
    }

    @Test
    @DisplayName("публичный https-адрес проходит")
    void allowsPublicHttps() {
        assertDoesNotThrow(() -> RemoteImageFetcher.requirePublicHttps(URI.create("https://8.8.8.8/a.png")));
    }
}
