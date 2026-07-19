package ru.agimate.controlapi.connectors.integrations.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;

import java.io.ByteArrayInputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Материализация входящих файлов Telegram: скачивает фото/документ через Bot API и сохраняет в
 * файловый слой, заменяя сырые дескрипторы ({@code photo}/{@code document}) в data триггера на
 * {@code parts} со ссылками {@code agf_}. Делается ОДИН раз на ingest-границе (webhook/long-poll),
 * до персиста триггера — {@code handleInput} остаётся детерминированной функцией от данных.
 *
 * <p>Деградация: любой сбой скачивания → триггер возвращается без изменений (handler отдаст текстовую
 * заглушку, как раньше), событие не теряется. Токен в логи/исключения не попадает.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramMediaService {

    private static final String PHOTO_RECEIVED = "photo_received";
    private static final String DOCUMENT_RECEIVED = "document_received";
    private static final String PHOTO_MIME = "image/jpeg";
    /** Bot API отдаёт ботам файлы примерно до этого размера — выше не пытаемся скачивать. */
    private static final long DOWNLOAD_LIMIT_BYTES = 20L * 1024 * 1024;

    private final TelegramApiClient telegramApiClient;
    private final FileStorageService fileStorageService;

    /** Есть ли во входящем триггере медиа, требующее скачивания (гейт перед расшифровкой токена). */
    public boolean hasMedia(Trigger trigger) {
        String name = trigger.name();
        return PHOTO_RECEIVED.equals(name) || DOCUMENT_RECEIVED.equals(name);
    }

    /**
     * Скачивает медиа триггера и возвращает копию с {@code data.parts}; для не-медиа или при сбое —
     * исходный триггер без изменений.
     */
    @SuppressWarnings("unchecked")
    public Trigger materialize(String token, UUID userId, String connectionId, Trigger trigger) {
        if (!hasMedia(trigger) || token == null || token.isBlank() || userId == null) {
            return trigger;
        }
        Map<String, Object> data = trigger.data() != null ? trigger.data() : Map.of();
        try {
            Descriptor descriptor = PHOTO_RECEIVED.equals(trigger.name())
                    ? photoDescriptor((List<Map<String, Object>>) data.get("photo"))
                    : documentDescriptor((Map<String, Object>) data.get("document"));
            if (descriptor == null) {
                return trigger;
            }
            Map<String, Object> part = download(token, userId, connectionId, descriptor);
            if (part == null) {
                return trigger;
            }
            Map<String, Object> newData = new LinkedHashMap<>(data);
            newData.remove("photo");
            newData.remove("document");
            newData.put("parts", List.of(part));
            return trigger.withData(newData);
        } catch (Exception e) {
            // Только класс исключения: RestClient может вложить URL с токеном в message/cause.
            log.warn("Failed to materialize Telegram media for connection {}: {}",
                    connectionId, e.getClass().getSimpleName());
            return trigger;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> download(String token, UUID userId, String connectionId, Descriptor descriptor) {
        Map<String, Object> getFile = telegramApiClient.getFile(token, descriptor.fileId());
        if (!Boolean.TRUE.equals(getFile.get("ok"))) {
            log.warn("Telegram getFile not ok for connection {}", connectionId);
            return null;
        }
        Map<String, Object> result = (Map<String, Object>) getFile.get("result");
        String filePath = result != null ? (String) result.get("file_path") : null;
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        long declaredSize = result.get("file_size") instanceof Number n ? n.longValue() : 0L;
        if (declaredSize > DOWNLOAD_LIMIT_BYTES) {
            log.warn("Telegram file too large to download for connection {}: {} bytes", connectionId, declaredSize);
            return null;
        }

        byte[] bytes = telegramApiClient.downloadFile(token, filePath);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        StoredFile stored = fileStorageService.store(userId, "telegram:" + connectionId,
                descriptor.mime(), bytes.length, new ByteArrayInputStream(bytes), null);

        String fileId = FileIds.external(stored.getId());
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("type", Part.typeForMime(descriptor.mime()));
        part.put("fileId", fileId);
        part.put("mime", descriptor.mime());
        part.put("size", stored.getSizeBytes());
        if (descriptor.name() != null && !descriptor.name().isBlank()) {
            part.put("name", descriptor.name());
        }
        return part;
    }

    /** Самый крупный {@code PhotoSize} (последний/max по file_size) — его и подаём модели. */
    private static Descriptor photoDescriptor(List<Map<String, Object>> photos) {
        if (photos == null || photos.isEmpty()) {
            return null;
        }
        Map<String, Object> largest = photos.stream()
                .max(Comparator.comparingLong(p -> p.get("file_size") instanceof Number n ? n.longValue() : 0L))
                .orElse(photos.get(photos.size() - 1));
        Object fileId = largest.get("file_id");
        return fileId == null ? null : new Descriptor(fileId.toString(), PHOTO_MIME, null);
    }

    private static Descriptor documentDescriptor(Map<String, Object> document) {
        if (document == null) {
            return null;
        }
        Object fileId = document.get("file_id");
        if (fileId == null) {
            return null;
        }
        String mime = document.get("mime_type") != null
                ? document.get("mime_type").toString() : "application/octet-stream";
        String name = document.get("file_name") != null ? document.get("file_name").toString() : null;
        return new Descriptor(fileId.toString(), mime, name);
    }

    /** Дескриптор одного файла к скачиванию. */
    private record Descriptor(String fileId, String mime, String name) {
    }
}
