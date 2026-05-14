package ru.agimate.deviceapi.grpc.service;

import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.agentworker.HealthCheckRequest;
import ru.agimate.agentworker.HealthCheckResponse;
import ru.agimate.agentworker.SendMessageRequest;
import ru.agimate.agentworker.SendMessageResponse;
import ru.agimate.agentworker.WorkerControlGrpc;
import ru.agimate.agentworker.WorkerMessageType;

import java.time.Instant;

@Service
@Slf4j
public class WorkerControlGrpcService extends WorkerControlGrpc.WorkerControlImplBase {

    @Override
    public void healthCheck(HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
        Instant now = Instant.now();
        HealthCheckResponse response = HealthCheckResponse.newBuilder()
                .setStatus("OK")
                .setServerTime(Timestamp.newBuilder()
                        .setSeconds(now.getEpochSecond())
                        .setNanos(now.getNano())
                        .build())
                .setPoolId(WorkerPoolContextHolder.current().poolId())
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void sendMessage(SendMessageRequest request, StreamObserver<SendMessageResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        WorkerMessageType type = request.getType();
        String content = request.getContent();
        if (type == WorkerMessageType.WORKER_MESSAGE_TYPE_ERROR) {
            log.error("WorkerControl.SendMessage pool={} {}", poolId, content);
        } else {
            log.info("WorkerControl.SendMessage pool={} type={} {}", poolId, type, content);
        }
        responseObserver.onNext(SendMessageResponse.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
