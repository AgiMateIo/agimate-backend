package ru.agimate.controlapi.storage;

import lombok.experimental.UtilityClass;
import org.springframework.http.MediaType;

import java.util.Map;

/**
 * The name a file goes out to a human under: {@code Content-Disposition} on both delivery paths and
 * the multipart part name when a file is forwarded to a chat.
 *
 * <p>A stored name is never touched — what the producer knew is what the person gets, extension or
 * no extension. Synthesis is only for a file that has no name in nature (a generated image, a
 * Telegram photo), where the alternative is the raw {@code agf_<uuid>}: unreadable, and with nothing
 * for the OS to open it by.
 */
@UtilityClass
public class FileNames {

    /**
     * MIME → extension. Only what this system actually produces or accepts often enough for the
     * fallback to be wrong about it — {@code audio/mpeg} is {@code .mp3}, and the xlsx subtype is not
     * an extension at all.
     */
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/svg+xml", "svg"),
            Map.entry("text/plain", "txt"),
            Map.entry("text/markdown", "md"),
            Map.entry("audio/mpeg", "mp3"),
            Map.entry("audio/x-wav", "wav"),
            Map.entry("audio/wav", "wav"),
            Map.entry("video/quicktime", "mov"),
            Map.entry("application/octet-stream", "bin"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"));

    private static final String DEFAULT_EXTENSION = "bin";

    /** Length of the id tail in a synthetic name: enough that two files of a day do not collide. */
    private static final int ID_TAIL_LENGTH = 8;

    /**
     * The stored name as it is, otherwise {@code <kind>-<id tail>.<ext>} — {@code image-1b0e5f2a.png}.
     * The result is a pure function of the row: the same file downloads under the same name from
     * every path and on every attempt.
     */
    public static String forDownload(FileLink link) {
        String stored = link.name();
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return kindForMime(link.mime()) + "-" + idTail(link.fileId()) + "." + extension(link.mime());
    }

    /** What kind of thing this is for a human: {@code image|video|audio|file}. */
    public static String kindForMime(String mime) {
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

    /**
     * Extension for a MIME: the table, otherwise the subtype when it can pass for one. The subtype is
     * taken from a parsed media type, never by cutting the string — an upload carries the client's
     * header verbatim, parameters included ({@code text/plain;charset=UTF-8}).
     */
    private static String extension(String mime) {
        if (mime == null) {
            return DEFAULT_EXTENSION;
        }
        MediaType parsed;
        try {
            parsed = MediaType.parseMediaType(mime);
        } catch (Exception e) {
            return DEFAULT_EXTENSION;
        }
        String type = parsed.getType() + "/" + parsed.getSubtype();
        String known = EXTENSIONS.get(type);
        if (known != null) {
            return known;
        }
        // "svg+xml" and the like → the part before '+'; a long vnd.* subtype is not an extension.
        String subtype = parsed.getSubtype();
        int plus = subtype.indexOf('+');
        if (plus > 0) {
            subtype = subtype.substring(0, plus);
        }
        return subtype.matches("[a-z0-9]{1,8}") ? subtype : DEFAULT_EXTENSION;
    }

    /** Tail of the id — the one thing that keeps two nameless files of one kind apart. */
    private static String idTail(String fileId) {
        if (fileId == null || fileId.length() < ID_TAIL_LENGTH) {
            return "file";
        }
        return fileId.substring(fileId.length() - ID_TAIL_LENGTH);
    }
}
