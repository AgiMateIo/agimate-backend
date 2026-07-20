package ru.agimate.agentworker.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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
    @DisplayName("image-part с байтами → UserMessage с одним Media + guidance «видишь напрямую»")
    void attachesImageMedia() {
        AgentChatMessage user = AgentChatMessage.user("что на фото?",
                List.of(new FilePartRef("agf_1", "image", "image/png", 3, "s.png")));
        Map<String, byte[]> bytes = Map.of("agf_1", new byte[]{1, 2, 3});

        List<Message> out = mapper.toSpringMessages(List.of(user), bytes, true);

        UserMessage msg = assertInstanceOf(UserMessage.class, out.get(1));
        assertEquals("что на фото?", msg.getText());
        assertEquals(1, ((MediaContent) msg).getMedia().size());
        assertEquals("image/png", ((MediaContent) msg).getMedia().get(0).getMimeType().toString());
        SystemMessage guidance = assertInstanceOf(SystemMessage.class, out.get(0));
        assertEquals(LlmMessageMapper.IMAGE_VISIBLE_GUIDANCE, guidance.getText());
    }

    @Test
    @DisplayName("модель без image-входа → media не подмешивается, guidance «не видны»")
    void blindModelGetsStubOnly() {
        AgentChatMessage user = AgentChatMessage.user("[изображение. id: agf_1]",
                List.of(new FilePartRef("agf_1", "image", "image/png", 3, null)));

        List<Message> out = mapper.toSpringMessages(List.of(user),
                Map.of("agf_1", new byte[]{1, 2, 3}), false);

        UserMessage msg = assertInstanceOf(UserMessage.class, out.get(1));
        assertTrue(((MediaContent) msg).getMedia().isEmpty());
        SystemMessage guidance = assertInstanceOf(SystemMessage.class, out.get(0));
        assertEquals(LlmMessageMapper.IMAGE_NOT_VISIBLE_GUIDANCE, guidance.getText());
    }

    @Test
    @DisplayName("guidance встаёт после ведущих system-сообщений, до диалога")
    void guidanceInsertedAfterLeadingSystem() {
        AgentChatMessage system = AgentChatMessage.system("ты — ассистент");
        AgentChatMessage user = AgentChatMessage.user("смотри",
                List.of(new FilePartRef("agf_1", "image", "image/png", 3, null)));

        List<Message> out = mapper.toSpringMessages(List.of(system, user), Map.of(), false);

        assertEquals("ты — ассистент", ((SystemMessage) out.get(0)).getText());
        assertEquals(LlmMessageMapper.IMAGE_NOT_VISIBLE_GUIDANCE, ((SystemMessage) out.get(1)).getText());
        assertInstanceOf(UserMessage.class, out.get(2));
    }

    @Test
    @DisplayName("без image-вложений guidance не добавляется")
    void noGuidanceWithoutImageParts() {
        List<Message> out = mapper.toSpringMessages(List.of(AgentChatMessage.user("привет")), Map.of(), false);

        assertEquals(1, out.size());
        assertInstanceOf(UserMessage.class, out.get(0));
    }

    @Test
    @DisplayName("нет байтов для ссылки → без Media (текст со стабом)")
    void skipsWhenBytesMissing() {
        AgentChatMessage user = AgentChatMessage.user("[приложено изображение: agf_1]",
                List.of(new FilePartRef("agf_1", "image", "image/png", 3, null)));

        List<Message> out = mapper.toSpringMessages(List.of(user), Map.of(), true);

        UserMessage msg = assertInstanceOf(UserMessage.class, out.get(1));
        assertTrue(((MediaContent) msg).getMedia().isEmpty());
    }

    @Test
    @DisplayName("не-image part не подаётся как Media даже при наличии байтов")
    void ignoresNonImage() {
        AgentChatMessage user = AgentChatMessage.user("документ",
                List.of(new FilePartRef("agf_2", "file", "application/pdf", 3, "d.pdf")));
        List<Message> out = mapper.toSpringMessages(List.of(user), Map.of("agf_2", new byte[]{1}), true);

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
