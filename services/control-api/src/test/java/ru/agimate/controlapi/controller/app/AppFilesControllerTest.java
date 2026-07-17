package ru.agimate.controlapi.controller.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.mock.web.MockMultipartFile;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.controller.app.dto.FileResponse;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.FileStatus;
import ru.agimate.controlapi.security.AppPrincipal;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppFilesController")
class AppFilesControllerTest {

    private static final UUID APP_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final AppPrincipal PRINCIPAL = new AppPrincipal("app", APP_ID, USER_ID);

    @Mock
    private AppService appService;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private InboundRateLimiter rateLimiter;

    private AppFilesController controller;

    @BeforeEach
    void setUp() {
        controller = new AppFilesController(appService, fileStorageService, rateLimiter);
    }

    private App app() {
        App app = new App();
        app.setUserId(USER_ID);
        return app;
    }

    private static StoredFile storedFile() {
        return StoredFile.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .status(FileStatus.READY)
                .mime("image/png")
                .sizeBytes(5L)
                .sha256("abc")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
    }

    @Test
    @DisplayName("upload: сохраняет от имени владельца приложения и возвращает agf_-id")
    void uploadStoresFile() {
        when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, APP_ID)).thenReturn(true);
        when(appService.getApp(PRINCIPAL)).thenReturn(app());
        StoredFile stored = storedFile();
        when(fileStorageService.store(eq(USER_ID), any(), eq("image/png"), anyLong(), any(), isNull()))
                .thenReturn(stored);

        MockMultipartFile file = new MockMultipartFile(
                "file", "shot.png", "image/png", "hello".getBytes(StandardCharsets.UTF_8));
        SuccessResponse<FileResponse> response = controller.uploadFile(file, PRINCIPAL);

        assertEquals(FileIds.external(stored.getId()), response.getResponse().id());
        assertEquals("image/png", response.getResponse().mime());
        assertEquals(5L, response.getResponse().size());
    }

    @Test
    @DisplayName("upload: без content-type → application/octet-stream")
    void uploadDefaultsMime() {
        when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, APP_ID)).thenReturn(true);
        when(appService.getApp(PRINCIPAL)).thenReturn(app());
        when(fileStorageService.store(eq(USER_ID), any(), eq("application/octet-stream"),
                anyLong(), any(), isNull())).thenReturn(storedFile());

        MockMultipartFile file = new MockMultipartFile(
                "file", "blob", null, "hello".getBytes(StandardCharsets.UTF_8));
        assertNotNull(controller.uploadFile(file, PRINCIPAL).getResponse());
    }

    @Test
    @DisplayName("upload: превышение rate limit → 429 до похода в БД")
    void uploadRateLimited() {
        when(rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, APP_ID)).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile(
                "file", "shot.png", "image/png", "hello".getBytes(StandardCharsets.UTF_8));
        assertThrows(TooManyRequestsStatusException.class, () -> controller.uploadFile(file, PRINCIPAL));
        verifyNoInteractions(appService, fileStorageService);
    }

    @Test
    @DisplayName("download: отдаёт контент с mime и длиной")
    void downloadStreamsContent() throws Exception {
        when(appService.getApp(PRINCIPAL)).thenReturn(app());
        StoredFile stored = storedFile();
        String fileId = FileIds.external(stored.getId());
        when(fileStorageService.open(USER_ID, fileId)).thenReturn(new FileStorageService.FileContent(
                stored, new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))));

        ResponseEntity<InputStreamResource> response = controller.downloadFile(fileId, PRINCIPAL);

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals(5L, response.getHeaders().getContentLength());
        assertEquals("hello", new String(
                response.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("download: битый mime в метаданных → octet-stream, не 500")
    void downloadFallsBackOnBadMime() {
        when(appService.getApp(PRINCIPAL)).thenReturn(app());
        StoredFile stored = storedFile();
        stored.setMime("definitely not a mime");
        String fileId = FileIds.external(stored.getId());
        when(fileStorageService.open(USER_ID, fileId)).thenReturn(new FileStorageService.FileContent(
                stored, new ByteArrayInputStream(new byte[]{1})));

        ResponseEntity<InputStreamResource> response = controller.downloadFile(fileId, PRINCIPAL);
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
    }
}
