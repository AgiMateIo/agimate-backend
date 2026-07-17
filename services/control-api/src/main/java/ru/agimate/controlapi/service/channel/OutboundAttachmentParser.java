package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attach-конвенция исходящего ответа агента: маркер {@code [[attach:agf_<uuid>]]} в тексте →
 * {@link Part} c {@code storageRef}. Маркеры вырезаются из текста всегда (даже невалидные —
 * пользователь не должен видеть служебную разметку); part создаётся только для файла, доступного
 * владельцу канала ({@code FileStorageService.findReadable}: свой + READY + не просрочен) — чужой
 * или галлюцинированный id молча отбрасывается с warn'ом.
 *
 * <p>Парсится только текст ответа агента (доверенный автор); маркеры внутри tool-результатов
 * сюда не попадают. Семантику маркера агенту объясняет system-блок {@code RunContextService},
 * который добавляется только для каналов с {@code supportsOutboundAttachments()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundAttachmentParser {

    private static final Pattern MARKER =
            Pattern.compile("\\[\\[attach:(" + FileIds.PREFIX + "[0-9a-fA-F\\-]{36})]]");

    private final FileStorageService fileStorageService;

    /**
     * Извлекает вложения из текста ответа. Без маркеров возвращает {@code outbound} как есть;
     * с уже заполненными {@code parts} (будущие структурные продюсеры) текст не трогается.
     */
    public OutboundMessage parse(UUID ownerUserId, OutboundMessage outbound) {
        String text = outbound.text();
        if (text == null || !outbound.parts().isEmpty()) {
            return outbound;
        }
        Matcher matcher = MARKER.matcher(text);
        if (!matcher.find()) {
            return outbound;
        }

        List<Part> parts = new ArrayList<>();
        StringBuilder clean = new StringBuilder();
        matcher.reset();
        while (matcher.find()) {
            String fileId = matcher.group(1);
            fileStorageService.findReadable(ownerUserId, fileId).ifPresentOrElse(
                    file -> parts.add(new Part(
                            partType(file.getMime()), fileId, file.getMime(), file.getSizeBytes(), Map.of())),
                    () -> log.warn("Dropping unresolvable attachment marker {} (user {})", fileId, ownerUserId));
            matcher.appendReplacement(clean, "");
        }
        matcher.appendTail(clean);
        return new OutboundMessage(clean.toString().strip(), List.copyOf(parts));
    }

    private static String partType(String mime) {
        if (mime == null) {
            return "file";
        }
        if (mime.startsWith("image/")) {
            return "image";
        }
        if (mime.startsWith("video/")) {
            return "video";
        }
        if (mime.startsWith("audio/")) {
            return "audio";
        }
        return "file";
    }
}
