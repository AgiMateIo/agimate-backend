package ru.agimate.controlapi.grpc.auth;

import ru.agimate.common.security.keys.ParsedAuthkey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.security.keys.AppKeyUtils;
import ru.agimate.common.security.keys.ParsedAppKey;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerPoolKeyAuthService {

    private final WorkerPoolRegistry registry;

    public Optional<AuthenticatedPool> validateKey(String fullKey) {
        if (fullKey == null || fullKey.isBlank()) {
            return Optional.empty();
        }

        ParsedAppKey parsedKey;
        try {
            parsedKey = AppKeyUtils.parse(fullKey);
        } catch (IllegalArgumentException e) {
            log.debug("Invalid worker pool key format: {}", e.getMessage());
            return Optional.empty();
        }

        if (!WorkerPoolRegistry.WORKER_POOL_KEY_PREFIX.equals(parsedKey.prefix())) {
            log.debug("Worker pool key has wrong prefix: {}", parsedKey.prefix());
            return Optional.empty();
        }

        if (!AppKeyUtils.verifyChecksum(parsedKey)) {
            log.debug("Worker pool key checksum verification failed");
            return Optional.empty();
        }

        Optional<ParsedAuthkey> entry = registry.findByKeyId(parsedKey.keyId());
        if (entry.isEmpty()) {
            log.debug("Worker pool not found for keyId: {}", parsedKey.keyId());
            return Optional.empty();
        }

        if (!AppKeyUtils.verifySecret(parsedKey.secret(), entry.get().keyHash())) {
            log.debug("Worker pool key secret verification failed for keyId: {}", parsedKey.keyId());
            return Optional.empty();
        }

        return Optional.of(new AuthenticatedPool(parsedKey.keyId()));
    }

    public record AuthenticatedPool(String poolId) {}
}
