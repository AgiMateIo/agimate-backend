package ru.agimate.controlapi.service.llm.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RemoteImageFetcher — ссылка из ответа провайдера как недоверенный ввод")
class RemoteImageFetcherTest {

    // Проверка адреса живёт в PublicTargets (и проверяется там же): здесь остаётся только то,
    // что специфично для картинки — по http за ней не ходим даже на публичный адрес.
    @Test
    @DisplayName("http и не-URL отвергаются: скачиваем только по https")
    void requiresHttps() {
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requireHttps(URI.create("http://cdn.example.com/a.png")));
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requireHttps(URI.create("file:///etc/passwd")));
        assertDoesNotThrow(
                () -> RemoteImageFetcher.requireHttps(URI.create("https://cdn.example.com/a.png")));
    }
}
