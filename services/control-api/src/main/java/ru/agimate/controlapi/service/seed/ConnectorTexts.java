package ru.agimate.controlapi.service.seed;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.ContentProperties;

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
 * скилов, где язык фиксируется первым сидингом. Тексты промпта живут отдельно в {@link PromptTexts}:
 * у них другой читатель (модель, не пользователь) и другая цена ошибки — правка меняет поведение
 * агента, а не подпись в интерфейсе.
 */
@Component
public class ConnectorTexts {

    private final Properties texts;

    public ConnectorTexts(ContentProperties contentProperties) {
        this.texts = SeedTextBundle.load(contentProperties.getLanguage(), "connectors.properties");
    }

    /** Отображаемое имя коннектора; нет перевода — значение из кода. */
    public String name(String connectorCode, String fallback) {
        return texts.getProperty(connectorCode + ".name", fallback);
    }

    /** Описание коннектора для каталога подключений; нет перевода — значение из кода. */
    public String description(String connectorCode, String fallback) {
        return texts.getProperty(connectorCode + ".description", fallback);
    }
}
