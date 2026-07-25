package ru.agimate.controlapi.service.seed;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.ContentProperties;

import java.util.Properties;

/**
 * Локализация доверенных инструкций, которые платформа кладёт агенту в промпт:
 * {@code seed/<lang>/prompt.properties}.
 *
 * <p>Это не подписи в интерфейсе, а <b>поведение</b>: правила автономной обработки событий, запрет
 * имитировать вызов тула текстом, attach-конвенция, инструкции реакции на событие коннектора. Модель
 * понимает и русский, но соблюдает инструкцию надёжнее, когда та на языке остального промпта — а в
 * EN-инсталляции инструкции агента и его скилы английские.
 *
 * <p>Отдельный бандл от {@link ConnectorTexts} именно поэтому: у каталога коннекторов читатель
 * человек и цена ошибки — некрасивая подпись, здесь читатель модель и цена ошибки — другое поведение
 * агента. Такие тексты нельзя отдавать на перевод по тем же правилам.
 *
 * <p>Ключи: {@code run.trigger.guidance}, {@code run.tool-call.guidance},
 * {@code run.attachment.guidance} — платформенные, применяются к каждому подходящему рану;
 * {@code connector.<code>.<trigger>.guidance} с фолбэком на {@code connector.<code>.guidance} —
 * инструкция реакции на событие конкретного коннектора.
 */
@Component
public class PromptTexts {

    /** Правила автономной обработки событий — trigger-раны. */
    public static final String RUN_TRIGGER_GUIDANCE = "run.trigger.guidance";
    /** Запрет имитировать вызов тула текстом — раны, у которых есть тулы. */
    public static final String RUN_TOOL_CALL_GUIDANCE = "run.tool-call.guidance";
    /** Attach-конвенция — DIALOGUE-раны, чей prompt-канал умеет вложения. */
    public static final String RUN_ATTACHMENT_GUIDANCE = "run.attachment.guidance";

    private final Properties texts;

    public PromptTexts(ContentProperties contentProperties) {
        this.texts = SeedTextBundle.load(contentProperties.getLanguage(), "prompt.properties");
    }

    /** Платформенный блок промпта по ключу; нет перевода — значение из кода. */
    public String get(String key, String fallback) {
        return texts.getProperty(key, fallback);
    }

    /**
     * Инструкция реакции на событие коннектора ({@code ContextDirectives.guidance}). Сначала ключ
     * с именем триггера, затем общий для коннектора — так коннектор с одной инструкцией на несколько
     * событий (board) держит её в одном ключе, и переводы не разъезжаются между копиями.
     */
    public String triggerGuidance(String connectorCode, String triggerName, String fallback) {
        String specific = texts.getProperty("connector.%s.%s.guidance".formatted(connectorCode, triggerName));
        if (specific != null) {
            return specific;
        }
        return texts.getProperty("connector.%s.guidance".formatted(connectorCode), fallback);
    }
}
