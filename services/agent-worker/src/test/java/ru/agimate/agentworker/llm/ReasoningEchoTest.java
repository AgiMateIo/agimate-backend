package ru.agimate.agentworker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.common.util.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ReasoningEcho — пустое рассуждение уезжает строгому провайдеру")
class ReasoningEchoTest {

    private static final String THINKING_TURN =
            "{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"Сначала список окон\","
                    + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"windows_list\",\"arguments\":\"{}\"}}]}";

    private static final String SILENT_TURN =
            "{\"role\":\"assistant\",\"content\":\"\","
                    + "\"tool_calls\":[{\"id\":\"c2\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"screenshot\",\"arguments\":\"{}\"}}]}";

    private static final String TOOL_RESULT =
            "{\"role\":\"tool\",\"tool_call_id\":\"c2\",\"content\":\"{}\"}";

    private static final String TOOLS =
            "[{\"type\":\"function\",\"function\":{\"name\":\"windows_list\","
                    + "\"parameters\":{\"type\":\"object\"}}}]";

    private static byte[] request(String messages, String tools) {
        return ("{\"model\":\"deepseek-flash\",\"messages\":" + messages
                + ",\"tools\":" + tools + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode patched(byte[] body) throws IOException {
        byte[] out = ReasoningEcho.patch(body);
        assertNotNull(out, "тело должно быть изменено");
        return JsonUtils.MAPPER.readTree(out);
    }

    @Test
    @DisplayName("tools + непустое рассуждение → пустому assistant-ходу дописывается пустая строка")
    void silentTurnGetsEmptyReasoning() throws IOException {
        JsonNode root = patched(request(
                "[" + THINKING_TURN + "," + TOOL_RESULT + "," + SILENT_TURN + "]", TOOLS));

        assertEquals("Сначала список окон", root.at("/messages/0/reasoning_content").asText());
        JsonNode filled = root.at("/messages/2/reasoning_content");
        assertFalse(filled.isMissingNode(), "поле не дописано");
        assertEquals("", filled.asText());
    }

    @Test
    @DisplayName("user, system и tool не трогаются — поле только на assistant")
    void onlyAssistantMessagesAreTouched() throws IOException {
        String user = "{\"role\":\"user\",\"content\":\"посмотри окна\"}";
        JsonNode root = patched(request(
                "[" + user + "," + THINKING_TURN + "," + TOOL_RESULT + "," + SILENT_TURN + "]", TOOLS));

        assertTrue(root.at("/messages/0/reasoning_content").isMissingNode());
        assertTrue(root.at("/messages/2/reasoning_content").isMissingNode());
    }

    @Test
    @DisplayName("без tools тело не меняется")
    void noToolsNoPatch() throws IOException {
        assertNull(ReasoningEcho.patch(request("[" + THINKING_TURN + "," + SILENT_TURN + "]", "[]")));
    }

    @Test
    @DisplayName("ни одного непустого рассуждения → тело не меняется")
    void noReasoningNoPatch() throws IOException {
        assertNull(ReasoningEcho.patch(request("[" + SILENT_TURN + "]", TOOLS)));
    }

    @Test
    @DisplayName("у всех assistant-ходов поле уже есть → тело не меняется")
    void everyTurnAlreadyCarriesTheField() throws IOException {
        String emptyTurn = "{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"\"}";
        assertNull(ReasoningEcho.patch(request("[" + THINKING_TURN + "," + emptyTurn + "]", TOOLS)));
    }

    @Test
    @DisplayName("поле не-строка (null) заменяется пустой строкой")
    void nonStringFieldBecomesEmptyString() throws IOException {
        String nullTurn = "{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":null}";
        JsonNode root = patched(request("[" + THINKING_TURN + "," + nullTurn + "]", TOOLS));

        assertEquals("", root.at("/messages/1/reasoning_content").asText());
    }

    @Test
    @DisplayName("не JSON — исключение, решение оставить тело как есть принимает вызывающий")
    void nonJsonThrows() {
        assertThrows(IOException.class,
                () -> ReasoningEcho.patch("не json".getBytes(StandardCharsets.UTF_8)));
    }
}
