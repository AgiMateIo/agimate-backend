package ru.agimate.controlapi.connectors.integrations.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.NewFile;

import java.io.ByteArrayInputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Materialisation of incoming Telegram files: downloads a photo or document through the Bot API and
 * saves it into the file layer, replacing the raw descriptors ({@code photo}/{@code document}) in the
 * trigger's data with {@code parts} carrying {@code agf_} references. Done ONCE at the ingest
 * boundary (webhook/long-poll), before the trigger is persisted — so {@code handleInput} stays a
 * deterministic function of the data.
 *
 * <p>Degradation: any download failure → the trigger is returned unchanged (the handler produces a
 * text stub, as before) and the event is not lost. The token never reaches the logs or exceptions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramMediaService {

    private static final String PHOTO_RECEIVED = "photo_received";
    private static final String DOCUMENT_RECEIVED = "document_received";
    private static final String PHOTO_MIME = "image/jpeg";
    /** The Bot API hands bots files up to roughly this size — beyond it we do not attempt a download. */
    private static final long DOWNLOAD_LIMIT_BYTES = 20L * 1024 * 1024;

    private final TelegramApiClient telegramApiClient;
    private final FileStorageService fileStorageService;

    /** Whether the incoming trigger carries media that needs downloading (the gate before decrypting the token). */
    public boolean hasMedia(Trigger trigger) {
        String name = trigger.name();
        return PHOTO_RECEIVED.equals(name) || DOCUMENT_RECEIVED.equals(name);
    }

    /**
     * Downloads the trigger's media and returns a copy with {@code data.parts}; for non-media or on
     * failure it returns the original trigger unchanged.
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
            // The exception's class only: RestClient may embed a URL containing the token into the message or cause.
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
        // No agentId: ingest happens in the webhook, before routing decides which agents receive the
        // message — and there may be several of them.
        StoredFile stored = fileStorageService.store(NewFile.builder()
                .userId(userId)
                .origin("telegram:" + connectionId)
                .name(descriptor.name())
                .mime(descriptor.mime())
                .sizeBytes(bytes.length)
                .build(), new ByteArrayInputStream(bytes));

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

    /** The largest {@code PhotoSize} (the last one, max by file_size) — that is what we feed to the model. */
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

    /** Descriptor of one file to download. */
    private record Descriptor(String fileId, String mime, String name) {
    }
}
