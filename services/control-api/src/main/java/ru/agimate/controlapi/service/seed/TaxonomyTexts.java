package ru.agimate.controlapi.service.seed;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.ContentProperties;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.enums.ContentTag;

import java.util.Properties;

/**
 * Localisation of the catalogue's taxonomy: {@code seed/texts/<lang>/taxonomy.properties} with the keys
 * {@code category.<code>}, {@code tag.<code>} and {@code tag-group.<code>}, where the code is the enum
 * constant in kebab-case ({@code own-token}).
 *
 * <p>Same rule as {@link ConnectorTexts}: English lives in the enum and is the last fallback, so there
 * is no bundle for {@link ContentLanguage#DEFAULT} — otherwise one text would sit in two places and
 * drift. Unlike skills and presets, the vocabulary is not stored per installation: the labels are
 * resolved on every response, so changing {@code app.content.language} translates the catalogue's
 * sections with no migration.
 */
@Component
public class TaxonomyTexts {

    private final Properties texts;

    public TaxonomyTexts(ContentProperties contentProperties) {
        this.texts = SeedTextBundle.load(contentProperties.getLanguage(), "taxonomy.properties");
    }

    public String label(ContentCategory category) {
        return texts.getProperty("category." + code(category.name()), category.getLabel());
    }

    public String label(ContentTag tag) {
        return texts.getProperty("tag." + code(tag.name()), tag.getLabel());
    }

    public String label(ContentTag.Group group) {
        return texts.getProperty("tag-group." + code(group.name()), group.getLabel());
    }

    /** The key in the bundle: an enum constant in kebab-case, as it is written in the frontmatter. */
    public static String code(String enumName) {
        return enumName.toLowerCase().replace('_', '-');
    }
}
