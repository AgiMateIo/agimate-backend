package ru.agimate.controlapi.service.seed;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.agimate.controlapi.config.ContentProperties;
import ru.agimate.controlapi.database.enums.ContentLanguage;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Which content language a call means: the request's {@code Accept-Language}, else the installation's
 * default ({@code app.content.language}).
 *
 * <p>The header is read here rather than through Spring's {@code LocaleResolver}: the resolver's
 * locale also drives validation messages, and a default set there would switch them for every client
 * that sends no header. Outside an HTTP request (a run, a trigger, a scheduler) there is no header,
 * and the default applies.
 */
@Component
@RequiredArgsConstructor
public class ContentLanguageResolver {

    private static final List<String> SUPPORTED = Arrays.stream(ContentLanguage.values())
            .map(ContentLanguage::dir)
            .toList();

    private final ContentProperties contentProperties;

    public ContentLanguage defaultLanguage() {
        return contentProperties.getLanguage();
    }

    /** The best supported language of the current request's {@code Accept-Language}; none — the default. */
    public ContentLanguage requestLanguage() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return defaultLanguage();
        }
        return fromAcceptLanguage(attributes.getRequest().getHeader(HttpHeaders.ACCEPT_LANGUAGE), defaultLanguage());
    }

    /**
     * Weighted ranges ({@code es-ES,es;q=0.9,en;q=0.8}) matched by the primary subtag, highest weight
     * first. Not {@link Locale#filterTags}: its RFC 4647 filtering does not match the range
     * {@code en-US} to the tag {@code en}, and a browser may send only the regional form.
     */
    static ContentLanguage fromAcceptLanguage(String header, ContentLanguage fallback) {
        if (header == null || header.isBlank()) {
            return fallback;
        }
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(header);
        } catch (IllegalArgumentException e) {
            // A malformed header is the client's problem, not a reason to fail the call.
            return fallback;
        }
        for (Locale.LanguageRange range : ranges) {
            if (range.getWeight() <= 0) {
                continue;
            }
            String primary = range.getRange().split("-", 2)[0];
            if (SUPPORTED.contains(primary)) {
                return ContentLanguage.valueOf(primary.toUpperCase(Locale.ROOT));
            }
        }
        return fallback;
    }
}
