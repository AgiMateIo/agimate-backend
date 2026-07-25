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
 * Загрузка properties-бандла перевода из {@code seed/<lang>/<file>}.
 *
 * <p>Русский живёт в коде и служит фолбэком, поэтому для {@link ContentLanguage#DEFAULT} бандлов нет:
 * запрос отдаёт пустые {@link Properties}, и все ключи уходят в фолбэк. Отсутствующий бандл для
 * остального языка — warning, а не отказ старта: инсталляция останется на русском, но поднимется.
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
