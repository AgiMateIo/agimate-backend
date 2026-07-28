package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.service.seed.ContentLanguage;

/**
 * Language of the installation's system content ({@code APP_CONTENT_LANGUAGE}). An enum field rather
 * than a string: a typo in the value fails the startup instead of silently sending the seeding to a
 * fallback.
 *
 * <p>Read only during seeding ({@code SystemSkillBootstrap}/{@code SystemPresetBootstrap}) and when
 * assembling connector texts — the database holds a single set of strings, so changing the language
 * on an already-seeded environment translates nothing by itself (see docs/services/control-api.md).
 */
@Component
@ConfigurationProperties(prefix = "app.content")
@Getter
@Setter
public class ContentProperties {

    private ContentLanguage language = ContentLanguage.DEFAULT;
}
