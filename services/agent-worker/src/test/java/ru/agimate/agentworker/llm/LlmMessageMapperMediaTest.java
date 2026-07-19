package ru.agimate.agentworker.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.MediaContent;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.FilePartRef;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LlmMessageMapper — мультимодальный user-ход")
class LlmMessageMapperMediaTest {

    private final LlmMessageMapper mapper = new LlmMessageMapper();

    @Test
    @DisplayName("image-part с байтами → UserMessage с одним Media")
    void attachesImageMedia() {
        AgentChatMessage user = AgentChatMessage.user("что на фото?",
                List.of(new FilePartRef("agf_1", "image", "image/png", 3, "s.png")));
        Map<String, byte[]> bytes = Map.of("agf_1", new byte[]{1, 2, 3});

        List<Message> out = mapper.toSpringMessages(List.of(user), bytes);

        UserMessage msg = assertInstanceOf(UserMessage.class, out.get(0));
        assertEquals("что на фото?", msg.getText());
        assertEquals(1, ((MediaContent) msg).getMedia().size());
        assertEquals("image/png", ((MediaContent) msg).getMedia().get(0).getMimeType().toString());
    }

    @Test
    @DisplayName("нет байтов для ссылки → без Media (текст со стабом)")
    void skipsWhenBytesMissing() {
        AgentChatMessage user = AgentChatMessage.user("[приложено изображение: agf_1]",
                List.of(new FilePartRef("agf_1", "image", "image/png", 3, null)));

        List<Message> out = mapper.toSpringMessages(List.of(user), Map.of());

        UserMessage msg = assertInstanceOf(UserMessage.class, out.get(0));
        assertTrue(((MediaContent) msg).getMedia().isEmpty());
    }

    @Test
    @DisplayName("не-image part не подаётся как Media даже при наличии байтов")
    void ignoresNonImage() {
        AgentChatMessage user = AgentChatMessage.user("документ",
                List.of(new FilePartRef("agf_2", "file", "application/pdf", 3, "d.pdf")));
        List<Message> out = mapper.toSpringMessages(List.of(user), Map.of("agf_2", new byte[]{1}));

        UserMessage msg = assertInstanceOf(UserMessage.class, out.get(0));
        assertTrue(((MediaContent) msg).getMedia().isEmpty());
    }

    @Test
    @DisplayName("однопараметрический overload — без media, обычный текст")
    void singleArgOverloadNoMedia() {
        List<Message> out = mapper.toSpringMessages(List.of(AgentChatMessage.user("привет")));
        assertInstanceOf(UserMessage.class, out.get(0));
        assertTrue(((MediaContent) out.get(0)).getMedia().isEmpty());
    }
}
