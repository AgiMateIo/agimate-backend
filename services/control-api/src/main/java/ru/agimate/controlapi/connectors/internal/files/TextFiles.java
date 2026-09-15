package ru.agimate.controlapi.connectors.internal.files;

import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What counts as a text file for the files connector, and how one is paged for a model. */
final class TextFiles {

    /** The types a model is expected to write; anything else it saves is plain text. */
    private static final Map<String, String> MIME_BY_EXTENSION = Map.of(
            "html", "text/html",
            "htm", "text/html",
            "md", "text/markdown",
            "csv", "text/csv",
            "json", "application/json");

    static final String DEFAULT_MIME = "text/plain";

    /**
     * A line longer than this is split for paging: minified HTML is often a single line, and a
     * window that cannot cut one would never get past it.
     */
    static final int MAX_LINE_CHARS = 2_000;

    private TextFiles() {
    }

    /** The mime a saved file gets from the extension of its name. */
    static String mimeForName(String name) {
        if (name == null) {
            return DEFAULT_MIME;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return DEFAULT_MIME;
        }
        return MIME_BY_EXTENSION.getOrDefault(name.substring(dot + 1).toLowerCase(Locale.ROOT), DEFAULT_MIME);
    }

    /**
     * Whether the contents can be read and edited as text. The mime is parsed, never cut: an upload
     * stores the client's header verbatim, parameters included ({@code text/plain;charset=UTF-8}).
     */
    static boolean isText(String mime) {
        if (mime == null) {
            return false;
        }
        MediaType parsed;
        try {
            parsed = MediaType.parseMediaType(mime);
        } catch (Exception e) {
            return false;
        }
        String subtype = parsed.getSubtype();
        return parsed.getType().equals("text")
                || parsed.getType().equals("application") && (subtype.equals("json") || subtype.equals("xml")
                        || subtype.equals("javascript"))
                || subtype.endsWith("+json") || subtype.endsWith("+xml");
    }

    /** The text as paging lines: its own lines, the long ones split into {@link #MAX_LINE_CHARS} pieces. */
    static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            for (int at = 0; at == 0 || at < line.length(); at += MAX_LINE_CHARS) {
                lines.add(line.substring(at, Math.min(line.length(), at + MAX_LINE_CHARS)));
            }
        }
        // A trailing line break is not an empty last line.
        if (lines.size() > 1 && lines.getLast().isEmpty() && (text.endsWith("\n") || text.endsWith("\r"))) {
            lines.removeLast();
        }
        return lines;
    }
}
