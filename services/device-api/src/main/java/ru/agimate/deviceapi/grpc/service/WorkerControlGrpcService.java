package ru.agimate.deviceapi.grpc.service;

import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.agentworker.HealthCheckRequest;
import ru.agimate.agentworker.HealthCheckResponse;
import ru.agimate.agentworker.WorkerControlGrpc;

import java.time.Instant;

@Service
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
}
