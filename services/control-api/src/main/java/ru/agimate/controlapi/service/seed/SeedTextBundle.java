package ru.agimate.controlapi.service.seed;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Loading of a translation properties bundle from {@code seed/<lang>/<file>}.
 *
 * <p>English lives in the code and serves as the fallback, so for {@link ContentLanguage#DEFAULT}
 * there are no bundles: a request returns empty {@link Properties} and every key falls back. A missing
 * bundle for another language is a warning, not a refusal to start: the installation stays in English
 * but comes up.
 */
@Slf4j
@UtilityClass
class SeedTextBundle {

    static Properties load(ContentLanguage language, String fileName) {
        Properties properties = new Properties();
        if (language == ContentLanguage.DEFAULT) {
            return properties;
        }

        String path = "seed/%s/%s".formatted(language.dir(), fileName);
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            log.warn("No {} — these texts stay in {}", path, ContentLanguage.DEFAULT);
            return properties;
        }
        try (InputStream in = resource.getInputStream()) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            log.info("Loaded {} texts from {}", properties.size(), path);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read seed texts: " + path, e);
        }
    }
}
