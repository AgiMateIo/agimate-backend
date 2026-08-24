package ru.agimate.controlapi.service.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.controlapi.database.enums.FileReferenceKind;
import ru.agimate.controlapi.database.repositories.FileReferenceRepository;
import ru.agimate.controlapi.storage.FileIds;

import java.util.Collection;
import java.util.UUID;

/**
 * The one place that writes {@code file_references} (docs/connectors/files.md). Callers are the
 * funnels a file passes through — the trigger router for everything inbound, the outbound channel
 * service for everything the agent sends, the producers of the tool layer.
 *
 * <p>Every method is best-effort by contract: a reference is navigation, and losing one must never
 * cost a delivery. Failures are logged, not propagated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileReferenceService {

    private final FileReferenceRepository fileReferenceRepository;

    /**
     * @param fileIds public ids ({@code agf_…}) as they travel in message parts; anything that is not
     *                one is skipped — the parts of a channel are not ours to trust
     */
    public void record(Collection<String> fileIds, UUID sessionId, UUID agentId, FileReferenceKind kind) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        for (String fileId : fileIds) {
            FileIds.parse(fileId).ifPresent(id -> record(id, sessionId, agentId, kind));
        }
    }

    public void record(UUID fileId, UUID sessionId, UUID agentId, FileReferenceKind kind) {
        try {
            fileReferenceRepository.record(fileId, sessionId, agentId, kind.name());
        } catch (Exception e) {
            // A file with no reference is still readable by id; a failed delivery is not recoverable.
            log.warn("Failed to record {} reference for file {} in session {}: {}",
                    kind, FileIds.external(fileId), sessionId, e.getMessage());
        }
    }
}
