package ru.agimate.common.security.apikey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.user.v1.IntrospectApiKeyRequest;
import ru.agimate.user.v1.IntrospectApiKeyResponse;
import ru.agimate.user.v1.UserApiServiceGrpc;

import java.time.Duration;
import java.util.Optional;

@Slf4j
public class ApiKeyIntrospectService {

    private final UserApiServiceGrpc.UserApiServiceBlockingStub userApiStub;

    private final Cache<String, Optional<ApiKeyIntrospectResult>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(10_000)
            .build();

    public ApiKeyIntrospectService(UserApiServiceGrpc.UserApiServiceBlockingStub userApiStub) {
        this.userApiStub = userApiStub;
    }

    public Optional<ApiKeyIntrospectResult> introspect(String apiKey) {
        return cache.get(apiKey, key -> {
            try {
                IntrospectApiKeyResponse response = userApiStub.introspectApiKey(
                        IntrospectApiKeyRequest.newBuilder()
                                .setApiKey(key)
                                .build()
                );

                if (response.getValid()) {
                    return Optional.of(new ApiKeyIntrospectResult(
                            response.getKeyPubId(),
                            response.getUserPubId()
                    ));
                }
                return Optional.empty();
            } catch (Exception e) {
                log.error("Failed to introspect API key via gRPC: {}", e.getMessage(), e);
                return Optional.empty();
            }
        });
    }
}
