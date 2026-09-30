package ru.agimate.controlapi.connectors.internal.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Synthetic pages under {@code src/test/resources/web}: markup of real sites, text of our own. */
public final class WebFixtures {

    private WebFixtures() {
    }

    public static byte[] bytes(String name) {
        try (InputStream in = WebFixtures.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    public static Document document(String name) {
        return Jsoup.parse(text(name), "https://example.ru/section/page");
    }
}
