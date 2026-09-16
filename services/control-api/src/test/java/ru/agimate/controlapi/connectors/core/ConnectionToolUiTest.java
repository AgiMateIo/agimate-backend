package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.dto.ToolUi;
import ru.agimate.controlapi.database.entities.ConnectionTool;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConnectionToolMapper — _meta.ui вью MCP Apps")
class ConnectionToolUiTest {

    private static ToolUi ui(String meta) {
        return ConnectionToolMapper.toSpec(ConnectionTool.builder()
                .connectionId(UUID.randomUUID())
                .name("t")
                .meta(meta)
                .build()).ui();
    }

    @Test
    @DisplayName("вложенная форма: ссылка и видимость")
    void nestedForm() {
        ToolUi ui = ui("{\"ui\":{\"resourceUri\":\"ui://s/weather\",\"visibility\":[\"model\",\"app\"]}}");

        assertEquals("ui://s/weather", ui.resourceUri());
        assertEquals(List.of("model", "app"), ui.visibility());
        assertTrue(ui.callableFromView());
    }

    @Test
    @DisplayName("плоская форма черновика ui/resourceUri тоже читается")
    void flatForm() {
        assertEquals("ui://s/weather", ui("{\"ui/resourceUri\":\"ui://s/weather\"}").resourceUri());
    }

    @Test
    @DisplayName("при обеих формах побеждает вложенная")
    void nestedWins() {
        assertEquals("ui://s/new",
                ui("{\"ui/resourceUri\":\"ui://s/old\",\"ui\":{\"resourceUri\":\"ui://s/new\"}}").resourceUri());
    }

    @Test
    @DisplayName("видимость без своей вью: помощник, которого зовут вью сервера")
    void visibilityWithoutView() {
        ToolUi ui = ui("{\"ui\":{\"visibility\":[\"app\"]}}");

        assertNull(ui.resourceUri());
        assertTrue(ui.callableFromView());
    }

    @Test
    @DisplayName("строже спеки: без объявленной видимости вью тул не зовёт")
    void undeclaredVisibilityIsNotCallable() {
        assertFalse(ui("{\"ui\":{\"resourceUri\":\"ui://s/weather\"}}").callableFromView());
    }

    @Test
    @DisplayName("не ui:// схема, мусор и отсутствие _meta → null")
    void nothingToUnderstand() {
        assertNull(ui("{\"ui\":{\"resourceUri\":\"https://evil.example/page\"}}"));
        assertNull(ui("not json"));
        assertNull(ui("{\"other\":1}"));
        assertNull(ui(null));
    }
}
