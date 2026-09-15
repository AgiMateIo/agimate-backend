package ru.agimate.controlapi.connectors.internal.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileLink;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.FileStorageService.FileContent;
import ru.agimate.controlapi.storage.FileVersionConflictException;
import ru.agimate.controlapi.storage.NewFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("files-коннектор — тулы через executeTool (env биндится по-настоящему)")
class FilesToolServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final ConnectorEnv env = new ConnectorEnv(
            null, userId, agentId, UUID.randomUUID(), null, sessionId, Map.of(), null);

    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private StoredFileRepository storedFileRepository;
    private FilesConnectorService handler;

    @BeforeEach
    void setUp() {
        handler = new FilesConnectorService(new FilesToolService(fileStorageService, storedFileRepository));
    }

    private StoredFile file(String name, String mime, int version) {
        return StoredFile.builder().id(UUID.randomUUID()).userId(userId).status(FileStatus.READY)
                .name(name).mime(mime).sizeBytes(5L).version(version).build();
    }

    private FileContent content(StoredFile file, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new FileContent(FileLink.of(file), bytes.length, new ByteArrayInputStream(bytes));
    }

    @Test
    @DisplayName("save_file без fileId: новый файл, mime по имени, сессия и агент из env")
    void saveNewFile() {
        StoredFile stored = file("report.html", "text/html", 1);
        when(fileStorageService.store(any(), any())).thenReturn(stored);

        Map<String, Object> result = handler.executeTool(env, "save_file",
                Map.of("content", "<h1>Hi</h1>", "name", "report.html"));

        ArgumentCaptor<NewFile> spec = ArgumentCaptor.forClass(NewFile.class);
        verify(fileStorageService).store(spec.capture(), any());
        assertEquals("text/html", spec.getValue().mime());
        assertEquals(sessionId, spec.getValue().sessionId());
        assertEquals(agentId, spec.getValue().agentId());
        assertEquals(11L, spec.getValue().sizeBytes());
        assertEquals(Map.of("id", FileIds.external(stored.getId()), "name", "report.html",
                "mime", "text/html", "size", 5L, "version", 1), result.get("file"));
    }

    @Test
    @DisplayName("save_file без fileId и без имени — отказ")
    void saveNewFileRequiresName() {
        assertThrows(ConnectorException.class,
                () -> handler.executeTool(env, "save_file", Map.of("content", "text")));
    }

    @Test
    @DisplayName("save_file с fileId: новая версия от прочитанной, имя и текстовый mime сохраняются")
    void saveNewVersion() {
        StoredFile current = file("notes", "text/markdown", 3);
        String fileId = FileIds.external(current.getId());
        when(fileStorageService.findReadable(userId, fileId)).thenReturn(Optional.of(current));
        when(fileStorageService.storeVersion(eq(fileId), eq(3), any(), any())).thenReturn(file("notes", "text/markdown", 4));

        handler.executeTool(env, "save_file", Map.of("content", "# new", "fileId", fileId));

        ArgumentCaptor<NewFile> spec = ArgumentCaptor.forClass(NewFile.class);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorageService).storeVersion(eq(fileId), eq(3), spec.capture(), bytes.capture());
        assertEquals("text/markdown", spec.getValue().mime());
        assertNull(spec.getValue().name());
        assertArrayEquals("# new".getBytes(StandardCharsets.UTF_8), bytes.getValue());
    }

    @Test
    @DisplayName("edit_file: замены к текущему тексту, запись от его версии")
    void editFile() {
        StoredFile current = file("page.html", "text/html", 2);
        String fileId = FileIds.external(current.getId());
        when(fileStorageService.open(userId, fileId)).thenReturn(content(current, "<p>old</p>"));
        when(fileStorageService.storeVersion(eq(fileId), eq(2), any(), any())).thenReturn(file("page.html", "text/html", 3));

        handler.executeTool(env, "edit_file", Map.of("fileId", fileId,
                "edits", List.of(Map.of("oldText", "old", "newText", "new"))));

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(fileStorageService).storeVersion(eq(fileId), eq(2), any(), bytes.capture());
        assertEquals("<p>new</p>", new String(bytes.getValue(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("edit_file: одновременная запись — текст отказа уходит агенту")
    void editConflictSurfaces() {
        StoredFile current = file("page.html", "text/html", 2);
        String fileId = FileIds.external(current.getId());
        when(fileStorageService.open(userId, fileId)).thenReturn(content(current, "<p>old</p>"));
        when(fileStorageService.storeVersion(any(), anyInt(), any(), any()))
                .thenThrow(new FileVersionConflictException(fileId));

        ConnectorException e = assertThrows(ConnectorException.class, () -> handler.executeTool(env, "edit_file",
                Map.of("fileId", fileId, "edits", List.of(Map.of("oldText", "old", "newText", "new")))));

        assertTrue(e.getMessage().contains("read it again"), e.getMessage());
    }

    @Test
    @DisplayName("edit_file по картинке — отказ без записи")
    void editRejectsBinary() {
        StoredFile current = file("shot.png", "image/png", 1);
        String fileId = FileIds.external(current.getId());
        when(fileStorageService.open(userId, fileId)).thenReturn(content(current, "PNG"));

        assertThrows(ConnectorException.class, () -> handler.executeTool(env, "edit_file",
                Map.of("fileId", fileId, "edits", List.of(Map.of("oldText", "P", "newText", "J")))));

        verify(fileStorageService, never()).storeVersion(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("read_file: окно по строкам и nextOffset до конца файла")
    void readFileWindows() {
        StoredFile current = file("a.txt", "text/plain", 1);
        String fileId = FileIds.external(current.getId());
        when(fileStorageService.open(userId, fileId)).thenReturn(content(current, "l1\nl2\nl3\n"));

        Map<String, Object> first = handler.executeTool(env, "read_file", Map.of("fileId", fileId, "limit", 2));

        assertEquals("l1\nl2", first.get("content"));
        assertEquals(3, first.get("totalLines"));
        assertEquals(3, first.get("nextOffset"));

        when(fileStorageService.open(userId, fileId)).thenReturn(content(current, "l1\nl2\nl3\n"));
        Map<String, Object> rest = handler.executeTool(env, "read_file", Map.of("fileId", fileId, "offset", 3));

        assertEquals("l3", rest.get("content"));
        assertNull(rest.get("nextOffset"));
    }
}
