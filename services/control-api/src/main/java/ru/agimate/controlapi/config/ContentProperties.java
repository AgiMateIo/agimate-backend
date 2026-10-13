package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.enums.ContentLanguage;

/**
 * The installation's default content language ({@code APP_CONTENT_LANGUAGE}): the language of a call
 * whose client names none ({@code ContentLanguageResolver}), of the catalogues while listings do not
 * follow the request, and of connector and prompt texts. Every language's presets and skills are seeded
 * regardless. An enum field rather than a string: a typo in the value fails the startup.
 */
@Component
@ConfigurationProperties(prefix = "app.content")
@Getter
@Setter
public class ContentProperties {

    private ContentLanguage language = ContentLanguage.DEFAULT;
}
