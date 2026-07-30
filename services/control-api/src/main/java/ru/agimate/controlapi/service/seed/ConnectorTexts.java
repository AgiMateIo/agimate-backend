package ru.agimate.controlapi.service.seed;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.ContentProperties;

import java.util.Properties;

/**
 * Localisation of the connector catalogue's texts: {@code seed/texts/<lang>/connectors.properties} with the
 * keys {@code <code>.name} and {@code <code>.description}.
 *
 * <p>English lives in the code ({@code connectorName()}/{@code connectorDescription()}) and serves as
 * the last fallback, which is why there is no file for {@link ContentLanguage#DEFAULT} — translations
 * are added only for the other languages. The asymmetry is deliberate: otherwise the same text would
 * sit both in Java and in the properties and would drift apart when one of them was edited.
 *
 * <p>The {@code connectors} catalogue is rewritten on every start ({@code ConnectorBootstrap}), so
 * changing {@code app.content.language} translates it with no migrations — unlike presets and skills,
 * where the language is fixed by the first seeding. The prompt's texts live separately in
 * {@link PromptTexts}: they have a different reader (the model, not the user) and a different cost of
 * error — an edit changes the agent's behaviour, not a caption in the interface.
 */
@Component
public class ConnectorTexts {

    private final Properties texts;

    public ConnectorTexts(ContentProperties contentProperties) {
        this.texts = SeedTextBundle.load(contentProperties.getLanguage(), "connectors.properties");
    }

    /** The connector's display name; no translation — the value from the code. */
    public String name(String connectorCode, String fallback) {
        return texts.getProperty(connectorCode + ".name", fallback);
    }

    /** The connector's description for the connections catalogue; no translation — the value from the code. */
    public String description(String connectorCode, String fallback) {
        return texts.getProperty(connectorCode + ".description", fallback);
    }
}
