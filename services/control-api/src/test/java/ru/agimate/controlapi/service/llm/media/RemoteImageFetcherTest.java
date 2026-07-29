package ru.agimate.controlapi.service.llm.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RemoteImageFetcher — ссылка из ответа провайдера как недоверенный ввод")
class RemoteImageFetcherTest {

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

    @Test
    @DisplayName("loopback, приватные и link-local адреса отвергаются — иначе это SSRF в нашу же сеть")
    void rejectsPrivateAddresses() throws UnknownHostException {
        MediaInferenceException e = assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublic(InetAddress.getByName("127.0.0.1")));
        assertTrue(e.getMessage().contains("private address"), e.getMessage());

        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublic(InetAddress.getByName("10.0.0.5")));
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublic(InetAddress.getByName("192.168.1.1")));
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublic(InetAddress.getByName("169.254.169.254")));
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublic(InetAddress.getByName("0.0.0.0")));
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.requirePublic(InetAddress.getByName("::1")));
    }

    @Test
    @DisplayName("публичный адрес проходит")
    void allowsPublicAddress() throws UnknownHostException {
        assertDoesNotThrow(() -> RemoteImageFetcher.requirePublic(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    @DisplayName("резолвер проверяет каждый адрес имени: соединение увидит только выверенные")
    void resolverVetsEveryAddress() {
        // localhost резолвится локально (hosts), в 127.0.0.1 и/или ::1 — годиться не должен ни один.
        assertThrows(MediaInferenceException.class,
                () -> RemoteImageFetcher.resolvePublicOnly("localhost"));
    }
}
