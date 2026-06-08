package ru.agimate.controlapi.service.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderRendererTest {

    @Test
    @DisplayName("replaces {text} with agent text")
    void textPlaceholder() {
        Map<String, Object> template = Map.of("body", "{text}");
        Map<String, Object> result = PlaceholderRenderer.render(template, "Hello!", Map.of());
        assertEquals("Hello!", result.get("body"));
    }

    @Test
    @DisplayName("resolves {trigger.path} from trigger input")
    void triggerPathPlaceholder() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("chat_id", "{trigger.data.message.chat_id}");
        template.put("text", "{text}");
        Map<String, Object> triggerInput = Map.of("data", Map.of("message", Map.of("chat_id", 12345L)));
        Map<String, Object> result = PlaceholderRenderer.render(template, "Hi", triggerInput);

        assertEquals(12345L, result.get("chat_id"));
        assertEquals("Hi", result.get("text"));
    }

    @Test
    @DisplayName("interpolates within a string")
    void stringInterpolation() {
        Map<String, Object> template = Map.of("body", "User {trigger.user}: {text}");
        Map<String, Object> triggerInput = Map.of("user", "alice");
        Map<String, Object> result = PlaceholderRenderer.render(template, "hi", triggerInput);
        assertEquals("User alice: hi", result.get("body"));
    }

    @Test
    @DisplayName("missing placeholder resolves to empty string in interpolation")
    void missingPlaceholderInterpolation() {
        Map<String, Object> template = Map.of("body", "A:{trigger.missing}.B");
        Map<String, Object> result = PlaceholderRenderer.render(template, null, Map.of());
        assertEquals("A:.B", result.get("body"));
    }

    @Test
    @DisplayName("preserves non-string values and recurses through nested maps and lists")
    void recursiveRendering() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("scalar", 42);
        nested.put("text", "{text}");
        nested.put("items", List.of("{text}", "raw", Map.of("x", "{trigger.k}")));
        Map<String, Object> template = Map.of("payload", nested);

        Map<String, Object> result = PlaceholderRenderer.render(template, "ok", Map.of("k", "v"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result.get("payload");
        assertEquals(42, payload.get("scalar"));
        assertEquals("ok", payload.get("text"));
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) payload.get("items");
        assertEquals("ok", items.get(0));
        assertEquals("raw", items.get(1));
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) items.get(2);
        assertEquals("v", last.get("x"));
    }
}
