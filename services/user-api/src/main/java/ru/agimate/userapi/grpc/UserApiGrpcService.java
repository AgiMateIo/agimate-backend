package ru.agimate.userapi.grpc;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.user.v1.IntrospectApiKeyRequest;
import ru.agimate.user.v1.IntrospectApiKeyResponse;
import ru.agimate.user.v1.UserApiServiceGrpc;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.service.ServiceApiKeyService;
import ru.agimate.userapi.service.UserService;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserApiGrpcService extends UserApiServiceGrpc.UserApiServiceImplBase {

    private final ServiceApiKeyService serviceApiKeyService;
    private final UserService userService;

    @Override
    public void introspectApiKey(IntrospectApiKeyRequest request,
                                 StreamObserver<IntrospectApiKeyResponse> responseObserver) {
        try {
            log.debug("gRPC introspectApiKey called");

            var keyOpt = serviceApiKeyService.validateKey(request.getApiKey());

            IntrospectApiKeyResponse response;
            if (keyOpt.isPresent()) {
                var key = keyOpt.get();
                String userRole = userService.findByPubId(key.getUserPubId())
                        .map(UserEntity::getRole)
                        .map(Enum::name)
                        .orElse("GUEST");
                response = IntrospectApiKeyResponse.newBuilder()
                        .setValid(true)
                        .setKeyPubId(key.getPubId().toString())
                        .setUserPubId(key.getUserPubId().toString())
                        .setUserRole(userRole)
                        .build();
            } else {
                response = IntrospectApiKeyResponse.newBuilder()
                        .setValid(false)
                        .build();
            }

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error in introspectApiKey: {}", e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to introspect API key: " + e.getMessage())
                    .asRuntimeException());
        }
    }
}
