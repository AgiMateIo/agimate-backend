package ru.agimate.controlapi.service.seed;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.ContentProperties;

import java.util.Properties;

/**
 * Localisation of the LLM provider catalogue: {@code seed/<lang>/llm-providers.properties} with the
 * key {@code <code>.description}.
 *
 * <p>Only the description has a key. A provider's name is a brand — it reads the same on any
 * installation, and a translation entry for it would only invite someone to transliterate it.
 *
 * <p>English lives in {@code seed/llm-providers.yaml} and is the last fallback, so there is no
 * bundle for {@link ContentLanguage#DEFAULT} — the same asymmetry as {@link ConnectorTexts}, and for
 * the same reason: otherwise one text would sit in two files and drift. The catalogue is upserted on
 * every start, so changing {@code app.content.language} re-translates it with no migration.
 */
@Component
public class LlmCatalogTexts {

    private final Properties texts;

    public LlmCatalogTexts(ContentProperties contentProperties) {
        this.texts = SeedTextBundle.load(contentProperties.getLanguage(), "llm-providers.properties");
    }

    /** The entry's description for the catalogue; no translation — the value from the seed file. */
    public String description(String code, String fallback) {
        return texts.getProperty(code + ".description", fallback);
    }
}
