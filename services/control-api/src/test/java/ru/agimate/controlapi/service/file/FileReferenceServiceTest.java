package ru.agimate.controlapi.service.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.enums.FileReferenceKind;
import ru.agimate.controlapi.database.repositories.FileReferenceRepository;
import ru.agimate.controlapi.storage.FileIds;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileReferenceService — где файл засветился")
class FileReferenceServiceTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock
    private FileReferenceRepository fileReferenceRepository;

    @InjectMocks
    private FileReferenceService service;

    @Test
    @DisplayName("публичные agf_-идентификаторы разбираются в строки")
    void recordsEveryParsableId() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        service.record(List.of(FileIds.external(first), FileIds.external(second)),
                SESSION_ID, AGENT_ID, FileReferenceKind.INBOUND);

        verify(fileReferenceRepository).record(eq(first), eq(SESSION_ID), eq(AGENT_ID), eq("INBOUND"));
        verify(fileReferenceRepository).record(eq(second), eq(SESSION_ID), eq(AGENT_ID), eq("INBOUND"));
    }

    @Test
    @DisplayName("мусор в parts канала пропускается, а не роняет запись")
    void skipsWhatIsNotAFileId() {
        UUID real = UUID.randomUUID();

        service.record(List.of("https://example.com/x.png", "agf_not-a-uuid", FileIds.external(real)),
                SESSION_ID, AGENT_ID, FileReferenceKind.OUTBOUND);

        verify(fileReferenceRepository).record(eq(real), any(), any(), eq("OUTBOUND"));
        verify(fileReferenceRepository, never()).record(eq(null), any(), any(), any());
    }

    @Test
    @DisplayName("сбой записи не выходит наружу — ссылка это навигация, доставка важнее")
    void swallowsFailures() {
        UUID fileId = UUID.randomUUID();
        when(fileReferenceRepository.record(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("deadlock"));

        assertDoesNotThrow(() -> service.record(fileId, SESSION_ID, AGENT_ID, FileReferenceKind.TOOL));
    }

    @Test
    @DisplayName("пустые вложения не ходят в базу")
    void emptyPartsAreNoWork() {
        service.record(List.of(), SESSION_ID, AGENT_ID, FileReferenceKind.INBOUND);
        service.record((java.util.Collection<String>) null, SESSION_ID, AGENT_ID, FileReferenceKind.INBOUND);

        verify(fileReferenceRepository, never()).record(any(), any(), any(), any());
    }
}
