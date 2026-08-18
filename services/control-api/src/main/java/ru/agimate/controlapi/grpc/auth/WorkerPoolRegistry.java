package ru.agimate.controlapi.grpc.auth;

import ru.agimate.common.security.keys.ParsedAuthkey;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.WorkerPoolProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkerPoolRegistry {

    public static final String WORKER_POOL_KEY_PREFIX = "wrkp";

    private final WorkerPoolProperties properties;

    private Map<String, ParsedAuthkey> byKeyId = Map.of();

    @PostConstruct
    void init() {
        Map<String, ParsedAuthkey> map = new HashMap<>();
        for (int i = 0; i < properties.authkeys().size(); i++) {
            String authkey = properties.authkeys().get(i);
            ParsedAuthkey parsed = ParsedAuthkey.parse(authkey);
            if (!WORKER_POOL_KEY_PREFIX.equals(parsed.prefix())) {
                throw new IllegalStateException(
                        "Worker pool authkey at index " + i + " has wrong prefix '"
                                + parsed.prefix() + "', expected '" + WORKER_POOL_KEY_PREFIX + "'");
            }
            ParsedAuthkey prev = map.putIfAbsent(parsed.keyId(), parsed);
            if (prev != null) {
                throw new IllegalStateException(
                        "Duplicate worker pool authkey with keyId=" + parsed.keyId());
            }
        }
        this.byKeyId = Map.copyOf(map);
        log.info("Loaded {} worker pool(s)", byKeyId.size());
    }

    public Optional<ParsedAuthkey> findByKeyId(String keyId) {
        return Optional.ofNullable(byKeyId.get(keyId));
    }

    public int size() {
        return byKeyId.size();
    }
}
