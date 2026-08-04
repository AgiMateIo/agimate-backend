package ru.agimate.controlapi.connectors.integrations.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.NewFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramMediaService")
class TelegramMediaServiceTest {

    private static final String TOKEN = "token123";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String CONNECTION_ID = UUID.randomUUID().toString();

    @Mock
    private TelegramApiClient telegramApiClient;
    @Mock
    private FileStorageService fileStorageService;

    private TelegramMediaService service() {
        return new TelegramMediaService(telegramApiClient, fileStorageService);
    }

    private static Trigger photoTrigger(List<Map<String, Object>> photo) {
        return Trigger.createBasic("telegram", CONNECTION_ID, "photo_received",
                new java.util.LinkedHashMap<>(Map.of("chatId", 1, "photo", photo, "caption", "look")));
    }

    private StoredFile stored(UUID id, String mime, long size) {
        return StoredFile.builder().id(id).userId(USER_ID).mime(mime).sizeBytes(size).build();
    }

    @Nested
    @DisplayName("hasMedia")
    class HasMedia {

        @Test
        @DisplayName("true для photo_received/document_received, false для message_received")
        void detectsMedia() {
            TelegramMediaService s = service();
            assertTrue(s.hasMedia(Trigger.createBasic("telegram", CONNECTION_ID, "photo_received", Map.of())));
            assertTrue(s.hasMedia(Trigger.createBasic("telegram", CONNECTION_ID, "document_received", Map.of())));
            assertTrue(!s.hasMedia(Trigger.createBasic("telegram", CONNECTION_ID, "message_received", Map.of())));
        }
    }

    @Nested
    @DisplayName("materialize")
    class Materialize {

        @Test
        @DisplayName("photo: качает крупнейший PhotoSize, кладёт в файлы, заменяет data.photo на parts")
        void materializesLargestPhoto() {
            List<Map<String, Object>> photo = List.of(
                    Map.of("file_id", "small", "file_size", 100),
                    Map.of("file_id", "big", "file_size", 9000));
            when(telegramApiClient.getFile(TOKEN, "big"))
                    .thenReturn(Map.of("ok", true, "result",
                            Map.of("file_path", "photos/f.jpg", "file_size", 9000)));
            when(telegramApiClient.downloadFile(TOKEN, "photos/f.jpg")).thenReturn(new byte[9000]);
            UUID fileId = UUID.randomUUID();
            when(fileStorageService.store(any(NewFile.class), any(InputStream.class)))
                    .thenReturn(stored(fileId, "image/jpeg", 9000));

            Trigger out = service().materialize(TOKEN, USER_ID, CONNECTION_ID, photoTrigger(photo));

            ArgumentCaptor<NewFile> spec = ArgumentCaptor.forClass(NewFile.class);
            verify(fileStorageService).store(spec.capture(), any(InputStream.class));
            assertEquals(USER_ID, spec.getValue().userId());
            assertEquals("telegram:" + CONNECTION_ID, spec.getValue().origin());
            assertEquals("image/jpeg", spec.getValue().mime());
            // Ингест идёт до маршрутизации — получатели ещё не выбраны.
            assertNull(spec.getValue().agentId());
            // У фото в Telegram имени нет — придумывать его нельзя.
            assertNull(spec.getValue().name());
            assertNull(out.data().get("photo"));
            List<?> parts = (List<?>) out.data().get("parts");
            assertEquals(1, parts.size());
            Map<?, ?> part = (Map<?, ?>) parts.get(0);
            assertEquals("image", part.get("type"));
            assertEquals(FileIds.external(fileId), part.get("fileId"));
            assertEquals("image/jpeg", part.get("mime"));
            assertEquals("look", out.data().get("caption"));
        }

        @Test
        @DisplayName("document: сохраняет mime/имя из дескриптора")
        void materializesDocument() {
            Trigger trigger = Trigger.createBasic("telegram", CONNECTION_ID, "document_received",
                    new java.util.LinkedHashMap<>(Map.of("document",
                            Map.of("file_id", "doc1", "mime_type", "application/pdf", "file_name", "report.pdf"))));
            when(telegramApiClient.getFile(TOKEN, "doc1"))
                    .thenReturn(Map.of("ok", true, "result",
                            Map.of("file_path", "docs/report.pdf", "file_size", 512)));
            when(telegramApiClient.downloadFile(TOKEN, "docs/report.pdf")).thenReturn(new byte[512]);
            UUID fileId = UUID.randomUUID();
            when(fileStorageService.store(any(NewFile.class), any(InputStream.class)))
                    .thenReturn(stored(fileId, "application/pdf", 512));

            Trigger out = service().materialize(TOKEN, USER_ID, CONNECTION_ID, trigger);

            ArgumentCaptor<NewFile> spec = ArgumentCaptor.forClass(NewFile.class);
            verify(fileStorageService).store(spec.capture(), any(InputStream.class));
            assertEquals("application/pdf", spec.getValue().mime());
            assertEquals("report.pdf", spec.getValue().name());
            Map<?, ?> part = (Map<?, ?>) ((List<?>) out.data().get("parts")).get(0);
            assertEquals("file", part.get("type"));
            assertEquals("report.pdf", part.get("name"));
        }

        @Test
        @DisplayName("сбой getFile → триггер без изменений (деградация к заглушке)")
        void degradesOnFailure() {
            List<Map<String, Object>> photo = List.of(Map.of("file_id", "big", "file_size", 9000));
            when(telegramApiClient.getFile(TOKEN, "big")).thenThrow(new RuntimeException("boom /bot123/getFile"));
            Trigger in = photoTrigger(photo);

            Trigger out = service().materialize(TOKEN, USER_ID, CONNECTION_ID, in);

            assertSame(in, out);
            assertNull(out.data().get("parts"));
        }

        @Test
        @DisplayName("не-медиа триггер — без обращения к API")
        void ignoresNonMedia() {
            Trigger in = Trigger.createBasic("telegram", CONNECTION_ID, "message_received",
                    Map.of("text", "hi"));
            Trigger out = service().materialize(TOKEN, USER_ID, CONNECTION_ID, in);
            assertSame(in, out);
            verifyNoInteractions(telegramApiClient, fileStorageService);
        }

        @Test
        @DisplayName("нет токена — без обращения к API")
        void noToken() {
            Trigger in = photoTrigger(List.of(Map.of("file_id", "big")));
            Trigger out = service().materialize(null, USER_ID, CONNECTION_ID, in);
            assertSame(in, out);
            verifyNoInteractions(telegramApiClient, fileStorageService);
        }
    }
}
