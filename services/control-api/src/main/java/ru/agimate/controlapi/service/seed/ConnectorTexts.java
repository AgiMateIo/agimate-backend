package ru.agimate.controlapi.service.seed;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.ContentProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Локализация текстов каталога коннекторов: {@code seed/<lang>/connectors.properties} с ключами
 * {@code <code>.name} и {@code <code>.description}.
 *
 * <p>Русский живёт в коде ({@code connectorName()}/{@code connectorDescription()}) и служит
 * последним фолбэком, поэтому файла для {@link ContentLanguage#DEFAULT} нет — переводы добавляются
 * только для остальных языков. Асимметрия сознательная: иначе один и тот же текст лежал бы и в Java,
 * и в properties, и разъезжался бы при правке одного из них.
 *
 * <p>Каталог {@code connectors} перезаписывается на каждом старте ({@code ConnectorBootstrap}),
 * поэтому смена {@code app.content.language} переводит его без миграций — в отличие от пресетов и
 * скилов, где язык фиксируется первым сидингом.
 */
@Slf4j
@Component
public class ConnectorTexts {

    private static final String RESOURCE = "seed/%s/connectors.properties";

    private final Properties texts = new Properties();

    public ConnectorTexts(ContentProperties contentProperties) {
        ContentLanguage language = contentProperties.getLanguage();
        if (language != ContentLanguage.DEFAULT) {
            load(language);
        }
    }

    /** Отображаемое имя коннектора; нет перевода — значение из кода. */
    public String name(String connectorCode, String fallback) {
        return texts.getProperty(connectorCode + ".name", fallback);
    }

    /** Описание коннектора для каталога подключений; нет перевода — значение из кода. */
    public String description(String connectorCode, String fallback) {
        return texts.getProperty(connectorCode + ".description", fallback);
    }

    private void load(ContentLanguage language) {
        String path = RESOURCE.formatted(language.dir());
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            log.warn("No {} — connector catalog stays in {}", path, ContentLanguage.DEFAULT);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            texts.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            log.info("Loaded {} connector catalog texts from {}", texts.size(), path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read connector texts: " + path, e);
        }
    }
}
