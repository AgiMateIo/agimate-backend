package ru.agimate.deviceapi.grpc.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.deviceapi.config.WorkerPoolProperties;
import ru.agimate.deviceapi.util.AppKeyUtils;
import ru.agimate.deviceapi.util.GeneratedAppKey;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerPoolKeyAuthServiceTest {

    private GeneratedAppKey generated;
    private WorkerPoolKeyAuthService service;

    @BeforeEach
    void setUp() {
        generated = AppKeyUtils.generate(WorkerPoolRegistry.WORKER_POOL_KEY_PREFIX);
        String authkey = ParsedWorkerAuthkey.build(
                WorkerPoolRegistry.WORKER_POOL_KEY_PREFIX, generated);
        WorkerPoolProperties props = new WorkerPoolProperties(List.of(authkey));
        WorkerPoolRegistry registry = new WorkerPoolRegistry(props);
        registry.init();
        service = new WorkerPoolKeyAuthService(registry);
    }

    @Test
    @DisplayName("validates correct full key")
    void validates_correctKey() {
        Optional<WorkerPoolKeyAuthService.AuthenticatedPool> result = service.validateKey(generated.fullKey());
        assertTrue(result.isPresent());
        assertEquals(generated.keyId(), result.get().poolId());
    }

    @Test
    @DisplayName("rejects null/blank/short")
    void rejects_invalidInput() {
        assertTrue(service.validateKey(null).isEmpty());
        assertTrue(service.validateKey("").isEmpty());
        assertTrue(service.validateKey("garbage").isEmpty());
    }

    @Test
    @DisplayName("rejects key with wrong prefix")
    void rejects_wrongPrefix() {
        GeneratedAppKey other = AppKeyUtils.generate("agnt");
        assertTrue(service.validateKey(other.fullKey()).isEmpty());
    }

    @Test
    @DisplayName("rejects unknown keyId")
    void rejects_unknownKeyId() {
        GeneratedAppKey orphan = AppKeyUtils.generate(WorkerPoolRegistry.WORKER_POOL_KEY_PREFIX);
        assertTrue(service.validateKey(orphan.fullKey()).isEmpty());
    }

    @Test
    @DisplayName("rejects key whose secret does not match stored hash")
    void rejects_wrongSecret() {
        // Tamper: take the generated keyId but produce a different secret payload
        GeneratedAppKey tampered = AppKeyUtils.generate(WorkerPoolRegistry.WORKER_POOL_KEY_PREFIX);
        // Replace the keyId portion of tampered key with the registered keyId,
        // making the CRC/SHA mismatch the stored hash.
        String fake = WorkerPoolRegistry.WORKER_POOL_KEY_PREFIX
                + generated.keyId()
                + tampered.fullKey().substring(16);
        assertTrue(service.validateKey(fake).isEmpty());
    }
}
