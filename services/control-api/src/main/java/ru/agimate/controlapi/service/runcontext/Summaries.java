package ru.agimate.controlapi.service.runcontext;

import lombok.experimental.UtilityClass;

/**
 * The listing line of a deferred tool: the first sentence of its description, bounded. Computed in
 * one place, at context assembly — nothing declares a summary, neither {@code @Tool} nor
 * {@code connection_tools}; an MCP description of several kilobytes contributes its opening line.
 */
@UtilityClass
class Summaries {

    /** Hard bound on a listing line: a name plus this is what a deferred tool costs on every call. */
    static final int MAX_SUMMARY_CHARS = 160;

    private static final String ELLIPSIS = "…";

    static String of(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        String text = firstSentence(description.strip());
        if (text.length() <= MAX_SUMMARY_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf(' ', MAX_SUMMARY_CHARS - ELLIPSIS.length());
        if (cut < MAX_SUMMARY_CHARS / 2) {
            cut = MAX_SUMMARY_CHARS - ELLIPSIS.length(); // one giant token: cut it, do not drop the line
        }
        return text.substring(0, cut).stripTrailing() + ELLIPSIS;
    }

    /** Up to the first sentence end followed by whitespace, or the first line break — whichever comes first. */
    private static String firstSentence(String text) {
        int end = text.length();
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                end = i;
                break;
            }
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(text.charAt(i + 1))) {
                end = i + 1;
                break;
            }
        }
        return text.substring(0, end).strip();
    }
}
