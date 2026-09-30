package ru.agimate.controlapi.connectors.internal.web.page;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import ru.agimate.common.net.TargetNotAllowedException;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that knows the order of {@code web_fetch}: fetch, decode, extract, convert, cut.
 * The steps between are {@link ContentExtractor} and {@link MarkdownConverter}, each replaceable.
 *
 * <p>Redirects are followed here because {@link PublicOnlyHttp} refuses to: every hop is a new
 * connection, so its resolver vets every address the chain leads to.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebPageReader {

    static final int MAX_REDIRECTS = 5;
    /** Counted on decompressed bytes: httpclient5 undoes gzip itself, so a small download may inflate. */
    static final int MAX_BYTES = 5 * 1024 * 1024;
    /** For the whole call with all its hops — the worker waits 60 seconds for any tool by default. */
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration HOP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MIN_HOP_TIMEOUT = Duration.ofSeconds(1);

    private static final String USER_AGENT = "Mozilla/5.0 (compatible; AgiMate/1.0; +https://agimate.ru)";
    private static final Pattern ABSOLUTE_URL = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*://)([^/?#:@]+)(.*)$", Pattern.DOTALL);
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    /**
     * Statuses an anti-bot wall answers with (498 is Wildberries'); their body is read to tell a wall
     * from a plain refusal.
     */
    private static final Set<Integer> WALL_STATUSES = Set.of(401, 403, 429, 498, 503);

    private static final List<String> CHALLENGE_MARKERS = List.of(
            "just a moment", "checking your browser", "verify you are human", "attention required",
            "captcha", "капча", "вы не робот", "не робот", "доступ ограничен", "проверка браузера",
            "antibot", "anti-bot");
    private static final String CHALLENGE_ELEMENTS = "#challenge-form, #cf-challenge-running, .g-recaptcha, "
            + ".h-captcha, .smart-captcha, [data-sitekey], [data-site-key], form[action*=captcha], "
            + "script[src*=challenge-platform], link[href*=antibot], script[src*=antibot]";
    /** A wall is a short page; an article that merely mentions a captcha is long. */
    private static final int CHALLENGE_MAX_TEXT = 3000;

    private final PublicOnlyHttp http;
    private final ContentExtractor extractor;
    private final MarkdownConverter converter;

    public WebPage read(String url, int maxChars) {
        Fetched fetched = fetch(url);
        if (fetched.plainText()) {
            String text = decodePlain(fetched);
            Cut cut = cut(text.strip(), maxChars);
            return new WebPage(fetched.url().toString(), null, cut.text(), cut.truncated());
        }
        Document document = parse(fetched);
        if (isChallenge(document)) {
            throw challenge();
        }
        ExtractedContent extracted = extractor.extract(document);
        String content = assemble(extracted);
        if (content.isEmpty()) {
            throw new ConnectorException("The page has no readable text: it is probably rendered by "
                    + "JavaScript in the browser. Try another source.");
        }
        Cut cut = cut(content, maxChars);
        return new WebPage(fetched.url().toString(), extracted.title(), cut.text(), cut.truncated());
    }

    private record Fetched(URI url, MediaType type, byte[] body) {

        boolean plainText() {
            return type != null && type.isCompatibleWith(MediaType.TEXT_PLAIN);
        }
    }

    private Fetched fetch(String url) {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        URI current = vet(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            try (ClientHttpResponse response = request(current, deadline).execute()) {
                int status = response.getStatusCode().value();
                if (REDIRECTS.contains(status)) {
                    String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
                    if (location == null || location.isBlank()) {
                        throw new ConnectorException("The page redirects without saying where (HTTP " + status + ")");
                    }
                    current = vet(current.resolve(toAscii(location.strip())).toString());
                    continue;
                }
                MediaType type = contentType(response.getHeaders());
                if (status >= 200 && status < 300) {
                    requireReadable(type);
                    return new Fetched(current, type, readBody(response.getBody(), deadline));
                }
                if (WALL_STATUSES.contains(status) && isHtml(type)) {
                    Document wall = parse(new Fetched(current, type, readBody(response.getBody(), deadline)));
                    // An empty page refusing access is a wall too: the challenge lives in a script it loads.
                    if (isChallenge(wall) || wall.body() == null
                            || wall.body().text().length() < HeuristicContentExtractor.MIN_TEXT) {
                        throw challenge();
                    }
                }
                throw new ConnectorException("The page answered HTTP " + status);
            } catch (TargetNotAllowedException e) {
                throw new ConnectorException("This address cannot be fetched: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new ConnectorException("The page redirects to an invalid address");
            } catch (IOException e) {
                throw new ConnectorException(e.getCause() instanceof TargetNotAllowedException refused
                        ? "This address cannot be fetched: " + refused.getMessage()
                        : "Could not load the page: " + e.getMessage());
            }
        }
        throw new ConnectorException("Too many redirects (more than " + MAX_REDIRECTS + ")");
    }

    private URI vet(String url) {
        try {
            return http.requireSyntax(toAscii(url.strip()));
        } catch (TargetNotAllowedException e) {
            throw new ConnectorException(e.getMessage());
        }
    }

    /**
     * {@link URI} accepts neither a Cyrillic host ({@code .рф}) nor a Cyrillic path, and both are
     * ordinary in the Russian web: the host goes to punycode, the rest is percent-encoded.
     */
    static String toAscii(String url) {
        Matcher matcher = ABSOLUTE_URL.matcher(url);
        if (!matcher.matches()) {
            return percentEncodeNonAscii(url);
        }
        String host;
        try {
            host = IDN.toASCII(matcher.group(2), IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Invalid host in the URL: " + matcher.group(2));
        }
        return matcher.group(1) + host + percentEncodeNonAscii(matcher.group(3));
    }

    private static String percentEncodeNonAscii(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            if (codePoint > 0x7F || codePoint == ' ') {
                for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(String.format("%02X", b & 0xFF));
                }
            } else {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    private ClientHttpRequest request(URI uri, long deadline) throws IOException {
        Duration left = Duration.ofNanos(deadline - System.nanoTime());
        if (left.compareTo(MIN_HOP_TIMEOUT) < 0) {
            throw timedOut();
        }
        ClientHttpRequest request = http.requestFactory(left.compareTo(HOP_TIMEOUT) < 0 ? left : HOP_TIMEOUT)
                .createRequest(uri, HttpMethod.GET);
        HttpHeaders headers = request.getHeaders();
        headers.set(HttpHeaders.USER_AGENT, USER_AGENT);
        headers.set(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1");
        headers.set(HttpHeaders.ACCEPT_LANGUAGE, "ru,en;q=0.8");
        return request;
    }

    /** A body beyond the cap is cut, not refused: the beginning of a huge page is still its main part. */
    private static byte[] readBody(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while (out.size() < MAX_BYTES && (read = in.read(buffer, 0, Math.min(buffer.length, MAX_BYTES - out.size()))) != -1) {
            out.write(buffer, 0, read);
            if (System.nanoTime() > deadline) {
                throw timedOut();
            }
        }
        return out.toByteArray();
    }

    private static ConnectorException timedOut() {
        return new ConnectorException("The page took too long to load (over " + DEADLINE.toSeconds() + " seconds)");
    }

    /** No or unparsable Content-Type — sniffed as HTML, which is what such a server almost always sends. */
    private static MediaType contentType(HttpHeaders headers) {
        try {
            return headers.getContentType();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isHtml(MediaType type) {
        return type == null || type.isCompatibleWith(MediaType.TEXT_HTML)
                || type.isCompatibleWith(MediaType.APPLICATION_XHTML_XML);
    }

    private static void requireReadable(MediaType type) {
        if (!isHtml(type) && !type.isCompatibleWith(MediaType.TEXT_PLAIN)) {
            throw new ConnectorException("Unsupported content type " + type.getType() + "/" + type.getSubtype()
                    + ": only HTML pages and plain text can be read");
        }
    }

    /** The header's charset if it names one we know; otherwise jsoup looks at the BOM and {@code <meta charset>}. */
    private static String declaredCharset(MediaType type) {
        try {
            Charset charset = type == null ? null : type.getCharset();
            return charset == null ? null : charset.name();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Document parse(Fetched fetched) {
        try {
            return Jsoup.parse(new ByteArrayInputStream(fetched.body()), declaredCharset(fetched.type()),
                    fetched.url().toString());
        } catch (IOException e) {
            throw new ConnectorException("Could not parse the page: " + e.getMessage());
        }
    }

    private static String decodePlain(Fetched fetched) {
        String name = declaredCharset(fetched.type());
        String text = new String(fetched.body(), name == null ? StandardCharsets.UTF_8 : Charset.forName(name));
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    static boolean isChallenge(Document document) {
        String text = document.body() == null ? "" : document.body().text();
        if (text.length() > CHALLENGE_MAX_TEXT) {
            return false;
        }
        if (document.selectFirst(CHALLENGE_ELEMENTS) != null) {
            return true;
        }
        String haystack = (document.title() + " " + text).toLowerCase(Locale.ROOT);
        return CHALLENGE_MARKERS.stream().anyMatch(haystack::contains);
    }

    private static ConnectorException challenge() {
        return new ConnectorException("The site does not let automated requests through (an anti-bot "
                + "check or a captcha). Try another source.");
    }

    /** Metadata goes first: it is short, and cutting the text must not take the date or the price with it. */
    private String assemble(ExtractedContent extracted) {
        StringBuilder out = new StringBuilder();
        if (!extracted.metadata().isEmpty()) {
            out.append("Page metadata:\n");
            for (Map.Entry<String, String> entry : extracted.metadata().entrySet()) {
                out.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        if (extracted.body() != null) {
            String markdown = converter.convert(extracted.body());
            if (!markdown.isEmpty()) {
                out.append(out.isEmpty() ? "" : "\n").append(markdown);
            }
        }
        return out.toString().strip();
    }

    record Cut(String text, boolean truncated) {
    }

    /** At a paragraph break when one is in the second half of the budget, else at a line break, else hard. */
    static Cut cut(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return new Cut(text, false);
        }
        int at = text.lastIndexOf("\n\n", maxChars);
        if (at < maxChars / 2) {
            at = text.lastIndexOf('\n', maxChars);
        }
        if (at < maxChars / 2) {
            at = Character.isHighSurrogate(text.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
        }
        return new Cut(text.substring(0, at).stripTrailing(), true);
    }
}
