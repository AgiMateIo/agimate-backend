package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.service.seed.ContentLanguage;

/**
 * Язык системного контента инсталляции ({@code APP_CONTENT_LANGUAGE}). Поле-enum, а не строка:
 * опечатка в значении роняет старт, а не молча уводит сидинг на фолбэк.
 *
 * <p>Читается только на этапе сидинга ({@code SystemSkillBootstrap}/{@code SystemPresetBootstrap})
 * и при сборке текстов коннекторов — в БД лежит один набор строк, поэтому смена языка на уже
 * засеянном окружении сама по себе ничего не переводит (см. docs/services/control-api.md).
 */
@Component
@ConfigurationProperties(prefix = "app.content")
@Getter
@Setter
public class ContentProperties {

    private ContentLanguage language = ContentLanguage.DEFAULT;
}
