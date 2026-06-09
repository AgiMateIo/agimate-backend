package ru.agimate.controlapi.connectors.integrations.telegram;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@UtilityClass
public class TelegramUtils {

    public static final String CONNECTOR_CODE = "telegram";

    /** Telegram's hard limit for a single sendMessage/editMessageText text (UTF-16 code units, == String.length()). */
    public static final int MAX_MESSAGE_LENGTH = 4096;

    /** Telegram's hard limit for a media caption (sendPhoto and friends). */
    public static final int MAX_CAPTION_LENGTH = 1024;

    /**
     * Splits {@code text} into chunks of at most {@code limit} characters so each fits a single
     * Telegram message. Prefers to break on a newline, then on whitespace, and only hard-cuts when
     * a single run has no such boundary — keeping all content across messages.
     * <p>
     * Note: a split can fall inside an HTML/MarkdownV2 entity that spans the boundary; breaking on
     * line boundaries first makes that rare, but very long pre-formatted blocks are not entity-safe.
     */
    public static List<String> splitMessage(String text, int limit) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= limit) {
            chunks.add(text);
            return chunks;
        }
        int pos = 0;
        int len = text.length();
        while (pos < len) {
            if (len - pos <= limit) {
                chunks.add(text.substring(pos));
                break;
            }
            int window = pos + limit;
            int splitAt = text.lastIndexOf('\n', window - 1);
            if (splitAt <= pos) {
                splitAt = text.lastIndexOf(' ', window - 1);
            }
            if (splitAt <= pos) {
                // No newline/space within the window — hard cut at the limit.
                chunks.add(text.substring(pos, window));
                pos = window;
            } else {
                chunks.add(text.substring(pos, splitAt));
                pos = splitAt + 1; // drop the boundary newline/space
            }
        }
        return chunks;
    }

    /**
     * Truncates {@code text} to at most {@code limit} characters, appending an ellipsis when cut.
     * Used where the content cannot be split across messages (a single caption or an edited message).
     */
    public static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit - 1) + "…";
    }

    /**
     * Нормализует один Telegram update (уже распарсенный) в {@link Trigger}.
     * Используется и webhook-путём ({@code normalizeInbound}), и long-poll'ом — без
     * промежуточной сериализации в JSON и обратно.
     */
    @SuppressWarnings("unchecked")
    public static Trigger normalizeUpdate(Map<String, Object> update, String identity) {
        String triggerName;
        Map<String, Object> triggerData = new LinkedHashMap<>();

        if (update.containsKey("callback_query")) {
            triggerName = "telegram.callback_query";
            Map<String, Object> callbackQuery = (Map<String, Object>) update.get("callback_query");
            triggerData.put("callbackQueryId", callbackQuery.get("id"));
            triggerData.put("data", callbackQuery.get("data"));
            if (callbackQuery.containsKey("message")) {
                Map<String, Object> msg = (Map<String, Object>) callbackQuery.get("message");
                triggerData.put("chatId", ((Map<String, Object>) msg.get("chat")).get("id"));
                triggerData.put("messageId", msg.get("message_id"));
            }
            if (callbackQuery.containsKey("from")) {
                triggerData.put("from", callbackQuery.get("from"));
            }
        } else if (update.containsKey("message")) {
            Map<String, Object> message = (Map<String, Object>) update.get("message");
            Map<String, Object> chat = (Map<String, Object>) message.get("chat");

            triggerData.put("chatId", chat.get("id"));
            triggerData.put("messageId", message.get("message_id"));
            if (message.containsKey("from")) {
                triggerData.put("from", message.get("from"));
            }

            String text = (String) message.get("text");

            if (message.containsKey("photo")) {
                triggerName = "telegram.photo_received";
                triggerData.put("photo", message.get("photo"));
                triggerData.put("caption", message.get("caption"));
            } else if (message.containsKey("document")) {
                triggerName = "telegram.document_received";
                triggerData.put("document", message.get("document"));
                triggerData.put("caption", message.get("caption"));
            } else if (text != null && text.startsWith("/")) {
                triggerName = "telegram.command_received";
                triggerData.put("text", text);
                String[] parts = text.split("\\s+", 2);
                triggerData.put("command", parts[0]);
                if (parts.length > 1) {
                    triggerData.put("args", parts[1]);
                }
            } else {
                triggerName = "telegram.message_received";
                triggerData.put("text", text);
            }
        } else if (update.containsKey("channel_post")) {
            log.debug("Unsupported Telegram update type, keys: {}", update.keySet());
            triggerName = "telegram.channel_post";
            triggerData.put("raw", update);
        } else {
            log.debug("Unsupported Telegram update type, keys: {}", update.keySet());
            triggerName = "telegram.unknown";
            triggerData.put("raw", update);
        }

        return Trigger.createBasic(CONNECTOR_CODE, identity, triggerName, triggerData);
    }
}
