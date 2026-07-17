package ru.agimate.controlapi.service.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboundAttachmentParser")
class OutboundAttachmentParserTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private FileStorageService fileStorageService;

    private OutboundAttachmentParser parser;

    @BeforeEach
    void setUp() {
        parser = new OutboundAttachmentParser(fileStorageService);
    }

    private static StoredFile file(UUID id, String mime) {
        return StoredFile.builder()
                .id(id).userId(USER_ID).status(FileStatus.READY)
                .mime(mime).sizeBytes(42L)
                .expiresAt(LocalDateTime.now().plusDays(1)).build();
    }

    @Test
    @DisplayName("без маркеров — исходное сообщение как есть, без похода в storage")
    void noMarkers() {
        OutboundMessage outbound = OutboundMessage.text("просто текст");
        assertSame(outbound, parser.parse(USER_ID, outbound));
        verifyNoInteractions(fileStorageService);
    }

    @Test
    @DisplayName("валидный маркер → part с mime/size, текст очищен")
    void validMarker() {
        UUID id = UUID.randomUUID();
        String fileId = FileIds.external(id);
        when(fileStorageService.findReadable(USER_ID, fileId)).thenReturn(Optional.of(file(id, "image/png")));

        OutboundMessage result = parser.parse(USER_ID,
                OutboundMessage.text("Вот скриншот [[attach:" + fileId + "]] готов"));

        assertEquals("Вот скриншот  готов".replace("  ", " ").trim(),
                result.text().replace("  ", " ").trim());
        assertEquals(1, result.parts().size());
        Part part = result.parts().get(0);
        assertEquals("image", part.type());
        assertEquals(fileId, part.storageRef());
        assertEquals("image/png", part.mime());
        assertEquals(42L, part.size());
    }

    @Test
    @DisplayName("недоступный файл: маркер вырезан, part не создан")
    void unresolvableMarkerDropped() {
        String fileId = FileIds.external(UUID.randomUUID());
        when(fileStorageService.findReadable(USER_ID, fileId)).thenReturn(Optional.empty());

        OutboundMessage result = parser.parse(USER_ID,
                OutboundMessage.text("Держи [[attach:" + fileId + "]]"));

        assertEquals("Держи", result.text());
        assertTrue(result.parts().isEmpty());
    }

    @Test
    @DisplayName("несколько маркеров → части в порядке появления, типы по mime")
    void multipleMarkers() {
        UUID img = UUID.randomUUID();
        UUID vid = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        when(fileStorageService.findReadable(eq(USER_ID), any())).thenAnswer(inv -> {
            String ref = inv.getArgument(1);
            if (ref.equals(FileIds.external(img))) return Optional.of(file(img, "image/jpeg"));
            if (ref.equals(FileIds.external(vid))) return Optional.of(file(vid, "video/mp4"));
            if (ref.equals(FileIds.external(doc))) return Optional.of(file(doc, "application/pdf"));
            return Optional.empty();
        });

        OutboundMessage result = parser.parse(USER_ID, OutboundMessage.text(
                "[[attach:" + FileIds.external(img) + "]][[attach:" + FileIds.external(vid) + "]]"
                        + "[[attach:" + FileIds.external(doc) + "]]"));

        assertEquals("", result.text());
        assertEquals(List.of("image", "video", "file"),
                result.parts().stream().map(Part::type).toList());
    }

    @Test
    @DisplayName("кривой синтаксис/не-agf id — не маркер, текст не трогается")
    void malformedMarkersIgnored() {
        String text = "[[attach:file_123]] и [[attach:agf_not-a-uuid...]]";
        OutboundMessage result = parser.parse(USER_ID, OutboundMessage.text(text));
        assertSame(text, result.text());
        assertTrue(result.parts().isEmpty());
    }

    @Test
    @DisplayName("уже заполненные parts — сообщение не трогается")
    void structuredPartsPassThrough() {
        OutboundMessage outbound = new OutboundMessage("text [[attach:" + FileIds.external(UUID.randomUUID()) + "]]",
                List.of(new Part("image", "agf_x", "image/png", 1, java.util.Map.of())));
        assertSame(outbound, parser.parse(USER_ID, outbound));
        verifyNoInteractions(fileStorageService);
    }
}
