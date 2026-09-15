package ru.agimate.controlapi.connectors.internal.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.files.dto.FilesDtos.TextEdit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TextEdits — замены с единственным вхождением")
class TextEditsTest {

    @Test
    @DisplayName("замены применяются по порядку, каждая к результату предыдущей")
    void appliesInOrder() {
        String result = TextEdits.apply("<h1>Draft</h1><p>old</p>", List.of(
                new TextEdit("Draft", "Report"),
                new TextEdit("<h1>Report</h1>", "<h1>Final report</h1>"),
                new TextEdit("<p>old</p>", "")));

        assertEquals("<h1>Final report</h1>", result);
    }

    @Test
    @DisplayName("фрагмент не найден — ошибка с номером правки")
    void notFound() {
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> TextEdits.apply("hello", List.of(new TextEdit("hello", "hi"), new TextEdit("bye", "x"))));

        assertTrue(e.getMessage().startsWith("edit #2: oldText not found"), e.getMessage());
    }

    @Test
    @DisplayName("фрагмент встречается несколько раз — ошибка с числом вхождений")
    void ambiguous() {
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> TextEdits.apply("a-b-a-b-a", List.of(new TextEdit("a", "c"))));

        assertTrue(e.getMessage().contains("occurs 3 times"), e.getMessage());
    }

    @Test
    @DisplayName("перекрывающиеся вхождения — тоже неоднозначность: «\\n\\n» в «a\\n\\n\\nb» встречается дважды")
    void overlappingIsAmbiguous() {
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> TextEdits.apply("a\n\n\nb", List.of(new TextEdit("\n\n", "\n"))));

        assertTrue(e.getMessage().contains("occurs 2 times"), e.getMessage());
    }

    @Test
    @DisplayName("пустой список и пустой oldText — отказ")
    void rejectsEmpty() {
        assertThrows(ConnectorException.class, () -> TextEdits.apply("a", List.of()));
        assertThrows(ConnectorException.class, () -> TextEdits.apply("a", List.of(new TextEdit("", "b"))));
    }
}
