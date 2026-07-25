package ru.agimate.controlapi.service.seed;

/**
 * Язык системного контента — пресетов и скилов, отдаваемых пользователю, и текстов, которые
 * платформа кладёт в промпт агента ({@code app.content.language}). Не язык ответов агента: тот
 * определяется языком пользователя и задан в самих инструкциях.
 *
 * <p>Каждому значению соответствует папка {@code resources/seed/<lang>/} (см.
 * {@link SeedContentLocator}), поэтому добавление языка = новая константа + каталог.
 */
public enum ContentLanguage {

    RU,
    EN;

    /** Язык-первоисточник: на него уходит фолбэк, если контента для выбранного языка нет. */
    public static final ContentLanguage DEFAULT = RU;

    /** Сегмент пути в {@code resources/seed/}. */
    public String dir() {
        return name().toLowerCase();
    }
}
