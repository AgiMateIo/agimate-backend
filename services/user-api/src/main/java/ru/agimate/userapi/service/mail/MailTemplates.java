package ru.agimate.userapi.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The letters themselves: {@code mail/<lang>/<name>.html} for the body, one
 * {@code mail/<lang>/subjects.properties} per language for the subjects — the same split the seeded
 * texts of control-api use, and the one a translator can work with without touching markup.
 *
 * <p>Values are substituted into {@code {{placeholder}}} and HTML-escaped on the way in: a display
 * name is written by the person it belongs to, and it arrives here as text, not as markup.
 */
@Slf4j
@Component
public class MailTemplates {

    /**
     * Where a language without its own letters lands. English rather than the installation language:
     * a missing translation should read oddly, not fail to arrive.
     */
    private static final String FALLBACK_LANGUAGE = "en";

    private final String language;

    /** Rendered per call, read from the classpath once — the files cannot change under a running service. */
    private final Map<String, Letter> sources = new ConcurrentHashMap<>();

    public MailTemplates(@Value("${app.content.language:ru}") String language) {
        this.language = language;
    }

    /** Subject and body, both ready to hand to the transport. */
    public record Letter(String subject, String html) {}

    public Letter render(String name, Map<String, String> variables) {
        Letter source = sources.computeIfAbsent(name, this::load);
        return new Letter(
                substitute(source.subject(), variables, name),
                substitute(source.html(), variables, name));
    }

    private Letter load(String name) {
        String resolved = resolveLanguage(name);
        ClassPathResource body = body(resolved, name);
        try {
            return new Letter(subjects(resolved).getProperty(name), body.getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read mail template " + body.getPath(), e);
        }
    }

    private String resolveLanguage(String name) {
        if (body(language, name).exists()) {
            return language;
        }
        log.warn("no {} translation of the {} letter — falling back to {}", language, name, FALLBACK_LANGUAGE);
        return FALLBACK_LANGUAGE;
    }

    private ClassPathResource body(String lang, String name) {
        return new ClassPathResource("mail/" + lang + "/" + name + ".html");
    }

    private Properties subjects(String lang) {
        ClassPathResource resource = new ClassPathResource("mail/" + lang + "/subjects.properties");
        Properties properties = new Properties();
        // Explicitly UTF-8: the default of the two-argument load is ISO-8859-1, and every subject
        // here is Cyrillic.
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read mail subjects of language " + lang, e);
        }
        return properties;
    }

    /**
     * A placeholder left unfilled means the caller and the letter disagree about what the letter
     * needs — most often a renamed variable. Sending it anyway would show the reader the markers.
     */
    private String substitute(String template, Map<String, String> variables, String name) {
        if (template == null) {
            throw new IllegalStateException("No subject for the mail template " + name);
        }
        String result = template;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            result = result.replace("{{" + variable.getKey() + "}}", HtmlUtils.htmlEscape(variable.getValue()));
        }
        if (result.contains("{{")) {
            throw new IllegalStateException("Unfilled placeholder in the mail template " + name);
        }
        return result;
    }
}
