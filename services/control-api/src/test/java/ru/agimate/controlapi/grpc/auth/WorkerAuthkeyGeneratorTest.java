package ru.agimate.controlapi.grpc.auth;

import ru.agimate.common.security.keys.ParsedAuthkey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.agimate.common.security.keys.AppKeyUtils;
import ru.agimate.common.security.keys.GeneratedAppKey;

/**
 * Manual generator for worker pool authkeys. Disabled by default.
 * <p>
 * Run: {@code ./gradlew :control-api:test --tests "*WorkerAuthkeyGeneratorTest" -Dgenerate.worker.authkey=true}
 */
class WorkerAuthkeyGeneratorTest {

    @Test
    @DisplayName("generate worker pool authkey")
    @EnabledIfSystemProperty(named = "generate.worker.authkey", matches = "true")
    void generate() {
        GeneratedAppKey generated = AppKeyUtils.generate(WorkerPoolRegistry.WORKER_POOL_KEY_PREFIX);
        String authkey = ParsedAuthkey.build(WorkerPoolRegistry.WORKER_POOL_KEY_PREFIX, generated);

        System.out.println();
        System.out.println("=== Worker Pool Authkey Generated ===");
        System.out.println("Full key (give to worker, set as authorization Bearer): " + generated.fullKey());
        System.out.println("Authkey  (put in WORKER_POOLS_AUTHKEYS_0):              " + authkey);
        System.out.println("KeyId   (poolId on PoC):                                " + generated.keyId());
        System.out.println("=====================================");
    }
}
