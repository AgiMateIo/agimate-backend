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
 * The attach convention of an agent's outgoing answer: the marker {@code [[attach:agf_<uuid>]]} in
 * the text → a {@link Part} with a {@code storageRef}. Markers are always cut out of the text (even
 * invalid ones — the user must not see internal markup); a part is created only for a file the
 * channel's owner may read ({@code FileStorageService.findReadable}: own + READY + not expired) — a
 * foreign or hallucinated id is silently dropped with a warning.
 *
 * <p>Only the agent's answer text is parsed (a trusted author); markers inside tool results never get
 * here. The marker's meaning is explained to the agent by a system block from
 * {@code RunContextService}, which is added only for channels with
 * {@code supportsOutboundAttachments()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundAttachmentParser {

    private static final Pattern MARKER =
            Pattern.compile("\\[\\[attach:(" + FileIds.PREFIX + "[0-9a-fA-F\\-]{36})]]");

    private final FileStorageService fileStorageService;

    /**
     * Extracts attachments from the answer's text. With no markers it returns {@code outbound}
     * unchanged; when {@code parts} are already populated (future structural producers) the text is
     * left alone.
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
                            partType(file.getMime()), fileId, file.getMime(), file.getSizeBytes(),
                            file.getName() != null ? Map.of("name", file.getName()) : Map.of())),
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
