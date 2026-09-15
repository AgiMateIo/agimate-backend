package ru.agimate.controlapi.connectors.internal.files;

import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.files.dto.FilesDtos.TextEdit;

import java.util.List;

/**
 * Exact-string replacements for {@code edit_file}. Each {@code oldText} must occur exactly once in the
 * text as the previous replacements left it: a second occurrence means the model would be guessing
 * which one it meant, and none means it is editing text it has not read. All or nothing — a refused
 * edit leaves the whole batch unapplied.
 */
final class TextEdits {

    private TextEdits() {
    }

    static String apply(String text, List<TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            throw new ConnectorException("edits is empty: give at least one {oldText, newText}");
        }
        String result = text;
        for (int index = 0; index < edits.size(); index++) {
            TextEdit edit = edits.get(index);
            String label = "edit #" + (index + 1);
            if (edit == null || edit.oldText() == null || edit.oldText().isEmpty()) {
                throw new ConnectorException(label + ": oldText is empty");
            }
            String newText = edit.newText() == null ? "" : edit.newText();
            int first = result.indexOf(edit.oldText());
            if (first < 0) {
                throw new ConnectorException(label + ": oldText not found. It must match the file exactly, "
                        + "whitespace and line breaks included — read the file again. Nothing was changed");
            }
            int occurrences = count(result, edit.oldText(), first);
            if (occurrences > 1) {
                throw new ConnectorException(label + ": oldText occurs " + occurrences + " times; "
                        + "include more surrounding text so it matches once. Nothing was changed");
            }
            result = result.substring(0, first) + newText + result.substring(first + edit.oldText().length());
        }
        return result;
    }

    private static int count(String text, String fragment, int first) {
        int occurrences = 0;
        // Step by one, not by the fragment: "\n\n" sits twice in "\n\n\n", and either place is a different edit.
        for (int at = first; at >= 0; at = text.indexOf(fragment, at + 1)) {
            occurrences++;
        }
        return occurrences;
    }
}
